package com.boss.guitartrainer

import com.boss.guitartrainer.dsp.Direction
import com.boss.guitartrainer.dsp.DirectionFeatures
import com.boss.guitartrainer.dsp.DirectionModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Kotlin feature extraction must match the Python training code (validation/dir2feat.py). */
class DirectionTest {
    private fun init() { if (Direction.spec == null) Direction.initFromJson(File("src/main/assets/direction_generic.json").readText()) }

    @Test fun featuresMatchPython() {
        init()
        val fx = Json.parseToJsonElement(File("src/test/resources/direction_fixture.json").readText()).jsonObject
        val sig = fx["signal"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
        val expected = fx["features"]!!.jsonArray.map { it.jsonPrimitive.double }
        val onset = fx["onset"]!!.jsonPrimitive.int
        val f = DirectionFeatures(Direction.spec!!, 48000).compute(sig, onset)!!
        assertEquals(expected.size, f.size)
        var maxErr = 0.0
        for (i in f.indices) maxErr = maxOf(maxErr, abs(f[i] - expected[i]) / (1.0 + abs(expected[i])))
        assertTrue("max rel err $maxErr", maxErr < 1e-4)
        val p = Direction.generic!!.probDown(f)
        assertEquals(fx["prob_down"]!!.jsonPrimitive.double, p, 1e-3)
    }

    @Test fun downVsUpOnSynthStrums() {
        init()
        val sr = 48000
        val df = DirectionFeatures(Direction.spec!!, sr)
        var correct = 0; var n = 0
        val X = ArrayList<DoubleArray>(); val y = ArrayList<Int>()
        for (k in 0 until 40) {
            val down = k % 2 == 0
            val x = FloatArray(sr / 2)
            TestSynth.noise(x, 0.001, k)
            TestSynth.strum(x, sr, 6000, listOf(40, 47, 52, 56, 59, 64), down, spreadMs = 25.0 + (k % 5) * 3, seed = k + 7)
            val f = df.compute(x, 6000) ?: continue
            X.add(f); y.add(if (down) 1 else 0)
            val pd = Direction.generic!!.probDown(f)
            if ((pd > 0.5) == down) correct++
            n++
        }
        // generic model was trained on real steel-string recordings; synthetic strums only need to be better than chance
        assertTrue("generic acc $correct/$n", correct >= n * 0.55)
        val (g, a) = DirectionModel.crossValidate(Direction.generic!!, X, y, Direction.spec!!.lambda_personal)
        assertTrue("adapted $a vs generic $g", a >= g - 0.1)
    }
}
