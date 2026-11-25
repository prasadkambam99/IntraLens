package com.nmx.intradaylens.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy: 15-minute Opening Range Breakout (ORB).
 *  - BUY when price breaks above the high of the first 15 minutes, SELL (short) when it breaks below the low.
 *  - Stop-loss on the other side of the range (kept between 0.35% and 1.2%).
 *  - Target = 1.5 x risk. Entries only until 1:00 PM. Everything closed at 3:15 PM.
 * The ML model learns which breakouts tend to reach target before stop.
 */
object Strategy {

    val FEATURE_NAMES = listOf(
        "Gap vs ATR",
        "Opening range size",
        "Opening volume",
        "Close inside range",
        "Price vs VWAP",
        "Yesterday's move",
        "5-day move",
        "Trend vs 20-day EMA",
        "RSI",
        "Yesterday's close position",
        "NIFTY gap",
        "NIFTY first 15 min",
        "Stop size vs ATR",
        "Room to yesterday's extreme"
    )

    class Setup(val plan: TradePlan, val features: DoubleArray, val reasons: List<String>)

    fun marketContext(history: List<Session>, today: Session?): MarketContext {
        if (today == null || history.isEmpty() || !today.orComplete) return MarketContext.NONE
        val prevClose = history.last().close
        val or = today.orBars
        return MarketContext(
            gapPct = (today.open - prevClose) / prevClose * 100,
            orMovePct = (or.last().c - today.open) / today.open * 100
        )
    }

    fun setup(history: List<Session>, today: Session, dir: Direction, mkt: MarketContext, rule: PlanRule = PlanRule.STOCK): Setup? {
        if (history.size < Rules.MIN_HISTORY_DAYS || !today.orComplete) return null
        val s = dir.sign.toDouble()
        val prev = history.last()
        val prevClose = prev.close
        val closes = history.map { it.close }
        val atrPct = max(Indicators.atr(history) / prevClose, 0.003)
        val ema20 = Indicators.ema(closes, 20)
        val rsi = Indicators.rsi(closes)

        val or = today.orBars
        val orh = or.maxOf { it.h }
        val orl = or.minOf { it.l }
        val orClose = or.last().c
        val orRange = max(orh - orl, orClose * 0.0005)
        val orVol = or.sumOf { it.v }.toDouble()
        val avgOrVol = history.takeLast(10).map { d -> d.orBars.sumOf { it.v }.toDouble() }
            .filter { it > 0 }.ifEmpty { listOf(1.0) }.average()
        val vwap = Indicators.vwap(or)

        // Trade plan
        val entry = if (dir == Direction.LONG) orh * (1 + Rules.ENTRY_BUFFER) else orl * (1 - Rules.ENTRY_BUFFER)
        val rawRisk = if (dir == Direction.LONG) entry - orl else orh - entry
        val risk = rule.fixedRiskPoints ?: rawRisk.coerceIn(entry * Rules.MIN_RISK, entry * Rules.MAX_RISK)
        val stop = entry - s * risk
        val target = entry + s * risk * rule.rewardR
        val plan = TradePlan(dir, entry, stop, target)

        val gap = (today.open - prevClose) / prevClose
        val ret1 = if (history.size >= 2) (prevClose - history[history.size - 2].close) / history[history.size - 2].close else 0.0
        val ret5 = if (history.size >= 6) (prevClose - history[history.size - 6].close) / history[history.size - 6].close else 0.0
        val prevRange = max(prev.high - prev.low, prevClose * 0.0005)
        val prevPos = (prevClose - prev.low) / prevRange
        val room = if (dir == Direction.LONG) (prev.high - entry) / entry else (entry - prev.low) / entry
        val volRatio = max(orVol, 1.0) / max(avgOrVol, 1.0)

        val f = doubleArrayOf(
            gap / atrPct * s,
            (orRange / orClose) / atrPct,
            ln(volRatio).coerceIn(-3.0, 3.0),
            (2 * (orClose - orl) / orRange - 1) * s,
            (orClose - vwap) / orClose / atrPct * s,
            ret1 / atrPct * s,
            ret5 / atrPct * s,
            (prevClose - ema20) / prevClose / atrPct * s,
            (rsi - 50) / 50 * s,
            (2 * prevPos - 1) * s,
            mkt.gapPct * s,
            mkt.orMovePct * s,
            (risk / entry) / atrPct,
            (room / atrPct).coerceIn(-5.0, 5.0)
        )

        val reasons = buildReasons(dir, gap, volRatio, orVol > 0, prevClose, ema20, rsi, mkt, room, orRange / orClose, atrPct)
        return Setup(plan, f, reasons)
    }

