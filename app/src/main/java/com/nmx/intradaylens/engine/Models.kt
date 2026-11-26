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

/** All bars (1-min or 5-min) of one trading session. */
data class Session(val date: LocalDate, val bars: List<Candle>) {
    val open get() = bars.first().o
    val high get() = bars.maxOf { it.h }
    val low get() = bars.minOf { it.l }
    val close get() = bars.last().c
    val volume get() = bars.sumOf { it.v }

    /** Bars of the opening range (9:15–9:30), whatever the bar size. */
    val orBars: List<Candle> get() = bars.filter { it.time.isBefore(Rules.PICKS_READY) }

    /** Bar length in seconds (300 for 5-min, 60 for 1-min). */
    val barSeconds: Long get() = if (bars.size >= 2) (bars[1].t - bars[0].t).coerceIn(60, 300) else 300

    /** True once the first 15 minutes are finished. */
    val orComplete: Boolean get() {
        val o = orBars
        if (o.isEmpty()) return false
        if (bars.size > o.size) return true
        val endOfLast = Instant.ofEpochSecond(o.last().t + barSeconds).atZone(IST).toLocalTime()
        return !endOfLast.isBefore(Rules.PICKS_READY)
    }
}

enum class Direction(val sign: Int, val label: String) {
    LONG(1, "BUY"), SHORT(-1, "SELL")
}

enum class TradeStatus(val label: String) {
    WAITING("Waiting for entry"),
    ACTIVE("In trade"),
    WIN("Target hit"),
    LOSS("Stop-loss hit"),
    BREAKEVEN("Exited at entry price"),
    TRAIL_EXIT("Trailing stop exit (profit)"),
    TIME_EXIT("Closed at 3:15 PM"),
    NOT_TRIGGERED("No entry today");

    val done get() = this == WIN || this == LOSS || this == BREAKEVEN || this == TRAIL_EXIT || this == TIME_EXIT || this == NOT_TRIGGERED
    val traded get() = this == WIN || this == LOSS || this == BREAKEVEN || this == TRAIL_EXIT || this == TIME_EXIT || this == ACTIVE
}

/** Simple label instead of a raw percentage. */
enum class Strength(val label: String) {
    STRONG("Strong"), GOOD("Good"), WEAK("Weak"), SKIP("Skip");

    companion object {
        /** Edge = probability minus the win rate needed to break even. */
        fun of(probability: Double, breakEven: Double): Strength {
            val edge = probability - breakEven
            return when {
                edge >= 0.15 -> STRONG
                edge >= 0.07 -> GOOD
                edge >= 0.0 -> WEAK
                else -> SKIP
            }
        }
    }
}

/** Trading rules used everywhere (training, back-test and live). */
object Rules {
    val ENTRY_DEADLINE: LocalTime = LocalTime.of(13, 0)
    val EXIT_TIME: LocalTime = LocalTime.of(15, 15)
    val MARKET_OPEN: LocalTime = LocalTime.of(9, 15)
    val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
    val PICKS_READY: LocalTime = LocalTime.of(9, 30)
    const val ENTRY_BUFFER = 0.0005               // 0.05% beyond the range
    const val MIN_RISK = 0.0035                   // stock stop at least 0.35% away
    const val MAX_RISK = 0.012                    // stock stop at most 1.2% away
    const val REWARD_R = 1.5                      // stock target = 1.5 x risk
    const val MIN_HISTORY_DAYS = 21
    const val CONFIRM_MINUTES = 5                 // entry only on a 5-minute candle CLOSE beyond the level
    const val VOLUME_CONFIRM = 0.7                // breakout candle volume >= 70% of the day's average so far
    const val BREAKEVEN_AT = 0.5                  // halfway to target -> move stop to entry
}

/**
 * How stop-loss and target are placed.
 * Stocks: stop at the other side of the opening range (0.35%–1.2%), target 1.5x risk.
 * Indices "smart": stop = [atrMult] x the normal size of a 5-minute candle (recent days), so normal noise doesn't hit it.
 * Indices "fixed": fixed points, e.g. 20 points stop and 20 points target.
 * [trail] = trailing stop: after entry the stop follows the best price, keeping the same distance; it never moves back.
 */
data class PlanRule(
    val fixedRiskPoints: Double? = null,
    val rewardR: Double = Rules.REWARD_R,
    val atrMult: Double? = null,
    val trail: Boolean = false
) {
    companion object {
        val STOCK = PlanRule()
        fun points(risk: Double, target: Double, trail: Boolean = false) = PlanRule(risk, target / risk, trail = trail)
        fun smart(mult: Double = 1.5, rewardR: Double = 1.5) = PlanRule(null, rewardR, mult, trail = true)
    }
}

/** Trade plan. Before entry, stop/target are estimates from the trigger level; they are fixed from the real fill. */
data class TradePlan(
    val direction: Direction,
    val entry: Double,
    val stop: Double,
    val target: Double
) {
    val risk get() = kotlin.math.abs(entry - stop)
    val reward get() = kotlin.math.abs(target - entry)
    val riskPct get() = risk / entry * 100
    val rewardPct get() = reward / entry * 100
    /** Reward-to-risk ratio. */
    val rr get() = reward / risk
    /** Win rate needed to break even. */
    val breakEven get() = 1.0 / (1.0 + rr)
}

data class TradeResult(
    val status: TradeStatus,
    val entryTime: Long? = null,
    val fillPrice: Double? = null,
    val exitTime: Long? = null,
    val exitPrice: Double? = null,
    /** Result in R (1R = risk taken). Live R while ACTIVE. */
    val r: Double = 0.0,
    val lastPrice: Double? = null,
    /** Real levels once entered (from the fill price). */
    val stop: Double? = null,
    val target: Double? = null,
    /** True once the stop is at or beyond the entry price (no-loss / trailing). */
    val movedToCost: Boolean = false
)

/** Market backdrop for one stock on one day. */
data class MarketContext(
    val gapPct: Double,
    val orMovePct: Double,
    val vixLevel: Double = 15.0,
    val vixChangePct: Double = 0.0,
    val sectorGapPct: Double = 0.0,
    val sectorOrMovePct: Double = 0.0
) {
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
    val levels: DayLevels,
    val orLow: Double = 0.0,
    val orHigh: Double = 0.0,
    val rule: PlanRule = PlanRule.STOCK
) {
    /** Expected R per trade if the entry triggers. */
    val expectedR get() = probability * plan.rr - (1 - probability)
    val strength get() = Strength.of(probability, plan.breakEven)
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
