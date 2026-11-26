package com.nmx.intradaylens.data

import android.content.Context
import com.nmx.intradaylens.engine.Candle
import com.nmx.intradaylens.engine.EnsembleModel
import com.nmx.intradaylens.engine.IST
import com.nmx.intradaylens.engine.Indicators
import com.nmx.intradaylens.engine.MarketData
import com.nmx.intradaylens.engine.ModelReport
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.engine.PlanRule
import com.nmx.intradaylens.engine.Rules
import com.nmx.intradaylens.engine.Sentiment
import com.nmx.intradaylens.engine.Session
import com.nmx.intradaylens.engine.Strategy
import com.nmx.intradaylens.engine.TradeStatus
import com.nmx.intradaylens.engine.Trainer
import com.nmx.intradaylens.engine.WatchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

enum class Phase(val label: String) {
    PRE_OPEN("Market opens at 9:15 AM"),
    OPENING_RANGE("Opening range forming — picks at 9:30 AM"),
    LIVE("Market live"),
    CLOSED("Market closed")
}

/** FULL = 60 days + retrain, NORMAL = last few days, LIVE = only what's needed for today's trades (fast). */
enum class RefreshMode { FULL, NORMAL, LIVE }

data class IndexQuote(val name: String, val last: Double, val changePct: Double)

data class Snapshot(
    val sessionDate: LocalDate,
    val isToday: Boolean,
    val phase: Phase,
    val picks: List<Pick>,
    val watch: List<WatchItem>,
    val indices: List<IndexQuote>,
    val indexPicks: List<Pick> = emptyList(),
    val indexNote: String? = null,
    val indexReport: ModelReport? = null,
    val failed: List<String>,
    val updatedAt: Long,
    val modelReport: ModelReport?,
    /** Start time (epoch sec) of the newest bar received for NIFTY. */
    val dataTime: Long? = null,
    /** Why everything is "Skip today" (wild day or loss limit), or null. */
    val blockReason: String? = null,
    val lossesToday: Int = 0,
    /** Big news today (RBI policy, results season, etc.). */
    val eventNote: String? = null
)

data class StatusChange(val pick: JournalPick, val old: JournalPick?)

class Repository private constructor(ctx: Context) {
    val store = Store(ctx.applicationContext)
    private val appCtx = ctx.applicationContext
    private val mutex = Mutex()

    @Volatile var sessions: Map<String, List<Session>> = emptyMap(); private set
    @Volatile var market: MarketData = MarketData(emptyList()); private set
    @Volatile var indexSessions: Map<String, List<Session>> = emptyMap(); private set
    /** 1-minute sessions for the indices (needed for accurate 20-point stop/target checks). */
    @Volatile var indexIntraday: Map<String, List<Session>> = emptyMap(); private set
    @Volatile var lastSnapshot: Snapshot? = null; private set
    @Volatile private var eventNote: String? = null
    private val newsCache = HashMap<String, Pair<Long, List<Headline>>>()

    companion object {
        const val INDEX = "^NSEI"
        const val VIX = "^INDIAVIX"
        /** App name -> Yahoo symbol for the two indices. */
        val INDICES = linkedMapOf("NIFTY" to "^NSEI", "BANKNIFTY" to "^NSEBANK")
        val INDEX_LABELS = mapOf("NIFTY" to "NIFTY 50", "BANKNIFTY" to "BANK NIFTY")
        fun isIndex(symbol: String) = symbol in INDICES
        fun label(symbol: String) = INDEX_LABELS[symbol] ?: symbol

        @Volatile private var inst: Repository? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Repository(ctx).also { inst = it } }

        fun now(): ZonedDateTime = ZonedDateTime.now(IST)

