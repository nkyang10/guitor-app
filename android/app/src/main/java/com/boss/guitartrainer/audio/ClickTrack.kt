package com.boss.guitartrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Low-latency synthesised output (metronome clicks, calibration pings, cue tones).
 * Sounds are scheduled at absolute output frame positions; [frameToNanos] maps an output frame to the
 * time it reaches the speaker (AudioTrack timestamps, fallback = buffer estimate).
 */
class ClickTrack {
    companion object { const val SR = AudioEngine.SR }

    enum class Sound { CLICK, ACCENT, PING, CUE_HIGH, CUE_LOW }

    private data class Item(val frame: Long, val sound: Sound, val gain: Float) : Comparable<Item> {
        override fun compareTo(other: Item) = frame.compareTo(other.frame)
    }

    private val queue = PriorityQueue<Item>()
    private val active = ArrayList<Pair<FloatArray, IntArray>>()   // sample buffer + [pos]
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile var framesWritten = 0L
        private set
    @Volatile private var tsFrame = 0L
    @Volatile private var tsNanos = 0L
    @Volatile var hasTimestamp = false
        private set
    @Volatile var volume = 0.8f

    // Metronome clicks are narrow-band tones ABOVE the onset detector's 80 Hz-6 kHz band, so the
    // phone speaker's clicks are not mistaken for strums when no headphones are used.
    private val sounds: Map<Sound, FloatArray> = mapOf(
        Sound.CLICK to hannTone(7500.0, 0.014, 0.55),
        Sound.ACCENT to hannTone(7500.0, 0.022, 0.95),
        Sound.PING to ping(),
        Sound.CUE_HIGH to tone(880.0, 0.12, 0.04, 0.5),
        Sound.CUE_LOW to tone(440.0, 0.12, 0.04, 0.5),
    )

    private fun tone(f: Double, dur: Double, tau: Double, amp: Double) = FloatArray((dur * SR).toInt()) {
        val t = it.toDouble() / SR
        (amp * sin(2 * PI * f * t) * exp(-t / tau)).toFloat()
    }

    private fun hannTone(f: Double, dur: Double, amp: Double): FloatArray {
        val n = (dur * SR).toInt()
        return FloatArray(n) {
            val t = it.toDouble() / SR
            val w = 0.5 - 0.5 * kotlin.math.cos(2 * PI * it / (n - 1))
            (amp * w * sin(2 * PI * f * t)).toFloat()
        }
    }

    /** Broadband ping for loopback latency measurement (sharp onset, easy to detect). */
    private fun ping(): FloatArray {
        val r = java.util.Random(7)
        return FloatArray((0.03 * SR).toInt()) {
            val t = it.toDouble() / SR
            ((0.6 * sin(2 * PI * 2000 * t) + 0.4 * (r.nextFloat() * 2 - 1)) * exp(-t / 0.006)).toFloat()
        }
    }

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(minBuf, 1024 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        framesWritten = 0; hasTimestamp = false
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val block = FloatArray(192)
            val ts = AudioTimestamp()
            var n = 0
            t.play()
            while (running) {
                render(block)
                t.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                framesWritten += block.size
                if (n++ % 16 == 0 && t.getTimestamp(ts)) { tsFrame = ts.framePosition; tsNanos = ts.nanoTime; hasTimestamp = true }
            }
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }, "click-track").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        synchronized(queue) { queue.clear() }
        active.clear()
    }

    /** Estimated presentation time of output frame [frame]. */
    fun frameToNanos(frame: Long): Long {
        if (hasTimestamp) return tsNanos + ((frame - tsFrame) * 1_000_000_000L) / SR
        val bufferFrames = (track?.bufferSizeInFrames ?: 1024).toLong()
        return System.nanoTime() + ((frame - framesWritten + bufferFrames) * 1_000_000_000L) / SR
    }

    fun nanosToFrame(nanos: Long): Long {
        if (hasTimestamp) return tsFrame + ((nanos - tsNanos) * SR) / 1_000_000_000L
        val bufferFrames = (track?.bufferSizeInFrames ?: 1024).toLong()
        return framesWritten - bufferFrames + ((nanos - System.nanoTime()) * SR) / 1_000_000_000L
    }

    /** Frame index safely in the future (writes are ahead of playback by the buffer). */
    fun futureFrame(secondsAhead: Double): Long = framesWritten + (track?.bufferSizeInFrames ?: 1024) + (secondsAhead * SR).toLong()

    fun schedule(frame: Long, sound: Sound, gain: Float = 1f) = synchronized(queue) { queue.add(Item(frame, sound, gain)) }

    fun clearScheduled() = synchronized(queue) { queue.clear() }

    private fun render(block: FloatArray) {
        block.fill(0f)
        val start = framesWritten
        val end = start + block.size
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.peek()!!.frame < end) {
                val it = queue.poll()!!
                if (it.frame < start - SR) continue  // stale
                val snd = sounds[it.sound]!!
                val buf = FloatArray(snd.size) { i -> snd[i] * it.gain }
                active.add(buf to intArrayOf(-(maxOf(0L, it.frame - start)).toInt()))
            }
        }
        val iter = active.iterator()
        while (iter.hasNext()) {
            val (buf, posA) = iter.next()
            var pos = posA[0]
            for (i in block.indices) {
                if (pos >= 0 && pos < buf.size) block[i] += buf[pos] * volume
                pos++
            }
            posA[0] = pos
            if (pos >= buf.size) iter.remove()
        }
    }
}
