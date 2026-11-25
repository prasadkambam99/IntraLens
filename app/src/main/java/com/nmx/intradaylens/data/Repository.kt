package com.nmx.intradaylens.data

import android.content.Context
import com.nmx.intradaylens.engine.Candle
import com.nmx.intradaylens.engine.IST
import com.nmx.intradaylens.engine.Indicators
import com.nmx.intradaylens.engine.LogisticModel
import com.nmx.intradaylens.engine.ModelReport
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.engine.PlanRule
import com.nmx.intradaylens.engine.Rules
import com.nmx.intradaylens.engine.Sentiment
import com.nmx.intradaylens.engine.Session
import com.nmx.intradaylens.engine.Strategy
import com.nmx.intradaylens.engine.TradePlan
import com.nmx.intradaylens.engine.Trainer
import com.nmx.intradaylens.engine.WatchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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

data class IndexQuote(val name: String, val last: Double, val changePct: Double)

data class Snapshot(
    val sessionDate: LocalDate,
    val isToday: Boolean,
    val phase: Phase,
    val picks: List<Pick>,
    val watch: List<WatchItem>,
    val indices: List<IndexQuote>,
    /** Today's opening-range plan for NIFTY 50 and BANK NIFTY. */
    val indexPicks: List<Pick> = emptyList(),
    val indexNote: String? = null,
    val indexReport: ModelReport? = null,
    val failed: List<String>,
    val updatedAt: Long,
    val modelReport: ModelReport?,
    /** Start time (epoch sec) of the newest 5-min bar received for NIFTY. */
    val dataTime: Long? = null
)

data class StatusChange(val pick: JournalPick, val old: JournalPick?)

class Repository private constructor(ctx: Context) {
    val store = Store(ctx.applicationContext)

    @Volatile var sessions: Map<String, List<Session>> = emptyMap(); private set
    @Volatile var market: List<Session> = emptyList(); private set
    @Volatile var indexSessions: Map<String, List<Session>> = emptyMap(); private set
    /** 1-minute sessions for the indices (needed for accurate 20-point stop/target checks). */
    @Volatile var indexIntraday: Map<String, List<Session>> = emptyMap(); private set
    private val newsCache = HashMap<String, Pair<Long, List<Headline>>>()

