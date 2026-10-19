package com.boss.guitartrainer.dsp

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Strum-direction model v2 (Phase-0 validation/dir2.py, dir_export.py).
 * 12-band IIR filterbank (80-5000 Hz) -> per-band envelope -> normalised cumulative-energy curves,
 * per-band 10/25/50 % onset times, band levels and onset-time slope across bands (303 features),
 * logistic regression trained on GuitarSet (6 players, 6930 labelled strums).
 * Down-strum: low strings sound first, so low bands reach their energy earlier.
 * Personal calibration = MAP adaptation of the generic weights (Gaussian prior centred on them).
 */
@Serializable
data class DirectionSpec(
    val version: Int, val kind: String, val nb: Int, val edges: List<Double>,
    val pre: Double, val post: Double, val step: Double, val warm: Double, val lp_hz: Double, val t0_offset: Double,
    val mean: List<Double>, val scale: List<Double>, val w: List<Double>, val b: Double,
    val lambda_personal: Double = 30.0,
    val sos: Map<String, SosSet>,
)

@Serializable
data class SosSet(val bands: List<List<List<Double>>>, val lp: List<List<Double>>)

/** Cascade of second-order sections, direct-form II transposed (same as scipy.signal.sosfilt). */
class Sos(sections: List<List<Double>>) {
    private val c = sections.map { it.toDoubleArray() }
    fun filter(x: DoubleArray, out: DoubleArray = DoubleArray(x.size)): DoubleArray {
        var cur = x
        for (s in c) {
            val b0 = s[0] / s[3]; val b1 = s[1] / s[3]; val b2 = s[2] / s[3]; val a1 = s[4] / s[3]; val a2 = s[5] / s[3]
            var z1 = 0.0; var z2 = 0.0
            for (i in cur.indices) {
                val xi = cur[i]
                val y = b0 * xi + z1
                z1 = b1 * xi - a1 * y + z2
                z2 = b2 * xi - a2 * y
                out[i] = y
            }
            cur = out
        }
        return out
    }
}

class DirectionFeatures(val spec: DirectionSpec, val sampleRate: Int) {
    private val set: SosSet = spec.sos[sampleRate.toString()]
        ?: spec.sos.minByOrNull { abs(it.key.toInt() - sampleRate) }!!.value
    private val bands = set.bands.map { Sos(it) }
    private val lp = Sos(set.lp)
    val samplesBefore = ((spec.pre + spec.warm) * sampleRate).roundToInt()
    val samplesAfter = (spec.post * sampleRate).roundToInt()
    private val off = (spec.warm * sampleRate).toInt()
    private val hop = (spec.step * sampleRate).toInt()
    val dim get() = spec.mean.size

    /** [x] audio, [onsetIdx] = onset position inside x. Returns the feature vector or null if out of range. */
    fun compute(x: FloatArray, onsetIdx: Int): DoubleArray? {
        val a = onsetIdx - samplesBefore
        val bEnd = onsetIdx + samplesAfter
        if (a < 0 || bEnd > x.size) return null
        val seg = DoubleArray(bEnd - a) { x[a + it].toDouble() }
        val nb = spec.nb
        val lenE = seg.size - off
        val nT = lenE / hop
        val curves = Array(nb) { DoubleArray(nT) }
        val tons = Array(nb) { DoubleArray(3) }
        val lvl = DoubleArray(nb)
        val tmp = DoubleArray(seg.size)
        val tmp2 = DoubleArray(seg.size)
        val sub = seg.copyOfRange(off, seg.size)
        val subOut = DoubleArray(sub.size)
        val qs = doubleArrayOf(0.1, 0.25, 0.5)
        for (bi in 0 until nb) {
            bands[bi].filter(seg, tmp)
            for (i in tmp.indices) tmp[i] = abs(tmp[i])
            lp.filter(tmp, tmp2)
            val c = curves[bi]
            for (k in 0 until nT) {
                var s = 0.0
                val base = off + k * hop
                for (j in 0 until hop) { val v = tmp2[base + j]; s += v * v }
                c[k] = s
            }
            var acc = 0.0
            for (k in 0 until nT) { acc += c[k]; c[k] = acc }
            val tot = acc + 1e-12
            for (k in 0 until nT) c[k] /= tot
            for (qi in 0 until 3) {
                var idx = 0
                for (k in 0 until nT) if (c[k] >= qs[qi]) { idx = k; break }
                tons[bi][qi] = idx * spec.step * 1000.0
            }
            // level: std of the band signal over seg[off:], filtered from zero state (as in training)
            bands[bi].filter(sub, subOut)
            var m = 0.0
            for (v in subOut) m += v
            m /= subOut.size
            var vs = 0.0
            for (v in subOut) vs += (v - m) * (v - m)
            lvl[bi] = log10(sqrt(vs / subOut.size) + 1e-9)
        }
        val lmax = lvl.max()
        for (i in lvl.indices) lvl[i] -= lmax
        val out = ArrayList<Double>(dim)
        for (bi in 0 until nb) { var k = 0; while (k < nT) { out.add(curves[bi][k]); k += 2 } }
        val colMean = DoubleArray(3) { q -> (0 until nb).sumOf { tons[it][q] } / nb }
        for (bi in 0 until nb) for (q in 0 until 3) out.add(tons[bi][q] - colMean[q])
        for (v in lvl) out.add(v)
        val xm = (nb - 1) / 2.0
        var sxx = 0.0
        for (i in 0 until nb) sxx += (i - xm) * (i - xm)
        for (q in 0 until 3) {
            var sxy = 0.0
            for (i in 0 until nb) sxy += (i - xm) * (tons[i][q] - colMean[q])
            out.add(sxy / sxx)
        }
        if (out.size != dim) return null
        return out.toDoubleArray()
    }
}

