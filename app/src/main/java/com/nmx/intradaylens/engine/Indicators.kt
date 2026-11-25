package com.nmx.intradaylens.engine

import kotlin.math.abs
import kotlin.math.max

object Indicators {

    /** Split raw 5-minute candles into market sessions (9:15–15:30 IST). */
    fun sessions(candles: List<Candle>): List<Session> =
        candles
            .filter { it.c > 0 && it.h > 0 && it.l > 0 && it.o > 0 }
            .filter { val t = it.time; !t.isBefore(Rules.MARKET_OPEN) && t.isBefore(Rules.MARKET_CLOSE) }
            .groupBy { it.date }
            .toSortedMap()
            .map { (d, bars) -> Session(d, bars.sortedBy { it.t }) }

    fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var e = values.first()
        for (i in 1 until values.size) e = values[i] * k + e * (1 - k)
        return e
    }

    /** Wilder RSI on closing prices. */
    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period; loss /= period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + max(d, 0.0)) / period
            loss = (loss * (period - 1) + max(-d, 0.0)) / period
        }
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100 - 100 / (1 + rs)
    }

    /** Average true range of daily sessions (simple average of last [period]). */
    fun atr(days: List<Session>, period: Int = 14): Double {
        if (days.size < 2) return days.firstOrNull()?.let { it.high - it.low } ?: 0.0
        val trs = (1 until days.size).map { i ->
            val d = days[i]; val pc = days[i - 1].close
            maxOf(d.high - d.low, abs(d.high - pc), abs(d.low - pc))
        }
        return trs.takeLast(period).average()
    }

    fun vwap(bars: List<Candle>): Double {
        var pv = 0.0; var vol = 0.0
        for (b in bars) { val tp = (b.h + b.l + b.c) / 3; pv += tp * b.v; vol += b.v }
        return if (vol > 0) pv / vol else bars.last().c
    }

    fun levels(history: List<Session>): DayLevels {
        val prev = history.last()
        val closes = history.map { it.close }
        val p = (prev.high + prev.low + prev.close) / 3
        return DayLevels(
            prevHigh = prev.high,
            prevLow = prev.low,
            prevClose = prev.close,
            pivot = p,
            r1 = 2 * p - prev.low,
            s1 = 2 * p - prev.high,
            atrPct = atr(history) / prev.close * 100,
            rsi = rsi(closes),
            ema20 = ema(closes, 20)
        )
    }
}
