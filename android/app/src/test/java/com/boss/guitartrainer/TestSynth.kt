package com.boss.guitartrainer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tiny Karplus-Strong guitar synth for tests (mirrors validation/synth.py idea). */
object TestSynth {
    fun pluck(out: FloatArray, sr: Int, start: Int, hz: Double, amp: Double, seed: Int, decay: Double = 0.996, maxSec: Double = 2.5) {
        val period = (sr / hz).toInt().coerceAtLeast(2)
        val r = java.util.Random(seed.toLong())
        val buf = DoubleArray(period) { (r.nextDouble() * 2 - 1) * amp }
        var idx = 0
        val len = minOf(out.size - start, (sr * maxSec).toInt())
        val fade = (0.012 * sr).toInt()
        for (i in 0 until len) {
            val v = buf[idx]
            val nxt = buf[(idx + 1) % period]
            buf[idx] = decay * 0.5 * (v + nxt)
            val g = if (i > len - fade) (len - i).toDouble() / fade else 1.0
            out[start + i] += (v * g).toFloat()
            idx = (idx + 1) % period
        }
    }

    fun midiHz(m: Int) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)

    /** Strum: notes (midis) low->high for down, high->low for up, [spreadMs] total. */
    fun strum(out: FloatArray, sr: Int, start: Int, midis: List<Int>, down: Boolean, spreadMs: Double = 18.0, amp: Double = 0.25, seed: Int = 1,
              maxSec: Double = 2.5) {
        val order = if (down) midis else midis.reversed()
        order.forEachIndexed { k, m ->
            val off = (k * spreadMs / 1000.0 / (order.size - 1).coerceAtLeast(1) * sr).toInt()
            pluck(out, sr, start + off, midiHz(m), amp, seed * 31 + k, maxSec = maxSec)
        }
    }

    fun sine(sr: Int, n: Int, hz: Double, amp: Double = 0.5) = FloatArray(n) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    fun noise(out: FloatArray, amp: Double, seed: Int = 3) { val r = java.util.Random(seed.toLong()); for (i in out.indices) out[i] += ((r.nextDouble() * 2 - 1) * amp).toFloat() }

    fun decayEnv(n: Int, tau: Double, sr: Int) = DoubleArray(n) { exp(-it.toDouble() / sr / tau) }
}
