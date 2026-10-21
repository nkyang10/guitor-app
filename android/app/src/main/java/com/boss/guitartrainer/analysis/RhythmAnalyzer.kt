package com.boss.guitartrainer.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.boss.guitartrainer.dsp.Fft
import com.boss.guitartrainer.music.BeatGrid
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.GridOps
import com.boss.guitartrainer.music.SongSetup
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Onset strength of a song, ~100 frames / s, in three bands: low (bass / kick 40–200 Hz), mid (guitar body / snare
 * 200 Hz–4 kHz) and high (pick noise / hats 4–11 kHz). Frame k sits at [t0] + k / [fps] seconds of the AUDIO FILE,
 * i.e. the same clock as ExoPlayer's position. Each band is baseline-removed and RMS-normalised.
 */
class OnsetEnvelope(val fps: Double, val t0: Double, val low: FloatArray, val mid: FloatArray, val high: FloatArray) {
    val size get() = mid.size
    val duration get() = t0 + size / fps
    fun timeOf(k: Double) = t0 + k / fps
    fun frameOf(t: Double) = (t - t0) * fps

    /** Every attack (drums, bass, guitar): used for beat tracking. */
    val full: FloatArray by lazy { mix(0.8f, 1f, 0.5f) }

    /** Strum / pick attacks (mid + high): used to read the song's strum rhythm. */
    val rhythm: FloatArray by lazy { mix(0f, 1f, 0.5f) }

    private val smoothFull: FloatArray by lazy { FloatArray(size) { i -> 0.25f * full[max(0, i - 1)] + 0.5f * full[i] + 0.25f * full[min(size - 1, i + 1)] } }

    fun mix(a: Float, b: Float, c: Float) = FloatArray(size) { a * low[it] + b * mid[it] + c * high[it] }

    /** Max of [env] within ±[win] seconds around [t]. */
    fun peak(env: FloatArray, t: Double, win: Double): Float {
        if (size == 0) return 0f
        val a = floor(frameOf(t - win)).toInt().coerceAtLeast(0)
        val b = ceil(frameOf(t + win)).toInt().coerceAtMost(size - 1)
        var m = 0f
        for (i in a..b) if (env[i] > m) m = env[i]
        return m
    }

    /** Linear interpolation of the (lightly smoothed) full envelope at time [t]. */
    fun valueAt(t: Double): Float {
        val f = frameOf(t)
        if (f < 0 || f > size - 1) return 0f
        val i = f.toInt().coerceAtMost(size - 2).coerceAtLeast(0)
        val fr = (f - i).toFloat()
        return smoothFull[i] * (1 - fr) + smoothFull[i + 1] * fr
    }

    fun toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(4 + 8 + 8 + 4 + size * 12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC); bb.putDouble(fps); bb.putDouble(t0); bb.putInt(size)
        for (a in arrayOf(low, mid, high)) for (v in a) bb.putFloat(v)
        return bb.array()
    }

    companion object {
        private const val MAGIC = 0x31564E45 // "ENV1"
        const val FILE = "rhythm_env.bin"

        fun fromBytes(b: ByteArray): OnsetEnvelope? = runCatching {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.int == MAGIC)
            val fps = bb.double; val t0 = bb.double; val n = bb.int
            require(n in 0..2_000_000 && b.size >= 24 + n * 12)
            val arrs = Array(3) { FloatArray(n) { bb.float } }
            OnsetEnvelope(fps, t0, arrs[0], arrs[1], arrs[2])
        }.getOrNull()

        fun load(dir: File): OnsetEnvelope? = File(dir, FILE).takeIf { it.exists() }?.let { runCatching { fromBytes(it.readBytes()) }.getOrNull() }

        fun save(dir: File, e: OnsetEnvelope) {
            val tmp = File(dir, "$FILE.tmp"); tmp.writeBytes(e.toBytes())
            if (!tmp.renameTo(File(dir, FILE))) { File(dir, FILE).delete(); tmp.renameTo(File(dir, FILE)) }
        }
    }
}

internal class FloatList(cap: Int = 4096) {
    private var a = FloatArray(cap); var n = 0; private set
    fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun toArray() = a.copyOf(n)
}

