package com.nmx.intradaylens.engine

import kotlin.random.Random

/**
 * Small gradient-boosted decision trees (like XGBoost/LightGBM, but tiny) for yes/no prediction.
 * Finds combinations such as "gap up + high volume + strong sector" that a straight-line model misses.
 */
class GradientBoost(
    private val base: Double,
    private val learningRate: Double,
    private val trees: List<Tree>
) {
    class Tree(
        val feature: IntArray,      // -1 = leaf
        val threshold: DoubleArray, // go left if x <= threshold
        val left: IntArray,
        val right: IntArray,
        val value: DoubleArray
    ) {
        fun eval(x: DoubleArray): Double {
            var n = 0
            while (feature[n] >= 0) n = if (x[feature[n]] <= threshold[n]) left[n] else right[n]
            return value[n]
        }
    }

    fun margin(x: DoubleArray): Double {
        var f = base
        for (t in trees) f += learningRate * t.eval(x)
        return f
    }

    fun predict(x: DoubleArray): Double = LogisticModel.sigmoid(margin(x))

    fun serialize(): String = buildString {
        append(base).append('~').append(learningRate)
        for (t in trees) {
            append('~')
            append(listOf(
                t.feature.joinToString(","), t.threshold.joinToString(","), t.left.joinToString(","),
                t.right.joinToString(","), t.value.joinToString(",")
            ).joinToString(":"))
        }
    }

    companion object {
        fun deserialize(s: String): GradientBoost? = runCatching {
            val parts = s.split("~")
            val trees = parts.drop(2).map { tp ->
                val a = tp.split(":")
                Tree(
                    a[0].split(",").map { it.toInt() }.toIntArray(),
                    a[1].split(",").map { it.toDouble() }.toDoubleArray(),
                    a[2].split(",").map { it.toInt() }.toIntArray(),
                    a[3].split(",").map { it.toInt() }.toIntArray(),
                    a[4].split(",").map { it.toDouble() }.toDoubleArray()
                )
            }
            GradientBoost(parts[0].toDouble(), parts[1].toDouble(), trees)
        }.getOrNull()

        fun train(
            xs: List<DoubleArray>, ys: List<Int>,
            nTrees: Int = 120, depth: Int = 3, learningRate: Double = 0.05,
            minLeaf: Int = 20, lambda: Double = 2.0, subsample: Double = 0.8, bins: Int = 24, seed: Int = 7
        ): GradientBoost {
            val n = xs.size
            val nf = xs[0].size
            val rnd = Random(seed)
            // Candidate thresholds per feature = quantiles
            val cuts = Array(nf) { j ->
                val sorted = xs.map { it[j] }.sorted()
                (1 until bins).map { k -> sorted[(k * (n - 1)) / bins] }.distinct().toDoubleArray()
            }
            val binOf = Array(n) { i -> IntArray(nf) { j -> cuts[j].indexOfFirst { xs[i][j] <= it }.let { if (it < 0) cuts[j].size else it } } }
            val baseRate = ys.average().coerceIn(0.02, 0.98)
            val base = kotlin.math.ln(baseRate / (1 - baseRate))
            val f = DoubleArray(n) { base }
            val trees = ArrayList<Tree>()
            repeat(nTrees) {
                val g = DoubleArray(n); val h = DoubleArray(n)
                for (i in 0 until n) { val p = LogisticModel.sigmoid(f[i]); g[i] = p - ys[i]; h[i] = maxOf(p * (1 - p), 1e-6) }
                val rows = (0 until n).filter { rnd.nextDouble() < subsample }.toIntArray()
                val feat = ArrayList<Int>(); val thr = ArrayList<Double>(); val lf = ArrayList<Int>(); val rt = ArrayList<Int>(); val v = ArrayList<Double>()

                fun build(idx: IntArray, d: Int): Int {
                    val node = feat.size
                    feat += -1; thr += 0.0; lf += -1; rt += -1; v += 0.0
                    var gs = 0.0; var hs = 0.0
                    for (i in idx) { gs += g[i]; hs += h[i] }
                    v[node] = -gs / (hs + lambda)
                    if (d >= depth || idx.size < 2 * minLeaf) return node
                    val parentScore = gs * gs / (hs + lambda)
                    var bestGain = 1e-6; var bestF = -1; var bestB = -1
                    for (j in 0 until nf) {
                        val nb = cuts[j].size + 1
                        val gb = DoubleArray(nb); val hb = DoubleArray(nb); val cb = IntArray(nb)
                        for (i in idx) { val b = binOf[i][j]; gb[b] += g[i]; hb[b] += h[i]; cb[b]++ }
                        var gl = 0.0; var hl = 0.0; var cl = 0
                        for (b in 0 until nb - 1) {
                            gl += gb[b]; hl += hb[b]; cl += cb[b]
                            val cr = idx.size - cl
                            if (cl < minLeaf || cr < minLeaf) continue
                            val gr = gs - gl; val hr = hs - hl
                            val gain = gl * gl / (hl + lambda) + gr * gr / (hr + lambda) - parentScore
                            if (gain > bestGain) { bestGain = gain; bestF = j; bestB = b }
                        }
                    }
                    if (bestF < 0) return node
                    val leftIdx = idx.filter { binOf[it][bestF] <= bestB }.toIntArray()
                    val rightIdx = idx.filter { binOf[it][bestF] > bestB }.toIntArray()
                    feat[node] = bestF
                    thr[node] = cuts[bestF][bestB]
                    val l = build(leftIdx, d + 1)
                    val r = build(rightIdx, d + 1)
                    lf[node] = l; rt[node] = r
                    return node
                }
                build(rows, 0)
                val tree = Tree(feat.toIntArray(), thr.toDoubleArray(), lf.toIntArray(), rt.toIntArray(), v.toDoubleArray())
                trees += tree
                for (i in 0 until n) f[i] += learningRate * tree.eval(xs[i])
            }
            return GradientBoost(base, learningRate, trees)
        }
    }
}

/**
 * The app's AI: a straight-line model (logistic regression) plus boosted trees.
 * The trainer tests all three options on unseen days and keeps whichever predicted best.
 */
class EnsembleModel(val lr: LogisticModel, val gb: GradientBoost?, val mode: Mode) {
    enum class Mode(val label: String) { LINEAR("Simple model"), TREES("Boosted trees"), BLEND("Blend of both") }

    fun predict(x: DoubleArray): Double = when {
        mode == Mode.LINEAR || gb == null -> lr.predict(x)
        mode == Mode.TREES -> gb.predict(x)
        else -> (lr.predict(x) + gb.predict(x)) / 2
    }

    fun serialize(): String = "v2;${mode.name};${lr.serialize()};${gb?.serialize() ?: ""}"

    companion object {
        fun deserialize(s: String, featureCount: Int): EnsembleModel? = runCatching {
            if (!s.startsWith("v2;")) return null
            val p = s.split(";")
            val lr = LogisticModel.deserialize(p[2]) ?: return null
            if (lr.mean.size != featureCount) return null
            val gb = p.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { GradientBoost.deserialize(it) }
            EnsembleModel(lr, gb, Mode.valueOf(p[1]))
        }.getOrNull()
    }
}
