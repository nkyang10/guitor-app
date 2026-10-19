package com.boss.guitartrainer.dsp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Chord verification ("is the expected voicing ringing?") – not free transcription.
 * - chroma (75 Hz - 2.5 kHz, log-compressed, semitone-centred bins) vs expected chroma
 *   built from the voicing's notes with 4 harmonics; cosine similarity
 * - per-note presence: peak energy at f0/2f0/3f0 relative to the local spectral floor (dB)
 * Thresholds from Phase-0 validation.
 */
class ChordAnalyzer(private val sampleRate: Int, val nfft: Int = 8192) {
    companion object {
        /** Phase-0 ROC (validation/results_chord.json): GuitarSet real strums TPR 95 % / wrong-chord FPR 18 % at 0.60;
         *  TPR 87 % / FPR 10.5 % at 0.65. Synthetic nylon: TPR 100 % at both. */
        var SIM_THRESHOLD = 0.60          // beginner (lenient: few false "wrong chord")
        var SIM_THRESHOLD_STRICT = 0.65   // intermediate
        /** Ringing notes pass 95-98 % at 20 dB; muted strings caught 42-57 % -> used only as a hint. */
        var PRESENCE_DB = 20.0
        const val DELAY_SEC = 0.03
    }

    private val fft = Fft(nfft)
    private val window = Fft.hann(nfft)
    val mag = DoubleArray(nfft / 2 + 1)
    private val binHz = sampleRate.toDouble() / nfft
    val chroma = DoubleArray(12)

    /** Analyse nfft samples starting at [offset] of [x]. */
    fun analyse(x: FloatArray, offset: Int) {
        fft.magnitude(x, offset, window, mag)
        computeChroma()
    }

    private fun computeChroma() {
        chroma.fill(0.0)
        var mx = 1e-12
        for (k in mag.indices) if (mag[k] > mx) mx = mag[k]
        val k0 = (75.0 / binHz).toInt().coerceAtLeast(1)
        val k1 = (2500.0 / binHz).toInt().coerceAtMost(mag.size - 1)
        for (k in k0..k1) {
            val f = k * binHz
            val m = 69.0 + 12.0 * ln(f / 440.0) / ln(2.0)
            val r = m.roundToInt()
            val dev = abs(m - r)
            val w = (1.0 - 2.0 * dev).coerceIn(0.0, 1.0)
            if (w <= 0) continue
            chroma[((r % 12) + 12) % 12] += w * ln1p(100.0 * mag[k] / mx)
        }
        normalize(chroma)
    }

    fun similarity(midis: List<Int>): Double {
        val e = expectedChroma(midis)
        var s = 0.0
        for (i in 0 until 12) s += e[i] * chroma[i]
        return s
    }

    /** dB above local floor for a note (uses current [mag]). */
    fun presence(midi: Int): Double {
        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        var tot = 0.0
        val weights = doubleArrayOf(1.0, 0.7, 0.4)
        for (h in 1..3) {
            val lo = f0 * h * 2.0.pow(-35.0 / 1200.0)
            val hi = f0 * h * 2.0.pow(35.0 / 1200.0)
            var pk = 0.0
            var k = floor(lo / binHz).toInt().coerceAtLeast(0)
            val kh = (hi / binHz).toInt().coerceAtMost(mag.size - 1)
            // include the nearest bin when the band is narrower than one bin
            if (k > kh) k = kh
            while (k <= kh) { val f = k * binHz; if (f >= lo - binHz / 2 && f <= hi + binHz / 2) pk = max(pk, mag[k]); k++ }
            tot += weights[h - 1] * pk
        }
        val a = (f0 * 0.5 / binHz).toInt().coerceAtLeast(1)
        val b = (f0 * 4 / binHz).toInt().coerceAtMost(mag.size - 1)
        val seg = DoubleArray(max(1, b - a)) { mag[(a + it).coerceAtMost(mag.size - 1)] }
        seg.sort()
        val floorV = seg[seg.size / 2] + 1e-9
        return 20.0 * log10(tot / floorV + 1e-9)
    }

    fun topPitchClasses(n: Int = 4): List<Int> = (0 until 12).sortedByDescending { chroma[it] }.take(n)

    private fun normalize(v: DoubleArray) {
        var s = 0.0
        for (x in v) s += x * x
        val nrm = sqrt(s) + 1e-12
        for (i in v.indices) v[i] /= nrm
    }

    fun expectedChroma(midis: List<Int>, nHarm: Int = 4): DoubleArray {
        val c = DoubleArray(12)
        for (m in midis) for (h in 1..nHarm) {
            val mh = m + 12.0 * ln(h.toDouble()) / ln(2.0)
            c[((mh.roundToInt() % 12) + 12) % 12] += 1.0 / h
        }
        normalize(c)
        return c
    }
}
