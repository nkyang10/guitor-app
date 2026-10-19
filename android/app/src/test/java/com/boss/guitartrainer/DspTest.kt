package com.boss.guitartrainer

import com.boss.guitartrainer.dsp.MpmDetector
import com.boss.guitartrainer.dsp.Notes
import com.boss.guitartrainer.dsp.OnsetDetector
import com.boss.guitartrainer.dsp.YinDetector
import com.boss.guitartrainer.dsp.ChordAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspTest {
    private val sr = 48000

    @Test fun mpmFindsOpenStrings() {
        for (hz in listOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val x = TestSynth.sine(sr, 4096, hz)
            val r = MpmDetector(2048, sr).detect(x, 0)
            val c = Notes.cents(r.hz, hz)
            assertTrue("MPM $hz -> ${r.hz} ($c cents)", abs(c) < 2.0)
            assertTrue(r.clarity > 0.9)
        }
    }

    @Test fun mpmOnPluckedString() {
        val x = FloatArray(sr)
        TestSynth.pluck(x, sr, 0, 110.0, 0.5, 4)
        val r = MpmDetector(2048, sr).detect(x, 4800)
        assertTrue("pluck 110 -> ${r.hz}", abs(Notes.cents(r.hz, 110.0)) < 15)
    }

    @Test fun yinFindsA() {
        val x = TestSynth.sine(sr, 4096, 220.0)
        val r = YinDetector(2048, sr).detect(x, 0)
        assertTrue(abs(Notes.cents(r.hz, 220.0)) < 3)
    }

    @Test fun onsetDetectorFindsStrums() {
        val x = FloatArray(sr * 6)
        TestSynth.noise(x, 0.002)
        val times = (0 until 10).map { 0.5 + it * 0.5 }
        times.forEachIndexed { i, t -> TestSynth.strum(x, sr, (t * sr).toInt(), listOf(48, 52, 55, 60, 64), i % 2 == 0, seed = i + 1) }
        val found = ArrayList<Long>()
        val det = OnsetDetector(sr) { s, _, weak -> if (!weak) found.add(s) }
        var pos = 0
        while (pos < x.size) { val n = minOf(480, x.size - pos); det.process(x.copyOfRange(pos, pos + n), n, pos.toLong()); pos += n }
        val ft = found.map { it.toDouble() / sr }
        var hit = 0
        for (t in times) if (ft.any { abs(it - t) < 0.03 }) hit++
        assertTrue("hits $hit/10 found=${ft.map { "%.3f".format(it) }}", hit >= 9)
        val extras = ft.count { f -> times.none { abs(it - f) < 0.06 } }
        assertTrue("extras $extras", extras <= 1)
    }

    @Test fun chordAnalyzerSeparatesChords() {
        val x = FloatArray(sr)
        val c = listOf(48, 52, 55, 60, 64)
        TestSynth.strum(x, sr, 0, c, true)
        val a = ChordAnalyzer(sr)
        a.analyse(x, (0.03 * sr).toInt())
        val simC = a.similarity(c)
        val simFsharp = a.similarity(listOf(54, 58, 61, 66))
        assertTrue("C=$simC F#=$simFsharp", simC > 0.7 && simC > simFsharp + 0.2)
        assertTrue(a.presence(52) > ChordAnalyzer.PRESENCE_DB)
    }

    @Test fun notesNames() {
        assertEquals("A4", Notes.name(69)); assertEquals("E2", Notes.name(40))
    }
}
