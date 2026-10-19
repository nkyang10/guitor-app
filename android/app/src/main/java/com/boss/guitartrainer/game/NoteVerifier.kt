package com.boss.guitartrainer.game

import android.content.Context
import android.util.Log
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.dsp.BasicPitch
import com.boss.guitartrainer.dsp.Resampler
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Background "AI check" for fingerstyle: every second, runs Basic Pitch on the last ~2 s of microphone audio
 * and confirms / rejects the expected notes of events whose onset lies in the reliable part of the window.
 */
class NoteVerifier(context: Context, private val engine: AudioEngine, private val judge: Judge) : AutoCloseable {
    private val model: BasicPitch? = try { BasicPitch(context) } catch (e: Throwable) { Log.w("NoteVerifier", "Basic Pitch unavailable", e); null }
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val done = HashSet<Int>()
    private val sr = AudioEngine.SR
    private val winIn = (BasicPitch.WIN.toDouble() / BasicPitch.SR * sr).toInt() + 64
    private val buf = FloatArray(winIn)
    @Volatile var checked = 0; private set
    @Volatile var confirmed = 0; private set
    val available get() = model != null

    fun start() {
        if (model == null) return
        exec.scheduleWithFixedDelay({ try { tick() } catch (e: Throwable) { Log.e("NoteVerifier", "tick", e) } }, 1500, 1000, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val m = model ?: return
        val end = engine.captured
        val start = end - winIn
        if (start < 0) return
        engine.ring.read(start, buf)
        val x22 = Resampler.resample(buf, sr, BasicPitch.SR)
        val res = m.run(x22, start.toDouble() / sr)
        val results = judge.results
        for (i in results.indices) {
            if (i in done) continue
            val r = results[i]
            if (r.onsetSample < 0 || judge.events[i].type != "note") continue
            val t = r.onsetSample.toDouble() / sr
            if (!res.reliable(t)) continue
            val acts = judge.events[i].midis.map { res.activation(it, t) }
            val okCount = acts.count { it >= BasicPitch.VERIFY_THRESHOLD }
            val ok = okCount == acts.size || (acts.size >= 3 && okCount >= acts.size - 1)
            judge.onAiNotes(i, ok)
            done += i; checked++; if (ok) confirmed++
        }
    }

    override fun close() {
        exec.shutdownNow()
        try { exec.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        model?.close()
    }
}
