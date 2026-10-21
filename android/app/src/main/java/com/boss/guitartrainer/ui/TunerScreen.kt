package com.boss.guitartrainer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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

/** Everything the tuner face needs (kept plain so it can be previewed / screenshot-tested). */
data class TunerUi(
    val tunings: List<Pair<String, String>>,
    val tuningId: String,
    val tuningName: String,
    val note: String?,
    val hz: Double?,
    val cents: Double?,
    val targetIdx: Int?,
    val manualTarget: Int,              // -1 = auto
    val stringNames: List<String>,      // index 0 = 6th string
    val tuned: Set<Int>,
    val level: Float,
    val a4: Double,
    val error: String?,
)

@Composable
fun TunerScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val tuning = Tunings.byId(settings.tuningId)
    val proc = remember { TunerProcessor { store.settings.value.a4 } }
    val mic = remember { MicSession(ctx, AudioEngine.MicSource.AUTO) }
    var err by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableIntStateOf(-1) }       // -1 = auto
    val tuned = remember { mutableStateOf(setOf<Int>()) }
    DisposableEffect(Unit) {
        if (!mic.start(proc)) err = "開唔到咪高峰（請俾錄音權限）" else store.bump("tuner")
        onDispose { mic.stop() }
    }
    val reading by proc.state.collectAsState()
    val hz = reading.hz
    val midi = hz?.let { Notes.hzToMidi(it, settings.a4) }
    val autoIdx = midi?.let { m -> tuning.midis.indices.minByOrNull { abs(tuning.midis[it] - m) } }
    val idx = if (target >= 0) target else autoIdx
    val cents = if (hz != null && idx != null) Notes.cents(hz, Notes.midiToHz(tuning.midis[idx].toDouble(), settings.a4)) else null
    if (cents != null && abs(cents) < 4 && idx != null && idx !in tuned.value) tuned.value = tuned.value + idx
    LaunchedEffect(tuned.value.isNotEmpty()) { if (tuned.value.isNotEmpty()) store.markToday("tuner") }

    TunerContent(
        TunerUi(Tunings.ALL.map { it.id to it.name }, tuning.id, tuning.name, midi?.let { Notes.name(Math.round(it).toInt()) }, hz, cents,
            idx, target, tuning.midis.indices.map { tuning.stringName(it) }, tuned.value, (reading.level * 12).toFloat(), settings.a4, err),
        onBack = { nav.popBackStack() },
        onTuning = { id -> store.updateSettings { it.copy(tuningId = id) }; tuned.value = emptySet() },
        onTarget = { target = it },
    )
}

private fun centsTone(c: Double?): Tone? = when {
    c == null -> null
    abs(c) < 4 -> Tone.MINT
    abs(c) < 15 -> Tone.SUN
    else -> Tone.ROSE
}

