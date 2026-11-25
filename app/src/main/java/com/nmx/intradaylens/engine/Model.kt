package com.nmx.intradaylens.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Small on-device AI: L2-regularised logistic regression trained with Newton's method (IRLS).
 * It learns from the last ~2 months of real 5-minute data which breakouts reached their target.
 */
class LogisticModel(
    val weights: DoubleArray,   // [bias, w1..wn]
    val mean: DoubleArray,
    val std: DoubleArray
) {
    fun predict(x: DoubleArray): Double {
        var z = weights[0]
        for (i in x.indices) z += weights[i + 1] * (x[i] - mean[i]) / std[i]
        return sigmoid(z)
    }

    /** Contribution of each feature to the decision (positive = raised the probability). */
    fun contributions(x: DoubleArray): DoubleArray =
        DoubleArray(x.size) { i -> weights[i + 1] * (x[i] - mean[i]) / std[i] }

    fun serialize(): String = listOf(weights, mean, std).joinToString("|") { a -> a.joinToString(",") }

    companion object {
        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

        fun deserialize(s: String): LogisticModel? = runCatching {
            val p = s.split("|").map { part -> part.split(",").map { it.toDouble() }.toDoubleArray() }
            LogisticModel(p[0], p[1], p[2])
        }.getOrNull()

        fun train(xs: List<DoubleArray>, ys: List<Int>, lambda: Double = 2.0, iterations: Int = 15): LogisticModel {
            require(xs.isNotEmpty())
            val n = xs[0].size
            val mean = DoubleArray(n) { j -> xs.sumOf { it[j] } / xs.size }
            val std = DoubleArray(n) { j ->
                val v = xs.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / xs.size
                sqrt(v).takeIf { it > 1e-9 } ?: 1.0
            }
            val z = xs.map { x -> DoubleArray(n + 1) { j -> if (j == 0) 1.0 else (x[j - 1] - mean[j - 1]) / std[j - 1] } }
            val d = n + 1
            val base = ys.average().coerceIn(0.01, 0.99)
            val w = DoubleArray(d).also { it[0] = ln(base / (1 - base)) }
            repeat(iterations) {
                val grad = DoubleArray(d)
                val hess = Array(d) { DoubleArray(d) }
                for (k in z.indices) {
                    val row = z[k]
                    var s = 0.0
                    for (j in 0 until d) s += w[j] * row[j]
                    val p = sigmoid(s)
                    val err = p - ys[k]
                    val wt = p * (1 - p)
                    for (a in 0 until d) {
                        grad[a] += err * row[a]
                        val ra = row[a] * wt
                        for (b in a until d) hess[a][b] += ra * row[b]
                    }
                }
                for (a in 0 until d) for (b in 0 until a) hess[a][b] = hess[b][a]
                for (a in 1 until d) { grad[a] += lambda * w[a]; hess[a][a] += lambda }
                hess[0][0] += 1e-6
                val step = solve(hess, grad) ?: return@repeat
                var maxStep = 0.0
                for (j in 0 until d) { w[j] -= step[j]; maxStep = maxOf(maxStep, kotlin.math.abs(step[j])) }
                if (maxStep < 1e-6) return LogisticModel(w, mean, std)
            }
            return LogisticModel(w, mean, std)
        }

        /** Gaussian elimination with partial pivoting. */
        private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val n = b0.size
            val a = Array(n) { a0[it].copyOf() }
            val b = b0.copyOf()
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
                if (kotlin.math.abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                val tb = b[col]; b[col] = b[piv]; b[piv] = tb
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}
