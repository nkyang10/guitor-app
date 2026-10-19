package com.boss.guitartrainer

import com.boss.guitartrainer.analysis.SongAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Kotlin tempo estimator must pick the same BPM as server/analysis.py on identical onset envelopes. */
class TempoTest {
    @Test fun matchesPython() {
        val text = javaClass.classLoader!!.getResource("tempo_fixture.json")!!.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        val hopSec = root["hopSec"]!!.jsonPrimitive.double
        var worst = 0.0
        for (c in root["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val env = o["env"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            val expected = o["expected"]!!.jsonPrimitive.double
            val (bpm, period) = SongAnalyzer.tempo(env, hopSec)
            println("TEMPO ${o["name"]!!.jsonPrimitive.content}: kotlin $bpm python $expected period $period")
            worst = maxOf(worst, abs(bpm - expected))
            assertTrue("${o["name"]} kotlin $bpm vs python $expected", abs(bpm - expected) <= 1.0)
        }
        println("TEMPO worst diff $worst")
    }
}
