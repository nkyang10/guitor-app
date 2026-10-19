package com.boss.guitartrainer.audio

import android.content.Context

/** Acquire the shared capture engine for a screen (tuner, chord check, calibration) and attach processors. */
class MicSession(context: Context, private val source: AudioEngine.MicSource = AudioEngine.MicSource.AUTO) {
    val engine = AudioEngine.get(context)
    private val procs = ArrayList<AudioEngine.Processor>()
    var active = false; private set

    fun start(vararg p: AudioEngine.Processor): Boolean {
        if (active) return true
        if (!engine.acquire(source)) return false
        p.forEach { engine.add(it); procs.add(it) }
        active = true
        return true
    }

    fun stop() {
        if (!active) return
        procs.forEach { engine.remove(it) }
        procs.clear()
        engine.release()
        active = false
    }
}
