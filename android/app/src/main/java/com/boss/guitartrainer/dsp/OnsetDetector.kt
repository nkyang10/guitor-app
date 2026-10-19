package com.boss.guitartrainer.dsp

import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming onset detector (validated in Phase 0, see validation/REPORT.md):
 * log-magnitude spectral flux (lambda = 1, lag 2, 80 Hz - 6 kHz), frame 1024 / hop 256 @ 48 kHz (5.3 ms),
 * adaptive threshold  thr = median(last 24 ODF) * mult + delta * max(1, mean(last 1.5 s ODF)),
 * peak picking with 2-frame look-ahead, minimum inter-onset interval 60 ms.
 * Onset time = frame centre (no extra refinement). Real recordings (GuitarSet mic): recall 0.97 (strums)
 * / 0.99 (single notes) with guided acceptance; timing SD ~18 ms incl. annotation jitter.
 *
 * Peaks with ratio (odf/thr) >= [weakRatio] but < 1 are reported as weak candidates; the judge
 * accepts them only inside an expected window ("verify, don't transcribe").
 */
class OnsetDetector(
    private val sampleRate: Int,
    var delta: Double = DEFAULT_DELTA,
    var mult: Double = DEFAULT_MULT,
    var weakRatio: Double = 0.7,
    private val minIoiSec: Double = 0.06,
    private val listener: (onsetSample: Long, ratio: Double, weak: Boolean) -> Unit,
) {
    companion object {
        const val FRAME = 1024
        const val HOP = 256
        const val MED_LEN = 24
        const val SCALE_LEN = 280
        const val LAMBDA = 1.0
        const val LAG = 2
        var DEFAULT_DELTA = 1.0
        var DEFAULT_MULT = 1.5
    }

    private val fft = Fft(FRAME)
    private val window = Fft.hann(FRAME)
    private val power = DoubleArray(FRAME / 2 + 1)
    private val kLo = (80.0 * FRAME / sampleRate).toInt().coerceAtLeast(1)
    private val kHi = (6000.0 * FRAME / sampleRate).toInt().coerceAtMost(FRAME / 2)
    private val nb = kHi - kLo + 1
    private val hist = Array(LAG + 1) { DoubleArray(nb) }
    private var histPos = 0
    private var frames = 0L
    private val cur = DoubleArray(nb)
    private val odf = ArrayDeque<Double>()
    private val odfStart = ArrayDeque<Long>()
    private val scaleHist = ArrayDeque<Double>()
    private var scaleSum = 0.0
    private val buf = FloatArray(FRAME)
    private var bufFill = 0
    private var lastOnset = Long.MIN_VALUE / 2
    private var lastWeak = Long.MIN_VALUE / 2

    @Volatile var level = 0.0          // RMS of the last frame (for UI meters)
        private set

    fun reset() {
        bufFill = 0; frames = 0; histPos = 0; odf.clear(); odfStart.clear(); scaleHist.clear(); scaleSum = 0.0
        lastOnset = Long.MIN_VALUE / 2; lastWeak = Long.MIN_VALUE / 2
    }

    /** Feed consecutive samples; [startSample] = absolute capture index of data[0]. */
    fun process(data: FloatArray, count: Int, startSample: Long) {
        var i = 0
        while (i < count) {
            val take = minOf(count - i, FRAME - bufFill)
            System.arraycopy(data, i, buf, bufFill, take)
            bufFill += take
            i += take
            if (bufFill == FRAME) {
                analyse(startSample + i - FRAME)
                System.arraycopy(buf, HOP, buf, 0, FRAME - HOP)
                bufFill = FRAME - HOP
            }
        }
    }

    private fun analyse(frameStart: Long) {
        var e = 0.0
        for (v in buf) e += v * v
        level = sqrt(e / FRAME)
        fft.power(buf, 0, window, power)
        for (k in kLo..kHi) cur[k - kLo] = ln1p(LAMBDA * sqrt(power[k]))
        var flux = 0.0
        if (frames >= LAG) {
            val p = hist[(histPos + 1) % (LAG + 1)]          // frame from LAG hops ago
            for (j in 0 until nb) flux += max(0.0, cur[j] - p[j])
        }
        System.arraycopy(cur, 0, hist[histPos], 0, nb)
        histPos = (histPos + 1) % (LAG + 1)
        frames++

        odf.addLast(flux); odfStart.addLast(frameStart)
        while (odf.size > MED_LEN + 3) { odf.removeFirst(); odfStart.removeFirst() }
        // candidate frame i = size-3 (needs i-1, i+1, i+2); scale uses ODF strictly before i
        val n = odf.size
        if (n >= 6) {
            val i = n - 3
            val v = odf[i]
            if (v >= odf[i - 1] && v >= odf[i + 1] && v >= odf[i + 2] && v > 0) {
                val past = ArrayList<Double>(MED_LEN)
                for (j in 0 until i) past.add(odf[j])
                past.sort()
                val med = past[past.size / 2]
                val scale = max(1.0, if (scaleHist.isEmpty()) 1.0 else scaleSum / scaleHist.size)
                val thr = med * mult + delta * scale
                val ratio = v / thr
                val t = odfStart[i] + FRAME / 2
                if (ratio > 1.0) {
                    if (t - lastOnset >= minIoiSec * sampleRate) { lastOnset = t; listener(t, ratio, false) }
                } else if (ratio >= weakRatio && t - lastOnset >= minIoiSec * sampleRate && t - lastWeak >= minIoiSec * sampleRate) {
                    lastWeak = t; listener(t, ratio, true)
                }
            }
            // push the value that just became "past" for the scale (frame i-? keeps causal order)
        }
        scaleHist.addLast(flux); scaleSum += flux
        while (scaleHist.size > SCALE_LEN) scaleSum -= scaleHist.removeFirst()
    }
}
