package com.nmx.intradaylens.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** One price bar. [t] = bar start time, epoch seconds. */
data class Candle(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Long
) {
    val time: LocalTime get() = Instant.ofEpochSecond(t).atZone(IST).toLocalTime()
    val date: LocalDate get() = Instant.ofEpochSecond(t).atZone(IST).toLocalDate()
}

/** All 5-minute bars of one trading session. */
data class Session(val date: LocalDate, val bars: List<Candle>) {
    val open get() = bars.first().o
    val high get() = bars.maxOf { it.h }
    val low get() = bars.minOf { it.l }
    val close get() = bars.last().c
    val volume get() = bars.sumOf { it.v }
}

enum class Direction(val sign: Int, val label: String) {
    LONG(1, "BUY"), SHORT(-1, "SELL")
}

enum class TradeStatus(val label: String) {
    WAITING("Waiting for entry"),
    ACTIVE("In trade"),
    WIN("Target hit"),
    LOSS("Stop-loss hit"),
    TIME_EXIT("Closed at 3:15 PM"),
    NOT_TRIGGERED("Entry never triggered")
}

/** Trading rules used everywhere (training, back-test and live). */
object Rules {
    const val OR_BARS = 3                         // opening range = first 15 minutes
    val ENTRY_DEADLINE: LocalTime = LocalTime.of(13, 0)
    val EXIT_TIME: LocalTime = LocalTime.of(15, 15)
    val MARKET_OPEN: LocalTime = LocalTime.of(9, 15)
    val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
    val PICKS_READY: LocalTime = LocalTime.of(9, 30)
    const val ENTRY_BUFFER = 0.0005               // 0.05% above/below the range
    const val MIN_RISK = 0.0035                   // stop at least 0.35% away
    const val MAX_RISK = 0.012                    // stop at most 1.2% away
    const val REWARD_R = 1.5                      // target = 1.5 x risk
    const val MIN_HISTORY_DAYS = 21
}

/** Trade plan for one stock / one direction on one day. */
data class TradePlan(
    val direction: Direction,
    val entry: Double,
    val stop: Double,
    val target: Double
) {
    val risk get() = kotlin.math.abs(entry - stop)
    val riskPct get() = risk / entry * 100
    val rewardPct get() = kotlin.math.abs(target - entry) / entry * 100
}

data class TradeResult(
    val status: TradeStatus,
    val entryTime: Long? = null,
    val fillPrice: Double? = null,
    val exitTime: Long? = null,
    val exitPrice: Double? = null,
    /** Result in R (1R = risk taken). Live R while ACTIVE. */
    val r: Double = 0.0,
    val lastPrice: Double? = null
)

/** Context of the market index (NIFTY) for the same day. */
data class MarketContext(val gapPct: Double, val orMovePct: Double) {
    companion object { val NONE = MarketContext(0.0, 0.0) }
}

data class Pick(
    val symbol: String,
    val date: LocalDate,
    val plan: TradePlan,
    val probability: Double,
    val reasons: List<String>,
    val features: DoubleArray,
    val result: TradeResult,
    val levels: DayLevels
) {
    /** Expected R per trade if the entry triggers (ignores 3:15 exits). */
    val expectedR get() = probability * Rules.REWARD_R - (1 - probability)
}

/** Useful reference levels from the previous session. */
data class DayLevels(
    val prevHigh: Double,
    val prevLow: Double,
    val prevClose: Double,
    val pivot: Double,
    val r1: Double,
    val s1: Double,
    val atrPct: Double,
    val rsi: Double,
    val ema20: Double
)

/** Pre-market idea (before the opening range is complete). */
data class WatchItem(
    val symbol: String,
    val score: Double,
    val bias: Direction?,
    val levels: DayLevels,
    val notes: List<String>
)
