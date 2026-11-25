package com.nmx.intradaylens.data

import android.content.Context
import com.nmx.intradaylens.engine.Bucket
import com.nmx.intradaylens.engine.Candle
import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.LogisticModel
import com.nmx.intradaylens.engine.ModelReport
import com.nmx.intradaylens.engine.TradeStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val universe: List<String>,
    val topN: Int = 8,
    val minProb: Double = 0.45,
    val riskRupees: Int = 500,
    val notifications: Boolean = true,
    /** NIFTY / BANK NIFTY fixed stop-loss and target, in index points. */
    val indexRiskPts: Int = 20,
    val indexTargetPts: Int = 20
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

/** One prediction saved the moment it was made, so the 2-month test is honest. */
data class JournalPick(
    val date: String,
    val symbol: String,
    val direction: Direction,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val probability: Double,
    val recordedAt: Long,
    val status: TradeStatus = TradeStatus.WAITING,
    val r: Double = 0.0,
    val fill: Double? = null,
    val exitPrice: Double? = null
) {
    val closed get() = status in setOf(TradeStatus.WIN, TradeStatus.LOSS, TradeStatus.TIME_EXIT, TradeStatus.NOT_TRIGGERED)
}

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("intraday", Context.MODE_PRIVATE)
    private val cacheDir = File(ctx.filesDir, "candles").apply { mkdirs() }

    // ---------- settings ----------
    fun settings(): Settings = Settings(
        universe = prefs.getString("universe", null)?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?: Settings.DEFAULT_UNIVERSE,
        topN = prefs.getInt("topN", 8),
        minProb = prefs.getFloat("minProb", 0.45f).toDouble(),
        riskRupees = prefs.getInt("risk", 500),
        notifications = prefs.getBoolean("notify", true),
        indexRiskPts = prefs.getInt("idxRisk", 20),
        indexTargetPts = prefs.getInt("idxTarget", 20)
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("universe", s.universe.joinToString(","))
            .putInt("topN", s.topN)
            .putFloat("minProb", s.minProb.toFloat())
            .putInt("risk", s.riskRupees)
            .putBoolean("notify", s.notifications)
            .putInt("idxRisk", s.indexRiskPts)
            .putInt("idxTarget", s.indexTargetPts)
            .apply()
    }

    // ---------- candle cache (grows every day, so the AI gets more history over time) ----------
    private fun file(sym: String) = File(cacheDir, sym.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".csv")

    fun loadCandles(sym: String): List<Candle> {
        val f = file(sym)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 6) null else runCatching {
                Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toLong())
            }.getOrNull()
        }
    }

    fun mergeCandles(sym: String, fresh: List<Candle>, maxBars: Int = 75 * 250): List<Candle> {
        val map = java.util.TreeMap<Long, Candle>()
        loadCandles(sym).forEach { map[it.t] = it }
        fresh.forEach { map[it.t] = it }
        // keep about one year of 5-minute bars
        val all = map.values.toList().takeLast(maxBars)
        file(sym).writeText(all.joinToString("\n") { "${it.t},${it.o},${it.h},${it.l},${it.c},${it.v}" })
        return all
    }

    var lastFullFetchDay: String?
        get() = prefs.getString("fullFetchDay", null)
        set(v) = prefs.edit().putString("fullFetchDay", v).apply()

    var lastUpdated: Long
        get() = prefs.getLong("lastUpdated", 0L)
        set(v) = prefs.edit().putLong("lastUpdated", v).apply()

    // ---------- model ----------
    var modelDay: String?
        get() = prefs.getString("modelDay", null)
        set(v) = prefs.edit().putString("modelDay", v).apply()

    var indexModelDay: String?
        get() = prefs.getString("indexModelDay", null)
        set(v) = prefs.edit().putString("indexModelDay", v).apply()

    /** [key] = "" for the stock model, "index" for the NIFTY / BANK NIFTY model. */
    fun saveModel(m: LogisticModel, r: ModelReport, key: String = "") {
        prefs.edit().putString("model$key", m.serialize()).putString("report$key", reportToJson(r).toString()).apply()
    }

    fun loadModel(key: String = ""): LogisticModel? = prefs.getString("model$key", null)?.let { LogisticModel.deserialize(it) }
    fun loadReport(key: String = ""): ModelReport? = prefs.getString("report$key", null)?.let { runCatching { reportFromJson(JSONObject(it)) }.getOrNull() }

    private fun reportToJson(r: ModelReport) = JSONObject().apply {
        put("trainedAt", r.trainedAt); put("stocks", r.stocks); put("days", r.days)
        put("trainSamples", r.trainSamples); put("testSamples", r.testSamples)
        put("testFrom", r.testFrom); put("testTo", r.testTo); put("base", r.baseWinRate)
        put("ll", r.modelLogLoss); put("llb", r.baselineLogLoss)
        put("topPicks", r.topPicks); put("topTrig", r.topTriggered); put("topWin", r.topWinRate)
        put("topR", r.topAvgR); put("allR", r.allAvgR)
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
            (0 until w.length()).map { i -> w.getJSONObject(i).let { it.getString("n") to it.getDouble("w") } }
        )
    }

    // ---------- journal ----------
    private val journalFile = File(ctx.filesDir, "journal.json")

    fun journal(): List<JournalPick> {
        if (!journalFile.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(journalFile.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                JournalPick(
                    o.getString("date"), o.getString("symbol"), Direction.valueOf(o.getString("dir")),
                    o.getDouble("entry"), o.getDouble("stop"), o.getDouble("target"), o.getDouble("p"),
                    o.getLong("at"), TradeStatus.valueOf(o.getString("status")), o.getDouble("r"),
                    if (o.has("fill")) o.getDouble("fill") else null,
                    if (o.has("exit")) o.getDouble("exit") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveJournal(list: List<JournalPick>) {
        val a = JSONArray()
        list.forEach { j ->
            a.put(JSONObject().apply {
                put("date", j.date); put("symbol", j.symbol); put("dir", j.direction.name)
                put("entry", j.entry); put("stop", j.stop); put("target", j.target); put("p", j.probability)
                put("at", j.recordedAt); put("status", j.status.name); put("r", j.r)
                j.fill?.let { put("fill", it) }; j.exitPrice?.let { put("exit", it) }
            })
        }
        journalFile.writeText(a.toString())
    }

    fun clearJournal() = journalFile.delete()
}
