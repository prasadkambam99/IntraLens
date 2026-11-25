package com.nmx.intradaylens.engine

/**
 * Tiny offline news-tone scorer for financial headlines.
 * Returns -1 (very negative) .. +1 (very positive). Shown for information; it is not
 * used by the probability model because there is no news history to learn from.
 */
object Sentiment {
    private val positive = listOf(
        "surge", "surges", "jump", "jumps", "rally", "rallies", "soar", "soars", "gain", "gains", "rise", "rises",
        "record high", "beats", "beat estimates", "profit rises", "profit jumps", "upgrade", "upgrades", "buy rating",
        "outperform", "order win", "wins order", "bags order", "bags", "secures", "approval", "approves", "expansion",
        "dividend", "bonus", "buyback", "strong", "robust", "bullish", "upside", "target raised", "raises target",
        "top gainer", "52-week high", "all-time high", "growth", "boost", "partnership", "launches"
    )
    private val negative = listOf(
        "fall", "falls", "drop", "drops", "slump", "slumps", "plunge", "plunges", "decline", "declines", "slide", "slides",
        "tumble", "tumbles", "crash", "loss", "losses", "miss", "misses", "downgrade", "downgrades", "sell rating",
        "underperform", "probe", "penalty", "fine", "raid", "fraud", "lawsuit", "weak", "cut", "cuts", "bearish",
        "resigns", "resignation", "default", "ban", "top loser", "52-week low", "slowdown", "concern", "warning",
        "target cut", "lowers target", "pledge", "selloff", "sell-off", "layoffs"
    )

    fun score(headline: String): Double {
        val h = " " + headline.lowercase() + " "
        val p = positive.count { h.contains(" $it") }
        val n = negative.count { h.contains(" $it") }
        return if (p + n == 0) 0.0 else (p - n).toDouble() / (p + n)
    }

    fun average(headlines: List<String>): Double? {
        val scored = headlines.map { score(it) }.filter { it != 0.0 }
        return if (scored.isEmpty()) null else scored.average()
    }
}
