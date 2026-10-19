package com.boss.guitartrainer.game

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.audio.ClickTrack
import com.boss.guitartrainer.audio.LiveAnalyzer
import com.boss.guitartrainer.data.Calibration
import com.boss.guitartrainer.music.Chart
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/**
 * One play-through: microphone analysis -> judge, metronome (practice) or ExoPlayer (song mode),
 * clock mapping with latency compensation, optional Basic Pitch verification.
 */
class GameSession(
    private val context: Context,
    val chart: Chart,
    val settings: DifficultySettings,
    calibration: Calibration,
    val mode: String,                      // strum | finger | song
    private val songAudio: File? = null,
    extraOffsetMs: Int = 0,
    private val useAi: Boolean = true,
    private val micSource: AudioEngine.MicSource = AudioEngine.MicSource.AUTO,
    private val loop: ClosedFloatingPointRange<Double>? = null,
    private val metronomeVolume: Float = 0.8f,
) : LiveAnalyzer.Sink {
    enum class State { IDLE, COUNT_IN, PLAYING, PAUSED, FINISHED }

    private val sr = AudioEngine.SR
    val engine = AudioEngine.get(context)
    val tempo = settings.tempoFactor
    val judge = Judge(chart, settings, tempo)
    private val analyzer = LiveAnalyzer(engine, this).also { it.directionModel = calibration.direction }
    private val click = ClickTrack()
    var player: ExoPlayer? = null; private set
    private var verifier: NoteVerifier? = null
    private val latencyNs = (((calibration.latencyMs ?: 0.0) + extraOffsetMs) * 1e6).toLong()
    @Volatile var state = State.IDLE; private set
    private var startFrame = 0L
    private var startNanos = 0L
    val countInBeats = if (mode == "song" || settings.waitMode) 0 else settings.countInBars * chart.beatsPerBar
    private val spb = 60.0 / chart.bpm
    @Volatile private var posRefSec = 0.0
    @Volatile private var nanosRef = 0L
    @Volatile private var playing = false
    private var waitTime = 0.0
    private var lastWaitNanos = 0L
    private var pausedAt = 0.0
    @Volatile var loops = 0; private set
    val aiChecked get() = verifier?.checked ?: 0
    val aiConfirmed get() = verifier?.confirmed ?: 0
    val aiAvailable get() = verifier?.available == true
    val inputLevel get() = analyzer.detector.level
    var wallStart = 0L; private set

    fun start(): Boolean {
        if (!engine.acquire(micSource)) return false
        analyzer.reset()
        engine.add(analyzer)
        wallStart = System.currentTimeMillis()
        if (mode == "finger" && useAi) verifier = NoteVerifier(context, engine, judge).also { it.start() }
        val from = loop?.start ?: 0.0
        if (mode == "song") {
            val p = ExoPlayer.Builder(context).build()
            songAudio?.let { p.setMediaItem(MediaItem.fromUri(Uri.fromFile(it))) }
            p.playbackParameters = PlaybackParameters(tempo.toFloat())
            p.prepare()
            if (from > 0) p.seekTo(((from - chart.audioOffset) * 1000).toLong().coerceAtLeast(0))
            p.play()
            player = p
            playing = true
            state = State.PLAYING
        } else if (settings.waitMode) {
            waitTime = from - 0.5
            lastWaitNanos = System.nanoTime()
            state = State.PLAYING
        } else {
            click.volume = metronomeVolume
            click.start()
            scheduleClicks(from)
            state = State.COUNT_IN
        }
        return true
    }

    private fun scheduleClicks(from: Double) {
        click.clearScheduled()
        val lead = click.futureFrame(0.25)
        val countInFrames = (countInBeats * spb / tempo * sr).toLong()
        startFrame = lead + countInFrames - (from / tempo * sr).toLong()
        val bpb = chart.beatsPerBar
        for (b in -countInBeats until 0) {
            val t = from + b * spb
            click.schedule(startFrame + (t / tempo * sr).toLong(), if ((b + countInBeats) % bpb == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK)
        }
        if (settings.metronome) {
            val end = loop?.endInclusive ?: (chart.duration + spb)
            val firstBeat = ceil(from / spb - 1e-6).toInt()
            var b = firstBeat
            while (b * spb <= end) {
                click.schedule(startFrame + (b * spb / tempo * sr).toLong(), if (b % bpb == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK)
                b++
            }
        }
        startNanos = click.frameToNanos(startFrame)
    }

    /** Chart time (s) for a capture-clock instant (already latency compensated by the caller). */
    fun chartTimeAt(nanos: Long): Double = when {
        mode == "song" -> posRefSec + (if (playing) (nanos - nanosRef) / 1e9 * tempo else 0.0)
        settings.waitMode -> synchronized(this) { waitTime }
        else -> (click.nanosToFrame(nanos) - startFrame).toDouble() / sr * tempo
    }

    override fun onOnset(id: Long, sample: Long, ratio: Double, weak: Boolean) {
        if (state != State.PLAYING && state != State.COUNT_IN) return
        val nanos = engine.sampleToNanos(sample) - latencyNs
        val t = chartTimeAt(nanos)
        if (state == State.COUNT_IN && t < -0.15) return
        judge.onOnset(id, sample, t, ratio, weak)
    }

    override fun onAnalysis(id: Long, sample: Long, a: LiveAnalyzer.Analysis) = judge.onAnalysis(id, a)

    /** Call every UI frame (main thread). Returns current chart time. */
    fun tick(now: Long = System.nanoTime()): Double {
        val p = player
        if (p != null) {
            posRefSec = p.currentPosition / 1000.0 + chart.audioOffset
            nanosRef = now
            playing = p.isPlaying
        }
        val t = when {
            state == State.PAUSED -> pausedAt
            mode == "song" -> posRefSec
            settings.waitMode -> synchronized(this) {
                val dt = (now - lastWaitNanos) / 1e9 * tempo
                lastWaitNanos = now
                val open = judge.firstOpen()
                val hold = judge.events.getOrNull(open)?.t ?: Double.MAX_VALUE
                waitTime = min(waitTime + dt, hold)
                waitTime
            }
            else -> chartTimeAt(now)
        }
        if (state == State.COUNT_IN && t >= (loop?.start ?: 0.0)) state = State.PLAYING
        if (state == State.PLAYING) {
            judge.update(t)
            val lp = loop
            if (lp != null && t > lp.endInclusive + settings.goodMs / 1000.0) {
                loops++
                restartLoop(lp)
            } else if (lp == null && judge.isFinished(t)) {
                state = State.FINISHED
            }
            if (mode == "song" && p != null && p.playbackState == androidx.media3.common.Player.STATE_ENDED) state = State.FINISHED
        }
        return t
    }

    private fun restartLoop(lp: ClosedFloatingPointRange<Double>) {
        onLoopEnd?.invoke(loops)
        judge.resetRange(lp.start, lp.endInclusive + 1e-3)
        when {
            mode == "song" -> player?.seekTo(((lp.start - chart.audioOffset) * 1000).toLong().coerceAtLeast(0))
            settings.waitMode -> synchronized(this) { waitTime = lp.start - 0.5 }
            else -> { scheduleClicks(lp.start); state = State.COUNT_IN }
        }
    }

    var onLoopEnd: ((Int) -> Unit)? = null

    fun pause() {
        if (state != State.PLAYING && state != State.COUNT_IN) return
        pausedAt = tick()
        state = State.PAUSED
        player?.pause()
        click.clearScheduled()
        analyzer.enabled = false
    }

    fun resume() {
        if (state != State.PAUSED) return
        analyzer.enabled = true
        val from = maxOf(0.0, pausedAt - 2 * spb)
        judge.resetRange(from, Double.MAX_VALUE)
        when {
            mode == "song" -> { player?.seekTo(((from - chart.audioOffset) * 1000).toLong().coerceAtLeast(0)); player?.play(); state = State.PLAYING }
            settings.waitMode -> { synchronized(this) { waitTime = from; lastWaitNanos = System.nanoTime() }; state = State.PLAYING }
            else -> { scheduleClicks(from); state = State.COUNT_IN }
        }
    }

    fun stop() {
        if (state == State.IDLE) return
        engine.remove(analyzer)
        engine.release()
        click.stop()
        player?.release(); player = null
        verifier?.close(); verifier = null
        if (state != State.FINISHED) state = State.FINISHED
    }

    fun elapsedSec() = (System.currentTimeMillis() - wallStart) / 1000.0
}
