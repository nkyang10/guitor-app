package com.boss.guitartrainer.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.boss.guitartrainer.dsp.Resampler
import java.io.File
import java.nio.ByteOrder

/** Decodes any Android-supported audio file to mono float at [targetSr] (MediaExtractor + MediaCodec). */
object AudioDecoder {
    fun decodeMono(file: File, targetSr: Int = SongAnalyzer.SR, maxSec: Double = 600.0, progress: (Double) -> Unit = {}): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalArgumentException("檔案入面冇音訊")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var out = FloatArray(1 shl 20)
        var n = 0
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        var inEos = false; var outEos = false
        val maxSamples = (maxSec * 192000).toLong()
        try {
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || ex.sampleTime > maxSec * 1e6) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            if (durUs > 0) progress((ex.sampleTime.toDouble() / durUs).coerceIn(0.0, 1.0))
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val frames: Int
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = ob.asFloatBuffer(); frames = fb.remaining() / ch
                        if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                        for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); out[n + f] = s / ch }
                    } else {
                        val sb = ob.asShortBuffer(); frames = sb.remaining() / ch
                        if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                        for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get() / 32768f; out[n + f] = s / ch }
                    }
                    n += frames
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n > maxSamples) outEos = true
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        val mono = out.copyOf(n)
        return if (sr == targetSr) mono else Resampler.resample(mono, sr, targetSr)
    }
}
