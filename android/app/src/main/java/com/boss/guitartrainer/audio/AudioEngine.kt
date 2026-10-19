package com.boss.guitartrainer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Microphone capture (AudioRecord, 48 kHz mono float) on a high-priority thread.
 * Uses UNPROCESSED when the device supports it (no AGC / noise suppression), else VOICE_RECOGNITION.
 * Optional AEC mode (VOICE_COMMUNICATION + AcousticEchoCanceler) for playing along with the speaker.
 * Every captured sample has an absolute index; [sampleToNanos] maps it to System.nanoTime() using
 * AudioRecord timestamps (fallback: read-time estimate).
 */
class AudioEngine(private val context: Context) {
    companion object {
        const val SR = 48000
        private const val TAG = "AudioEngine"
        @Volatile var instance: AudioEngine? = null
        fun get(context: Context): AudioEngine = instance ?: synchronized(this) {
            instance ?: AudioEngine(context.applicationContext).also { instance = it }
        }
    }

    fun interface Processor { fun onAudio(buf: FloatArray, n: Int, startSample: Long) }

    enum class MicSource(val label: String) { AUTO("自動（UNPROCESSED 優先）"), VOICE_RECOGNITION("VOICE_RECOGNITION"), MIC("MIC（預設）"), AEC("回音消除（VOICE_COMMUNICATION + AEC）") }

    private val processors = CopyOnWriteArrayList<Processor>()
    val ring = AudioRing(SR * 20)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var users = 0
    @Volatile var sourceInUse = ""
        private set
    @Volatile var aecActive = false
        private set

    // capture-clock mapping
    @Volatile private var tsFrame = 0L
    @Volatile private var tsNanos = 0L
    @Volatile var timestampsSupported = false
        private set

    fun add(p: Processor) { processors.add(p) }
    fun remove(p: Processor) { processors.remove(p) }

    val captured get() = ring.written

    fun sampleToNanos(sample: Long): Long = tsNanos + ((sample - tsFrame) * 1_000_000_000L) / SR
    fun nanosToSample(nanos: Long): Long = tsFrame + ((nanos - tsNanos) * SR) / 1_000_000_000L

    @Synchronized
    fun acquire(source: MicSource = MicSource.AUTO): Boolean {
        users++
        if (running) return true
        val ok = start(source)
        if (!ok) users = (users - 1).coerceAtLeast(0)
        return ok
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    @Synchronized
    fun restart(source: MicSource) {
        if (running) { stop(); start(source) }
    }

    @SuppressLint("MissingPermission")
    private fun start(source: MicSource): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedOk = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = when (source) {
            MicSource.AUTO -> if (unprocessedOk) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.MIC -> MediaRecorder.AudioSource.MIC
            MicSource.AEC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        sourceInUse = when (src) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "MIC"
        }
        val fmt = AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord.Builder().setAudioSource(src).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 4096 * 4)).build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        var aec: AcousticEchoCanceler? = null
        aecActive = false
        if (source == MicSource.AEC && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true; aecActive = it.enabled }
        }
        if (source != MicSource.AEC) {
            // make sure voice processing is off where the platform allows it
            try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = false; it.release() } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = false; it.release() } } catch (_: Exception) {}
        }
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(256)
            val ts = AudioTimestamp()
            var reads = 0
            rec.startRecording()
            val base = ring.written   // absolute index continues across restarts
            var framesRead = 0L
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (n < 0) Thread.sleep(5); continue }
                val start = ring.written
                ring.write(buf, n)
                framesRead += n
                if (reads % 32 == 0) {
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        tsFrame = base + ts.framePosition; tsNanos = ts.nanoTime; timestampsSupported = true
                    } else if (!timestampsSupported) {
                        tsFrame = start + n; tsNanos = System.nanoTime()
                    }
                }
                reads++
                for (p in processors) {
                    try { p.onAudio(buf, n, start) } catch (e: Exception) { Log.e(TAG, "processor", e) }
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            aec?.release()
            rec.release()
        }, "mic-capture").also { it.start() }
        return true
    }

    private fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
