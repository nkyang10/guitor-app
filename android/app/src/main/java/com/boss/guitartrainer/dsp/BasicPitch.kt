package com.boss.guitartrainer.dsp

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Spotify Basic Pitch (ICASSP 2022, Apache-2.0) "nmp.tflite": input 43844 samples @ 22050 Hz (1.99 s),
 * outputs note / onset posteriorgrams 172 frames x 88 pitches (A0..C8) + contour 172 x 264.
 * Used in VERIFY mode: "is the expected MIDI note active around time t?"
 * Phase 0 (GuitarSet solo, mic): verify AUC 0.92, balanced accuracy 0.88 at threshold 0.40;
 * ~30 ms per window on 2 desktop cores.
 */
class BasicPitch(context: Context) : AutoCloseable {
    companion object {
        const val SR = 22050
        const val WIN = 43844
        const val FRAMES = 172
        const val FPS = 22050.0 / 256.0
        var VERIFY_THRESHOLD = 0.40
        fun available(context: Context) = try { context.assets.openFd("nmp.tflite").close(); true } catch (e: Exception) { false }
    }

    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(WIN * 4).order(ByteOrder.nativeOrder())
    private val noteOut = Array(1) { Array(FRAMES) { FloatArray(88) } }
    private val onsetOut = Array(1) { Array(FRAMES) { FloatArray(88) } }
    private val contourOut = Array(1) { Array(FRAMES) { FloatArray(264) } }
    private var noteIdx = 1
    private var onsetIdx = 2
    private var contourIdx = 0

    init {
        val fd = context.assets.openFd("nmp.tflite")
        val model: MappedByteBuffer = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        // identify outputs by name suffix (StatefulPartitionedCall:0 = contour, :1 = note, :2 = onset)
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val name = t.name()
            if (t.shape().last() == 264) contourIdx = i
            else if (name.endsWith(":1")) noteIdx = i
            else if (name.endsWith(":2")) onsetIdx = i
        }
    }

    class Result(val note: Array<FloatArray>, val onset: Array<FloatArray>, val startSec: Double) {
        /** max(note, onset) activation for [midi] in frames around [timeSec] (absolute, same clock as startSec). */
        fun activation(midi: Int, timeSec: Double, before: Double = 0.05, after: Double = 0.08): Double {
            val p = midi - 21
            if (p < 0 || p >= 88) return 0.0
            val f0 = floor((timeSec - before - startSec) * FPS).toInt().coerceAtLeast(0)
            val f1 = floor((timeSec + after - startSec) * FPS).toInt().coerceAtMost(note.size - 1)
            var m = 0.0
            for (f in f0..f1) m = max(m, max(note[f][p], onset[f][p]).toDouble())
            return m
        }
        fun reliable(timeSec: Double) = timeSec - startSec in (15 / FPS)..((FRAMES - 15) / FPS)
    }

    /** Run on exactly WIN samples at 22050 Hz. */
    @Synchronized
    fun run(x22: FloatArray, startSec: Double): Result {
        input.rewind()
        for (i in 0 until WIN) input.putFloat(if (i < x22.size) x22[i] else 0f)
        input.rewind()
        val outputs = HashMap<Int, Any>()
        outputs[noteIdx] = noteOut; outputs[onsetIdx] = onsetOut; outputs[contourIdx] = contourOut
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        return Result(Array(FRAMES) { noteOut[0][it].copyOf() }, Array(FRAMES) { onsetOut[0][it].copyOf() }, startSec)
    }

    override fun close() = interpreter.close()
}

/** Windowed-sinc resampler (any ratio), good enough for analysis (not hi-fi). */
object Resampler {
    fun resample(x: FloatArray, srIn: Int, srOut: Int, taps: Int = 16): FloatArray {
        val ratio = srOut.toDouble() / srIn
        val n = (x.size * ratio).toInt()
        val out = FloatArray(n)
        val cutoff = min(1.0, ratio) * 0.95
        for (i in 0 until n) {
            val t = i / ratio
            val c = floor(t).toInt()
            var acc = 0.0; var wsum = 0.0
            for (k in c - taps + 1..c + taps) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val arg = PI * d * cutoff
                val sinc = if (kotlin.math.abs(arg) < 1e-9) 1.0 else kotlin.math.sin(arg) / arg
                val w = 0.5 + 0.5 * cos(PI * d / taps)
                val h = sinc * w
                acc += x[k] * h; wsum += h
            }
            out[i] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }
}