/** Standardised logistic regression. y = 1 means down-strum. */
@Serializable
data class DirectionModel(
    val mean: List<Double>, val scale: List<Double>, val w: List<Double>, val b: Double,
    val kind: String = "generic", val trainedOn: Int = 0,
    /** balanced accuracy of the generic model on the user's calibration strums (cross-validated). */
    val genericAccuracy: Double = 0.0,
    /** balanced accuracy of the adapted model (cross-validated). */
    val cvAccuracy: Double = 0.0,
    val createdAt: Long = 0,
) {
    fun z(f: DoubleArray): DoubleArray = DoubleArray(f.size) { (f[it] - mean[it]) / scale[it] }

    fun probDown(f: DoubleArray): Double {
        if (f.size != w.size) return 0.5
        var s = b
        for (i in f.indices) s += w[i] * (f[i] - mean[i]) / scale[i]
        return 1.0 / (1.0 + exp(-s))
    }

    companion object {
        /** MAP adaptation: minimise class-balanced log-loss + lambda/2 * ||w - w0||^2 (same as dir_export.py). */
        fun adapt(base: DirectionModel, X: List<DoubleArray>, y: List<Int>, lambda: Double,
                  iters: Int = 300, lr: Double = 0.1): DirectionModel {
            val n = X.size
            val d = base.w.size
            val Z = X.map { base.z(it) }
            val nPos = y.count { it == 1 }.coerceAtLeast(1); val nNeg = y.count { it == 0 }.coerceAtLeast(1)
            val cw = DoubleArray(n) { if (y[it] == 1) 0.5 / nPos * n else 0.5 / nNeg * n }
            val w0 = base.w.toDoubleArray()
            val w = w0.copyOf(); var b = base.b
            repeat(iters) {
                val gw = DoubleArray(d); var gb = 0.0
                for (i in 0 until n) {
                    var s = b
                    val zi = Z[i]
                    for (j in 0 until d) s += w[j] * zi[j]
                    val g = cw[i] * (1.0 / (1.0 + exp(-s)) - y[i])
                    for (j in 0 until d) gw[j] += g * zi[j]
                    gb += g
                }
                for (j in 0 until d) w[j] -= lr * (gw[j] / n + lambda * (w[j] - w0[j]) / n)
                b -= lr * gb / n
            }
            return base.copy(w = w.toList(), b = b, kind = "personal", trainedOn = n, createdAt = System.currentTimeMillis())
        }

        fun balancedAccuracy(m: DirectionModel, X: List<DoubleArray>, y: List<Int>): Double {
            var tp = 0; var p = 0; var tn = 0; var nn = 0
            for (i in X.indices) {
                val pd = m.probDown(X[i]) >= 0.5
                if (y[i] == 1) { p++; if (pd) tp++ } else { nn++; if (!pd) tn++ }
            }
            if (p == 0 || nn == 0) return 0.0
            return 0.5 * (tp.toDouble() / p + tn.toDouble() / nn)
        }

        /** k-fold CV: returns (generic balanced acc, adapted balanced acc). */
        fun crossValidate(base: DirectionModel, X: List<DoubleArray>, y: List<Int>, lambda: Double, k: Int = 4): Pair<Double, Double> {
            if (X.size < 8) return balancedAccuracy(base, X, y) to 0.0
            val idx = X.indices.shuffled(java.util.Random(1))
            val predA = BooleanArray(X.size)
            for (f in 0 until k) {
                val test = idx.filterIndexed { i, _ -> i % k == f }
                val train = idx.filterIndexed { i, _ -> i % k != f }
                val m = if (train.map { y[it] }.distinct().size < 2) base else adapt(base, train.map { X[it] }, train.map { y[it] }, lambda)
                for (t in test) predA[t] = m.probDown(X[t]) >= 0.5
            }
            var tp = 0; var p = 0; var tn = 0; var nn = 0
            for (i in X.indices) { if (y[i] == 1) { p++; if (predA[i]) tp++ } else { nn++; if (!predA[i]) tn++ } }
            val adapted = if (p == 0 || nn == 0) 0.0 else 0.5 * (tp.toDouble() / p + tn.toDouble() / nn)
            return balancedAccuracy(base, X, y) to adapted
        }
    }
}

/** Loads the generic model shipped in assets (direction_generic.json). */
object Direction {
    @Volatile var spec: DirectionSpec? = null
    @Volatile var generic: DirectionModel? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun init(ctx: Context) {
        if (spec != null) return
        runCatching {
            val text = ctx.assets.open("direction_generic.json").bufferedReader().use { it.readText() }
            initFromJson(text)
        }
    }

    fun initFromJson(text: String) {
        val s = json.decodeFromString(DirectionSpec.serializer(), text)
        spec = s
        generic = DirectionModel(s.mean, s.scale, s.w, s.b, kind = "generic")
    }
}

fun confidenceOf(p: Double) = max(p, 1 - p)
