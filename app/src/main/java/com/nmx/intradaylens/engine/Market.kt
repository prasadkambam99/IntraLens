package com.nmx.intradaylens.engine

import java.time.LocalDate
import kotlin.math.abs

/** Everything about the wider market: NIFTY, India VIX and sector indices. */
class MarketData(
    val nifty: List<Session>,
    val vix: List<Session> = emptyList(),
    /** Sector code -> sessions of that sector index. */
    val sectors: Map<String, List<Session>> = emptyMap(),
    /** Stock symbol -> sector code. */
    val sectorOf: Map<String, String> = emptyMap()
) {
    private fun index(list: List<Session>) = list.withIndex().associate { (i, s) -> s.date to i }
    private val niftyIdx = index(nifty)
    private val vixIdx = index(vix)
    private val sectorIdx = sectors.mapValues { index(it.value) }

    private fun gapAndMove(list: List<Session>, idx: Map<LocalDate, Int>, date: LocalDate): Pair<Double, Double>? {
        val i = idx[date] ?: return null
        if (i < 1) return null
        val today = list[i]
        if (!today.orComplete) return null
        val prevClose = list[i - 1].close
        val or = today.orBars
        return (today.open - prevClose) / prevClose * 100 to (or.last().c - today.open) / today.open * 100
    }

    fun context(symbol: String, date: LocalDate): MarketContext {
        val (gap, move) = gapAndMove(nifty, niftyIdx, date) ?: (0.0 to 0.0)
        val sec = sectorOf[symbol]?.let { code ->
            val l = sectors[code]; val ix = sectorIdx[code]
            if (l != null && ix != null) gapAndMove(l, ix, date) else null
        } ?: (gap to move)
        var vixLevel = 15.0
        var vixChange = 0.0
        vixIdx[date]?.let { i ->
            if (i >= 1) {
                val prev = vix[i - 1].close
                vixLevel = prev
                vixChange = (vix[i].orBars.ifEmpty { vix[i].bars }.last().c - prev) / prev * 100
            }
        } ?: vix.lastOrNull { it.date < date }?.let { vixLevel = it.close }
        return MarketContext(gap, move, vixLevel, vixChange, sec.first, sec.second)
    }

    /** "Wild day" check used by the safe-day filter. Returns a reason, or null if the day looks normal. */
    fun wildDayReason(date: LocalDate): String? {
        val c = context("", date)
        return when {
            c.vixLevel >= 22 -> "India VIX is high (${String.format("%.1f", c.vixLevel)}) — market is nervous"
            c.vixChangePct >= 10 -> "India VIX jumped ${String.format("%.0f", c.vixChangePct)}% this morning"
            abs(c.gapPct) >= 1.0 -> "NIFTY opened with a big ${if (c.gapPct > 0) "gap up" else "gap down"} (${String.format("%.1f", abs(c.gapPct))}%)"
            else -> null
        }
    }

    companion object {
        /** Yahoo symbols of NSE sector indices. */
        val SECTOR_INDEX = linkedMapOf(
            "IT" to "^CNXIT", "BANK" to "^NSEBANK", "AUTO" to "^CNXAUTO", "PHARMA" to "^CNXPHARMA",
            "FMCG" to "^CNXFMCG", "METAL" to "^CNXMETAL", "ENERGY" to "^CNXENERGY", "INFRA" to "^CNXINFRA"
        )

        /** Sector of each default stock (anything missing simply uses NIFTY). */
        val SECTOR_OF: Map<String, String> = buildMap {
            listOf("INFY", "TCS", "HCLTECH", "WIPRO", "TECHM", "LTIM").forEach { put(it, "IT") }
            listOf("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BAJFINANCE", "BAJAJFINSV",
                "SHRIRAMFIN", "JIOFIN", "SBILIFE", "HDFCLIFE", "BANKBARODA", "PNB").forEach { put(it, "BANK") }
            listOf("M&M", "MARUTI", "BAJAJ-AUTO", "EICHERMOT", "HEROMOTOCO", "TMPV", "TMCV", "TVSMOTOR").forEach { put(it, "AUTO") }
            listOf("SUNPHARMA", "CIPLA", "DRREDDY", "APOLLOHOSP", "DIVISLAB", "LUPIN").forEach { put(it, "PHARMA") }
            listOf("ITC", "HINDUNILVR", "NESTLEIND", "TATACONSUM", "TITAN", "TRENT", "ASIANPAINT", "ETERNAL", "BRITANNIA").forEach { put(it, "FMCG") }
            listOf("TATASTEEL", "JSWSTEEL", "HINDALCO", "COALINDIA", "ADANIENT", "VEDL").forEach { put(it, "METAL") }
            listOf("RELIANCE", "ONGC", "NTPC", "POWERGRID", "BPCL", "TATAPOWER").forEach { put(it, "ENERGY") }
            listOf("LT", "ULTRACEMCO", "GRASIM", "ADANIPORTS", "BEL", "BHARTIARTL", "HAL").forEach { put(it, "INFRA") }
        }
    }
}
