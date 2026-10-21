package com.boss.guitartrainer

import com.boss.guitartrainer.analysis.OnsetEnvelope
import com.boss.guitartrainer.analysis.RhythmAnalyzer
import com.boss.guitartrainer.analysis.TapSync
import com.boss.guitartrainer.music.BeatGrid
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.GridOps
import com.boss.guitartrainer.music.PatternLevel
import com.boss.guitartrainer.music.PatternLibrary
import com.boss.guitartrainer.music.PatternSuggest
import com.boss.guitartrainer.music.Section
import com.boss.guitartrainer.music.SongPrefs
import com.boss.guitartrainer.music.SongSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** 歌曲 tab tools: key change, beat grid fixes, onset envelope timing, beat tracking, tap sync, pattern suggestions. */
class SongToolsTest {
    private val lib = PatternLibrary.parse(File("src/main/assets/patterns.json").readText())

    // ------------------------------------------------------------------ synthetic material

    private class Song(val x: FloatArray, val beats: List<Double>, val downs: List<Double>)

    private fun kick(x: FloatArray, sr: Int, t: Double) {
        val s = (t * sr).toInt(); var ph = 0.0
        for (i in 0 until (0.25 * sr).toInt()) {
            if (s + i >= x.size) break
            val tt = i.toDouble() / sr
            ph += 2 * PI * (50 + 70 * exp(-tt / 0.03)) / sr
            x[s + i] += (0.45 * sin(ph) * exp(-tt / 0.12)).toFloat()
        }
    }

    private fun snare(x: FloatArray, sr: Int, t: Double, seed: Int) {
        val r = java.util.Random(seed.toLong()); val s = (t * sr).toInt()
        for (i in 0 until (0.18 * sr).toInt()) {
            if (s + i >= x.size) break
            val tt = i.toDouble() / sr
            x[s + i] += (((r.nextDouble() * 2 - 1) * 0.18 + 0.1 * sin(2 * PI * 190 * tt)) * exp(-tt / 0.05)).toFloat()
        }
    }

    /** Strummed C G Am F (one chord per bar) on [steps] + optional kick (1, 3) / snare (2, 4). */
    private fun song(sr: Int, bpm: Double, bars: Int, steps: String, sub: Int, bpb: Int = 4, start: Double = 0.62, drums: Boolean = true): Song {
        require(steps.length == bpb * sub)
        val spb = 60.0 / bpm
        val x = FloatArray(((start + (bars * bpb + 2) * spb) * sr).toInt())
        TestSynth.noise(x, 0.0008)
        val chords = listOf(listOf(48, 52, 55, 60, 64), listOf(43, 47, 50, 55, 59, 67), listOf(45, 52, 57, 60, 64), listOf(41, 48, 53, 57, 60, 65))
        val hits = steps.indices.filter { steps[it] != '-' }
        for (b in 0 until bars) {
            for ((hi, k) in hits.withIndex()) {
                val t = start + (b * bpb + k.toDouble() / sub) * spb
                val nextK = if (hi + 1 < hits.size) hits[hi + 1] else steps.length + hits.first()
                val ring = (nextK - k).toDouble() / sub * spb + 0.03
                val down = steps[k] == 'D'
                TestSynth.strum(x, sr, (t * sr).toInt(), chords[b % 4], down, spreadMs = if (down) 14.0 else 9.0,
                    amp = if (down) 0.16 else 0.09, seed = b * 17 + k, maxSec = ring)
            }
            if (drums) for (j in 0 until bpb) {
                val t = start + (b * bpb + j) * spb
                if (j % 2 == 0) kick(x, sr, t) else snare(x, sr, t, b * 7 + j)
            }
        }
        val beats = (0..bars * bpb).map { start + it * spb }
        return Song(x, beats, beats.filterIndexed { i, _ -> i % bpb == 0 })
    }