/**
 * Streaming 3-band spectral flux at the file's own sample rate (no resampling, no whole-song buffer).
 * Frames are centred (frame f is centred on sample f * hop); flux f = rise from frame f-1 to f, so it sits at (f - 0.5) / fps.
 */
class FluxStream(val sr: Int) {
    val n = if (sr < 32000) 1024 else 2048
    val hop = max(1, (sr / 100.0).roundToInt())
    val fps = sr.toDouble() / hop
    private val fft = Fft(n)
    private val win = Fft.hann(n)
    private val norm = 2.0 / win.sum()
    private val buf = FloatArray(n)
    private var fill = n / 2
    private val mag = DoubleArray(n / 2 + 1)
    private var cur = DoubleArray(n / 2 + 1)
    private var prev = DoubleArray(n / 2 + 1)
    private var frames = 0
    private val bands: Array<IntRange>
    private val out = Array(3) { FloatList() }
    var samples = 0L; private set

    init {
        fun bin(f: Double) = (f * n / sr).roundToInt().coerceIn(1, n / 2)
        val top = min(11000.0, sr / 2.0 - 50)
        bands = arrayOf(bin(40.0) until bin(200.0), bin(200.0) until bin(4000.0), bin(4000.0) until max(bin(4000.0) + 1, bin(top)))
    }

    fun push(x: FloatArray, off: Int = 0, len: Int = x.size - off) {
        var i = off; val end = off + len
        samples += len
        while (i < end) {
            val k = min(n - fill, end - i)
            System.arraycopy(x, i, buf, fill, k); fill += k; i += k
            if (fill == n) { frame(); System.arraycopy(buf, hop, buf, 0, n - hop); fill = n - hop }
        }
    }

    private fun frame() {
        fft.magnitude(buf, 0, win, mag)
        for (k in 0..n / 2) cur[k] = ln(1.0 + 1000.0 * mag[k] * norm)
        for ((bi, r) in bands.withIndex()) {
            var s = 0.0
            if (frames > 0) for (k in r) { val d = cur[k] - prev[k]; if (d > 0) s += d }
            out[bi].add((s / max(1, r.last - r.first + 1)).toFloat())
        }
        val t = prev; prev = cur; cur = t
        frames++
    }

    fun finish(): OnsetEnvelope {
        push(FloatArray(n / 2))               // flush the tail so the last attacks get frames
        val w = max(1, (0.25 * fps).roundToInt())
        val arrs = out.map { post(it.toArray(), w) }
        return OnsetEnvelope(fps, -0.5 / fps + RhythmAnalyzer.WINDOW_LEAD * n / sr, arrs[0], arrs[1], arrs[2])
    }

    /** Subtract the ±0.25 s local mean (keeps attacks, drops sustained texture), half-wave rectify, RMS-normalise. */
    private fun post(x: FloatArray, w: Int): FloatArray {
        val n = x.size
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + x[i]
        val y = FloatArray(n)
        var ss = 0.0
        for (i in 0 until n) {
            val a = max(0, i - w); val b = min(n, i + w + 1)
            val m = (pre[b] - pre[a]) / (b - a)
            val v = max(0.0, x[i] - m)
            y[i] = v.toFloat(); ss += v * v
        }
        val rms = sqrt(ss / max(1, n))
        if (rms > 1e-9) for (i in 0 until n) y[i] = (y[i] / rms).toFloat()
        return y
    }
}

/** Beat tracking + grid checks on an [OnsetEnvelope]. All times here are AUDIO-file seconds. */
object RhythmAnalyzer {
    /**
     * A centred Hann frame already "sees" an attack before it happens, so flux peaks lead the true attack by
     * ~0.28 window lengths (measured in SongToolsTest: 44.1k/48k/22.05k/32k all within 1 ms after this shift).
     */
    const val WINDOW_LEAD = 0.28

    fun envelope(x: FloatArray, sr: Int): OnsetEnvelope = FluxStream(sr).also { it.push(x) }.finish()

