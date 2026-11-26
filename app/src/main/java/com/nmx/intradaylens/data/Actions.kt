package com.nmx.intradaylens.data

import com.nmx.intradaylens.engine.Direction
import com.nmx.intradaylens.engine.IST
import com.nmx.intradaylens.engine.Options
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.engine.Rules
import com.nmx.intradaylens.engine.Strength
import com.nmx.intradaylens.engine.TradeStatus
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs

enum class ActionKind { WAIT, BUY_NOW, HOLD, EXIT_NOW, DONE_WIN, DONE_LOSS, DONE_EVEN, DONE_TIME, NO_ENTRY, SKIP }

/** The one big word a card shows, plus one line of detail. */
data class Action(val kind: ActionKind, val title: String, val detail: String)

/** The 5 parts of the trading day shown in the clock bar. */
enum class DayStep(val label: String, val hint: String) {
    BEFORE("Get ready", "Market opens at 9:15 AM. Check the watchlist."),
    OPENING("Wait", "First 15 minutes: no trades. Picks at 9:30 AM."),
    ENTRY("Entry time", "Enter only when the app says Buy now (till 1:00 PM)."),
    MANAGE("Manage trades", "No new entries after 1:00 PM. Hold or exit open trades."),
    EXIT("Exit everything", "3:15 PM: close all open trades now."),
    CLOSED("Market closed", "See today's results. Next picks at 9:30 AM.");

    companion object {
        fun now(n: ZonedDateTime = Repository.now()): DayStep {
            if (n.dayOfWeek.value >= 6) return CLOSED
            val t = n.toLocalTime()
            return when {
                t.isBefore(Rules.MARKET_OPEN) -> BEFORE
                t.isBefore(Rules.PICKS_READY) -> OPENING
                t.isBefore(Rules.ENTRY_DEADLINE) -> ENTRY
                t.isBefore(Rules.EXIT_TIME) -> MANAGE
                t.isBefore(Rules.MARKET_CLOSE) -> EXIT
                else -> CLOSED
            }
        }
    }
}

object Actions {
    private fun hm(sec: Long) = Instant.ofEpochSecond(sec).atZone(IST).toLocalTime().hm()
    private fun p(x: Double) = if (x >= 1000) String.format("%,.0f", x) else String.format("%.2f", x)

    fun tradeable(pick: Pick, s: Settings): Boolean =
        if (s.strictMode) pick.strength == Strength.STRONG else pick.strength == Strength.STRONG || pick.strength == Strength.GOOD

    /** What to do, in plain words. */
    fun entryInstruction(pick: Pick): String {
        val opt = Options.idea(pick.symbol, pick.plan)
        val above = pick.plan.direction == Direction.LONG
        val lvl = p(pick.plan.entry)
        return when {
            opt != null -> "${opt.text.substringBefore(" (")} when a 5-min candle closes ${if (above) "above" else "below"} $lvl"
            above -> "Buy if a 5-min candle closes above $lvl"
            else -> "Sell (short) if a 5-min candle closes below $lvl"
        }
    }

    fun actionFor(pick: Pick, s: Settings, blockReason: String?, isToday: Boolean, now: ZonedDateTime = Repository.now()): Action {
        val r = pick.result
        val idx = Repository.isIndex(pick.symbol)
        val opt = Options.idea(pick.symbol, pick.plan)
        if (!tradeable(pick, s)) return Action(ActionKind.SKIP, "Skip today", "Setup not good enough. No trade.")
        val stop = r.stop ?: pick.plan.stop
        val target = r.target ?: pick.plan.target
        return when (r.status) {
            TradeStatus.ACTIVE -> {
                val t = now.toLocalTime()
                val justIn = r.entryTime != null && now.toEpochSecond() - r.entryTime <= 10 * 60
                when {
                    isToday && !t.isBefore(Rules.EXIT_TIME) -> Action(ActionKind.EXIT_NOW, "Exit now", "It's 3:15 PM — close this trade.")
                    isToday && justIn -> Action(ActionKind.BUY_NOW,
                        if (idx) "Buy now" else if (pick.plan.direction == Direction.LONG) "Buy now" else "Sell now",
                        (opt?.text?.substringBefore(" (") ?: "Entry ${p(r.fillPrice ?: pick.plan.entry)}") +
                            " • Exit if ${p(stop)} • Target ${p(target)}")
                    pick.rule.trail && r.fillPrice != null && abs(stop - r.fillPrice) < pick.rule.fixedRiskPoints.let { (it ?: 0.0) - 0.5 } ->
                        Action(ActionKind.HOLD, if (r.movedToCost) "Hold — profit locked" else "Hold — stop moved",
                            "Trailing stop now ${p(stop)} ${if (pick.plan.direction == Direction.LONG) "↑" else "↓"} • Target ${p(target)}")
                    r.movedToCost -> Action(ActionKind.HOLD, "Hold — no-loss stop",
                        "Move your stop-loss to ${p(r.fillPrice ?: pick.plan.entry)} (your entry). Target ${p(target)}.")
                    else -> Action(ActionKind.HOLD, "Hold", "Entered ${r.entryTime?.let { hm(it) } ?: ""} • Exit if ${p(stop)} • Target ${p(target)}")
                }
            }
            TradeStatus.WIN -> Action(ActionKind.DONE_WIN, "Done: target hit", doneLine(pick))
            TradeStatus.LOSS -> Action(ActionKind.DONE_LOSS, "Done: stop-loss hit", doneLine(pick))
            TradeStatus.BREAKEVEN -> Action(ActionKind.DONE_EVEN, "Done: exited at cost", doneLine(pick))
            TradeStatus.TRAIL_EXIT -> Action(ActionKind.DONE_WIN, "Done: profit locked", "Trailing stop exit • " + doneLine(pick))
            TradeStatus.TIME_EXIT -> Action(ActionKind.DONE_TIME, "Done: closed at 3:15", doneLine(pick))
            TradeStatus.NOT_TRIGGERED -> Action(ActionKind.NO_ENTRY, "No entry today", "Price never confirmed the breakout before 1:00 PM.")
            TradeStatus.WAITING -> when {
                blockReason != null -> Action(ActionKind.SKIP, "Skip today", blockReason)
                isToday && now.toLocalTime().isBefore(Rules.PICKS_READY) -> Action(ActionKind.WAIT, "Wait", "Picks confirm at 9:30 AM.")
                else -> Action(ActionKind.WAIT, "Wait", entryInstruction(pick))
            }
        }
    }

    private fun doneLine(pick: Pick): String {
        val r = pick.result
        val inn = r.entryTime?.let { "In ${p(r.fillPrice ?: 0.0)} at ${hm(it)}" } ?: ""
        val out = r.exitTime?.let { ", out ${p(r.exitPrice ?: 0.0)} at ${hm(it)}" } ?: ""
        val pts = if (r.fillPrice != null && r.exitPrice != null)
            (r.exitPrice - r.fillPrice) * pick.plan.direction.sign else 0.0
        val res = if (Repository.isIndex(pick.symbol)) String.format(" (%+.0f pts)", pts) else String.format(" (%+.1fR)", r.r)
        return inn + out + res
    }
}
