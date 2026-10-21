package com.boss.guitartrainer.analysis

import com.boss.guitartrainer.dsp.Fft
import com.boss.guitartrainer.music.Capo
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.Section
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device song analysis (no PC needed; simpler than the server pipeline, no source separation):
 * spectral-flux onset envelope -> tempo (autocorrelation + log-normal prior) -> beat DP (Ellis 2007)
 * -> beat-synchronous chroma -> 24 maj/min templates + Viterbi (phase-aware change penalty) -> key (Krumhansl)
 * -> capo suggestion -> 8-bar sections grouped by chord sequence -> rule-based pattern per section.
 * Input: mono audio at [SR] Hz.
 */
object SongAnalyzer {
    const val SR = 22050
    private const val N_ONSET = 2048
    private const val HOP = 512
    private const val N_CHROMA = 8192
    private const val HOP_CHROMA = 2048
    private val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    class Debug(val bpm: Double, val beats: List<Double>, val chordsPerBeat: List<String>, val key: String)
    @Volatile var lastDebug: Debug? = null

    /** [n] = number of valid samples in [x] (the decoder hands over its buffer without a trimming copy). */
    fun analyze(x: FloatArray, title: String, source: String?, level: Int = 1, n: Int = x.size, progress: (Double) -> Unit = {}): Chart {
        require(n in 0..x.size) { "n=$n > ${x.size}" }
        val hopSec = HOP.toDouble() / SR
        val env = onsetEnvelope(x, n) { progress(0.05 + 0.3 * it) }
        val (bpm0, period) = tempo(env, hopSec)
        progress(0.4)
        val beatFrames = beatTrack(env, period)
        // drop beats in leading/trailing silence
        val rmsOk = activeRange(x, n)
        // frame f covers [f*HOP, f*HOP + N): the flux responds when the onset reaches the window centre
        val frameOffset = N_ONSET / 2.0 / SR
        var beats = beatFrames.map { it * hopSec + frameOffset }.filter { it >= rmsOk.first - 0.1 && it <= rmsOk.second + 0.1 }
        if (beats.size < 8) beats = (0 until (n / SR / (60.0 / bpm0)).toInt()).map { it * 60.0 / bpm0 }
        // report the tempo the beat tracker actually followed (the grid estimate is only ±1–2 %)
        val bpm = if (beats.size >= 8) {
            // least-squares slope of beat time vs index (a median interval is biased by the ±1-frame quantisation)
            val n = beats.size; val mi = (n - 1) / 2.0; val mt = beats.average()
            var num = 0.0; var den = 0.0
            for (i in 0 until n) { num += (i - mi) * (beats[i] - mt); den += (i - mi) * (i - mi) }
            val slope = num / den
            if (slope > 0 && abs(60.0 / slope / bpm0 - 1) < 0.1) 60.0 / slope else bpm0
        } else bpm0
        progress(0.5)
        val (chroma, bass, energy) = beatChroma(x, beats, n) { progress(0.5 + 0.3 * it) }
        // first pass: uniform change penalty -> estimate downbeat phase -> second pass phase-aware
        val first = viterbi(chroma, bass, energy, -1)
        val phase = downbeatPhase(first, energy)
        val states = viterbi(chroma, bass, energy, phase)
        progress(0.85)
        val labels = states.map { stateName(it) }
        val key = estimateKey(chroma)
        // spans (sounding chords)
        val spans = ArrayList<ChordSpan>()
        var i = 0
        while (i < labels.size) {
            var j = i
            while (j + 1 < labels.size && labels[j + 1] == labels[i]) j++
            val start = beats[i]
            val end = if (j + 1 < beats.size) beats[j + 1] else beats[j] + 60.0 / bpm
            spans += ChordSpan(start, end, labels[i], labels[i])
            i = j + 1
        }
        val dur = HashMap<String, Double>()
        for (s in spans) if (s.original != "N") dur[s.original] = (dur[s.original] ?: 0.0) + (s.end - s.start)
        val capo = Capo.suggest(dur, level)
        val shownSpans = spans.map { it.copy(label = capo.shapes[it.original] ?: it.original) }
        val downbeats = beats.filterIndexed { k, _ -> k % 4 == phase }
        val sections = sections(downbeats, beats, labels, energy, phase, bpm)
        lastDebug = Debug(bpm, beats, labels, key)
        progress(1.0)
        return Chart(
            id = "", title = title, bpm = bpm, beatsPerBar = 4, beats = beats, downbeats = downbeats,
            chords = shownSpans, sections = sections, events = emptyList(), key = key, capo = capo.capo,
            source = source, audioOffset = 0.0, kind = "song",
        )
    }