    /** Tempo candidates (bpm, strength 0..1) from the envelope autocorrelation, strongest first. */
    fun tempi(env: OnsetEnvelope, lo: Double = 50.0, hi: Double = 220.0): List<Pair<Double, Double>> {
        val x = env.full; val n = x.size
        val minLag = max(2, (60.0 / hi * env.fps).toInt()); val maxLag = min(n - 2, (60.0 / lo * env.fps).toInt() + 1)
        if (maxLag <= minLag) return emptyList()
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            ac[lag] = s / (n - lag)
        }
        val peaks = ArrayList<Pair<Double, Double>>()
        for (lag in minLag..maxLag) if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > 0) {
            val a = ac[lag - 1]; val b = ac[lag]; val c = ac[lag + 1]
            val den = a - 2 * b + c
            val d = if (abs(den) < 1e-12) 0.0 else (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
            peaks += 60.0 * env.fps / (lag + d) to b
        }
        val mx = peaks.maxOfOrNull { it.second } ?: return emptyList()
        return peaks.map { it.first to it.second / mx }.sortedByDescending { it.second }
    }

    /** Ellis dynamic-programming beat tracker; returns fractional beat frames. [period] is in frames. */
    fun trackFrames(onset: FloatArray, period: Double, tightness: Double = 100.0): DoubleArray {
        val n = onset.size
        if (n < 8 || period < 4) return DoubleArray(0)
        var mean = 0.0; for (v in onset) mean += v; mean /= n
        var sd = 0.0; for (v in onset) sd += (v - mean) * (v - mean); sd = sqrt(sd / n).coerceAtLeast(1e-9)
        val sigma = period / 32.0
        val hw = max(1, ceil(3 * sigma).toInt())
        val g = DoubleArray(2 * hw + 1) { exp(-0.5 * ((it - hw) / sigma).pow(2)) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (j in g.indices) { val k = i + j - hw; if (k in 0 until n) s += g[j] * onset[k] }
            local[i] = s / sd
        }
        val lo = max(1, (period / 2).roundToInt()); val hi = (2 * period).roundToInt()
        val tx = DoubleArray(hi + 1) { d -> if (d < lo) 0.0 else -tightness * ln(d / period).pow(2) }
        val cum = DoubleArray(n); val back = IntArray(n) { -1 }
        val localMax = local.maxOrNull() ?: 0.0
        var first = true
        for (i in 0 until n) {
            var best = Double.NEGATIVE_INFINITY; var arg = -1
            for (d in lo..hi) {
                val j = i - d
                val v = (if (j >= 0) cum[j] else 0.0) + tx[d]
                if (v > best) { best = v; arg = j }
            }
            cum[i] = local[i] + best
            if (first && local[i] < 0.01 * localMax) back[i] = -1 else { back[i] = arg; first = false }
        }
        val maxima = (1 until n - 1).filter { cum[it] > cum[it - 1] && cum[it] >= cum[it + 1] }
        if (maxima.isEmpty()) return DoubleArray(0)
        val med = maxima.map { cum[it] }.sorted()[maxima.size / 2]
        val last = maxima.lastOrNull { cum[it] >= 0.5 * med } ?: maxima.last()
        val beats = ArrayList<Int>()
        var b = last
        while (b >= 0) { beats += b; b = back[b] }
        beats.reverse()
        if (beats.isEmpty()) return DoubleArray(0)
        val sm = beats.map { local[it] }
        val thr = 0.5 * sqrt(sm.sumOf { it * it } / sm.size)
        var s = 0; var e = beats.size
        while (s < e && sm[s] < thr) s++
        while (e > s && sm[e - 1] < thr) e--
        return DoubleArray(e - s) { k ->
            val f = beats[s + k]
            if (f <= 0 || f >= n - 1) f.toDouble() else {
                val a = local[f - 1]; val bb = local[f]; val c = local[f + 1]
                val den = a - 2 * bb + c
                if (bb >= a && bb >= c && abs(den) > 1e-12) f + (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else f.toDouble()
            }
        }
    }

    /** Beat times (audio seconds) for a tempo near [bpm]. */
    fun track(env: OnsetEnvelope, bpm: Double, tightness: Double = 100.0): List<Double> =
        trackFrames(env.full, 60.0 / bpm * env.fps, tightness).map { env.timeOf(it) }

    /** Continue the edge tempo so the grid still covers [from]..[to] (soft intros / outros the tracker trimmed). */
    fun extend(beats: List<Double>, from: Double, to: Double): List<Double> {
        if (beats.size < 4) return beats
        val head = SongSetup.medianPeriod(beats.take(9)); val tail = SongSetup.medianPeriod(beats.takeLast(9))
        val pre = ArrayList<Double>()
        var t = beats.first() - head
        while (t >= from - head * 0.25 && t >= -0.05) { pre += t; t -= head }
        val post = ArrayList<Double>()
        t = beats.last() + tail
        while (t <= to + tail * 0.25) { post += t; t += tail }
        return pre.reversed() + beats + post
    }

    /** Mean envelope strength on the beats (±25 ms): higher = the grid sits on the attacks. */
    fun fit(env: OnsetEnvelope, beats: List<Double>): Double {
        var s = 0.0; var c = 0
        for (b in beats) { if (b < env.t0 || b > env.duration) continue; s += env.peak(env.full, b, 0.025); c++ }
        return if (c == 0) 0.0 else s / c
    }

    /** Shift (s) that best lines [beats] up with the attacks, searched in ±[range] with a mild preference for 0. */
    fun bestLag(env: OnsetEnvelope, beats: List<Double>, range: Double = 0.1, step: Double = 0.005): Double {
        if (beats.isEmpty()) return 0.0
        val steps = (2 * range / step).roundToInt()
        var best = 0.0; var bestScore = 0.0
        for (i in 0..steps) {
            val lag = -range + i * step
            var s = 0.0
            for (b in beats) s += env.valueAt(b + lag)
            s *= 1.0 - 0.15 * (lag / range).pow(2)
            if (s > bestScore + 1e-9) { bestScore = s; best = lag }
        }
        return if (abs(best) < step / 2) 0.0 else best
    }

    /** Downbeat phase that keeps the old downbeats (same tempo); -1 if the old grid does not overlap. */
    fun phaseFromOld(beats: List<Double>, oldDowns: List<Double>, bpb: Int): Int {
        if (beats.size < 2 || oldDowns.isEmpty()) return -1
        val period = SongSetup.medianPeriod(beats)
        val score = IntArray(bpb)
        for (d in oldDowns) {
            val i = SongSetup.nearest(beats, d)
            if (abs(beats[i] - d) < period * 0.35) score[i % bpb]++
        }
        val best = score.indices.maxByOrNull { score[it] } ?: return -1
        return if (score[best] == 0) -1 else best
    }

    /** New grid tracked at [bpm]; downbeats follow [oldDowns] when possible, otherwise the chord changes. */
    fun trackGrid(env: OnsetEnvelope, bpm: Double, bpb: Int, chords: List<ChordSpan>, oldDowns: List<Double>,
             cover: ClosedFloatingPointRange<Double>?, note: String): BeatGrid? {
        var beats = track(env, bpm)
        if (beats.size < 8) return null
        if (cover != null) beats = extend(beats, cover.start, cover.endInclusive)
        val ph = phaseFromOld(beats, oldDowns, bpb).takeIf { it >= 0 } ?: GridOps.phaseFromChords(beats, chords, bpb)
        return GridOps.withPhase(beats, bpb, ph, note)
    }

    data class Alignment(val grid: BeatGrid?, val offsetMs: Int, val message: String)

    /**
     * One-tap「自動對準」: re-track at the same tempo and keep it if it sits clearly better on the attacks,
     * otherwise just fix a constant early / late shift.
     * [grid] = current grid in CHART time, [offset] = chart time − audio time (seconds, incl. the user's offset).
     * Returned grid is in chart time with the user offset folded in (so the new offsetMs is 0).
     */
    fun autoAlign(env: OnsetEnvelope, grid: BeatGrid, chords: List<ChordSpan>, offset: Double, baseOffset: Double, userOffsetMs: Int): Alignment {
        val audioBeats = grid.beats.map { it - offset }
        val fOld = fit(env, audioBeats)
        val tracked = trackGrid(env, grid.bpm, grid.beatsPerBar, chords.map { it.copy(start = it.start - offset, end = it.end - offset) },
            grid.downbeats.map { it - offset }, (audioBeats.firstOrNull() ?: 0.0)..(audioBeats.lastOrNull() ?: env.duration), "auto")
        if (tracked != null) {
            val fNew = fit(env, tracked.beats)
            val sameTempo = abs(tracked.bpm / grid.bpm - 1) < 0.08
            if (sameTempo && fNew > fOld * 1.05) {
                val g = tracked.copy(beats = tracked.beats.map { it + baseOffset }, downbeats = tracked.downbeats.map { it + baseOffset })
                return Alignment(g, 0, "已重新跟住首歌對準每一拍")
            }
        }
        val lag = bestLag(env, audioBeats)
        if (abs(lag) >= 0.012) {
            val newMs = userOffsetMs - (lag * 1000).roundToInt()
            return Alignment(null, newMs, if (lag > 0) "拍子早咗 %d ms，已推遲".format((lag * 1000).roundToInt()) else "拍子遲咗 %d ms，已提早".format((-lag * 1000).roundToInt()))
        }
        return Alignment(null, userOffsetMs, "節拍已經貼住首歌 👍")
    }

    /**
     * Onset strength per bar at [slotsPerBeat] slots per beat (slots follow the beat grid, so tempo drift is fine).
     * [beats]/[downbeats] in AUDIO time. Bars that run past the grid are skipped.
     */
    fun barSlots(env: OnsetEnvelope, curve: FloatArray, beats: List<Double>, downbeats: List<Double>, bpb: Int, slotsPerBeat: Int,
                 range: ClosedFloatingPointRange<Double>? = null): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        if (beats.size < 2) return out
        for (d in downbeats) {
            if (range != null && (d < range.start - 0.05 || d >= range.endInclusive - 0.05)) continue
            val bi = SongSetup.nearest(beats, d)
            if (abs(beats[bi] - d) > 0.05 || bi + bpb >= beats.size) continue
            val v = FloatArray(bpb * slotsPerBeat)
            for (j in 0 until bpb) {
                val a = beats[bi + j]; val b = beats[bi + j + 1]
                val slot = (b - a) / slotsPerBeat
                val w = min(0.03, slot / 3)
                for (s in 0 until slotsPerBeat) v[j * slotsPerBeat + s] = env.peak(curve, a + slot * s, w)
            }
            out += v
        }
        return out
    }
}

