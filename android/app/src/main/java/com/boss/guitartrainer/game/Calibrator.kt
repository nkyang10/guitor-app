package com.boss.guitartrainer.game

import android.content.Context
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.audio.ClickTrack
import com.boss.guitartrainer.audio.LiveAnalyzer
import com.boss.guitartrainer.audio.MicSession
import com.boss.guitartrainer.dsp.Direction
import com.boss.guitartrainer.dsp.DirectionModel
import com.boss.guitartrainer.dsp.OnsetDetector
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

/** Latency and strum-direction calibration routines (run inside a coroutine). */
class Calibrator(private val context: Context, private val source: AudioEngine.MicSource) {
    data class LatencyResult(val ok: Boolean, val medianMs: Double, val sdMs: Double, val matched: Int, val total: Int, val message: String)
    data class DirectionResult(val ok: Boolean, val model: DirectionModel?, val genericAcc: Double, val adaptedAcc: Double,
                               val nDown: Int, val nUp: Int, val message: String)

    private fun stats(v: List<Double>): Pair<Double, Double> {
        val s = v.sorted()
        val med = if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
        val m = v.average()
        val sd = if (v.size > 1) sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1)) else 0.0
        return med to sd
    }

    /** Speaker -> mic loopback with broadband pings. [progress] gets 0..1. */
    suspend fun latencyAuto(progress: (Double) -> Unit): LatencyResult {
        val mic = MicSession(context, source)
        val onsets = ArrayList<Long>()
        val det = OnsetDetector(AudioEngine.SR) { s, _, weak -> if (!weak) synchronized(onsets) { onsets.add(s) } }
        if (!mic.start(AudioEngine.Processor { b, n, st -> det.process(b, n, st) })) return LatencyResult(false, 0.0, 0.0, 0, 0, "開唔到咪高峰")
        val click = ClickTrack()
        click.volume = 1f
        click.start()
        try {
            delay(400)
            val n = 12
            val gap = 0.55
            val first = click.futureFrame(0.3)
            val frames = (0 until n).map { first + (it * gap * AudioEngine.SR).toLong() }
            frames.forEach { click.schedule(it, ClickTrack.Sound.PING, 1f) }
            val total = 0.3 + n * gap + 0.6
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < total * 1000) { progress((System.currentTimeMillis() - t0) / (total * 1000)); delay(50) }
            val ons = synchronized(onsets) { onsets.toList() }
            val offs = ArrayList<Double>()
            for (f in frames) {
                val pn = click.frameToNanos(f)
                val best = ons.map { (mic.engine.sampleToNanos(it) - pn) / 1e6 }.filter { it > -60 && it < 450 }.minByOrNull { abs(it) }
                if (best != null) offs.add(best)
            }
            if (offs.size < n / 2) return LatencyResult(false, 0.0, 0.0, offs.size, n, "只聽到 ${offs.size}/$n 下提示聲：調大音量、除咗耳機，或者用「手動校準」。")
            val (med, sd) = stats(offs)
            val ok = sd < 25
            return LatencyResult(ok, med, sd, offs.size, n, if (ok) "完成" else "結果唔穩定（±%.0f ms），建議再試或用手動校準。".format(sd))
        } finally { click.stop(); mic.stop() }
    }

    /** User strums along with clicks. Includes the player's own anticipation (that is what we want to cancel). */
    suspend fun latencyManual(bpm: Int, beats: Int, onBeat: (Int) -> Unit): LatencyResult {
        val mic = MicSession(context, source)
        val onsets = ArrayList<Long>()
        val det = OnsetDetector(AudioEngine.SR) { s, _, weak -> if (!weak) synchronized(onsets) { onsets.add(s) } }
        if (!mic.start(AudioEngine.Processor { b, n, st -> det.process(b, n, st) })) return LatencyResult(false, 0.0, 0.0, 0, 0, "開唔到咪高峰")
        val click = ClickTrack()
        click.start()
        try {
            delay(300)
            val spb = 60.0 / bpm
            val first = click.futureFrame(0.4)
            val countIn = 4
            val frames = (0 until countIn + beats).map { first + (it * spb * AudioEngine.SR).toLong() }
            frames.forEachIndexed { i, f -> click.schedule(f, if (i % 4 == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK) }
            for ((i, f) in frames.withIndex()) {
                while (click.nanosToFrame(System.nanoTime()) < f) delay(5)
                onBeat(i - countIn)
            }
            delay((spb * 1000).toLong())
            val ons = synchronized(onsets) { onsets.toList() }
            val offs = ArrayList<Double>()
            for (f in frames.drop(countIn)) {
                val pn = click.frameToNanos(f)
                val best = ons.map { (mic.engine.sampleToNanos(it) - pn) / 1e6 }.filter { abs(it) < spb * 1000 * 0.45 }.minByOrNull { abs(it) }
                if (best != null) offs.add(best)
            }
            if (offs.size < beats / 2) return LatencyResult(false, 0.0, 0.0, offs.size, beats, "只偵測到 ${offs.size}/$beats 下掃弦，請大聲少少、靠近電話。")
            val (med, sd) = stats(offs)
            return LatencyResult(true, med, sd, offs.size, beats, "完成")
        } finally { click.stop(); mic.stop() }
    }

    /**
     * Direction calibration: alternate down/up eighth notes at [bpm] for [bars] bars of 4/4.
     * Labels come from the prompt grid. Returns the MAP-adapted model + cross-validated accuracies.
     */
    suspend fun direction(bpm: Int, bars: Int, onStep: (Int, String) -> Unit): DirectionResult {
        val generic = Direction.generic ?: return DirectionResult(false, null, 0.0, 0.0, 0, 0, "冇內置模型")
        val spec = Direction.spec!!
        val mic = MicSession(context, source)
        val feats = ArrayList<Pair<Long, DoubleArray>>()
        val la = LiveAnalyzer(mic.engine, object : LiveAnalyzer.Sink {
            override fun onOnset(id: Long, sample: Long, ratio: Double, weak: Boolean) {}
            override fun onAnalysis(id: Long, sample: Long, a: LiveAnalyzer.Analysis) {
                val f = a.features ?: return
                if (!a.weak) synchronized(feats) { feats.add(sample to f) }
            }
        })
        if (!mic.start(la)) return DirectionResult(false, null, 0.0, 0.0, 0, 0, "開唔到咪高峰")
        val click = ClickTrack()
        click.start()
        try {
            delay(300)
            val spb = 60.0 / bpm
            val first = click.futureFrame(0.5)
            val countIn = 4
            for (b in 0 until countIn + bars * 4) {
                val f = first + (b * spb * AudioEngine.SR).toLong()
                click.schedule(f, if (b % 4 == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK)
            }
            val steps = bars * 8
            val stepFrames = (0 until steps).map { first + ((countIn * spb + it * spb / 2) * AudioEngine.SR).toLong() }
            // count-in
            for (b in 0 until countIn) {
                val f = first + (b * spb * AudioEngine.SR).toLong()
                while (click.nanosToFrame(System.nanoTime()) < f) delay(5)
                onStep(-(countIn - b), "")
            }
            for ((i, f) in stepFrames.withIndex()) {
                while (click.nanosToFrame(System.nanoTime()) < f - (0.05 * AudioEngine.SR).toLong()) delay(5)
                onStep(i, if (i % 2 == 0) "D" else "U")
            }
            delay((spb * 1500).toLong())
            val list = synchronized(feats) { feats.toList() }
            val X = ArrayList<DoubleArray>(); val y = ArrayList<Int>()
            val half = spb / 2
            for ((s, f) in list) {
                val tn = mic.engine.sampleToNanos(s)
                var bestI = -1; var bestD = Double.MAX_VALUE
                for ((i, sf) in stepFrames.withIndex()) {
                    val d = abs((tn - click.frameToNanos(sf)) / 1e9)
                    if (d < bestD) { bestD = d; bestI = i }
                }
                if (bestI >= 0 && bestD < half * 0.45) { X.add(f); y.add(if (bestI % 2 == 0) 1 else 0) }
            }
            val nD = y.count { it == 1 }; val nU = y.size - nD
            if (nD < 8 || nU < 8) return DirectionResult(false, null, 0.0, 0.0, nD, nU, "收集到嘅掃弦唔夠（↓$nD ↑$nU），請跟住提示再試。")
            val (gAcc, aAcc) = DirectionModel.crossValidate(generic, X, y, spec.lambda_personal)
            val adapted = DirectionModel.adapt(generic, X, y, spec.lambda_personal)
            val useAdapted = aAcc >= gAcc - 0.02
            val model = (if (useAdapted) adapted else generic.copy(kind = "generic", trainedOn = X.size, createdAt = System.currentTimeMillis()))
                .copy(genericAccuracy = gAcc, cvAccuracy = if (useAdapted) aAcc else gAcc)
            return DirectionResult(true, model, gAcc, aAcc, nD, nU, "完成")
        } finally { click.stop(); mic.stop() }
    }
}
