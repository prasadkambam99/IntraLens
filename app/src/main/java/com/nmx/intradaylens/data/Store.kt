package com.nmx.intradaylens.data

import android.content.Context
import com.nmx.intradaylens.engine.Bucket
import com.nmx.intradaylens.engine.Candle
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.EnsembleModel
import com.nmx.intradaylens.engine.ModelReport
import com.nmx.intradaylens.engine.PlanRule
import com.nmx.intradaylens.engine.Strategy
import com.nmx.intradaylens.engine.TradePlan
import com.nmx.intradaylens.engine.TradeStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val universe: List<String>,
    val riskRupees: Int = 500,
    val notifications: Boolean = true,
    /** NIFTY / BANK NIFTY fixed stop-loss and target, in index points. */
    val indexRiskPts: Int = 20,
    val indexTargetPts: Int = 20,
    /** Only "Strong" setups get a Buy now (otherwise Strong + Good). */
    val strictMode: Boolean = false,
    /** Skip everything on wild days (high / jumping India VIX, big NIFTY gap). */
    val safeDayFilter: Boolean = true,
    /** Stop trading for the day after this many stop-losses. 0 = off. */
    val dailyLossLimit: Int = 2,
    /** Keep a live connection during market hours for instant loud alerts. */
    val liveAlerts: Boolean = true,
    /** How many stocks to show / record per day. */
    val topStocks: Int = 3
) {
    companion object {
        /** Large, liquid NSE stocks (NIFTY 50 style list). Edit in Settings. */
        val DEFAULT_UNIVERSE = listOf(
            "RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "BHARTIARTL", "SBIN", "ITC", "LT", "KOTAKBANK",
            "AXISBANK", "HINDUNILVR", "BAJFINANCE", "M&M", "MARUTI", "SUNPHARMA", "HCLTECH", "TITAN", "ULTRACEMCO", "NTPC",
            "POWERGRID", "ONGC", "TATASTEEL", "ADANIENT", "ADANIPORTS", "ASIANPAINT", "WIPRO", "NESTLEIND", "JSWSTEEL", "COALINDIA",
            "BAJAJFINSV", "TECHM", "GRASIM", "HINDALCO", "CIPLA", "DRREDDY", "BAJAJ-AUTO", "EICHERMOT", "HEROMOTOCO", "APOLLOHOSP",
            "TATACONSUM", "SBILIFE", "HDFCLIFE", "BEL", "TRENT", "SHRIRAMFIN", "JIOFIN", "ETERNAL", "INDUSINDBK", "TMPV"
        )
    }
}