    private fun pct(x: Double) = String.format("%.2f%%", x * 100)

    private fun buildReasons(
        dir: Direction, gap: Double, volRatio: Double, hasVolume: Boolean, prevClose: Double, ema20: Double, rsi: Double,
        mkt: MarketContext, room: Double, orRangePct: Double, atrPct: Double
    ): List<String> {
        val r = mutableListOf<String>()
        val up = dir == Direction.LONG
        r += "First 15-min range: ${pct(orRangePct)} (daily ATR ${pct(atrPct)})"
        if (abs(gap) >= 0.003) r += "Opened with a gap ${if (gap > 0) "up" else "down"} of ${pct(abs(gap))}"
        if (hasVolume) r += when {
            volRatio >= 1.5 -> "Strong opening volume: ${String.format("%.1f", volRatio)}x normal"
            volRatio <= 0.7 -> "Weak opening volume: ${String.format("%.1f", volRatio)}x normal"
            else -> "Normal opening volume (${String.format("%.1f", volRatio)}x)"
        }
        val aboveEma = prevClose > ema20
        r += if (aboveEma == up) "Trend supports the trade (price ${if (aboveEma) "above" else "below"} 20-day EMA)"
        else "Against the trend (price ${if (aboveEma) "above" else "below"} 20-day EMA)"
        r += "RSI(14) daily: ${String.format("%.0f", rsi)}"
        if (abs(mkt.gapPct) >= 0.2) {
            val agrees = (mkt.gapPct > 0) == up
            r += "NIFTY ${if (mkt.gapPct > 0) "gapped up" else "gapped down"} ${String.format("%.2f", abs(mkt.gapPct))}% — ${if (agrees) "supports" else "against"} this trade"
        }
        r += if (room > 0) "Room to yesterday's ${if (up) "high" else "low"}: ${pct(room)}"
        else "Already beyond yesterday's ${if (up) "high" else "low"} (breakout zone)"
        return r
    }

