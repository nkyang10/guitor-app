package com.boss.guitartrainer

import com.boss.guitartrainer.dsp.FloatBuf
import com.boss.guitartrainer.dsp.Resampler
import com.boss.guitartrainer.dsp.StreamResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The streaming resampler used by the song decoder must give the same samples as the old whole-array version. */
class StreamResamplerTest {
    private fun signal(n: Int, sr: Int, seed: Int = 1): FloatArray {
        val r = java.util.Random(seed.toLong())
        return FloatArray(n) { i ->
            (0.4 * sin(2 * PI * 440.0 * i / sr) + 0.2 * sin(2 * PI * 1234.5 * i / sr) + 0.1 * (r.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun streamed(x: FloatArray, srIn: Int, srOut: Int, maxChunk: Int, seed: Long): FloatBuf {
        val out = FloatBuf(16)
        val rs = StreamResampler(srIn, srOut, out)
        val rnd = java.util.Random(seed)
        var i = 0
        while (i < x.size) {
            val n = min(x.size - i, 1 + rnd.nextInt(maxChunk))
            rs.push(x.copyOfRange(i, i + n), n)
            i += n
        }
        rs.finish()
        return out
    }

    private fun assertClose(ref: FloatArray, got: FloatBuf, what: String) {
        assertTrue("$what: length ${got.size} vs ${ref.size}", abs(got.size - ref.size) <= 1)
        var maxDiff = 0.0
        for (k in 0 until min(got.size, ref.size)) maxDiff = max(maxDiff, abs(got.data[k] - ref[k]).toDouble())
        assertTrue("$what: max diff $maxDiff", maxDiff < 1e-5)
    }

    @Test fun streamingMatchesReference() {
        for (srIn in listOf(44100, 48000, 32000, 8000, 11025, 96000)) {
            val x = signal(srIn / 2 + 337, srIn)                 // ~0.5 s, odd length
            val ref = Resampler.resampleDirect(x, srIn, 22050)
            assertClose(ref, streamed(x, srIn, 22050, 5000, srIn.toLong()), "$srIn→22050")
        }
    }

    @Test fun tinyChunksAndOddRate() {
        // one sample at a time, and a rate with too many phases for the table (per-sample kernel path)
        val x = signal(3000, 44100)
        assertClose(Resampler.resampleDirect(x, 44100, 22050), streamed(x, 44100, 22050, 1, 7), "1-sample chunks")
        val y = signal(6000, 44056, 3)
        assertClose(Resampler.resampleDirect(y, 44056, 22050), streamed(y, 44056, 22050, 700, 9), "44056→22050")
    }

    @Test fun batchWrapperAndPassThrough() {
        val x = signal(20000, 48000)
        val ref = Resampler.resampleDirect(x, 48000, 22050)
        val fast = Resampler.resample(x, 48000, 22050)
        val b = FloatBuf(fast.size).apply { addAll(fast, fast.size) }
        assertClose(ref, b, "Resampler.resample")
        val same = streamed(x, 22050, 22050, 999, 1)
        assertEquals(x.size, same.size)
        for (k in x.indices) assertEquals(x[k], same.data[k], 0f)
    }

    @Test fun outputLengthIsExact() {
        // floor(n × out / in) for every chunking
        for (n in listOf(1, 2, 319, 320, 321, 48000, 48001)) {
            val got = streamed(signal(n, 48000), 48000, 22050, 4096, n.toLong()).size
            assertEquals("n=$n", (n.toLong() * 22050 / 48000).toInt(), got)
        }
    }

    @Test fun floatBufGrowsInSmallSteps() {
        val b = FloatBuf(1000)
        val chunk = FloatArray(700) { it.toFloat() }
        repeat(10) { b.addAll(chunk, chunk.size) }
        b.add(42f)
        assertEquals(7001, b.size)
        assertEquals(699f, b.data[6999], 0f)
        assertEquals(42f, b.data[7000], 0f)
        assertTrue("capacity ${b.data.size}", b.data.size < 7001 + (1 shl 16) + 10_000)
    }
}