/** One prediction saved the moment it was made (about 9:30 AM), so the 2-month test is honest. */
data class JournalPick(
    val date: String,
    val symbol: String,
    val direction: Direction,
    /** Trigger level (5-min candle must close beyond it). */
    val entry: Double,
    /** Estimated levels at 9:30; replaced by the real ones once entered. */
    val stop: Double,
    val target: Double,
    val probability: Double,
    val recordedAt: Long,
    val status: TradeStatus = TradeStatus.WAITING,
    val r: Double = 0.0,
    val fill: Double? = null,
    val exitPrice: Double? = null,
    val orLow: Double = 0.0,
    val orHigh: Double = 0.0,
    val fixedRiskPts: Double? = null,
    val rewardR: Double = 1.5,
    val movedToCost: Boolean = false,
    /** User tapped "I took this trade". */
    val taken: Boolean = false,
    val entryTime: Long? = null,
    val exitTime: Long? = null
) {
    val closed get() = status.done
    val key get() = date + "|" + symbol
    val rule get() = PlanRule(fixedRiskPts, rewardR)
    val plan get() = TradePlan(direction, entry, stop, target)
    val canSimulate get() = orLow > 0 && orHigh > 0
}

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("intraday", Context.MODE_PRIVATE)
    private val cacheDir = File(ctx.filesDir, "candles").apply { mkdirs() }

    // ---------- settings ----------
    fun settings(): Settings = Settings(
        universe = prefs.getString("universe", null)?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?: Settings.DEFAULT_UNIVERSE,
        riskRupees = prefs.getInt("risk", 500),
        notifications = prefs.getBoolean("notify", true),
        indexRiskPts = prefs.getInt("idxRisk", 20),
        indexTargetPts = prefs.getInt("idxTarget", 20),
        strictMode = prefs.getBoolean("strict", false),
        safeDayFilter = prefs.getBoolean("safeDay", true),
        dailyLossLimit = prefs.getInt("lossLimit", 2),
        liveAlerts = prefs.getBoolean("live", true),
        topStocks = prefs.getInt("topStocks", 3)
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("universe", s.universe.joinToString(","))
            .putInt("risk", s.riskRupees)
            .putBoolean("notify", s.notifications)
            .putInt("idxRisk", s.indexRiskPts)
            .putInt("idxTarget", s.indexTargetPts)
            .putBoolean("strict", s.strictMode)
            .putBoolean("safeDay", s.safeDayFilter)
            .putInt("lossLimit", s.dailyLossLimit)
            .putBoolean("live", s.liveAlerts)
            .putInt("topStocks", s.topStocks)
            .apply()
    }

    // ---------- candle cache (grows every day, so the AI gets more history over time) ----------
    private fun file(sym: String) = File(cacheDir, sym.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".csv")
    private val memCache = HashMap<String, List<Candle>>()

    @Synchronized
    fun loadCandles(sym: String): List<Candle> {
        memCache[sym]?.let { return it }
        val f = file(sym)
        if (!f.exists()) return emptyList()
        val list = f.readLines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 6) null else runCatching {
                Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toLong())
            }.getOrNull()
        }
        memCache[sym] = list
        return list
    }

    fun mergeCandles(sym: String, fresh: List<Candle>, maxBars: Int = 75 * 250): List<Candle> {
        val old = loadCandles(sym)
        val map = java.util.TreeMap<Long, Candle>()
        old.forEach { map[it.t] = it }
        fresh.forEach { map[it.t] = it }
        val all = map.values.toList().takeLast(maxBars)
        synchronized(this) { memCache[sym] = all }
        file(sym).writeText(all.joinToString("\n") { "${it.t},${it.o},${it.h},${it.l},${it.c},${it.v}" })
        return all
    }

    var lastFullFetchDay: String?
        get() = prefs.getString("fullFetchDay", null)
        set(v) = prefs.edit().putString("fullFetchDay", v).apply()

    var lastUpdated: Long
        get() = prefs.getLong("lastUpdated", 0L)
        set(v) = prefs.edit().putLong("lastUpdated", v).apply()

    /** Generic small flags (alerts sent today, weekly report sent, etc.). */
    fun flag(key: String): String? = prefs.getString("flag_$key", null)
    fun setFlag(key: String, value: String) = prefs.edit().putString("flag_$key", value).apply()

    // ---------- model ----------
    var modelDay: String?
        get() = prefs.getString("modelDay", null)
        set(v) = prefs.edit().putString("modelDay", v).apply()

    var indexModelDay: String?
        get() = prefs.getString("indexModelDay", null)
        set(v) = prefs.edit().putString("indexModelDay", v).apply()

    /** [key] = "" for the stock model, "index" for the NIFTY / BANK NIFTY model. */
    fun saveModel(m: EnsembleModel, r: ModelReport, key: String = "") {
        prefs.edit().putString("model2$key", m.serialize()).putString("report2$key", reportToJson(r).toString()).apply()
    }

    fun loadModel(key: String = ""): EnsembleModel? =
        prefs.getString("model2$key", null)?.let { EnsembleModel.deserialize(it, Strategy.FEATURE_NAMES.size) }

    fun loadReport(key: String = ""): ModelReport? =
        prefs.getString("report2$key", null)?.let { runCatching { reportFromJson(JSONObject(it)) }.getOrNull() }

    private fun reportToJson(r: ModelReport) = JSONObject().apply {
        put("trainedAt", r.trainedAt); put("stocks", r.stocks); put("days", r.days)
        put("trainSamples", r.trainSamples); put("testSamples", r.testSamples)
        put("testFrom", r.testFrom); put("testTo", r.testTo); put("base", r.baseWinRate)
        put("ll", r.modelLogLoss); put("llb", r.baselineLogLoss)
        put("topPicks", r.topPicks); put("topTrig", r.topTriggered); put("topWin", r.topWinRate)
        put("topR", r.topAvgR); put("allR", r.allAvgR)
        put("name", r.modelName); put("llLin", r.linearLogLoss); put("llTrees", r.treesLogLoss)
        put("buckets", JSONArray(r.buckets.map { b ->
            JSONObject().put("l", b.label).put("n", b.count).put("p", b.avgPredicted).put("a", b.actualWinRate)
        }))
        put("weights", JSONArray(r.featureWeights.map { JSONObject().put("n", it.first).put("w", it.second) }))
    }

    private fun reportFromJson(o: JSONObject): ModelReport {
        val b = o.getJSONArray("buckets"); val w = o.getJSONArray("weights")
        return ModelReport(
            o.getLong("trainedAt"), o.getInt("stocks"), o.getInt("days"), o.getInt("trainSamples"), o.getInt("testSamples"),
            o.getString("testFrom"), o.getString("testTo"), o.getDouble("base"), o.getDouble("ll"), o.getDouble("llb"),
            (0 until b.length()).map { i -> b.getJSONObject(i).let { Bucket(it.getString("l"), it.getInt("n"), it.getDouble("p"), it.getDouble("a")) } },
            o.getInt("topPicks"), o.getInt("topTrig"), o.getDouble("topWin"), o.getDouble("topR"), o.getDouble("allR"),
            (0 until w.length()).map { i -> w.getJSONObject(i).let { it.getString("n") to it.getDouble("w") } },
            o.optString("name", "Simple model"), o.optDouble("llLin", 0.0), o.optDouble("llTrees", 0.0)
        )
    }

    // ---------- journal ----------
    private val journalFile = File(ctx.filesDir, "journal.json")

    @Synchronized
    fun journal(): List<JournalPick> {
        if (!journalFile.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(journalFile.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                JournalPick(
                    date = o.getString("date"), symbol = o.getString("symbol"), direction = Direction.valueOf(o.getString("dir")),
                    entry = o.getDouble("entry"), stop = o.getDouble("stop"), target = o.getDouble("target"),
                    probability = o.getDouble("p"), recordedAt = o.getLong("at"),
                    status = runCatching { TradeStatus.valueOf(o.getString("status")) }.getOrDefault(TradeStatus.NOT_TRIGGERED),
                    r = o.getDouble("r"),
                    fill = if (o.has("fill")) o.getDouble("fill") else null,
                    exitPrice = if (o.has("exit")) o.getDouble("exit") else null,
                    orLow = o.optDouble("orl", 0.0), orHigh = o.optDouble("orh", 0.0),
                    fixedRiskPts = if (o.has("fixed")) o.getDouble("fixed") else null,
                    rewardR = o.optDouble("rr", 1.5),
                    movedToCost = o.optBoolean("moved", false),
                    taken = o.optBoolean("taken", false),
                    entryTime = if (o.has("et")) o.getLong("et") else null,
                    exitTime = if (o.has("xt")) o.getLong("xt") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveJournal(list: List<JournalPick>) {
        val a = JSONArray()
        list.forEach { j ->
            a.put(JSONObject().apply {
                put("date", j.date); put("symbol", j.symbol); put("dir", j.direction.name)
                put("entry", j.entry); put("stop", j.stop); put("target", j.target); put("p", j.probability)
                put("at", j.recordedAt); put("status", j.status.name); put("r", j.r)
                j.fill?.let { put("fill", it) }; j.exitPrice?.let { put("exit", it) }
                put("orl", j.orLow); put("orh", j.orHigh)
                j.fixedRiskPts?.let { put("fixed", it) }
                put("rr", j.rewardR); put("moved", j.movedToCost); put("taken", j.taken)
                j.entryTime?.let { put("et", it) }; j.exitTime?.let { put("xt", it) }
            })
        }
        journalFile.writeText(a.toString())
    }

    fun clearJournal() = synchronized(this) { journalFile.delete() }
}
