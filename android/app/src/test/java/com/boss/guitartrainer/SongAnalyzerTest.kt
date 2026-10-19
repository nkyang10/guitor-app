package com.boss.guitartrainer

import com.boss.guitartrainer.analysis.SongAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Synthetic songs: C G Am F one chord per bar, D-DU-UDU strums at 72 / 100 / 120 BPM -> tempo + chord accuracy. */
class SongAnalyzerTest {
    @Test fun tempoAndChords100() = song(100.0)
    @Test fun tempoAndChords72() = song(72.0)
    @Test fun tempoAndChords120() = song(120.0)

    private fun song(bpm: Double) {
        val sr = SongAnalyzer.SR
        val spb = 60.0 / bpm
        val prog = listOf("C" to listOf(48, 52, 55, 60, 64), "G" to listOf(43, 47, 50, 55, 59, 67), "Am" to listOf(45, 52, 57, 60, 64), "F" to listOf(41, 48, 53, 57, 60, 65))
        val bars = 24
        val x = FloatArray(((bars * 4 + 2) * spb * sr).toInt())
        TestSynth.noise(x, 0.001)
        val steps = "D-DU-UDU"
        for (b in 0 until bars) {
            val (_, notes) = prog[b % 4]
            val hits = steps.withIndex().filter { it.value != '-' }.map { it.index }
            for ((hi, k) in hits.withIndex()) {
                val c = steps[k]
                val t = 0.5 + (b * 4 + k * 0.5) * spb
                // notes ring until the next strum (the fretting hand stops old notes when changing chords)
                val nextK = if (hi + 1 < hits.size) hits[hi + 1] else 8
                val ring = (nextK - k) * 0.5 * spb + 0.04
                TestSynth.strum(x, sr, (t * sr).toInt(), notes, c == 'D', amp = if (k == 0) 0.3 else 0.18, seed = b * 8 + k, maxSec = ring)
            }
            // bass note on beat 1 (rings for 2 beats)
            TestSynth.pluck(x, sr, ((0.5 + b * 4 * spb) * sr).toInt(), TestSynth.midiHz(notes[0]), 0.25, b, maxSec = 2 * spb)
        }
        val chart = SongAnalyzer.analyze(x, "synthetic", null, 3)
        val dbg = SongAnalyzer.lastDebug!!
        println("BPM[$bpm] ${chart.bpm} beats0=${dbg.beats.take(6)} n=${dbg.beats.size}")
        println("PERBEAT " + dbg.chordsPerBeat.joinToString(" "))
        println("SPANS " + chart.chords.joinToString(" ") { "%s@%.2f".format(it.original, it.start) })
        // exact tempo, or a clean half/double (never the dotted-quarter 3/2 or 2/3 error)
        val r = chart.bpm / bpm
        assertTrue("bpm ${chart.bpm} for $bpm", abs(r - 1) < 0.04 || abs(r - 2) < 0.08 || abs(r - 0.5) < 0.02)
        // chord accuracy at bar centres (sounding chords = original)
        var ok = 0; var n = 0
        for (b in 1 until bars - 1) {
            val t = 0.5 + (b * 4 + 2) * spb
            val span = chart.chords.firstOrNull { t >= it.start && t < it.end } ?: continue
            n++
            if (span.original == prog[b % 4].first) ok++
        }
        assertTrue("chord acc $ok/$n  ${chart.chords.take(8).map { it.original }}", ok >= 0.8 * (bars - 2))
        assertTrue("key ${chart.key}", chart.key == "C" || chart.key == "Am")
        assertTrue(chart.sections.isNotEmpty())
        assertTrue("capo ${chart.capo}", chart.capo == 0)
    }
}
