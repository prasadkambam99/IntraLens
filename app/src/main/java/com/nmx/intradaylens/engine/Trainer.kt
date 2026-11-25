package com.nmx.intradaylens.engine

import java.time.LocalDate
import kotlin.math.ln

class Sample(
    val symbol: String,
    val date: LocalDate,
    val dir: Direction,
    val x: DoubleArray,
    val result: TradeResult
) {
    val triggered get() = result.status == TradeStatus.WIN || result.status == TradeStatus.LOSS || result.status == TradeStatus.TIME_EXIT
    val label get() = if (result.status == TradeStatus.WIN) 1 else 0
}

data class Bucket(val label: String, val count: Int, val avgPredicted: Double, val actualWinRate: Double)

data class ModelReport(
    val trainedAt: Long,
    val stocks: Int,
    val days: Int,
    val trainSamples: Int,
    val testSamples: Int,
    val testFrom: String,
    val testTo: String,
    val baseWinRate: Double,       // how often ANY breakout reached target (test period)
    val modelLogLoss: Double,
    val baselineLogLoss: Double,   // always predicting the base rate
    val buckets: List<Bucket>,
    val topPicks: Int,             // model's daily top picks in the test period
    val topTriggered: Int,
    val topWinRate: Double,
    val topAvgR: Double,
    val allAvgR: Double,           // every breakout, no model
    val featureWeights: List<Pair<String, Double>>
) {
    val modelBeatsBaseline get() = modelLogLoss < baselineLogLoss
}

object Trainer {

    /** Build one sample per stock / day / direction from complete past sessions. */
    fun buildSamples(
        data: Map<String, List<Session>>,
        market: List<Session>,
        excludeDate: LocalDate? = null,
        rule: PlanRule = PlanRule.STOCK,
        /** Optional finer bars (e.g. 1-minute) used for the trade itself; history still comes from [data]. */
        intraday: Map<String, List<Session>>? = null
    ): List<Sample> {
        val mktByDate = market.withIndex().associate { (i, s) -> s.date to i }
        val out = ArrayList<Sample>()
        for ((sym, sessions) in data) {
            val days = intraday?.get(sym) ?: if (intraday != null) emptyList() else sessions
            for (day in days) {
                val i = sessions.indexOfFirst { it.date == day.date }.let { if (it < 0) sessions.count { s -> s.date < day.date } else it }
                if (i < Rules.MIN_HISTORY_DAYS) continue
                if (day.date == excludeDate) continue
                if (day.bars.last().time.isBefore(Rules.EXIT_TIME.minusMinutes(5))) continue // incomplete day
                val hist = sessions.subList(maxOf(0, i - 40), i)
                val mi = mktByDate[day.date]
                val mkt = if (mi != null && mi > 0) Strategy.marketContext(market.subList(maxOf(0, mi - 5), mi), market[mi]) else MarketContext.NONE
                for (dir in Direction.values()) {
                    val st = Strategy.setup(hist, day, dir, mkt, rule) ?: continue
                    out += Sample(sym, day.date, dir, st.features, Strategy.simulate(day, st.plan))
                }
            }
        }
        return out
    }

