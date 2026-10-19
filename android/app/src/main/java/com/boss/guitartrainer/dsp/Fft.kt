package com.boss.guitartrainer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Iterative in-place radix-2 FFT with pre-computed twiddles. Not thread-safe (scratch buffers). */
class Fft(val n: Int) {
    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
    }

    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    val re = DoubleArray(n)
    val im = DoubleArray(n)

    fun transform(re: DoubleArray = this.re, im: DoubleArray = this.im) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]
                    val s = sinT[k]
                    val xr = re[j + half]
                    val xi = im[j + half]
                    val tr = xr * c - xi * s
                    val ti = xr * s + xi * c
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }

    /** Inverse transform (scaled by 1/n). */
    fun inverse(re: DoubleArray = this.re, im: DoubleArray = this.im) {
        for (i in 0 until n) im[i] = -im[i]
        transform(re, im)
        val s = 1.0 / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] = -im[i] * s
        }
    }

    /** Windowed power spectrum of x[offset until offset+window.size] (zero padded to n). out.size >= n/2+1 */
    fun power(x: FloatArray, offset: Int, window: DoubleArray, out: DoubleArray) {
        val m = window.size
        for (i in 0 until n) {
            val idx = offset + i
            re[i] = if (i < m && idx >= 0 && idx < x.size) x[idx] * window[i] else 0.0
            im[i] = 0.0
        }
        transform()
        for (k in 0..n / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }

    fun magnitude(x: FloatArray, offset: Int, window: DoubleArray, out: DoubleArray) {
        power(x, offset, window, out)
        for (k in 0..n / 2) out[k] = sqrt(out[k])
    }

    companion object {
        fun hann(m: Int) = DoubleArray(m) { 0.5 - 0.5 * cos(2.0 * PI * it / (m - 1)) }
        fun nextPow2(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return p
        }
    }
}
