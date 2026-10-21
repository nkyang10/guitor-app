package com.boss.guitartrainer

import com.boss.guitartrainer.analysis.Pcm
import com.boss.guitartrainer.analysis.SongAnalyzer
import com.boss.guitartrainer.dsp.FloatBuf
import com.boss.guitartrainer.dsp.StreamResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Runs in a separate JVM with a small heap (see [DecodeMemoryTest]): feeds a long 16-bit PCM song through the same
 * pipeline as AudioDecoder (decoder-sized blocks → Pcm.toMono → StreamResampler → FloatBuf) and analyses it.
 * Args: seconds sampleRate channels. Prints "OK …" with the peak heap use, exits 1 on OutOfMemoryError.
 */
object MemoryHarness {
    @JvmStatic fun main(args: Array<String>) {
        val seconds = args.getOrNull(0)?.toDouble() ?: 600.0
        val sr = args.getOrNull(1)?.toInt() ?: 48000
        val ch = args.getOrNull(2)?.toInt() ?: 2
        val rt = Runtime.getRuntime()
        val peakRef = AtomicLong(0)
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) { peakRef.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf); Thread.sleep(2) }
        }.apply { isDaemon = true; start() }
        try {
            val t0 = System.nanoTime()
            val out = FloatBuf(((seconds + 1) * SongAnalyzer.SR).toInt())
            val rs = StreamResampler(sr, SongAnalyzer.SR, out)
            val frames = 4096
            val block = ByteBuffer.allocateDirect(frames * ch * 2).order(ByteOrder.nativeOrder())
            val mono = FloatArray(frames)
            val total = (seconds * sr).toLong()
            // C (C E G) and G (G B D) alternating every 2 bars at 120 BPM, with a pluck-like decay on every beat
            val chords = arrayOf(doubleArrayOf(130.81, 164.81, 196.0, 261.63), doubleArrayOf(98.0, 123.47, 146.83, 196.0))
            var i = 0L
            while (i < total) {
                val n = minOf(frames.toLong(), total - i).toInt()
                block.clear()
                for (f in 0 until n) {
                    val t = (i + f).toDouble() / sr
                    val beat = t * 2.0
                    val env = Math.exp(-(beat - Math.floor(beat)) * 3.0)
                    val c = chords[((t / 4.0).toInt()) % 2]
                    var v = 0.0
                    for (hz in c) v += sin(2 * PI * hz * t)
                    val s = (v * 0.18 * env * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    repeat(ch) { block.putShort(s) }
                }
                block.flip()
                val m = Pcm.toMono(block, Pcm.ENC_16, ch, mono)
                rs.push(mono, m)
                i += n
            }
            rs.finish()
            val t1 = System.nanoTime()
            val chart = SongAnalyzer.analyze(out.data, "harness", null, 1, out.size)
            val t2 = System.nanoTime()
            running.set(false); sampler.join(50)
            val peak = peakRef.get()
            val mb = 1024 * 1024
            println("OK seconds=%.0f samples=%d decode=%.1fs analyse=%.1fs bpm=%.1f chords=%d peakHeap=%dMB max=%dMB".format(
                seconds, out.size, (t1 - t0) / 1e9, (t2 - t1) / 1e9, chart.bpm, chart.chords.size, peak / mb, rt.maxMemory() / mb))
            exitProcess(0)
        } catch (e: OutOfMemoryError) {
            println("OOM peakHeap=${peakRef.get() / (1024 * 1024)}MB: $e")
            exitProcess(1)
        }
    }
}
