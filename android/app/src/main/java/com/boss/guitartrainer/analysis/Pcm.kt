package com.boss.guitartrainer.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoder output (interleaved PCM in a ByteBuffer) → mono float. Pure JVM so it is unit-tested. */
object Pcm {
    // android.media.AudioFormat values (24-bit / 32-bit constants are API 31, so use the numbers)
    const val ENC_16 = 2
    const val ENC_8 = 3
    const val ENC_FLOAT = 4
    const val ENC_24_PACKED = 21
    const val ENC_32 = 22

    fun bytesPerSample(enc: Int): Int = when (enc) {
        ENC_8 -> 1
        ENC_24_PACKED -> 3
        ENC_FLOAT, ENC_32 -> 4
        else -> 2
    }

    /** Frames available in [b] (position..limit). */
    fun frames(b: ByteBuffer, enc: Int, channels: Int): Int = b.remaining() / (bytesPerSample(enc) * channels.coerceAtLeast(1))

    /**
     * Averages the channels of every frame in [b] (position..limit) into [out] (must hold [frames] values).
     * Returns the number of frames written. Does not move the buffer position.
     */
    fun toMono(b: ByteBuffer, enc: Int, channels: Int, out: FloatArray): Int {
        val ch = channels.coerceAtLeast(1)
        val bps = bytesPerSample(enc)
        val n = frames(b, enc, ch)
        val base = b.position()
        b.order(ByteOrder.nativeOrder())
        val inv = 1f / ch
        when (enc) {
            ENC_FLOAT -> for (f in 0 until n) {
                var s = 0f; var i = base + f * ch * 4
                for (c in 0 until ch) { s += b.getFloat(i); i += 4 }
                out[f] = s * inv
            }
            ENC_8 -> for (f in 0 until n) {
                var s = 0f; var i = base + f * ch
                for (c in 0 until ch) { s += ((b.get(i).toInt() and 0xFF) - 128) / 128f; i++ }
                out[f] = s * inv
            }
            ENC_24_PACKED -> for (f in 0 until n) {
                var s = 0f; var i = base + f * ch * 3
                for (c in 0 until ch) {
                    val v = if (b.order() == ByteOrder.LITTLE_ENDIAN)
                        (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i + 2).toInt() shl 16)
                    else (b.get(i + 2).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i).toInt() shl 16)
                    s += v / 8388608f; i += 3
                }
                out[f] = s * inv
            }
            ENC_32 -> for (f in 0 until n) {
                var s = 0f; var i = base + f * ch * 4
                for (c in 0 until ch) { s += b.getInt(i) / 2147483648f; i += 4 }
                out[f] = s * inv
            }
            else -> for (f in 0 until n) {
                var s = 0f; var i = base + f * ch * bps
                for (c in 0 until ch) { s += b.getShort(i) / 32768f; i += 2 }
                out[f] = s * inv
            }
        }
        return n
    }
}
