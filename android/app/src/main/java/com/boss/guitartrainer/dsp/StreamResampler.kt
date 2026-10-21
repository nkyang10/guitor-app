package com.boss.guitartrainer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Growable float buffer. [data] can be longer than [size] (no trimming copy at the end – callers pass [size] on),
 * so a 10-minute song needs one ~53 MB array instead of several copies.
 */
class FloatBuf(initialCapacity: Int) {
    var data = FloatArray(max(initialCapacity, 1024)); private set
    var size = 0; private set

    fun add(v: Float) {
        if (size == data.size) grow(1)
        data[size++] = v
    }

    fun addAll(src: FloatArray, n: Int) {
        if (size + n > data.size) grow(n)
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    /** +25 % (at least 64 k samples): a wrong length estimate costs a small copy, never a doubling. */
    private fun grow(min: Int) {
        data = data.copyOf(max(size + min, data.size + max(data.size / 4, 1 shl 16)))
    }
}

/**
 * Streaming windowed-sinc resampler. Same kernel as [Resampler.resample] (Hann window, cut-off 0.95 × Nyquist of the
 * lower rate, [taps] per side, weights renormalised at the edges) but fed block by block, so a song is never held at
 * its original sample rate, and with the kernel pre-computed per phase (≈ 20× faster than evaluating sin/cos per tap).
 */
class StreamResampler(private val srIn: Int, private val srOut: Int, private val out: FloatBuf, private val taps: Int = 16) {
    private val passThrough = srIn == srOut
    private val l: Long          // output phases: input position of output i = i * m / l (exact rational)
    private val m: Long
    private val cutoff = min(1.0, srOut.toDouble() / srIn) * 0.95
    private val width = 2 * taps
    private val table: Array<DoubleArray>?
    private val tableSum: DoubleArray?

    init {
        require(srIn > 0 && srOut > 0) { "bad sample rate $srIn → $srOut" }
        val g = gcd(srIn.toLong(), srOut.toLong())
        l = srOut / g; m = srIn / g
        if (!passThrough && l <= MAX_PHASES) {
            table = Array(l.toInt()) { p -> DoubleArray(width) { j -> coef(p.toDouble() / l + (taps - 1 - j)) } }
            tableSum = DoubleArray(l.toInt()) { p -> var s = 0.0; for (v in table[p]) s += v; s }
        } else { table = null; tableSum = null }
    }

    private var buf = FloatArray(8192)
    private var bufStart = 0L        // absolute input index of buf[0]
    private var bufLen = 0
    private var received = 0L        // input samples so far
    private var nextOut = 0L
    private var finished = false

    fun push(x: FloatArray, n: Int) {
        check(!finished) { "push after finish" }
        if (n <= 0) return
        if (passThrough) { out.addAll(x, n); received += n; return }
        compact()
        if (bufLen + n > buf.size) buf = buf.copyOf(max(buf.size * 2, bufLen + n))
        System.arraycopy(x, 0, buf, bufLen, n)
        bufLen += n; received += n
        produce(false)
    }

    /** Flushes the tail (edge-normalised like the batch version). Output length = floor(inputs × srOut / srIn). */
    fun finish() {
        if (finished) return
        finished = true
        if (!passThrough) produce(true)
    }

    private fun produce(final: Boolean) {
        val total = received
        val nOut = if (final) total * l / m else Long.MAX_VALUE
        while (nextOut < nOut) {
            val num = nextOut * m
            val c = num / l
            val p = (num % l).toInt()
            val hi = c + taps
            if (!final && hi >= total) break
            val lo = c - taps + 1
            var acc = 0.0
            var wsum = 0.0
            val h = table?.get(p)
            val base = (lo - bufStart).toInt()
            if (h != null && lo >= 0 && hi < total) {
                for (j in 0 until width) acc += buf[base + j] * h[j]
                wsum = tableSum!![p]
            } else {
                val frac = p.toDouble() / l
                for (j in 0 until width) {
                    val k = lo + j
                    if (k < 0 || k >= total) continue
                    val w = h?.get(j) ?: coef(frac + (taps - 1 - j))
                    acc += buf[base + j] * w; wsum += w
                }
            }
            out.add(if (wsum != 0.0) (acc / wsum).toFloat() else 0f)
            nextOut++
        }
    }

    /** Drops input that no future output can reach (keeps the history buffer at ~one block). */
    private fun compact() {
        val need = (nextOut * m) / l - taps + 1
        val drop = (need - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop <= 0) return
        System.arraycopy(buf, drop, buf, 0, bufLen - drop)
        bufLen -= drop; bufStart += drop
    }

    private fun coef(d: Double): Double {
        val arg = PI * d * cutoff
        val sinc = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
        val w = 0.5 + 0.5 * cos(PI * d / taps)
        return sinc * w
    }

    companion object {
        /** Above this many phases (odd rates) the kernel is evaluated per sample instead of tabulated. */
        const val MAX_PHASES = 4096L

        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /** Whole-array convenience wrapper. */
        fun resample(x: FloatArray, srIn: Int, srOut: Int, taps: Int = 16): FloatArray {
            if (srIn == srOut) return x.copyOf()
            val out = FloatBuf((x.size.toLong() * srOut / srIn).toInt() + 1)
            StreamResampler(srIn, srOut, out, taps).apply { push(x, x.size); finish() }
            return if (out.size == out.data.size) out.data else out.data.copyOf(out.size)
        }
    }
}
