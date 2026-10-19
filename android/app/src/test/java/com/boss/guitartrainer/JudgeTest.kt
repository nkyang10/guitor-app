package com.boss.guitartrainer

import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.game.Grade
import com.boss.guitartrainer.game.Judge
import com.boss.guitartrainer.game.SessionSummary
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.PatternLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JudgeTest {
    private val lib = PatternLibrary.parse(File("src/main/assets/patterns.json").readText())
    private val chart = ChartBuilder.strumPractice("t", listOf("C", "G"), lib.strum("s_alt")!!, 90.0, 2)
    private val settings = DifficultySettings.BEGINNER.copy(chordCheck = false, tempoPercent = 100)

    @Test fun perfectPlay() {
        val j = Judge(chart, settings, 1.0)
        chart.events.forEachIndexed { i, e -> j.onOnset(i.toLong() + 1, 0, e.t + 0.01, 3.0, false); j.update(e.t + 0.01) }
        j.update(chart.duration + 1)
        assertTrue(j.results.all { it.grade == Grade.PERFECT })
        assertEquals(chart.events.size, j.maxCombo)
        val rec = SessionSummary.build(j, "strum", "t", "s_alt", 90.0, 10.0)
        assertEquals(100.0, rec.accuracy, 1e-6); assertEquals(3, rec.stars)
    }

    @Test fun missesExtrasAndLate() {
        val j = Judge(chart, settings, 1.0)
        var id = 1L
        chart.events.forEachIndexed { i, e ->
            when (i % 4) {
                0 -> j.onOnset(id++, 0, e.t + 0.1, 3.0, false)      // 100 ms late -> GOOD
                1 -> {}                                               // skip -> MISS
                else -> j.onOnset(id++, 0, e.t, 3.0, false)
            }
            j.update(e.t + 0.3)
        }
        j.onOnset(id++, 0, chart.duration + 0.5, 3.0, false)         // extra
        j.update(chart.duration + 2)
        val n = chart.events.size
        assertEquals(n / 4, j.results.count { it.grade == Grade.MISS })
        assertEquals(n / 4, j.results.count { it.grade == Grade.GOOD })
        assertTrue(j.extras >= 1)
        val rec = SessionSummary.build(j, "strum", "t", "s_alt", 90.0, 10.0)
        assertTrue(rec.meanOffsetMs!! > 0)
    }

    @Test fun weakOnsetOnlyWhenNothingElse() {
        val j = Judge(chart, settings, 1.0)
        val e = chart.events[0]
        j.onOnset(1, 0, e.t + 0.02, 0.8, true)
        j.update(e.t + 0.5)
        assertEquals(Grade.PERFECT, j.results[0].grade)
        assertTrue(j.results[0].weak)
    }
}
