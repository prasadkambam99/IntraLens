package com.nmx.intradaylens.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy: 15-minute Opening Range Breakout (ORB), with confirmation.
 *  - BUY when a 5-minute candle CLOSES above the high of the first 15 minutes (SELL: closes below the low).
 *  - The breakout candle must have decent volume (stocks).
 *  - Stop-loss / target are set from the real entry price.
 *  - Halfway to target, the stop moves to the entry price (no-loss stop).
 *  - Entries only until 1:00 PM. Everything closed at 3:15 PM.
 * The AI learns which breakouts tend to reach target before stop.
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
        "Room to yesterday's extreme",
        "Sector gap",
        "Sector first 15 min",
        "India VIX level",
        "India VIX change"
    )

    class Setup(val plan: TradePlan, val features: DoubleArray, val reasons: List<String>, val orLow: Double, val orHigh: Double, val rule: PlanRule)

    /** Average high-low of 5-minute candles over the last few sessions (the "normal noise"). */
    fun avgFiveMinRange(history: List<Session>, days: Int = 3): Double {
        val bars = history.takeLast(days).flatMap { s ->
            if (s.barSeconds >= 300) s.bars
            else s.bars.chunked(5).map { c -> Candle(c.first().t, c.first().o, c.maxOf { it.h }, c.minOf { it.l }, c.last().c, c.sumOf { it.v }) }
        }
        return if (bars.isEmpty()) 0.0 else bars.map { it.h - it.l }.average()
    }

    /** Turn a "smart" rule into today's fixed number of points. */
    fun resolveRule(rule: PlanRule, history: List<Session>, price: Double): PlanRule {
        val m = rule.atrMult ?: return rule
        val noise = avgFiveMinRange(history)
        val risk = (m * noise).coerceIn(price * 0.0005, price * 0.006)
        return rule.copy(fixedRiskPoints = Math.round(risk).toDouble().coerceAtLeast(1.0), atrMult = null)
    }

    /** Trigger level for a direction (just beyond the opening range). */
    private fun trigger(orh: Double, orl: Double, dir: Direction) =
        if (dir == Direction.LONG) orh * (1 + Rules.ENTRY_BUFFER) else orl * (1 - Rules.ENTRY_BUFFER)

    /** Stop and target from an entry price. */
    fun levels(entry: Double, dir: Direction, orh: Double, orl: Double, rule: PlanRule): Pair<Double, Double> {
        val s = dir.sign
        val rawRisk = if (dir == Direction.LONG) entry - orl else orh - entry
        val risk = rule.fixedRiskPoints ?: rawRisk.coerceIn(entry * Rules.MIN_RISK, entry * Rules.MAX_RISK)
        return (entry - s * risk) to (entry + s * risk * rule.rewardR)
    }

    fun setup(history: List<Session>, today: Session, dir: Direction, mkt: MarketContext, rule0: PlanRule = PlanRule.STOCK): Setup? {
        if (history.size < Rules.MIN_HISTORY_DAYS || !today.orComplete) return null
        val rule = resolveRule(rule0, history, history.last().close)
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

        val entry = trigger(orh, orl, dir)
        val (stop, target) = levels(entry, dir, orh, orl, rule)
        val plan = TradePlan(dir, entry, stop, target)
        val risk = plan.risk

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
            (room / atrPct).coerceIn(-5.0, 5.0),
            mkt.sectorGapPct * s,
            mkt.sectorOrMovePct * s,
            ((mkt.vixLevel - 15) / 10).coerceIn(-2.0, 3.0),
            (mkt.vixChangePct / 10).coerceIn(-3.0, 3.0)
        )

        val reasons = buildReasons(dir, gap, volRatio, orVol > 0, prevClose, ema20, rsi, mkt, room, orRange / orClose, atrPct)
        return Setup(plan, f, reasons, orl, orh, rule)
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
        if (abs(mkt.sectorOrMovePct) >= 0.2 && (mkt.sectorOrMovePct != mkt.orMovePct)) {
            val agrees = (mkt.sectorOrMovePct > 0) == up
            r += "Sector ${if (mkt.sectorOrMovePct > 0) "up" else "down"} ${String.format("%.2f", abs(mkt.sectorOrMovePct))}% in first 15 min — ${if (agrees) "supports" else "against"}"
        }
        r += "India VIX ${String.format("%.1f", mkt.vixLevel)}" + if (abs(mkt.vixChangePct) >= 3) " (${String.format("%+.0f", mkt.vixChangePct)}% today)" else ""
        r += if (room > 0) "Room to yesterday's ${if (up) "high" else "low"}: ${pct(room)}"
        else "Already beyond yesterday's ${if (up) "high" else "low"} (breakout zone)"
        return r
    }

    /** True if this bar is the last bar of a 5-minute block (so its close = the 5-min candle close). */
    private fun closesFiveMinBlock(b: Candle, barSec: Long): Boolean {
        if (barSec >= 300) return true
        val minute = Instant.ofEpochSecond(b.t + barSec).atZone(IST).minute
        return minute % Rules.CONFIRM_MINUTES == 0
    }

    /**
     * Walk the bars after the opening range and see what the trade did.
     * Entry: 5-min candle close beyond the trigger (+ volume check). Fill = that close.
     * Conservative: if stop and target are touched in the same bar, it counts as a loss.
     */
    fun simulate(today: Session, plan: TradePlan, orLow: Double, orHigh: Double, rule: PlanRule): TradeResult {
        val bars = today.bars
        val long = plan.direction == Direction.LONG
        val barSec = today.barSeconds
        val start = today.orBars.size
        var entered = false
        var fill = 0.0
        var entryT = 0L
        var stop = plan.stop
        var target = plan.target
        var risk = plan.risk
        var moved = false
        var best = 0.0
        var last = bars.last().c
        var volSum = bars.take(start).sumOf { it.v }.toDouble()
        var volN = start
        // volume of the current 5-min block (for 1-min data)
        var blockVol = 0L

        fun rOf(exit: Double) = (exit - fill) * plan.direction.sign / risk

        for (i in start until bars.size) {
            val b = bars[i]
            val t = b.time
            last = b.c
            if (!t.isBefore(Rules.EXIT_TIME)) {
                if (entered) return TradeResult(TradeStatus.TIME_EXIT, entryT, fill, b.t, b.o, rOf(b.o), b.o, stop, target, moved)
                return TradeResult(TradeStatus.NOT_TRIGGERED, lastPrice = last)
            }
            if (!entered) {
                blockVol += b.v
                val blockDone = closesFiveMinBlock(b, barSec)
                if (blockDone && t.isBefore(Rules.ENTRY_DEADLINE)) {
                    val closedBeyond = if (long) b.c > plan.entry else b.c < plan.entry
                    val avgVol = if (volN > 0) volSum / volN else 0.0
                    val blockBars = (300 / barSec).toInt().coerceAtLeast(1)
                    val volOk = blockVol == 0L || avgVol <= 0 || blockVol.toDouble() / blockBars >= Rules.VOLUME_CONFIRM * avgVol
                    if (closedBeyond && volOk) {
                        entered = true
                        entryT = b.t
                        fill = b.c
                        val lv = levels(fill, plan.direction, orHigh, orLow, rule)
                        stop = lv.first; target = lv.second
                        risk = abs(fill - stop)
                        best = fill
                    }
                }
                if (blockDone) blockVol = 0L
                volSum += b.v; volN++
                continue
            }
            val stopHit = if (long) b.l <= stop else b.h >= stop
            val tgtHit = if (long) b.h >= target else b.l <= target
            if (stopHit) {
                val exit = if (long) min(stop, b.o) else max(stop, b.o)
                val rr = rOf(exit)
                val st = when {
                    rr > 0.05 -> TradeStatus.TRAIL_EXIT
                    moved -> TradeStatus.BREAKEVEN
                    else -> TradeStatus.LOSS
                }
                return TradeResult(st, entryT, fill, b.t, exit, rr, b.c, stop, target, moved)
            }
            if (tgtHit) {
                val exit = if (long) max(target, b.o) else min(target, b.o)
                return TradeResult(TradeStatus.WIN, entryT, fill, b.t, exit, rOf(exit), b.c, stop, target, moved)
            }
            if (rule.trail) {
                // Trailing stop: follow the best price, keep the same distance, never move back (applies from next bar)
                best = if (long) max(best, b.h) else min(best, b.l)
                val cand = best - plan.direction.sign * risk
                if (if (long) cand > stop else cand < stop) stop = cand
                if (if (long) stop >= fill else stop <= fill) moved = true
            } else if (!moved) {
                // No-loss stop: once price has gone halfway to target, move stop to entry (applies from next bar)
                val half = fill + plan.direction.sign * abs(target - fill) * Rules.BREAKEVEN_AT
                if (if (long) b.h >= half else b.l <= half) { moved = true; stop = fill }
            }
        }
        val lastBar = bars.last()
        val sessionOver = !lastBar.time.isBefore(Rules.EXIT_TIME.minusMinutes(5))
        return when {
            entered && sessionOver -> TradeResult(TradeStatus.TIME_EXIT, entryT, fill, lastBar.t, last, rOf(last), last, stop, target, moved)
            entered -> TradeResult(TradeStatus.ACTIVE, entryT, fill, r = rOf(last), lastPrice = last, stop = stop, target = target, movedToCost = moved)
            !lastBar.time.isBefore(Rules.ENTRY_DEADLINE) -> TradeResult(TradeStatus.NOT_TRIGGERED, lastPrice = last)
            else -> TradeResult(TradeStatus.WAITING, lastPrice = last)
        }
    }

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
