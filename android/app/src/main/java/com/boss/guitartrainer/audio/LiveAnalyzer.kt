package com.boss.guitartrainer.audio

import com.boss.guitartrainer.dsp.ChordAnalyzer
import com.boss.guitartrainer.dsp.Direction
import com.boss.guitartrainer.dsp.DirectionFeatures
import com.boss.guitartrainer.dsp.DirectionModel
import com.boss.guitartrainer.dsp.OnsetDetector

/**
 * Live analysis chain on the capture thread:
 * onset detector -> (after ~200 ms of audio) direction features + chord/note spectrum.
 * The sink gets onOnset immediately (timing feedback) and onAnalysis later (chord / direction / notes).
 * The [Analysis.chord] analyser is only valid during the callback.
 */
class LiveAnalyzer(private val engine: AudioEngine, private val sink: Sink) : AudioEngine.Processor {
    interface Sink {
        fun onOnset(id: Long, sample: Long, ratio: Double, weak: Boolean)
        fun onAnalysis(id: Long, sample: Long, a: Analysis)
    }

    class Analysis(val probDown: Double?, val features: DoubleArray?, val chord: ChordAnalyzer, val weak: Boolean)

    private data class Pending(val id: Long, val sample: Long, val weak: Boolean)

    private val sr = AudioEngine.SR
    @Volatile var directionModel: DirectionModel? = null
    @Volatile var enabled = true
    private var nextId = 1L
    private val pending = ArrayList<Pending>()
    private val chord = ChordAnalyzer(sr, 8192)
    private val dir: DirectionFeatures? = Direction.spec?.let { DirectionFeatures(it, sr) }
    private val chordBuf = FloatArray(8192)
    private val dirBefore = dir?.samplesBefore ?: 0
    private val dirAfter = dir?.samplesAfter ?: 0
    private val dirBuf = FloatArray(dirBefore + dirAfter + 64)
    private val chordDelay = (ChordAnalyzer.DELAY_SEC * sr).toLong()

    val detector = OnsetDetector(sr) { s, ratio, weak ->
        if (!enabled) return@OnsetDetector
        val id = nextId++
        pending.add(Pending(id, s, weak))
        sink.onOnset(id, s, ratio, weak)
    }

    override fun onAudio(buf: FloatArray, n: Int, startSample: Long) {
        detector.process(buf, n, startSample)
        if (pending.isEmpty()) return
        val now = startSample + n
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val need = maxOf(p.sample + chordDelay + chord.nfft, p.sample + dirAfter + 16)
            if (now < need) continue
            it.remove()
            var f: DoubleArray? = null
            if (dir != null) {
                engine.ring.read(p.sample - dirBefore, dirBuf)
                f = dir.compute(dirBuf, dirBefore)
            }
            val model = directionModel ?: Direction.generic
            val pd = if (f != null && model != null) model.probDown(f) else null
            engine.ring.read(p.sample + chordDelay, chordBuf)
            chord.analyse(chordBuf, 0)
            sink.onAnalysis(p.id, p.sample, Analysis(pd, f, chord, p.weak))
        }
    }

    fun reset() { pending.clear(); detector.reset() }
}
