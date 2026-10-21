package com.boss.guitartrainer.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.boss.guitartrainer.dsp.FloatBuf
import com.boss.guitartrainer.dsp.StreamResampler
import java.io.File
import kotlin.math.min

/**
 * Decodes any Android-supported audio file to mono float at [SongAnalyzer.SR] (MediaExtractor + MediaCodec).
 *
 * Memory: every decoder block is down-mixed and resampled straight away, so only the 22.05 kHz mono result is kept
 * (10 min ≈ 53 MB). The old version first collected the whole song at its original rate in a doubling array,
 * then copied and resampled it – 200–300 MB for a 6–10 minute song, which crashed phones with a 256 MB app limit.
 */
object AudioDecoder {
    const val MAX_SECONDS = 600.0

    class Decoded(
        /** Samples at [sampleRate]; only the first [size] values are valid (no trimming copy). */
        val data: FloatArray,
        val size: Int,
        val sampleRate: Int,
        /** Length of the source file in seconds (0 when unknown). */
        val sourceSeconds: Double,
        /** True when the file was longer than the limit and only the beginning was decoded. */
        val truncated: Boolean,
    ) {
        val seconds get() = size.toDouble() / sampleRate
    }

    class UnsupportedFormat(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /** Duration of the first audio track in seconds (0 when unknown), without decoding. */
    fun probeSeconds(file: File): Double {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0 } ?: 0.0
        } catch (_: Exception) { 0.0 } finally { ex.release() }
    }

    /**
     * [progress] gets 0..1 (at most every 0.5 %); it may throw (e.g. CancellationException) to abort – the codec is
     * always released.
     */
    fun decode(file: File, targetSr: Int = SongAnalyzer.SR, maxSec: Double = MAX_SECONDS, progress: (Double) -> Unit = {}): Decoded {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try { ex.setDataSource(file.absolutePath) } catch (e: Exception) { throw UnsupportedFormat("讀唔到呢個檔案", e) }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw UnsupportedFormat("檔案入面冇音訊")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else Pcm.ENC_16
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val srcSec = durUs / 1e6
            val limitUs = (maxSec * 1e6).toLong()
            // pre-size from the duration (+1 s); unknown length starts at 4 min and grows by 25 %
            val keepSec = if (durUs > 0) min(srcSec, maxSec) else 240.0
            val out = FloatBuf(((keepSec + 1.0) * targetSr).toInt())
            codec = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }
            } catch (e: Exception) { throw UnsupportedFormat("手機唔識解碼呢個格式（$mime），試下 MP3 / M4A", e) }

            var rs = StreamResampler(sr, targetSr, out)
            var scratch = FloatArray(16384)
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false; var cut = false
            var framesIn = 0L; var secIn = 0.0
            var lastP = -1.0
            var idle = 0
            fun report(p: Double) { if (p - lastP >= 0.005 || p >= 1.0) { lastP = p; progress(p.coerceIn(0.0, 1.0)) } }
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else if (t > limitUs) {
                            cut = true
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, t, 0)
                            ex.advance()
                            report(if (durUs > 0) t.toDouble() / min(durUs, limitUs) else secIn / maxSec)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    val nsr = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sr
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (nsr != sr) {           // e.g. HE-AAC: the decoder reports the real rate here
                        if (framesIn > 0) rs.finish()
                        sr = nsr
                        rs = StreamResampler(sr, targetSr, out)
                    }
                } else if (oi >= 0) {
                    idle = 0
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val frames = Pcm.frames(ob, enc, ch)
                        if (scratch.size < frames) scratch = FloatArray(frames)
                        val n = Pcm.toMono(ob, enc, ch, scratch)
                        rs.push(scratch, n)
                        framesIn += n; secIn += n.toDouble() / sr
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    if (secIn > maxSec + 1.0) { cut = true; outEos = true }   // safety net when timestamps are missing
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inEos && ++idle > 300) {
                    outEos = true                                            // decoder never signalled EOS (3 s)
                }
            }
            rs.finish()
            report(1.0)
            // a cut that loses less than half a second (e.g. a 10:00.3 file) does not count as "partial"
            return Decoded(out.data, out.size, targetSr, srcSec, if (srcSec > 0) srcSec > maxSec + 0.5 else cut)
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            ex.release()
        }
    }

    /** Compatibility helper: decoded samples as an exact-size array. */
    fun decodeMono(file: File, targetSr: Int = SongAnalyzer.SR, maxSec: Double = MAX_SECONDS, progress: (Double) -> Unit = {}): FloatArray {
        val d = decode(file, targetSr, maxSec, progress)
        return if (d.size == d.data.size) d.data else d.data.copyOf(d.size)
    }
}