    // ------------------------------------------------------------------ onset envelope
    fun onsetEnvelope(x: FloatArray, n: Int = x.size, progress: (Double) -> Unit = {}): DoubleArray {
        val fft = Fft(N_ONSET); val win = Fft.hann(N_ONSET)
        val nBins = N_ONSET / 2 + 1
        val kMax = (8000.0 * N_ONSET / SR).toInt().coerceAtMost(nBins - 1)
        val nFrames = max(0, (n - N_ONSET) / HOP + 1)
        val mag = DoubleArray(nBins)
        var prev = DoubleArray(kMax + 1)
        var cur = DoubleArray(kMax + 1)
        val flux = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            fft.magnitude(x, f * HOP, win, mag)
            for (k in 1..kMax) cur[k] = ln(1.0 + 10.0 * mag[k])
            if (f > 0) {
                var s = 0.0
                for (k in 1..kMax) { val d = cur[k] - prev[k]; if (d > 0) s += d }
                flux[f] = s
            }
            val t = prev; prev = cur; cur = t
            if (f % 500 == 0) progress(f.toDouble() / nFrames)
        }
        // subtract local mean (~0.5 s), half-wave rectify, normalise
        val w = 11
        val out = DoubleArray(nFrames)
        var acc = 0.0
        val pre = DoubleArray(nFrames + 1)
        for (k in 0 until nFrames) { acc += flux[k]; pre[k + 1] = acc }
        for (k in 0 until nFrames) {
            val a = max(0, k - w); val b = minOf(nFrames, k + w + 1)
            val m = (pre[b] - pre[a]) / (b - a)
            out[k] = max(0.0, flux[k] - m)
        }
        val sd = sqrt(out.sumOf { it * it } / max(1, nFrames)) + 1e-9
        for (k in out.indices) out[k] /= sd
        return out
    }

    /**
     * Returns (bpm, period in frames). Metrical autocorrelation scoring – a tempo is good when the onset envelope
     * repeats at the beat, the half note, the bar and the eighth – times a log-normal prior at 120 BPM
     * (σ 0.5 octave, 40 % weight). Same as server/analysis.py estimate_tempo(); tuned on GuitarSet (360 takes)
     * where it cut 3/2 and double-time errors (comp: 53 % → 61 % exact, 62 % → 76 % incl. half/double).
     */
    fun tempo(env: DoubleArray, hopSec: Double): Pair<Double, Double> {
        val n = env.size
        if (n < 8) return 120.0 to 60.0 / (120.0 * hopSec)
        val mean = env.average()
        val x = DoubleArray(n) { env[it] - mean }
        val maxLag = min(n - 1, (4 * 60.0 / 55.0 / hopSec).toInt() + 4)
        val ac = DoubleArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0.0
            for (t in lag until n) s += x[t] * x[t - lag]
            ac[lag] = s
        }
        val a0 = ac[0] + 1e-9
        for (i in ac.indices) ac[i] /= a0
        fun a(l: Double): Double {
            val i = l.toInt()
            if (i < 1 || i + 2 > maxLag) return 0.0
            return max(max(ac[i - 1], ac[i]), max(ac[i + 1], ac[i + 2]))
        }
        var bestBpm = 120.0
        var bestV = Double.NEGATIVE_INFINITY
        var k = 0
        while (true) {
            val bpm = 55.0 + 0.5 * k++
            if (bpm > 190.0) break
            val l = 60.0 / (bpm * hopSec)
            val s = a(l) + 0.5 * a(2 * l) + 0.5 * a(4 * l) + 0.5 * a(l / 2)
            val p = exp(-0.5 * (log2(bpm / 120.0) / 0.5).let { it * it })
            val v = s * (0.6 + 0.4 * p)
            if (v > bestV) { bestV = v; bestBpm = bpm }
        }
        return bestBpm to 60.0 / (bestBpm * hopSec)
    }

    /** Dynamic-programming beat tracker (Ellis 2007), tightness 100. Returns beat frames. */
    fun beatTrack(env: DoubleArray, period: Double, tightness: Double = 100.0): List<Int> {
        val n = env.size
        if (n == 0) return emptyList()
        // smooth with a Gaussian of width period/8
        val sigma = period / 16.0
        val half = (sigma * 4).toInt().coerceAtLeast(1)
        val g = DoubleArray(2 * half + 1) { exp(-0.5 * ((it - half) / sigma).let { v -> v * v }) }
        val local = DoubleArray(n)
        for (t in 0 until n) {
            var s = 0.0
            for (k in -half..half) { val i = t + k; if (i in 0 until n) s += env[i] * g[k + half] }
            local[t] = s
        }
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val lo = (period / 2).roundToInt(); val hi = (2 * period).roundToInt()
        for (t in 0 until n) {
            var bestV = Double.NEGATIVE_INFINITY; var bestI = -1
            val a = t - hi; val b = t - lo
            var tau = max(0, a)
            while (tau <= b) {
                val d = ln((t - tau) / period)
                val v = score[tau] - tightness * d * d
                if (v > bestV) { bestV = v; bestI = tau }
                tau++
            }
            score[t] = local[t] + if (bestI >= 0) max(0.0, bestV) else 0.0
            back[t] = if (bestI >= 0 && bestV > 0) bestI else -1
        }
        // last beat: best score in the final period
        var last = n - 1; var bv = Double.NEGATIVE_INFINITY
        for (t in max(0, n - hi) until n) if (score[t] > bv) { bv = score[t]; last = t }
        val beats = ArrayList<Int>()
        var t = last
        while (t >= 0) { beats.add(t); t = back[t] }
        beats.reverse()
        return beats
    }

    private fun activeRange(x: FloatArray, len: Int = x.size): Pair<Double, Double> {
        val blk = SR / 10
        val n = len / blk
        if (n == 0) return 0.0 to len.toDouble() / SR
        val r = DoubleArray(n) { b -> var s = 0.0; for (i in b * blk until (b + 1) * blk) s += x[i] * x[i]; sqrt(s / blk) }
        val mx = r.max().coerceAtLeast(1e-9)
        val thr = mx * 0.03
        val a = r.indexOfFirst { it > thr }.coerceAtLeast(0)
        val b = r.indexOfLast { it > thr }.coerceAtLeast(a)
        return a * 0.1 to (b + 1) * 0.1
    }

    // ------------------------------------------------------------------ chroma
    data class BeatChroma(val chroma: List<DoubleArray>, val bass: List<DoubleArray>, val energy: DoubleArray)

    fun beatChroma(x: FloatArray, beats: List<Double>, n: Int = x.size, progress: (Double) -> Unit = {}): BeatChroma {
        val fft = Fft(N_CHROMA); val win = Fft.hann(N_CHROMA)
        val mag = DoubleArray(N_CHROMA / 2 + 1)
        val binHz = SR.toDouble() / N_CHROMA
        val nFrames = max(0, (n - N_CHROMA) / HOP_CHROMA + 1)
        val frameC = ArrayList<DoubleArray>(nFrames); val frameB = ArrayList<DoubleArray>(nFrames); val frameE = DoubleArray(nFrames)
        // semitone salience (max magnitude within +-0.5 semitone) so dense high-frequency bins do not dominate
        val mLo = 36; val mHi = 95; val mBass = 55
        val bandLo = IntArray(mHi - mLo + 1) { i -> (440.0 * Math.pow(2.0, (mLo + i - 69 - 0.5) / 12.0) / binHz).toInt().coerceAtLeast(1) }
        val bandHi = IntArray(mHi - mLo + 1) { i -> (440.0 * Math.pow(2.0, (mLo + i - 69 + 0.5) / 12.0) / binHz).toInt().coerceAtMost(N_CHROMA / 2) }
        val sal = DoubleArray(mHi - mLo + 1)
        for (f in 0 until nFrames) {
            fft.magnitude(x, f * HOP_CHROMA, win, mag)
            val c = DoubleArray(12); val b = DoubleArray(12)
            var mx = 1e-9
            var e = 0.0
            for (i in sal.indices) {
                var m = 0.0
                for (k in bandLo[i]..maxOf(bandLo[i], bandHi[i])) if (mag[k] > m) m = mag[k]
                sal[i] = m; if (m > mx) mx = m; e += m * m
            }
            for (i in sal.indices) {
                val midi = mLo + i
                val octW = if (midi < 72) 1.0 else 0.6
                val v = ln(1.0 + 50.0 * sal[i] / mx) * octW
                c[midi % 12] += v
                if (midi <= mBass) b[midi % 12] += v
            }
            frameC.add(c); frameB.add(b); frameE[f] = e
            if (f % 200 == 0) progress(f.toDouble() / nFrames)
        }
        val hopSec = HOP_CHROMA.toDouble() / SR
        val centre = N_CHROMA / 2.0 / SR
        val outC = ArrayList<DoubleArray>(); val outB = ArrayList<DoubleArray>()
        val outE = DoubleArray(beats.size)
        for (i in beats.indices) {
            val a = beats[i]; val bEnd = if (i + 1 < beats.size) beats[i + 1] else a + (if (i > 0) a - beats[i - 1] else 0.5)
            val c = DoubleArray(12); val b = DoubleArray(12); var e = 0.0; var cnt = 0
            for (f in 0 until nFrames) {
                val t = f * hopSec + centre
                if (t >= a && t < bEnd) { for (p in 0 until 12) { c[p] += frameC[f][p]; b[p] += frameB[f][p] }; e += frameE[f]; cnt++ }
            }
            if (cnt == 0 && nFrames > 0) {
                val f = ((a + bEnd) / 2 - centre).div(hopSec).roundToInt().coerceIn(0, nFrames - 1)
                for (p in 0 until 12) { c[p] = frameC[f][p]; b[p] = frameB[f][p] }; e = frameE[f]; cnt = 1
            }
            normalize(c); val bm = b.max().coerceAtLeast(1e-9); for (p in 0 until 12) b[p] /= bm
            outC.add(c); outB.add(b); outE[i] = if (cnt > 0) e / cnt else 0.0
        }
        val em = outE.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        for (i in outE.indices) outE[i] = outE[i] / em
        return BeatChroma(outC, outB, outE)
    }

    private fun normalize(v: DoubleArray) { val n = sqrt(v.sumOf { it * it }) + 1e-12; for (i in v.indices) v[i] /= n }

    // ------------------------------------------------------------------ chords
    private val templates: List<DoubleArray> = (0 until 24).map { s ->
        val root = s % 12; val minor = s >= 12
        val notes = listOf(48 + root, 48 + root + (if (minor) 3 else 4), 48 + root + 7, 60 + root)
        val c = DoubleArray(12)
        for (m in notes) for (h in 1..4) {
            val mh = m + 12.0 * log2(h.toDouble())
            c[((mh.roundToInt() % 12) + 12) % 12] += 1.0 / h
        }
        normalize(c); c
    }

    fun stateName(s: Int): String = when {
        s == 24 -> "N"
        s >= 12 -> NAMES[s - 12] + "m"
        else -> NAMES[s]
    }

    private fun emission(c: DoubleArray, b: DoubleArray, e: Double, s: Int): Double {
        if (s == 24) return if (e < 0.02) 0.9 else 0.35
        var sim = 0.0
        val t = templates[s]
        for (p in 0 until 12) sim += c[p] * t[p]
        val root = s % 12
        return sim + 0.12 * b[root]
    }

    /** [phase] = downbeat phase (0..3) or -1 for a uniform change penalty. */
    fun viterbi(chroma: List<DoubleArray>, bass: List<DoubleArray>, energy: DoubleArray, phase: Int): IntArray {
        val n = chroma.size
        val S = 25
        if (n == 0) return IntArray(0)
        val kappa = 14.0
        val score = Array(n) { DoubleArray(S) }
        val back = Array(n) { IntArray(S) }
        for (s in 0 until S) score[0][s] = kappa * emission(chroma[0], bass[0], energy[0], s)
        for (i in 1 until n) {
            val pen = when {
                phase < 0 -> 3.5
                i % 4 == phase -> 2.0
                i % 2 == phase % 2 -> 4.0
                else -> 6.0
            }
            var bestPrev = 0; var bestPrevV = Double.NEGATIVE_INFINITY
            for (s in 0 until S) if (score[i - 1][s] > bestPrevV) { bestPrevV = score[i - 1][s]; bestPrev = s }
            for (s in 0 until S) {
                val stay = score[i - 1][s]
                val change = bestPrevV - pen
                val e = kappa * emission(chroma[i], bass[i], energy[i], s)
                if (stay >= change || bestPrev == s) { score[i][s] = stay + e; back[i][s] = s } else { score[i][s] = change + e; back[i][s] = bestPrev }
            }
        }
        val out = IntArray(n)
        var s = (0 until S).maxByOrNull { score[n - 1][it] }!!
        for (i in n - 1 downTo 0) { out[i] = s; s = back[i][s] }
        return out
    }

    fun downbeatPhase(states: IntArray, energy: DoubleArray): Int {
        val score = DoubleArray(4)
        for (i in 1 until states.size) if (states[i] != states[i - 1]) score[i % 4] += 1.0
        for (i in energy.indices) score[i % 4] += 0.15 * energy[i]
        return (0 until 4).maxByOrNull { score[it] }!!
    }

    // ------------------------------------------------------------------ key
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun estimateKey(chroma: List<DoubleArray>): String {
        val tot = DoubleArray(12)
        for (c in chroma) for (p in 0 until 12) tot[p] += c[p]
        var best = "C"; var bv = Double.NEGATIVE_INFINITY
        for (mode in 0..1) for (r in 0 until 12) {
            val prof = if (mode == 0) MAJOR else MINOR
            val rot = DoubleArray(12) { prof[((it - r) % 12 + 12) % 12] }
            val v = corr(tot, rot)
            if (v > bv) { bv = v; best = NAMES[r] + if (mode == 1) "m" else "" }
        }
        return best
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var s = 0.0; var sa = 0.0; var sb = 0.0
        for (i in a.indices) { s += (a[i] - ma) * (b[i] - mb); sa += (a[i] - ma) * (a[i] - ma); sb += (b[i] - mb) * (b[i] - mb) }
        return s / (sqrt(sa * sb) + 1e-12)
    }

    // ------------------------------------------------------------------ sections + patterns
    fun sections(downbeats: List<Double>, beats: List<Double>, labels: List<String>, energy: DoubleArray, phase: Int, bpm: Double): List<Section> {
        if (downbeats.isEmpty()) return emptyList()
        val barsPerBlock = if (downbeats.size >= 24) 8 else 4
        val bars = downbeats.size
        data class Block(val start: Double, val end: Double, val seq: List<String>, val energy: Double)
        val blocks = ArrayList<Block>()
        var b = 0
        while (b < bars) {
            val e = minOf(bars, b + barsPerBlock)
            val start = downbeats[b]
            val end = if (e < bars) downbeats[e] else beats.last() + 60.0 / bpm
            val seq = (b until e).map { k -> val bi = phase + 4 * k; labels.getOrElse(bi) { "N" } }
            val en = (b until e).flatMap { k -> (0 until 4).map { j -> energy.getOrElse(phase + 4 * k + j) { 0.0 } } }.average()
            blocks += Block(start, end, seq, en)
            b = e
        }
        val letters = ArrayList<Char>()
        val reps = ArrayList<List<String>>()
        for (bl in blocks) {
            var idx = -1
            for ((k, r) in reps.withIndex()) {
                val m = minOf(r.size, bl.seq.size)
                val same = (0 until m).count { r[it] == bl.seq[it] }
                if (m > 0 && same >= 0.75 * m) { idx = k; break }
            }
            if (idx < 0) { reps += bl.seq; idx = reps.size - 1 }
            letters += ('A' + idx)
        }
        val meanE = blocks.map { it.energy }.average()
        return blocks.mapIndexed { i, bl ->
            val loud = bl.energy > meanE * 1.1
            Section(bl.start, bl.end, "段落 ${letters[i]}" + if (loud) "（強）" else "", suggestPattern(bpm, loud))
        }
    }

    /** Rule-based default (the PC server matches the guitar stem's rhythm instead). */
    fun suggestPattern(bpm: Double, loud: Boolean): String = when {
        bpm < 80 -> if (loud) "s_folk" else "s_ballad"
        bpm < 115 -> if (loud) "s_pop1" else "s_folk"
        bpm < 140 -> if (loud) "s_alt" else "s_quarter"
        else -> if (loud) "s_eighth_down" else "s_quarter"
    }
}