/** Tap-along fix:「跟住首歌撳」gives the real tempo (fixes ×2, ÷2, 3:2) and whether the grid sits on the「&」. */
object TapSync {
    data class Result(val grid: BeatGrid?, val message: String)

    /** [taps] and [grid] in AUDIO time. */
    fun apply(taps: List<Double>, grid: BeatGrid, env: OnsetEnvelope?, chords: List<ChordSpan>, songEnd: Double): Result {
        if (taps.size < 6) return Result(null, "最少撳 6 下（而家 ${taps.size} 下）")
        val diffs = taps.zipWithNext { a, b -> b - a }.filter { it in 0.2..2.0 }.sorted()
        if (diffs.size < 4) return Result(null, "撳得唔穩定，再試一次")
        val med = diffs[diffs.size / 2]
        // least-squares period with integer tap counts (robust to one missed / double tap)
        val k = taps.map { ((it - taps.first()) / med).roundToInt().toDouble() }
        val mk = k.average(); val mt = taps.average()
        var num = 0.0; var den = 0.0
        for (i in taps.indices) { num += (k[i] - mk) * (taps[i] - mt); den += (k[i] - mk) * (k[i] - mk) }
        val pTap = if (den > 0) num / den else med
        val tapBpm = 60.0 / pTap
        val near = grid.beats.filter { it >= taps.first() - 2 && it <= taps.last() + 2 }
        val pGrid = SongSetup.medianPeriod(if (near.size >= 3) near else grid.beats)
        val r = pGrid / pTap
        var g = when {
            r in 0.88..1.14 -> grid
            r in 1.75..2.3 -> GridOps.double(grid)
            r in 0.43..0.57 -> GridOps.halve(grid)
            else -> null
        }
        var msg = when {
            g === grid -> "速度啱（%.0f BPM）".format(tapBpm)
            g != null && r > 1 -> "拍子太慢 → 改做 %.0f BPM".format(tapBpm)
            g != null -> "拍子太快 → 改做 %.0f BPM".format(tapBpm)
            else -> "重新跟你嘅速度 %.0f BPM 對拍".format(tapBpm)
        }
        var changed = g !== grid
        if (g == null) {
            g = env?.let { RhythmAnalyzer.trackGrid(it, tapBpm, grid.beatsPerBar, chords, emptyList(), 0.0..songEnd, "tap") }
                ?: uniform(taps, pTap, grid.beatsPerBar, songEnd)
            changed = true
        }
        // phase: are the taps half a beat away from the grid?
        val p = SongSetup.medianPeriod(g.beats)
        var sx = 0.0; var sy = 0.0
        for (t in taps) {
            val i = SongSetup.nearest(g.beats, t)
            val ph = (t - g.beats[i]) / p
            sx += cos(2 * Math.PI * ph); sy += sin(2 * Math.PI * ph)
        }
        val mean = atan2(sy, sx) / (2 * Math.PI)
        val strength = sqrt(sx * sx + sy * sy) / taps.size
        if (strength > 0.5 && abs(mean) > 0.3) { g = GridOps.halfBeat(g); msg += "，移咗半拍"; changed = true }
        if (!changed) return Result(null, "$msg，拍子已經啱 👍")
        return Result(g.copy(note = "tap"), msg)
    }

