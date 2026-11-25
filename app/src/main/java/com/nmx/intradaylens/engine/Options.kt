package com.nmx.intradaylens.engine

import kotlin.math.roundToLong

/** Simple option idea for an index pick: BUY signal -> CALL (CE), SELL signal -> PUT (PE), at-the-money strike. */
data class OptionIdea(val type: String, val strike: Long, val text: String, val shortLabel: String)

object Options {
    private val strikeStep = mapOf("NIFTY" to 50.0, "BANKNIFTY" to 100.0)

    fun idea(symbol: String, plan: TradePlan): OptionIdea? {
        val step = strikeStep[symbol] ?: return null
        val strike = ((plan.entry / step).roundToLong() * step).toLong()
        val isCall = plan.direction == Direction.LONG
        val type = if (isCall) "CE" else "PE"
        val name = if (symbol == "BANKNIFTY") "BANKNIFTY" else "NIFTY"
        return OptionIdea(
            type = type,
            strike = strike,
            text = "Buy $name $strike $type (${if (isCall) "CALL" else "PUT"}, nearest expiry)",
            shortLabel = if (isCall) "CALL" else "PUT"
        )
    }
}