    companion object {
        const val INDEX = "^NSEI"
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

    /**
     * Download prices, (re)train the AI once a day, scan all stocks and update the journal.
     * Returns the snapshot plus any journal status changes (used for notifications).
     */
    suspend fun refresh(progress: (Int, Int) -> Unit = { _, _ -> }, forceFull: Boolean = false): Pair<Snapshot, List<StatusChange>> =
        withContext(Dispatchers.IO) {
            val settings = store.settings()
            val today = now().toLocalDate()
            val full = forceFull || store.lastFullFetchDay != today.toString()
            val symbols = settings.universe + INDICES.values
            val done = AtomicInteger(0)
            val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
            val sem = Semaphore(6)

            val loaded: Map<String, List<Candle>> = coroutineScope {
                symbols.map { sym ->
                    async {
                        sem.withPermit {
                            val candles = try {
                                val fresh = MarketApi.candles(sym, if (full) "60d" else "5d")
                                store.mergeCandles(sym, fresh)
                            } catch (e: Exception) {
                                failed += sym
                                store.loadCandles(sym)
                            }
                            progress(done.incrementAndGet(), symbols.size)
                            sym to candles
                        }
                    }
                }.awaitAll().toMap()
            }
            if (full && failed.size < symbols.size / 2) store.lastFullFetchDay = today.toString()

            // 1-minute bars for NIFTY / BANK NIFTY (Yahoo keeps ~30 days; the app keeps adding every day)
            indexIntraday = INDICES.mapValues { (_, y) ->
                val key = "$y@1m"
                val bars = try {
                    val fresh = if (full || store.loadCandles(key).isEmpty()) {
                        val nowSec = System.currentTimeMillis() / 1000
                        (0 until 4).flatMap { k ->
                            val to = nowSec - k * 7L * 86400
                            runCatching { MarketApi.candlesBetween(y, to - 7L * 86400, to) }.getOrDefault(emptyList())
                        }
                    } else MarketApi.candles(y, "5d", "1m")
                    store.mergeCandles(key, fresh, maxBars = 375 * 130)
                } catch (e: Exception) {
                    store.loadCandles(key)
                }
                Indicators.sessions(bars)
            }.filterValues { it.isNotEmpty() }

            market = Indicators.sessions(loaded[INDEX].orEmpty())
            indexSessions = INDICES.mapValues { (_, y) -> Indicators.sessions(loaded[y].orEmpty()) }
                .filterValues { it.size > Rules.MIN_HISTORY_DAYS }
            sessions = settings.universe.associateWith { Indicators.sessions(loaded[it].orEmpty()) }
                .filterValues { it.size > Rules.MIN_HISTORY_DAYS }

            // Train once per day on completed sessions (never on today's unfinished data)
            var model = store.loadModel()
            if (model == null || store.modelDay != today.toString() || forceFull) {
                val samples = Trainer.buildSamples(sessions + indexSessions, market, excludeDate = today)
                Trainer.trainAndEvaluate(samples, sessions.size, settings.topN, settings.minProb)?.let { (m, rep) ->
                    model = m
                    store.saveModel(m, rep)
                    store.modelDay = today.toString()
                }
            }

            // Separate AI for the indices with the fixed-points rule (e.g. 20 pts stop / 20 pts target)
            val indexRule = PlanRule.points(settings.indexRiskPts.toDouble(), settings.indexTargetPts.toDouble())
            var indexModel = store.loadModel("index")
            if (indexModel == null || store.indexModelDay != today.toString() || forceFull) {
                val samples = Trainer.buildSamples(indexSessions, market, excludeDate = today, rule = indexRule, intraday = indexIntraday)
                Trainer.trainAndEvaluate(samples, INDICES.size, topN = 2, lambda = 5.0, minTriggered = 30)?.let { (m, rep) ->
                    indexModel = m
                    store.saveModel(m, rep, "index")
                    store.indexModelDay = today.toString()
                }
            }

            val phase = phaseNow()
            val todayHasRange = market.lastOrNull()?.let { it.date == today && it.bars.size >= Rules.OR_BARS } == true ||
                sessions.values.any { s -> s.lastOrNull()?.let { it.date == today && it.bars.size >= Rules.OR_BARS } == true }
            val sessionDate = when {
                todayHasRange -> today
                else -> (market.lastOrNull { it.date != today } ?: market.lastOrNull())?.date
                    ?: sessions.values.firstOrNull()?.lastOrNull()?.date ?: today
            }

            val picks = model?.let { Trainer.scanDay(sessionDate, sessions, market, it) }.orEmpty()
            val indexPicks = indexModel?.let { m -> Trainer.scanDay(sessionDate, indexSessions, market, m, indexRule, indexIntraday) }.orEmpty()
                .sortedBy { INDICES.keys.indexOf(it.symbol) }
            val indexNote = if (indexModel == null)
                "Index AI needs more 1-minute history (about 15 trading days). It keeps collecting every day — check again later." else null

            val watch = sessions.mapNotNull { (sym, s) ->
                val hist = s.filter { it.date != today }
                Strategy.watchItem(sym, hist, null)
            }.sortedByDescending { it.score }

            val changes = updateJournal(picks, indexPicks, sessionDate == today && phase == Phase.LIVE, settings)

            val indices = INDICES.keys.mapNotNull { sym ->
                indexSessions[sym]?.takeIf { it.size >= 2 }?.let { m ->
                    val last = m.last()
                    val prevClose = m[m.size - 2].close
                    IndexQuote(label(sym), last.close, (last.close - prevClose) / prevClose * 100)
                }
            }
            store.lastUpdated = System.currentTimeMillis()
            Snapshot(sessionDate, sessionDate == today, if (sessionDate == today) phase else Phase.CLOSED,
                picks, watch, indices, indexPicks, indexNote, store.loadReport("index"), failed.toList(), store.lastUpdated, store.loadReport(),
                market.lastOrNull()?.bars?.lastOrNull()?.t) to changes
        }

    /** Save today's top picks once (at/after 9:30) and re-check every saved pick against real prices. */
    private fun updateJournal(picks: List<Pick>, indexPicks: List<Pick>, live: Boolean, settings: Settings): List<StatusChange> {
        val old = store.journal()
        val list = old.toMutableList()
        val today = now().toLocalDate().toString()
        if (live && list.none { it.date == today }) {
            val chosen = indexPicks.filter { it.probability >= maxOf(settings.minProb, it.plan.breakEven) } +
                picks.filter { it.probability >= settings.minProb }.take(settings.topN)
            chosen.forEach { p ->
                list += JournalPick(today, p.symbol, p.plan.direction, p.plan.entry, p.plan.stop, p.plan.target,
                    p.probability, System.currentTimeMillis())
            }
        }
        val updated = list.map { j ->
            if (j.closed) return@map j
            val day = (sessions[j.symbol] ?: indexIntraday[j.symbol]?.takeIf { s -> s.any { it.date.toString() == j.date } } ?: indexSessions[j.symbol])?.firstOrNull { it.date.toString() == j.date } ?: return@map j
            val res = Strategy.simulate(day, TradePlan(j.direction, j.entry, j.stop, j.target))
            j.copy(status = res.status, r = res.r, fill = res.fillPrice, exitPrice = res.exitPrice)
        }
        store.saveJournal(updated)
        val oldByKey = old.associateBy { it.date + it.symbol }
        return updated.filter { n -> oldByKey[n.date + n.symbol]?.status != n.status }
            .map { StatusChange(it, oldByKey[it.date + it.symbol]) }
    }

    fun sessionFor(symbol: String, date: LocalDate): Session? =
        (sessions[symbol] ?: indexIntraday[symbol]?.takeIf { s -> s.any { it.date == date } } ?: indexSessions[symbol])?.firstOrNull { it.date == date }

    suspend fun news(symbol: String): List<Headline> = withContext(Dispatchers.IO) {
        newsCache[symbol]?.let { (t, h) -> if (System.currentTimeMillis() - t < 15 * 60_000) return@withContext h }
        val h = runCatching { MarketApi.news(if (isIndex(symbol)) label(symbol) else symbol) }.getOrDefault(emptyList())
        newsCache[symbol] = System.currentTimeMillis() to h
        h
    }

    fun newsTone(headlines: List<Headline>): Double? = Sentiment.average(headlines.map { it.title })

    fun model(): LogisticModel? = store.loadModel()
}

fun LocalTime.hm(): String = String.format("%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute) + if (hour < 12) " AM" else " PM"
