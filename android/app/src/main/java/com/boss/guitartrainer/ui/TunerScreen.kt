package com.boss.guitartrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.audio.MicSession
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.dsp.MpmDetector
import com.boss.guitartrainer.dsp.Notes
import com.boss.guitartrainer.dsp.PitchSmoother
import com.boss.guitartrainer.music.Tunings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** MPM pitch (2048 @ 48 kHz, validated in Phase 0) + clarity gate + median-of-5 smoothing. */
class TunerProcessor(private val a4: () -> Double) : AudioEngine.Processor {
    data class Reading(val hz: Double?, val level: Double, val clarity: Double)
    val state = MutableStateFlow(Reading(null, 0.0, 0.0))
    private val n = 2048
    private val hop = 1024
    private val ring = FloatArray(n)
    private val buf = FloatArray(n)
    private var w = 0
    private var total = 0L
    private var sinceLast = 0
    private val mpm = MpmDetector(n, AudioEngine.SR)
    private val smoother = PitchSmoother(0.8, 5)
    private var lastVoiced = 0L

    override fun onAudio(buf: FloatArray, n: Int, startSample: Long) {
        for (i in 0 until n) {
            ring[w] = buf[i]
            w = (w + 1) % this.n
            total++
            sinceLast++
            if (total >= this.n && sinceLast >= hop) {
                sinceLast = 0
                // unroll ring -> linear frame (oldest first)
                val tail = this.n - w
                System.arraycopy(ring, w, this.buf, 0, tail)
                System.arraycopy(ring, 0, this.buf, tail, w)
                analyse()
            }
        }
    }

    private fun analyse() {
        var s = 0.0
        for (v in buf) s += v * v
        val rms = sqrt(s / n)
        val r = mpm.detect(buf, 0)
        val hz = if (rms > 0.002) smoother.push(r, a4()) else { smoother.push(com.boss.guitartrainer.dsp.PitchResult(0.0, 0.0)); null }
        val now = System.currentTimeMillis()
        if (hz != null) lastVoiced = now
        val keep = hz ?: if (now - lastVoiced < 400) state.value.hz else null
        state.value = Reading(keep, rms, r.clarity)
    }
}

@Composable
fun TunerScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val tuning = Tunings.byId(settings.tuningId)
    val proc = remember { TunerProcessor { store.settings.value.a4 } }
    val mic = remember { MicSession(ctx, AudioEngine.MicSource.AUTO) }
    var err by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf(-1) }       // -1 = auto
    val tuned = remember { mutableStateOf(setOf<Int>()) }
    DisposableEffect(Unit) {
        if (!mic.start(proc)) err = "開唔到咪高峰（請俾錄音權限）" else store.bump("tuner")
        onDispose { mic.stop() }
    }
    val reading by proc.state.collectAsState()
    val hz = reading.hz
    // choose target string
    val midi = hz?.let { Notes.hzToMidi(it, settings.a4) }
    val autoIdx = midi?.let { m -> tuning.midis.indices.minByOrNull { abs(tuning.midis[it] - m) } }
    val idx = if (target >= 0) target else autoIdx
    val cents = if (hz != null && idx != null) Notes.cents(hz, Notes.midiToHz(tuning.midis[idx].toDouble(), settings.a4)) else null
    if (cents != null && abs(cents) < 4 && idx != null && idx !in tuned.value) tuned.value = tuned.value + idx

    Screen("調音器", { nav.popBackStack() }) {
        err?.let { Text(it, color = Palette.Miss) }
        ChipRow(Tunings.ALL.map { it.id to it.name }, tuning.id) { id -> store.updateSettings { it.copy(tuningId = id) }; tuned.value = emptySet() }
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val noteName = if (midi != null) Notes.name(Math.round(midi).toInt()) else "—"
                Text(noteName, fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    color = when { cents == null -> Palette.Muted; abs(cents) < 4 -> Palette.Perfect; abs(cents) < 15 -> Palette.Good; else -> Palette.Miss })
                Text(if (hz != null) "%.1f Hz".format(hz) else "請彈一條弦", color = Palette.Muted)
                Needle(cents)
                Text(when {
                    cents == null -> " "
                    abs(cents) < 4 -> "✓ 準！"
                    cents < 0 -> "偏低 %.0f cent → 扭緊啲".format(-cents)
                    else -> "偏高 %.0f cent → 放鬆啲".format(cents)
                }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (idx != null) Text("目標：${6 - idx}弦 ${tuning.stringName(idx)}", color = Palette.Muted)
            }
        }
        SectionCard("揀弦（預設自動）") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { target = -1 }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                    Text(if (target == -1) "●自動" else "自動", fontSize = 12.sp)
                }
                for (s in 0 until 6) {
                    val sel = target == s
                    val done = s in tuned.value
                    (if (sel) Button(onClick = { target = s }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                        Text(tuning.stringName(s), fontSize = 12.sp)
                    } else OutlinedButton(onClick = { target = s }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                        Text((if (done) "✓" else "") + tuning.stringName(s), fontSize = 12.sp, color = if (done) Palette.Perfect else Palette.Text)
                    })
                }
            }
            Hint("尼龍弦新換時會一直鬆，調完一輪再調多一兩輪。A4 = %.0f Hz（可喺設定改）".format(settings.a4))
        }
        SectionCard {
            Hint("輸入音量")
            LevelBar((reading.level * 12).toFloat())
            Hint("如果數字跳嚟跳去：靠近咪高峰，只彈一條弦，用手掌悶住其他弦。")
        }
    }
}

@Composable
private fun Needle(cents: Double?) {
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(8.dp)) {
        val cx = size.width / 2; val cy = size.height * 0.95f
        val r = size.height * 0.85f
        drawArc(Palette.Surface2, 200f, 140f, false, topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(18f, cap = StrokeCap.Round))
        drawArc(Palette.Perfect.copy(alpha = 0.5f), 270f - 6f, 12f, false, topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(18f))
        for (c in -50..50 step 10) {
            val a = Math.toRadians(270.0 + c * 1.4)
            val p0 = Offset(cx + (r - 22) * cos(a).toFloat(), cy + (r - 22) * sin(a).toFloat())
            val p1 = Offset(cx + (r - 36) * cos(a).toFloat(), cy + (r - 36) * sin(a).toFloat())
            drawLine(Palette.Line, p0, p1, strokeWidth = if (c == 0) 5f else 2f)
        }
        if (cents != null) {
            val c = cents.coerceIn(-50.0, 50.0)
            val a = Math.toRadians(270.0 + c * 1.4)
            val col = when { abs(cents) < 4 -> Palette.Perfect; abs(cents) < 15 -> Palette.Good; else -> Palette.Miss }
            drawLine(col, Offset(cx, cy), Offset(cx + (r - 10) * cos(a).toFloat(), cy + (r - 10) * sin(a).toFloat()), strokeWidth = 7f, cap = StrokeCap.Round)
            drawCircle(col, 10f, Offset(cx, cy))
        }
    }
}
