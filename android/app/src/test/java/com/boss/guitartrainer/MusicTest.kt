package com.boss.guitartrainer

import com.boss.guitartrainer.music.Capo
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.ChordNames
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.PatternLibrary
import com.boss.guitartrainer.music.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MusicTest {
    private val lib = PatternLibrary.parse(File("src/main/assets/patterns.json").readText())

    @Test fun chordNames() {
        assertEquals("C", ChordNames.normalize("C:maj"))
        assertEquals("Am7", ChordNames.normalize("A:min7"))
        assertEquals("Bb", ChordNames.normalize("A#"))
        assertEquals("Am", ChordNames.triad("Am7"))
        assertEquals("Fmaj7", ChordNames.simplify("F", 1))
        assertEquals("G", ChordNames.transpose("Bb", -3))
        assertEquals("Em", ChordNames.transpose("Gm", -3))
        assertEquals("N", ChordNames.normalize("N"))
    }

    @Test fun libraryCoversCommonChords() {
        for (c in listOf("C", "D", "E", "G", "A", "Am", "Em", "Dm", "F", "Bm", "E7", "A7", "D7", "G7", "B7"))
            assertNotNull("missing $c", ChordLibrary.forSymbol(c))
        assertTrue(lib.strum.size >= 13 && lib.finger.size >= 10)
    }

    @Test fun capoMakesFlatKeysEasy() {
        val ch = Capo.suggest(mapOf("Bb" to 10.0, "Eb" to 10.0, "F" to 10.0, "Gm" to 10.0), level = 1)
        assertEquals(3, ch.capo)
        assertEquals("G", ch.shapes["Bb"])
        val none = Capo.suggest(mapOf("C" to 10.0, "G" to 10.0, "Am" to 10.0, "F" to 5.0), level = 1)
        assertEquals(0, none.capo)
    }

    @Test fun strumPracticeChart() {
        val p = lib.strum("s_folk")!!
        val c = ChartBuilder.strumPractice("t", listOf("C", "Am", "F", "G"), p, 80.0, 4, simplifyLevel = 1)
        val perBar = p.steps.count { it == 'D' || it == 'U' || it == 'x' }
        assertEquals(perBar * 4, c.events.size)
        assertEquals("Fmaj7", c.chords[2].label)
        assertEquals(0.0, c.events[0].t, 1e-9)
        assertEquals("D", c.events[0].dir)
    }

    @Test fun fingerPracticeChart() {
        val p = lib.finger("f_pima")!!
        val c = ChartBuilder.fingerPractice("t", listOf("C", "G"), p, 70.0, 2)
        assertTrue(c.events.isNotEmpty())
        // C chord: thumb on 5th string (index 1), fret 3
        val first = c.events[0]
        assertEquals(listOf(1), first.strings); assertEquals(listOf(3), first.frets)
    }

    @Test fun songChartWithCapo() {
        val beats = (0 until 32).map { it * 0.6 }
        val base = Chart(title = "s", bpm = 100.0, beats = beats, downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 },
            chords = listOf(ChordSpan(0.0, 9.6, "G", "Bb"), ChordSpan(9.6, 19.2, "C", "Eb")),
            sections = listOf(Section(0.0, 19.2, "A", "s_alt")), capo = 3, kind = "song")
        val c = ChartBuilder.song(base, lib, "s_alt", 1)
        assertEquals("G", c.chords[0].label)
        assertEquals("C", c.chords[1].label)
        assertEquals(8 * 8, c.events.size)
        // sounding pitches include the capo
        assertEquals(46, c.events[0].midis.first())   // G shape + capo 3 = Bb2
    }

    @Test fun chartJsonRoundTrip() {
        val c = ChartBuilder.strumPractice("t", listOf("G", "D"), lib.strum("s_alt")!!, 90.0, 2)
        val back = Chart.parse(c.toJson())
        assertEquals(c.events.size, back.events.size)
        assertEquals(c.bpm, back.bpm, 1e-9)
    }
}