@Composable
fun TunerContent(u: TunerUi, onBack: () -> Unit, onTuning: (String) -> Unit, onTarget: (Int) -> Unit) {
    val reduce = rememberReduceMotion()
    Screen("調音器", onBack, subtitle = u.tuningName) {
        u.error?.let { Text(it, color = Palette.Miss, style = MaterialTheme.typography.bodyMedium) }
        ChipRow(u.tunings, u.tuningId, onTuning)
        val tone = centsTone(u.cents)
        val inTune = tone == Tone.MINT
        Column(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp)).padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // ---- note bubble
            val bubble by animateColorAsState(tone?.soft ?: Palette.Surface2, tween(220), label = "bubble")
            val ink by animateColorAsState(tone?.deep ?: Palette.Muted, tween(220), label = "ink")
            val glow = if (inTune && !reduce) rememberInfiniteTransition(label = "glow")
                .animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glowScale").value else 1f
            val pop by animateFloatAsState(if (inTune) 1.06f else 1f, spring(dampingRatio = 0.35f, stiffness = 400f), label = "pop")
            Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                if (inTune) Box(Modifier.size(160.dp).scale(glow).clip(CircleShape).background(Palette.Mint.copy(alpha = 0.28f)))
                Box(Modifier.size(146.dp).scale(pop).clip(CircleShape).background(bubble).border(4.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(u.note ?: "—", style = MaterialTheme.typography.displayMedium, color = ink)
                        Text(if (u.hz != null) "%.1f Hz".format(u.hz) else "請彈一條弦 🎵", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Gauge(u.cents)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("♭ 偏低", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
                Text("偏高 ♯", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
            Spacer(Modifier.height(10.dp))
            val msg = when {
                u.cents == null -> "彈一條弦，指針會話你知要扭邊邊"
                inTune -> "✓ 準！好正 🎉"
                u.cents < 0 -> "偏低 %.0f cent → 扭緊啲".format(-u.cents)
                else -> "偏高 %.0f cent → 放鬆啲".format(u.cents)
            }
            Box(Modifier.clip(CircleShape).background(tone?.soft ?: Palette.Surface2).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(msg, style = MaterialTheme.typography.titleSmall, color = tone?.deep ?: Palette.Muted, textAlign = TextAlign.Center)
            }
            if (u.targetIdx != null) {
                Spacer(Modifier.height(6.dp))
                Text("目標：${6 - u.targetIdx}弦 ${u.stringNames[u.targetIdx]}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("揀弦", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${u.tuned.size}/6 調好", style = MaterialTheme.typography.labelMedium, color = Palette.MintDeep)
                Spacer(Modifier.width(10.dp))
                val auto = u.manualTarget == -1
                Box(Modifier.clip(CircleShape).background(if (auto) Palette.Coral else Palette.Surface2)
                    .bounceClick(role = Role.RadioButton) { onTarget(-1) }.semantics { selected = auto }
                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("✨ 自動", style = MaterialTheme.typography.labelLarge, color = if (auto) Palette.Text else Palette.Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (s in 0 until 6) Peg(u.stringNames[s], 6 - s, sel = u.manualTarget == s, active = u.targetIdx == s, done = s in u.tuned) { onTarget(s) }
            }
            Hint("尼龍弦新換時會一直鬆，調完一輪再調多一兩輪。A4 = %.0f Hz（可以喺設定改）".format(u.a4))
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                LevelBar(u.level, Modifier.weight(1f), color = Palette.Mint)
            }
            Hint("如果音名跳嚟跳去：靠近咪高峰，只彈一條弦，用手掌輕輕悶住其他弦。")
        }
    }
}

@Composable
private fun Peg(name: String, number: Int, sel: Boolean, active: Boolean, done: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(when { sel -> Palette.Coral; done -> Palette.MintSoft; active -> Palette.SkySoft; else -> Palette.Surface2 }, label = "peg")
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.bounceClick(role = Role.RadioButton, onClick = onClick).semantics { selected = sel; contentDescription = "${number}弦 $name" }) {
        Box(Modifier.size(48.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(bg)
                .then(if (active && !sel) Modifier.border(2.dp, Palette.Sky, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                Text(name.trimEnd { it.isDigit() }, style = MaterialTheme.typography.titleMedium,
                    color = when { sel -> Palette.Text; done -> Palette.MintDeep; else -> Palette.Text })
            }
            if (done) Box(Modifier.size(18.dp).align(Alignment.TopEnd).clip(CircleShape).background(Palette.MintDeep), contentAlignment = Alignment.Center) {
                Text("✓", fontSize = 11.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${number}弦", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
}

/** Half-dial with soft colour zones; the needle springs to the reading. */
@Composable
private fun Gauge(cents: Double?) {
    val target = (cents ?: 0.0).coerceIn(-50.0, 50.0).toFloat()
    val shown by animateFloatAsState(target, spring(dampingRatio = 0.55f, stiffness = 140f), label = "needle")
    val tone = centsTone(cents)
    Canvas(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 8.dp)
        .semantics { contentDescription = cents?.let { "偏差 %.0f cent".format(it) } ?: "未有讀數" }) {
        val cx = size.width / 2; val cy = size.height * 0.96f
        val r = minOf(size.height * 0.88f, size.width / 2 * 0.92f)
        val tl = Offset(cx - r, cy - r); val sz = Size(2 * r, 2 * r)
        val sw = 22f
        fun ang(c: Float) = 270f + c * 1.6f                   // ±50 cent -> ±80°
        drawArc(Palette.RoseSoft, ang(-50f), 160f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(Palette.SunSoft, ang(-15f), 30f * 1.6f, false, tl, sz, style = Stroke(sw))
        drawArc(Palette.Mint.copy(alpha = 0.55f), ang(-4f), 8f * 1.6f, false, tl, sz, style = Stroke(sw))
        for (c in -50..50 step 10) {
            val a = Math.toRadians(ang(c.toFloat()).toDouble())
            val p0 = Offset(cx + (r - 20) * cos(a).toFloat(), cy + (r - 20) * sin(a).toFloat())
            val p1 = Offset(cx + (r - (if (c == 0) 40 else 32)) * cos(a).toFloat(), cy + (r - (if (c == 0) 40 else 32)) * sin(a).toFloat())
            drawLine(Palette.Grid.copy(alpha = if (c == 0) 0.9f else 0.55f), p0, p1, strokeWidth = if (c == 0) 5f else 3f, cap = StrokeCap.Round)
        }
        val a = Math.toRadians(ang(shown).toDouble())
        val col = if (cents == null) Palette.Grid.copy(alpha = 0.5f) else tone?.deep ?: Palette.Text
        val tip = Offset(cx + (r - 6) * cos(a).toFloat(), cy + (r - 6) * sin(a).toFloat())
        drawLine(col, Offset(cx, cy), tip, strokeWidth = 9f, cap = StrokeCap.Round)
        drawCircle(Color.White, 15f, Offset(cx, cy))
        drawCircle(col, 15f, Offset(cx, cy), style = Stroke(6f))
    }
}