    /**
     * Walk-forward check: train on the older 70% of days, test on the newest 30%.
     * Then the live model is retrained on all days.
     */
    fun trainAndEvaluate(samples: List<Sample>, stocks: Int, topN: Int = 5, minProb: Double = 0.0, lambda: Double = 2.0, minTriggered: Int = 60): Pair<LogisticModel, ModelReport>? {
        val trig = samples.filter { it.triggered }
        if (trig.size < minTriggered) return null
        val dates = samples.map { it.date }.distinct().sorted()
        val cut = dates[(dates.size * 0.7).toInt().coerceIn(1, dates.size - 1)]
        val train = trig.filter { it.date < cut }
        val testAll = samples.filter { it.date >= cut }
        val test = testAll.filter { it.triggered }
        if (train.size < minTriggered * 2 / 3 || test.isEmpty()) return null

        val m = LogisticModel.train(train.map { it.x }, train.map { it.label }, lambda)
        val base = train.map { it.label }.average().coerceIn(0.01, 0.99)
        var ll = 0.0; var llb = 0.0
        val preds = test.map { m.predict(it.x) }
        test.forEachIndexed { k, s ->
            val p = preds[k].coerceIn(1e-6, 1 - 1e-6)
            ll -= if (s.label == 1) ln(p) else ln(1 - p)
            llb -= if (s.label == 1) ln(base) else ln(1 - base)
        }
        val edges = listOf(0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 1.01)
        val buckets = (0 until edges.size - 1).mapNotNull { b ->
            val idx = preds.indices.filter { preds[it] >= edges[b] && preds[it] < edges[b + 1] }
            if (idx.isEmpty()) null else Bucket(
                "${(edges[b] * 100).toInt()}–${minOf(100, (edges[b + 1] * 100).toInt())}%",
                idx.size, idx.map { preds[it] }.average(), idx.map { test[it].label }.average()
            )
        }

        // Simulate the app: each test day, each stock's better direction, keep the top N.
        val chosen = testAll.groupBy { it.date to it.symbol }.values
            .map { both -> both.maxByOrNull { m.predict(it.x) }!! }
            .groupBy { it.date }
            .flatMap { (_, list) -> list.sortedByDescending { m.predict(it.x) }.filter { m.predict(it.x) >= minProb }.take(topN) }
        val chosenTrig = chosen.filter { it.triggered }

        val full = LogisticModel.train(trig.map { it.x }, trig.map { it.label }, lambda)
        val report = ModelReport(
            trainedAt = System.currentTimeMillis(),
            stocks = stocks,
            days = dates.size,
            trainSamples = train.size,
            testSamples = test.size,
            testFrom = cut.toString(),
            testTo = dates.last().toString(),
            baseWinRate = test.map { it.label }.average(),
            modelLogLoss = ll / test.size,
            baselineLogLoss = llb / test.size,
            buckets = buckets,
            topPicks = chosen.size,
            topTriggered = chosenTrig.size,
            topWinRate = if (chosenTrig.isEmpty()) 0.0 else chosenTrig.map { it.label }.average(),
            topAvgR = if (chosenTrig.isEmpty()) 0.0 else chosenTrig.map { it.result.r }.average(),
            allAvgR = test.map { it.result.r }.average(),
            featureWeights = Strategy.FEATURE_NAMES.mapIndexed { i, n -> n to full.weights[i + 1] }
        )
        return full to report
    }

    /** Today's (or any day's) picks: best direction per stock, ranked by probability. */
    fun scanDay(
        date: LocalDate,
        data: Map<String, List<Session>>,
        market: List<Session>,
        model: LogisticModel,
        rule: PlanRule = PlanRule.STOCK,
        intraday: Map<String, List<Session>>? = null
    ): List<Pick> {
        val mi = market.indexOfFirst { it.date == date }
        val mkt = if (mi > 0) Strategy.marketContext(market.subList(maxOf(0, mi - 5), mi), market[mi]) else MarketContext.NONE
        val picks = ArrayList<Pick>()
        for ((sym, sessions) in data) {
            val i0 = sessions.indexOfFirst { it.date == date }
            val i = if (i0 < 0) sessions.count { it.date < date } else i0
            if (i < Rules.MIN_HISTORY_DAYS) continue
            val day = intraday?.get(sym)?.firstOrNull { it.date == date } ?: sessions.getOrNull(i0) ?: continue
            val hist = sessions.subList(maxOf(0, i - 40), i)
            val best = Direction.values().mapNotNull { dir ->
                Strategy.setup(hist, day, dir, mkt, rule)?.let { it to model.predict(it.features) }
            }.maxByOrNull { it.second } ?: continue
            val (st, p) = best
            picks += Pick(sym, date, st.plan, p, st.reasons, st.features, Strategy.simulate(day, st.plan), Indicators.levels(hist))
        }
        return picks.sortedByDescending { it.probability }
    }
}
