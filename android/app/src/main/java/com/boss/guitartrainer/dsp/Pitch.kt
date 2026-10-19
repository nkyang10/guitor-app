package com.boss.guitartrainer.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class PitchResult(val hz: Double, val clarity: Double) {
    val voiced get() = hz > 0
}

object Notes {
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun hzToMidi(hz: Double, a4: Double = 440.0) = 69.0 + 12.0 * ln(hz / a4) / ln(2.0)
    fun midiToHz(m: Double, a4: Double = 440.0) = a4 * 2.0.pow((m - 69.0) / 12.0)
    fun cents(hz: Double, refHz: Double) = 1200.0 * ln(hz / refHz) / ln(2.0)
    fun name(midi: Int) = NAMES[((midi % 12) + 12) % 12] + (midi / 12 - 1)
    fun pcName(pc: Int) = NAMES[((pc % 12) + 12) % 12]
    fun nearestMidi(hz: Double, a4: Double = 440.0) = hzToMidi(hz, a4).roundToInt()
}

private fun parabolic(y: DoubleArray, i: Int): Pair<Double, Double> {
    if (i <= 0 || i >= y.size - 1) return i.toDouble() to y[i]
    val a = y[i - 1]; val b = y[i]; val c = y[i + 1]
    val den = a - 2 * b + c
    if (den == 0.0) return i.toDouble() to b
    val p = 0.5 * (a - c) / den
    return (i + p) to (b - 0.25 * (a - c) * p)
}

/**
 * McLeod Pitch Method (NSDF + key maxima), FFT autocorrelation.
 * Phase-0 validation (synthetic nylon + phone-mic model): median error 0.3 cent, gross (octave) errors
 * 4% vs 11-12% for YIN; with clarity >= 0.8 gate + median-of-5 smoothing p95 error 4.6 cents.
 */
class MpmDetector(private val frameSize: Int, private val sampleRate: Int,
                  private val fmin: Double = 70.0, private val fmax: Double = 1000.0,
                  private val k: Double = 0.93, private val minClarity: Double = 0.6) {
    private val fft = Fft(Fft.nextPow2(frameSize * 2))
    private val tauMax = min(frameSize - 1, (sampleRate / fmin).toInt() + 2)
    private val nsdf = DoubleArray(tauMax + 1)
    private val cum = DoubleArray(frameSize + 1)

    fun detect(x: FloatArray, offset: Int = 0): PitchResult {
        val n = frameSize
        val re = fft.re; val im = fft.im
        for (i in 0 until fft.n) { re[i] = if (i < n) x[offset + i].toDouble() else 0.0; im[i] = 0.0 }
        cum[0] = 0.0
        for (i in 0 until n) cum[i + 1] = cum[i] + re[i] * re[i]
        if (cum[n] < 1e-10) return PitchResult(0.0, 0.0)
        fft.transform()
        for (i in 0 until fft.n) { re[i] = re[i] * re[i] + im[i] * im[i]; im[i] = 0.0 }
        fft.inverse()
        for (tau in 0..tauMax) {
            val m = (cum[n - tau] - cum[0]) + (cum[n] - cum[tau])
            nsdf[tau] = if (m > 0) 2.0 * re[tau] / m else 0.0
        }
        val tauMin = max(1, (sampleRate / fmax).toInt())
        // key maxima between positive-going zero crossings
        val peaks = ArrayList<Int>(16)
        var pos = false
        var curI = -1
        var curV = -1e9
        for (tau in 1 until tauMax) {
            if (nsdf[tau - 1] < 0 && nsdf[tau] >= 0) { pos = true; curI = -1; curV = -1e9 }
            else if (nsdf[tau - 1] >= 0 && nsdf[tau] < 0 && pos) { if (curI >= 0) peaks.add(curI); pos = false }
            if (pos && nsdf[tau] > curV && tau >= tauMin) { curI = tau; curV = nsdf[tau] }
        }
        if (pos && curI >= 0) peaks.add(curI)
        if (peaks.isEmpty()) return PitchResult(0.0, 0.0)
        var best = -1e9
        for (p in peaks) best = max(best, nsdf[p])
        if (best < minClarity) return PitchResult(0.0, best)
        for (p in peaks) {
            if (nsdf[p] >= k * best) {
                val (t, v) = parabolic(nsdf, p)
                return PitchResult(sampleRate / t, min(1.0, v))
            }
        }
        return PitchResult(0.0, 0.0)
    }
}

/** YIN (kept as an option / for comparison; MPM is the default). */
class YinDetector(private val frameSize: Int, private val sampleRate: Int,
                  private val fmin: Double = 70.0, private val fmax: Double = 1000.0,
                  private val threshold: Double = 0.12) {
    private val w = frameSize / 2
    private val tauMax = min(w - 1, (sampleRate / fmin).toInt() + 1)
    private val d = DoubleArray(tauMax + 2)
    private val cmnd = DoubleArray(tauMax + 2)

    fun detect(x: FloatArray, offset: Int = 0): PitchResult {
        val tauMin = max(2, (sampleRate / fmax).toInt())
        for (tau in 1..tauMax + 1) {
            var s = 0.0
            for (j in 0 until w) {
                val diff = x[offset + j] - x[offset + j + tau]
                s += diff * diff
            }
            d[tau] = s
        }
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax + 1) {
            running += d[tau]
            cmnd[tau] = if (running > 0) d[tau] * tau / running else 1.0
        }
        var tau = tauMin
        var found = -1
        while (tau <= tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                found = tau; break
            }
            tau++
        }
        if (found < 0) {
            var bi = tauMin
            for (t in tauMin..tauMax) if (cmnd[t] < cmnd[bi]) bi = t
            if (cmnd[bi] > 0.35) return PitchResult(0.0, 1 - cmnd[bi])
            found = bi
        }
        val (t, _) = parabolic(cmnd, found)
        return PitchResult(sampleRate / t, 1 - cmnd[found])
    }
}

/** Post-processing validated in Phase 0: clarity gate + median of last 5 voiced estimates (in MIDI units). */
class PitchSmoother(private val minClarity: Double = 0.8, private val size: Int = 5) {
    private val hist = ArrayDeque<Double>()
    fun push(r: PitchResult, a4: Double = 440.0): Double? {
        if (r.voiced && r.clarity >= minClarity) hist.addLast(Notes.hzToMidi(r.hz, a4)) else hist.addLast(Double.NaN)
        while (hist.size > size) hist.removeFirst()
        val v = hist.filter { !it.isNaN() }.sorted()
        if (v.size < 3) return null
        val med = if (v.size % 2 == 1) v[v.size / 2] else 0.5 * (v[v.size / 2 - 1] + v[v.size / 2])
        return Notes.midiToHz(med, a4)
    }
    fun reset() = hist.clear()
}