        fun phaseNow(): Phase {
            val n = now()
            if (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) return Phase.CLOSED
            val t = n.toLocalTime()
            return when {
                t.isBefore(Rules.MARKET_OPEN) -> Phase.PRE_OPEN
                t.isBefore(Rules.PICKS_READY) -> Phase.OPENING_RANGE
                t.isBefore(Rules.MARKET_CLOSE) -> Phase.LIVE
                else -> Phase.CLOSED
            }
        }
    }

    private fun indexRule(s: Settings) = PlanRule.points(s.indexRiskPts.toDouble(), s.indexTargetPts.toDouble())

    suspend fun refresh(
        progress: (Int, Int) -> Unit = { _, _ -> },
        forceFull: Boolean = false,
        mode: RefreshMode = RefreshMode.NORMAL
    ): Pair<Snapshot, List<StatusChange>> = mutex.withLock {
        withContext(Dispatchers.IO) { doRefresh(progress, forceFull, mode) }
    }

    private suspend fun doRefresh(progress: (Int, Int) -> Unit, forceFull: Boolean, mode0: RefreshMode): Pair<Snapshot, List<StatusChange>> {
        val settings = store.settings()
        val today = now().toLocalDate()
        val firstToday = store.lastFullFetchDay != today.toString()
        val mode = if (forceFull || firstToday) RefreshMode.FULL else mode0
        val todayJournal = store.journal().filter { it.date == today.toString() }
        val recordedToday = store.flag("journalDay") == today.toString()

        val sectorSyms = MarketData.SECTOR_INDEX.values.filter { it !in INDICES.values }
        val allSyms = settings.universe + INDICES.values + VIX + sectorSyms
        val fetchSyms: List<String> = when {
            mode == RefreshMode.LIVE && recordedToday ->
                (todayJournal.filter { !isIndex(it.symbol) }.map { it.symbol } + INDICES.values + VIX).distinct()
            else -> allSyms
        }
        val range = when (mode) { RefreshMode.FULL -> "60d"; RefreshMode.NORMAL -> "5d"; RefreshMode.LIVE -> "1d" }

        val done = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sem = Semaphore(6)
        coroutineScope {
            fetchSyms.map { sym ->
                async {
                    sem.withPermit {
                        try {
                            store.mergeCandles(sym, MarketApi.candles(sym, range))
                        } catch (e: Exception) {
                            if (sym in settings.universe) failed += sym
                        }
                        progress(done.incrementAndGet(), fetchSyms.size + 2)
                    }
                }
            }.awaitAll()
        }
        if (mode == RefreshMode.FULL && failed.size < settings.universe.size / 2) store.lastFullFetchDay = today.toString()

        // 1-minute bars for NIFTY / BANK NIFTY (Yahoo keeps ~30 days; the app keeps adding every day)
        indexIntraday = INDICES.mapValues { (_, y) ->
            val key = "$y@1m"
            val bars = try {
                val fresh = if (mode == RefreshMode.FULL || store.loadCandles(key).isEmpty()) {
                    val nowSec = System.currentTimeMillis() / 1000
                    (0 until 4).flatMap { k ->
                        val to = nowSec - k * 7L * 86400
                        runCatching { MarketApi.candlesBetween(y, to - 7L * 86400, to) }.getOrDefault(emptyList())
                    }
                } else MarketApi.candles(y, if (mode == RefreshMode.LIVE) "1d" else "5d", "1m")
                store.mergeCandles(key, fresh, maxBars = 375 * 130)
            } catch (e: Exception) {
                store.loadCandles(key)
            }
            progress(done.incrementAndGet(), fetchSyms.size + 2)
            Indicators.sessions(bars)
        }.filterValues { it.isNotEmpty() }

        fun load(sym: String) = Indicators.sessions(store.loadCandles(sym))
        val nifty = load(INDEX)
        market = MarketData(
            nifty = nifty,
            vix = load(VIX),
            sectors = MarketData.SECTOR_INDEX.mapValues { (_, y) -> load(y) }.filterValues { it.isNotEmpty() },
            sectorOf = MarketData.SECTOR_OF
        )
        indexSessions = INDICES.mapValues { (_, y) -> load(y) }.filterValues { it.size > Rules.MIN_HISTORY_DAYS }
        sessions = settings.universe.associateWith { load(it) }.filterValues { it.size > Rules.MIN_HISTORY_DAYS }

        // Train once per day on completed sessions (never on today's unfinished data)
        var model = store.loadModel()
        if (model == null || (mode != RefreshMode.LIVE && store.modelDay != today.toString()) || forceFull) {
            val samples = Trainer.buildSamples(sessions, market, excludeDate = today)
            Trainer.trainAndEvaluate(samples, sessions.size, settings.topStocks)?.let { (m, rep) ->
                model = m
                store.saveModel(m, rep)
                store.modelDay = today.toString()
            }
        }
        val idxRule = indexRule(settings)
        var indexModel = store.loadModel("index")
        if (indexModel == null || (mode != RefreshMode.LIVE && store.indexModelDay != today.toString()) || forceFull) {
            val samples = Trainer.buildSamples(indexSessions, market, excludeDate = today, rule = idxRule, intraday = indexIntraday)
            Trainer.trainAndEvaluate(samples, INDICES.size, topN = 2, lambda = 5.0, minTriggered = 30)?.let { (m, rep) ->
                indexModel = m
                store.saveModel(m, rep, "index")
                store.indexModelDay = today.toString()
            }
        }

        // Event check (big news days) — once per normal refresh
        if (mode != RefreshMode.LIVE) eventNote = runCatching { eventCheck() }.getOrNull()

        val phase = phaseNow()
        val todayHasRange = nifty.lastOrNull()?.let { it.date == today && it.orComplete } == true ||
            sessions.values.any { s -> s.lastOrNull()?.let { it.date == today && it.orComplete } == true }
        val sessionDate = when {
            todayHasRange -> today
            else -> (nifty.lastOrNull { it.date != today } ?: nifty.lastOrNull())?.date
                ?: sessions.values.firstOrNull()?.lastOrNull()?.date ?: today
        }
        val isToday = sessionDate == today

        val picks = model?.let { Trainer.scanDay(sessionDate, sessions, market, it) }.orEmpty()
        val indexPicks = indexModel?.let { m -> Trainer.scanDay(sessionDate, indexSessions, market, m, idxRule, indexIntraday) }.orEmpty()
            .sortedBy { INDICES.keys.indexOf(it.symbol) }
        val indexNote = if (indexModel == null)
            "Index AI needs more 1-minute history (about 15 trading days). It keeps collecting every day — check again later." else null

        val watch = sessions.mapNotNull { (sym, s) -> Strategy.watchItem(sym, s.filter { it.date != today }, null) }
            .sortedByDescending { it.score }

        val wildReason = if (settings.safeDayFilter) market.wildDayReason(sessionDate) else null
        val changes = updateJournal(picks, indexPicks, isToday && phase == Phase.LIVE, settings, wildReason)
        val lossesToday = lossesToday()
        val lossBlock = if (settings.dailyLossLimit in 1..lossesToday)
            "Daily loss limit reached ($lossesToday stop-losses). Stop trading today." else null

        val indices = INDICES.keys.mapNotNull { sym ->
            indexSessions[sym]?.takeIf { it.size >= 2 }?.let { m ->
                val last = m.last()
                val prevClose = m[m.size - 2].close
                IndexQuote(label(sym), last.close, (last.close - prevClose) / prevClose * 100)
            }
        }
        store.lastUpdated = System.currentTimeMillis()
        val snap = Snapshot(
            sessionDate, isToday, if (isToday) phase else Phase.CLOSED,
            picks, watch, indices, indexPicks, indexNote, store.loadReport("index"), failed.toList(),
            store.lastUpdated, store.loadReport(), nifty.lastOrNull()?.bars?.lastOrNull()?.t,
            blockReason = wildReason ?: lossBlock, lossesToday = lossesToday, eventNote = eventNote
        )
        lastSnapshot = snap
        runCatching { com.nmx.intradaylens.widget.IndexWidget.update(appCtx, snap, settings) }
        return snap to changes
    }

    /** Stop-losses today: counts your taken trades if you marked any, otherwise all recorded picks. */
    fun lossesToday(): Int {
        val today = now().toLocalDate().toString()
        val j = store.journal().filter { it.date == today }
        val base = if (j.any { it.taken }) j.filter { it.taken } else j
        return base.count { it.status == TradeStatus.LOSS }
    }

    /** Save today's picks once (at/after 9:30) and re-check every saved pick against real prices. */
    private fun updateJournal(
        picks: List<Pick>, indexPicks: List<Pick>, live: Boolean, settings: Settings, wildReason: String?
    ): List<StatusChange> {
        val old = store.journal()
        val list = old.toMutableList()
        val today = now().toLocalDate().toString()
        if (live && store.flag("journalDay") != today && (picks.isNotEmpty() || indexPicks.isNotEmpty())) {
            if (wildReason == null) {
                val chosen = indexPicks.filter { Actions.tradeable(it, settings) } +
                    picks.filter { Actions.tradeable(it, settings) }.take(settings.topStocks)
                chosen.filter { c -> list.none { it.date == today && it.symbol == c.symbol } }.forEach { list += journalOf(it) }
            }
            store.setFlag("journalDay", today)
        }
        val updated = list.map { j -> resimulate(j) }
        store.saveJournal(updated)
        val oldByKey = old.associateBy { it.key }
        return updated.filter { n -> oldByKey[n.key]?.let { it.status != n.status || it.movedToCost != n.movedToCost } ?: true }
            .map { StatusChange(it, oldByKey[it.key]) }
    }

    private fun journalOf(p: Pick, taken: Boolean = false) = JournalPick(
        date = p.date.toString(), symbol = p.symbol, direction = p.plan.direction,
        entry = p.plan.entry, stop = p.plan.stop, target = p.plan.target, probability = p.probability,
        recordedAt = System.currentTimeMillis(), orLow = p.orLow, orHigh = p.orHigh,
        fixedRiskPts = p.rule.fixedRiskPoints, rewardR = p.rule.rewardR, taken = taken
    )

    private fun resimulate(j: JournalPick): JournalPick {
        if (j.closed || !j.canSimulate) return j
        val day = sessionFor(j.symbol, LocalDate.parse(j.date)) ?: return j
        val res = Strategy.simulate(day, j.plan, j.orLow, j.orHigh, j.rule)
        return j.copy(
            status = res.status, r = res.r, fill = res.fillPrice, exitPrice = res.exitPrice,
            stop = res.stop ?: j.stop, target = res.target ?: j.target, movedToCost = res.movedToCost,
            entryTime = res.entryTime, exitTime = res.exitTime
        )
    }

    /** "I took this trade" — toggles the mark, adding the pick to the journal if it wasn't recorded. */
    fun toggleTaken(p: Pick): Boolean {
        val list = store.journal().toMutableList()
        val i = list.indexOfFirst { it.date == p.date.toString() && it.symbol == p.symbol }
        val nowTaken: Boolean
        if (i >= 0) {
            nowTaken = !list[i].taken
            list[i] = list[i].copy(taken = nowTaken)
        } else {
            nowTaken = true
            list += resimulate(journalOf(p, taken = true))
        }
        store.saveJournal(list)
        return nowTaken
    }

    fun isTaken(p: Pick) = store.journal().any { it.date == p.date.toString() && it.symbol == p.symbol && it.taken }

    fun sessionFor(symbol: String, date: LocalDate): Session? =
        (sessions[symbol] ?: indexIntraday[symbol]?.takeIf { s -> s.any { it.date == date } } ?: indexSessions[symbol])
            ?.firstOrNull { it.date == date }

    private fun eventCheck(): String? {
        val heads = MarketApi.news("NIFTY 50 RBI market", 15)
        val todayMs = System.currentTimeMillis() - 24 * 3600_000L
        val recent = heads.filter { it.publishedMs == 0L || it.publishedMs >= todayMs }.map { it.title.lowercase() }
        return when {
            recent.any { it.contains("rbi") && (it.contains("policy") || it.contains("repo rate") || it.contains("mpc")) } ->
                "RBI policy news today — big sudden moves possible."
            recent.any { it.contains("budget") && it.contains("union") } -> "Union Budget news today — big sudden moves possible."
            recent.any { it.contains("fed") && (it.contains("rate") || it.contains("powell")) } -> "US Fed rate news — global markets may swing."
            recent.any { it.contains("election result") || it.contains("exit poll") } -> "Election news — expect sharp moves."
            else -> null
        }
    }

    /** Is a stock reporting results / big news today? (Used on the detail screen.) */
    fun stockEvent(headlines: List<Headline>): String? {
        val cutoff = System.currentTimeMillis() - 36 * 3600_000L
        val hit = headlines.filter { it.publishedMs == 0L || it.publishedMs >= cutoff }.map { it.title.lowercase() }
            .firstOrNull { t -> listOf("q1 result", "q2 result", "q3 result", "q4 result", "quarterly result", "earnings", "results today", "board meeting", "dividend", "block deal", "stake sale").any { t.contains(it) } }
        return hit?.let { "Company news / results around today — moves can be unpredictable." }
    }

    suspend fun news(symbol: String): List<Headline> = withContext(Dispatchers.IO) {
        newsCache[symbol]?.let { (t, h) -> if (System.currentTimeMillis() - t < 15 * 60_000) return@withContext h }
        val h = runCatching { MarketApi.news(if (isIndex(symbol)) label(symbol) else symbol) }.getOrDefault(emptyList())
        newsCache[symbol] = System.currentTimeMillis() to h
        h
    }

    fun newsTone(headlines: List<Headline>): Double? = Sentiment.average(headlines.map { it.title })

    fun model(symbol: String): EnsembleModel? = store.loadModel(if (isIndex(symbol)) "index" else "")
}

fun LocalTime.hm(): String = String.format("%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute) + if (hour < 12) " AM" else " PM"
