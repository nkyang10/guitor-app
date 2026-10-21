package com.boss.guitartrainer

import com.boss.guitartrainer.analysis.Pcm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoder output formats → mono float. */
class PcmTest {
    private fun buf(bytes: Int): ByteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder())
    private val le = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    @Test fun pcm16StereoIsAveraged() {
        val b = buf(12)
        b.putShort(16384); b.putShort(-16384)        // 0.5 / -0.5 → 0
        b.putShort(32767); b.putShort(32767)         // ≈ 1
        b.putShort(-32768); b.putShort(0)            // -1 / 0 → -0.5
        b.flip()
        val out = FloatArray(3)
        assertEquals(3, Pcm.frames(b, Pcm.ENC_16, 2))
        assertEquals(3, Pcm.toMono(b, Pcm.ENC_16, 2, out))
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(32767 / 32768f, out[1], 1e-6f)
        assertEquals(-0.5f, out[2], 1e-6f)
        assertEquals(0, b.position())                // buffer position untouched
    }

    @Test fun pcm8Unsigned() {
        val b = buf(3); b.put(0x80.toByte()); b.put(0xFF.toByte()); b.put(0x00); b.flip()
        val out = FloatArray(3)
        assertEquals(3, Pcm.toMono(b, Pcm.ENC_8, 1, out))
        assertEquals(0f, out[0], 1e-6f); assertEquals(127 / 128f, out[1], 1e-6f); assertEquals(-1f, out[2], 1e-6f)
    }

    @Test fun pcmFloatSixChannels() {
        val b = buf(4 * 6 * 2)
        repeat(6) { b.putFloat(0.6f) }
        repeat(6) { c -> b.putFloat(if (c % 2 == 0) 1f else -1f) }
        b.flip()
        val out = FloatArray(2)
        assertEquals(2, Pcm.toMono(b, Pcm.ENC_FLOAT, 6, out))
        assertEquals(0.6f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
    }

    @Test fun pcm24Packed() {
        val b = buf(6)
        fun put24(v: Int) {
            val bytes = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte())
            if (le) b.put(bytes) else b.put(bytes.reversedArray())
        }
        put24(0x400000); put24(-0x400000)            // +0.5 / -0.5 (mono)
        b.flip()
        val out = FloatArray(2)
        assertEquals(2, Pcm.toMono(b, Pcm.ENC_24_PACKED, 1, out))
        assertEquals(0.5f, out[0], 1e-6f); assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test fun pcm32AndOffset() {
        val b = buf(4 * 4)
        b.putInt(123); b.putInt(1 shl 30); b.putInt(-(1 shl 30)); b.putInt(0)
        b.position(4); b.limit(12)                    // only the two middle samples
        val out = FloatArray(2)
        assertEquals(2, Pcm.toMono(b, Pcm.ENC_32, 1, out))
        assertEquals(0.5f, out[0], 1e-6f); assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test fun partialFrameIsIgnored() {
        val b = buf(6); b.putShort(100); b.putShort(100); b.putShort(100); b.flip()   // 1.5 stereo frames
        assertEquals(1, Pcm.toMono(b, Pcm.ENC_16, 2, FloatArray(2)))
    }
}