    /**
     * Walk the bars after the opening range and see what the trade did.
     * Conservative: if stop and target are touched in the same 5-min bar, it counts as a loss.
     */
    fun simulate(today: Session, plan: TradePlan): TradeResult {
        val bars = today.bars
        val long = plan.direction == Direction.LONG
        var entered = false
        var fill = 0.0
        var entryT = 0L
        var last = bars.last().c
        for (i in today.orBars.size until bars.size) {
            val b = bars[i]
            val t = b.time
            last = b.c
            if (!t.isBefore(Rules.EXIT_TIME)) {
                if (entered) {
                    val exit = b.o
                    return TradeResult(TradeStatus.TIME_EXIT, entryT, fill, b.t, exit, rMult(plan, fill, exit), exit)
                }
                return TradeResult(TradeStatus.NOT_TRIGGERED, lastPrice = last)
            }
            if (!entered) {
                if (!t.isBefore(Rules.ENTRY_DEADLINE)) continue
                val trig = if (long) b.h >= plan.entry else b.l <= plan.entry
                if (!trig) continue
                entered = true
                entryT = b.t
                fill = if (long) max(plan.entry, b.o) else min(plan.entry, b.o)
                // In the entry bar, only a stop-loss is assumed possible after the fill (conservative),
                // unless the bar closed beyond the target.
                val stopHit = if (long) b.l <= plan.stop else b.h >= plan.stop
                if (stopHit) return TradeResult(TradeStatus.LOSS, entryT, fill, b.t, plan.stop, rMult(plan, fill, plan.stop), b.c)
                val tgtHit = if (long) b.c >= plan.target else b.c <= plan.target
                if (tgtHit) return TradeResult(TradeStatus.WIN, entryT, fill, b.t, plan.target, rMult(plan, fill, plan.target), b.c)
                continue
            }
            val stopHit = if (long) b.l <= plan.stop else b.h >= plan.stop
            val tgtHit = if (long) b.h >= plan.target else b.l <= plan.target
            if (stopHit) {
                val exit = if (long) min(plan.stop, b.o) else max(plan.stop, b.o) // gap through stop
                return TradeResult(TradeStatus.LOSS, entryT, fill, b.t, exit, rMult(plan, fill, exit), b.c)
            }
            if (tgtHit) {
                val exit = if (long) max(plan.target, b.o) else min(plan.target, b.o)
                return TradeResult(TradeStatus.WIN, entryT, fill, b.t, exit, rMult(plan, fill, exit), b.c)
            }
        }
        // Session not finished yet (live) or data ended early
        val lastBar = bars.last()
        val sessionOver = !lastBar.time.isBefore(Rules.EXIT_TIME.minusMinutes(5))
        return when {
            entered && sessionOver -> TradeResult(TradeStatus.TIME_EXIT, entryT, fill, lastBar.t, last, rMult(plan, fill, last), last)
            entered -> TradeResult(TradeStatus.ACTIVE, entryT, fill, r = rMult(plan, fill, last), lastPrice = last)
            !lastBar.time.isBefore(Rules.ENTRY_DEADLINE) -> TradeResult(TradeStatus.NOT_TRIGGERED, lastPrice = last)
            else -> TradeResult(TradeStatus.WAITING, lastPrice = last)
        }
    }

    private fun rMult(plan: TradePlan, fill: Double, exit: Double): Double =
        (exit - fill) * plan.direction.sign / plan.risk

    /** Pre-market ranking: which stocks look "ready to move" today. */
    fun watchItem(symbol: String, history: List<Session>, newsScore: Double?): WatchItem? {
        if (history.size < Rules.MIN_HISTORY_DAYS) return null
        val lv = Indicators.levels(history)
        val last7 = history.takeLast(7)
        val prev = history.last()
        val nr7 = last7.all { (it.high - it.low) >= (prev.high - prev.low) }
        val volAvg = history.takeLast(21).dropLast(1).map { it.volume.toDouble() }.average()
        val volRatio = if (volAvg > 0) prev.volume / volAvg else 1.0
        val high20 = history.takeLast(20).maxOf { it.high }
        val low20 = history.takeLast(20).minOf { it.low }
        val notes = mutableListOf<String>()
        var score = 0.0
        var biasScore = 0.0
        if (nr7) { score += 2; notes += "NR7: narrowest range of 7 days — breakout likely" }
        if (volRatio >= 1.5) { score += 1.5; notes += "Yesterday's volume ${String.format("%.1f", volRatio)}x average" }
        if (prev.close >= high20 * 0.99) { score += 1.5; biasScore += 1; notes += "Closing near 20-day high" }
        if (prev.close <= low20 * 1.01) { score += 1.5; biasScore -= 1; notes += "Closing near 20-day low" }
        if (lv.atrPct >= 1.5) { score += 1; notes += "Good daily movement (ATR ${String.format("%.1f", lv.atrPct)}%)" }
        if (prev.close > lv.ema20) biasScore += 0.5 else biasScore -= 0.5
        if (lv.rsi > 60) biasScore += 0.5 else if (lv.rsi < 40) biasScore -= 0.5
        newsScore?.let {
            if (abs(it) >= 0.25) {
                score += 1; biasScore += it * 2
                notes += "News tone: ${if (it > 0) "positive" else "negative"}"
            }
        }
        val bias = when { biasScore >= 1 -> Direction.LONG; biasScore <= -1 -> Direction.SHORT; else -> null }
        return WatchItem(symbol, score, bias, lv, notes)
    }
}