    private fun env(s: Song, sr: Int = 44100): OnsetEnvelope = RhythmAnalyzer.envelope(s.x, sr)

    private fun hitRate(truth: List<Double>, got: List<Double>, tol: Double = 0.025): Double =
        truth.drop(1).dropLast(1).count { b -> got.minOf { abs(it - b) } < tol }.toDouble() / (truth.size - 2)

    private fun meanErr(truth: List<Double>, got: List<Double>, tol: Double = 0.025): Double =
        truth.drop(1).dropLast(1).map { b -> got.minOf { abs(it - b) } }.filter { it < tol }.average()

    // ------------------------------------------------------------------ key / chords

    private fun chart4(): Chart {
        val beats = (0 until 33).map { 0.5 + it * 0.5 }
        val chords = listOf("G", "Em", "C", "D", "G", "Em", "C", "D").mapIndexed { i, c -> ChordSpan(0.5 + i * 2.0, 0.5 + (i + 1) * 2.0, c) }
        return Chart(title = "t", bpm = 120.0, beatsPerBar = 4, beats = beats, downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 },
            chords = chords, sections = listOf(Section(0.5, 8.5, "主歌", "s_folk"), Section(8.5, 16.5, "副歌", "s_pop1")), key = "G")
    }

    @Test fun keyChangeMovesEveryChord() {
        val c = SongSetup.effective(chart4(), SongPrefs(transpose = 2), null, 2)
        assertEquals(listOf("A", "F#m", "D", "E"), c.chords.take(4).map { it.original })
        assertEquals("A", c.key)
        assertEquals(0, c.pitchShift)
        val p = SongSetup.effective(chart4(), SongPrefs(transpose = -3, pitchFollow = true), null, 2)
        assertEquals("E", p.key); assertEquals(-3, p.pitchShift)
        assertEquals("Em", SongSetup.transposeKey("Gm", -3))
        assertEquals(2, SongSetup.keyShiftSuggestion("G", "A"))
        assertEquals(null, SongSetup.keyShiftSuggestion("G", "Am"))
        assertEquals(-5, SongSetup.semitonesBetween("C", "G"))
    }

    @Test fun chordEditScopes() {
        val base = chart4()
        // shown transposed +2: user changes the 2nd box (F#m) to F#7, only that one
        val one = SongSetup.editRange(base, 2.5, 4.5, "F#7", 2, "one")
        assertEquals("E7", one.chords[1].original); assertEquals("Em", one.chords[5].original)
        val same = SongSetup.editRange(base, 2.5, 4.5, "F#7", 2, "same")
        assertEquals(2, same.chords.count { it.original == "E7" })
        assertEquals(2, SongSetup.sameCount(base, 0))
    }

    @Test fun capoFollowsTheNewKey() {
        // Eb is awkward: the automatic capo should kick in after transposing G -> Bb
        val c = SongSetup.effective(chart4(), SongPrefs(transpose = 3), null, 1)
        assertTrue("capo ${c.capo}", c.capo > 0)
        val manual = SongSetup.effective(chart4(), SongPrefs(transpose = 3, capo = 0), null, 1)
        assertEquals(0, manual.capo)
        val off = SongSetup.effective(chart4(), SongPrefs(offsetMs = 40), null, 1)
        assertEquals(0.04, off.audioOffset, 1e-9)
    }

    // ------------------------------------------------------------------ grid ops

    @Test fun gridOps() {
        val g = GridOps.from(chart4())
        assertEquals(120.0, g.bpm, 0.01)
        val d = GridOps.double(g)
        assertEquals(240.0, d.bpm, 0.5); assertEquals(g.downbeats.first(), d.downbeats.first(), 1e-9)
        val h = GridOps.halve(g)
        assertEquals(60.0, h.bpm, 0.5); assertEquals(g.downbeats.first(), h.downbeats.first(), 1e-9)
        val hb = GridOps.halfBeat(g)
        assertEquals(g.beats[0] + 0.25, hb.beats[0], 1e-9)
        val r = GridOps.rotate(g, 1)
        assertEquals(g.beats[1], r.downbeats.first(), 1e-9)
        val m = GridOps.meter(g, 3)
        assertEquals(3, m.beatsPerBar); assertEquals(1.5, m.downbeats[1] - m.downbeats[0], 1e-9)
        // chords / sections snap to a shifted grid
        val shifted = SongSetup.applyGrid(chart4(), GridOps.shift(g, 0.1))
        assertEquals(0.6, shifted.chords[0].start, 1e-9); assertEquals(0.6, shifted.sections[0].start, 1e-9)
    }

    @Test fun builderNeverSpillsIntoTheNextBar() {
        // 3/4 song, 4/4 pattern picked by hand: 3 beats per bar only, nothing at / after the next downbeat
        val beats = (0 until 25).map { 0.5 + it * 0.5 }
        val base = Chart(title = "w", bpm = 120.0, beatsPerBar = 3, beats = beats, downbeats = beats.filterIndexed { i, _ -> i % 3 == 0 },
            chords = listOf(ChordSpan(0.5, 12.5, "C")), sections = listOf(Section(0.5, 12.5, "A", "s_folk")))
        val c = ChartBuilder.song(base, lib, "s_folk", 2)
        for (bar in 0 until 7) {
            val start = base.downbeats[bar]; val end = base.downbeats[bar + 1]
            val ev = c.events.filter { it.bar == bar }
            assertTrue(ev.all { it.t >= start - 1e-6 && it.t < end - 1e-6 })
            assertEquals(4, ev.size)   // D - D U - U: the first 3 beats of D-DU-UDU
        }
    }

    // ------------------------------------------------------------------ envelope + tracking

    @Test fun envelopePeaksLandOnAttacks() {
        val fails = ArrayList<String>()
        for (sr in listOf(44100, 48000, 22050, 32000)) {
            val times = (0 until 30).map { 0.5 + it * 0.53 + (it % 3) * 0.0037 }
            val x = FloatArray((17.5 * sr).toInt()); TestSynth.noise(x, 0.0005)
            times.forEachIndexed { i, t -> TestSynth.pluck(x, sr, (t * sr).toInt(), TestSynth.midiHz(52 + (i % 5) * 3), 0.3, i + 1, maxSec = 0.4) }
            val e = RhythmAnalyzer.envelope(x, sr)
            val errs = times.map { t ->
                val f = e.full
                val a = e.frameOf(t - 0.04).toInt().coerceAtLeast(1); val b = e.frameOf(t + 0.04).toInt().coerceAtMost(e.size - 2)
                var m = a; for (i in a..b) if (f[i] > f[m]) m = i
                val den = f[m - 1] - 2 * f[m] + f[m + 1]
                val d = if (abs(den) > 1e-9) (0.5f * (f[m - 1] - f[m + 1]) / den).coerceIn(-0.5f, 0.5f) else 0f
                (e.timeOf(m + d.toDouble()) - t) * 1000
            }
            val mean = errs.average(); val spread = errs.maxOf { abs(it - mean) }
            println("envelope sr=$sr fps=${"%.2f".format(e.fps)} bias=${"%.1f".format(mean)} ms spread=${"%.1f".format(spread)} ms")
            if (abs(mean) >= 4.0 || spread >= 9.0) fails += "sr=$sr bias=$mean spread=$spread"
        }
        assertTrue(fails.joinToString(), fails.isEmpty())
    }

    @Test fun envelopeCacheRoundTrip() {
        val s = song(22050, 100.0, 2, "D-D-D-D-", 2)
        val e = RhythmAnalyzer.envelope(s.x, 22050)
        val r = OnsetEnvelope.fromBytes(e.toBytes())!!
        assertEquals(e.size, r.size); assertEquals(e.t0, r.t0, 1e-12); assertEquals(e.mid[42], r.mid[42], 0f)
    }

    @Test fun tracksBeatsOfSteadySongs() {
        for (bpm in listOf(76.0, 96.0, 128.0)) {
            val s = song(44100, bpm, 16, "D-DU-UDU", 2)
            val e = env(s)
            val tempo = RhythmAnalyzer.tempi(e).take(3)
            val tr = RhythmAnalyzer.track(e, bpm)
            val hit = hitRate(s.beats, tr); val err = meanErr(s.beats, tr)
            println("track bpm=$bpm tempi=${tempo.map { "%.0f".format(it.first) }} hit=${"%.2f".format(hit)} err=${"%.1f".format(err * 1000)} ms")
            assertTrue("bpm $bpm hit $hit", hit > 0.9)
            assertTrue("bpm $bpm err $err", err < 0.012)
        }
    }

    @Test fun bestLagFindsAConstantShift() {
        val s = song(44100, 100.0, 12, "D-D-DUDU", 2)
        val e = env(s)
        for (shift in listOf(-0.06, -0.03, 0.04, 0.07)) assertEquals("shift $shift", -shift, RhythmAnalyzer.bestLag(e, s.beats.map { it + shift }), 0.008)
    }

    @Test fun autoAlignFixesALateGrid() {
        val s = song(44100, 92.0, 16, "D-DU-UDU", 2)
        val e = env(s)
        val late = GridOps.withPhase(s.beats.map { it + 0.045 }, 4, 0, "")
        val r = RhythmAnalyzer.autoAlign(e, late, emptyList(), 0.0, 0.0, 0)
        val audio = (r.grid ?: late).beats.map { it - r.offsetMs / 1000.0 }
        println("autoAlign: ${r.message} offset=${r.offsetMs} newGrid=${r.grid != null}")
        assertTrue(hitRate(s.beats, audio, 0.015) > 0.9)
        // a good grid is left alone
        val ok = RhythmAnalyzer.autoAlign(e, GridOps.withPhase(s.beats, 4, 0, ""), emptyList(), 0.0, 0.0, 0)
        assertEquals(null, ok.grid); assertTrue(abs(ok.offsetMs) <= 10)
    }

    @Test fun tapsFixWrongTempoAndHalfBeat() {
        val s = song(44100, 96.0, 16, "D-DU-UDU", 2)
        val e = env(s)
        val taps = s.beats.subList(8, 20).mapIndexed { i, b -> b + 0.02 * ((i % 3) - 1) }   // human-ish ±20 ms
        val good = GridOps.withPhase(s.beats, 4, 0, "")
        // grid twice too fast / too slow
        val r1 = TapSync.apply(taps, GridOps.double(good), e, emptyList(), s.beats.last())
        assertEquals(96.0, r1.grid!!.bpm, 2.0)
        val r2 = TapSync.apply(taps, GridOps.halve(good), e, emptyList(), s.beats.last())
        assertEquals(96.0, r2.grid!!.bpm, 2.0)
        // 3:2 error (grid at 144) -> retracked at the tapped tempo, on the real beats
        val wrong = GridOps.withPhase((0 until 120).map { 0.62 + it * 60.0 / 144 }, 4, 0, "")
        val r3 = TapSync.apply(taps, wrong, e, emptyList(), s.beats.last())
        assertEquals(96.0, r3.grid!!.bpm, 2.0)
        assertTrue("3:2 hit", hitRate(s.beats, r3.grid!!.beats, 0.03) > 0.85)
        // grid on the「&」
        val r4 = TapSync.apply(taps, GridOps.halfBeat(good), e, emptyList(), s.beats.last())
        assertTrue("half-beat hit", hitRate(s.beats, r4.grid!!.beats, 0.03) > 0.9)
        // already right -> nothing to change
        val r5 = TapSync.apply(taps, good, e, emptyList(), s.beats.last())
        assertEquals(null, r5.grid)
    }

    // ------------------------------------------------------------------ pattern suggestions

    private fun chartFor(s: Song, bpm: Double, bpb: Int = 4, pattern: String = "s_pop1") = Chart(title = "t", bpm = bpm, beatsPerBar = bpb,
        beats = s.beats, downbeats = s.downs, chords = listOf(ChordSpan(s.beats.first(), s.beats.last(), "C")),
        sections = listOf(Section(s.beats.first(), s.beats.last(), "A", pattern)))

    @Test fun readsTheOriginalStrumPattern() {
        for (drums in listOf(false, true)) {
            val s = song(44100, 90.0, 12, "D-DU-UDU", 2, drums = drums)
            val sugg = PatternSuggest.suggest(chartFor(s, 90.0), lib, env(s), "strum", 0.0)
            println("orig(drums=$drums) = ${sugg.sections[0].original}  normal=${sugg.sections[0].normal}")
            assertTrue(sugg.fromAudio)
            assertEquals("s_folk", sugg.sections[0].original)
        }
        val s2 = song(44100, 100.0, 12, "D-D-DUDU", 2, drums = false)
        assertEquals("s_pop1", PatternSuggest.suggest(chartFor(s2, 100.0, pattern = "s_folk"), lib, env(s2), "strum", 0.0).sections[0].original)
        // an off-library rhythm becomes a custom "原曲" pattern that the builder can play
        val s3 = song(44100, 96.0, 12, "D-D-D-DU", 2, drums = false)
        val o3 = PatternSuggest.suggest(chartFor(s3, 96.0), lib, env(s3), "strum", 0.0).sections[0].original
        assertEquals(PatternSuggest.strumId(4, 2, "D-D-D-DU"), o3)
        assertNotNull(PatternSuggest.customStrum(o3))
    }

    @Test fun detectsSixteenthFeel() {
        val s = song(44100, 76.0, 10, "D-DU-UDUD-DU-UDU", 4, drums = false)
        val sugg = PatternSuggest.suggest(chartFor(s, 76.0), lib, env(s), "strum", 0.0)
        println("16th orig = ${sugg.sections[0].original} feel16=${sugg.feel16}")
        assertTrue(sugg.feel16)
        assertEquals("s_16th", sugg.sections[0].original)
    }

    @Test fun fourLevelsAllPlayable() {
        val s = song(44100, 92.0, 12, "D-DU-UDU", 2)
        val c = chartFor(s, 92.0)
        for (family in listOf("strum", "finger")) {
            val sugg = PatternSuggest.suggest(c, lib, env(s), family, 0.0)
            val aug = PatternSuggest.augment(lib, sugg.allIds())
            val counts = PatternLevel.entries.map { l ->
                val pid = sugg.sections[0].get(l)
                assertTrue("$family $l $pid resolves", aug.strum(pid) != null || aug.finger(pid) != null)
                ChartBuilder.song(c, aug, pid, 2, patternOverride = sugg.ids(l)).events.size
            }
            println("$family levels=${PatternLevel.entries.map { sugg.sections[0].get(it) }} events=$counts")
            assertTrue(counts.all { it > 0 })
            assertTrue("easy has fewest notes", counts[0] <= counts.drop(1).min())
        }
        // without audio: tempo rules, still four playable picks
        val noAudio = PatternSuggest.suggest(c, lib, null, "strum", 0.0)
        assertTrue(!noAudio.fromAudio)
        assertEquals("s_quarter", noAudio.sections[0].easy)
        assertEquals(3, PatternSuggest.suggest(c.copy(beatsPerBar = 3), lib, null, "strum", 0.0).sections[0].easy.let { lib.strum(it)!!.beatsPerBar })
    }

    @Suppress("unused") private fun g(b: List<Double>) = BeatGrid(b, b.filterIndexed { i, _ -> i % 4 == 0 }, 4, 60.0 / (b[1] - b[0]))
}