    private fun uniform(taps: List<Double>, p: Double, bpb: Int, end: Double): BeatGrid {
        var t0 = taps.first()
        while (t0 - p >= 0) t0 -= p
        val beats = ArrayList<Double>()
        var t = t0
        while (t <= end + p * 0.5) { beats += t; t += p }
        val first = SongSetup.nearest(beats, taps.first())
        return GridOps.withPhase(beats, bpb, Math.floorMod(first, bpb), "tap")
    }
}

/** Android decode: MediaExtractor + MediaCodec → mono → [FluxStream]. Holds only one codec buffer at a time. */
object EnvelopeDecoder {
    class Cancelled : Exception()

    fun decode(file: File, maxSec: Double = 900.0, cancel: () -> Boolean = { false }, progress: (Float) -> Unit = {}): OnsetEnvelope {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("冇音訊軌")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val encoderDelay = if (fmt.containsKey("encoder-delay")) fmt.getInteger("encoder-delay") else 0
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0); c.start()
            var flux: FluxStream? = null
            var pcmFloat = false
            var written = 0L
            var delayShift = -1L
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var mono = FloatArray(8192)
            var lastProg = 0L
            while (!outDone) {
                if (cancel()) throw Cancelled()
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val frames = info.size / ((if (pcmFloat) 4 else 2) * ch)
                        if (mono.size < frames) mono = FloatArray(frames)
                        if (pcmFloat) { val fb = ob.asFloatBuffer(); for (i in 0 until frames) { var s = 0f; repeat(ch) { s += fb.get() }; mono[i] = s / ch } }
                        else { val sb = ob.asShortBuffer(); for (i in 0 until frames) { var s = 0; repeat(ch) { s += sb.get() }; mono[i] = s / (32768f * ch) } }
                        val fs = flux ?: FluxStream(sr).also { flux = it }
                        // Place by timestamp so priming samples line up with what ExoPlayer plays (it trims them).
                        val pts = (info.presentationTimeUs * sr / 1_000_000.0).roundToLong()
                        if (delayShift < 0) delayShift = if (pts >= 0) encoderDelay.toLong() else 0L
                        val pos = pts - delayShift
                        var from = 0
                        if (pos < written) from = min(frames.toLong(), written - pos).toInt()
                        else if (pos > written + 2 && pos - written < sr / 2) { val gap = FloatArray((pos - written).toInt()); fs.push(gap); written += gap.size }
                        if (from < frames) { fs.push(mono, from, frames - from); written += frames - from }
                        if (written > maxSec * sr) outDone = true
                        if (durUs > 0 && written - lastProg > sr) { lastProg = written; progress((written * 1e6 / sr / durUs).toFloat().coerceIn(0f, 1f)) }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            progress(1f)
            return (flux ?: error("解碼唔到")).finish()
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }
}
