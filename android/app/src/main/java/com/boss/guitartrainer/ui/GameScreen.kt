package com.boss.guitartrainer.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.res.ResourcesCompat
import com.boss.guitartrainer.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.EventResult
import com.boss.guitartrainer.game.Feedback
import com.boss.guitartrainer.game.GameSession
import com.boss.guitartrainer.game.Grade
import com.boss.guitartrainer.game.Progression
import com.boss.guitartrainer.game.SessionSummary
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.SongSetup
import com.boss.guitartrainer.music.ChartEvent
import com.boss.guitartrainer.music.ChordLibrary
import kotlin.math.ceil
import kotlin.math.floor

/** Chart time of the start of bar [k]. */
fun barTime(chart: Chart, k: Int): Double {
    if (chart.downbeats.isNotEmpty()) return chart.downbeats.getOrElse(k) { chart.downbeats.last() + (k - chart.downbeats.size + 1) * chart.beatsPerBar * 60.0 / chart.bpm }
    return k * chart.beatsPerBar * 60.0 / chart.bpm
}

fun barCount(chart: Chart): Int = if (chart.downbeats.isNotEmpty()) chart.downbeats.size else (chart.events.maxOfOrNull { it.bar } ?: 0) + 1

@Composable
fun GameScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val req0 = AppState.pendingGame
    if (req0 == null) { Screen("練習", { nav.popBackStack() }) { EmptyHint("🤔", "冇練習內容") }; return }
    var req by remember { mutableStateOf(req0) }
    var sessionKey by remember { mutableIntStateOf(0) }
    val settings = store.settings.value
    var nudgeMs by remember { mutableIntStateOf(0) }       // song mode「拍子早咗／遲咗」, saved to the song on finish
    val session = remember(sessionKey) {
        GameSession(ctx, req.chart, req.settings, store.calibration.value, req.mode, req.audio,
            extraOffsetMs = req.extraOffsetMs + if (req.mode == "song") settings.songExtraOffsetMs else 0,
            useAi = settings.useBasicPitch, micSource = settings.micSource, loop = req.loop, metronomeVolume = settings.metronomeVolume)
            .also { it.userOffset = nudgeMs / 1000.0 }
    }
    var t by remember { mutableDoubleStateOf(-10.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLoop by remember { mutableStateOf(false) }
    var lastFb by remember { mutableStateOf<Feedback?>(null) }
    var fbAt by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    fun finish() {
        if (finished) return
        finished = true
        val dur = session.elapsedSec()
        session.stop()
        val rec = SessionSummary.build(session.judge, req.mode, req.title, req.patternId, req.chart.bpm, dur, req.songId)
        val before = Progression.achievements(store.history.value, store.counters.value).filter { it.unlocked }.map { it.id }.toSet()
        if (rec.events > 0 && session.judge.progress() > 0.3) {
            store.addSession(rec)
            if (req.loop != null) store.bump("loops")
            if (req.settings.waitMode) store.bump("wait_sessions")
            req.songId?.let { id ->
                val s = store.songs.value.firstOrNull { it.id == id }
                if (s != null && (s.bestAccuracy ?: 0.0) < rec.accuracy) store.upsertSong(s.copy(bestAccuracy = rec.accuracy))
            }
        }
        val after = Progression.achievements(store.history.value, store.counters.value)
        val newOnes = after.filter { it.unlocked && it.id !in before }
        var out = req
        if (req.mode == "song" && nudgeMs != 0) {
            req.songId?.let { id -> SongSetup.nudge(store.songDir(id), nudgeMs) }
            out = req.copy(chart = req.chart.copy(audioOffset = req.chart.audioOffset + nudgeMs / 1000.0))
        }
        AppState.lastResult = ResultPayload(rec, session.judge.results.mapNotNull { it.deltaMs }, newOnes, out)
        nav.navigate("results") { popUpTo("game") { inclusive = true } }
    }

    LaunchedEffect(Unit) {
        runCatching { ResourcesCompat.getFont(ctx, R.font.nunito_extrabold) }.getOrNull()?.let { textPaint.typeface = it }
    }
    DisposableEffect(session) {
        if (!session.start()) error = "開唔到咪高峰（請喺設定俾錄音權限）"
        onDispose { session.stop() }
    }
    LaunchedEffect(session) {
        while (!finished) {
            withFrameNanos { }
            t = session.tick()
            val f = session.judge.feedback
            if (f != null && f.seq != lastFb?.seq) { lastFb = f; fbAt = System.currentTimeMillis() }
            if (session.state == GameSession.State.FINISHED && error == null) { finish(); break }
        }
    }

    val chart = req.chart
    val judge = session.judge
    val acc = if (judge.maxScore() > 0) {
        val judged = judge.results.count { it.grade != null }
        if (judged == 0) 100.0 else 100.0 * judge.score() / (judged * 100.0)
    } else 0.0
    val countIn = if (session.state == GameSession.State.COUNT_IN) {
        val spb = 60.0 / chart.bpm
        ceil(((req.loop?.start ?: 0.0) - t) / spb).toInt()
    } else 0
    val hud = GameHud(
        title = req.title,
        sub = "%.0f BPM · %s%s".format(chart.bpm * req.settings.tempoFactor, if (req.settings.preset == "beginner") "初學" else "中級",
            if (req.settings.waitMode) " · 等待模式" else ""),
        acc = acc, combo = judge.combo, progress = judge.progress().toFloat(),
        input = (session.inputLevel * 4).toFloat().coerceIn(0f, 1f),
        aiText = if (req.mode == "finger" && session.aiAvailable) "AI 驗證 ${session.aiConfirmed}/${session.aiChecked}" else null,
        error = error, countIn = countIn, feedback = lastFb, feedbackAgeMs = System.currentTimeMillis() - fbAt,
        waitHint = req.settings.waitMode && session.state == GameSession.State.PLAYING,
        loops = if (req.loop != null) session.loops else null, paused = paused,
    )
    GameContent(chart, judge.results, t, req.settings.tempoFactor, req.mode, hud,
        onPause = { if (paused) { session.resume(); paused = false } else { session.pause(); paused = true } },
        onLoop = { showLoop = true }, onFinish = { finish() },
        nudgeMs = if (req.mode == "song") nudgeMs else null,
        onNudge = { d -> nudgeMs = (nudgeMs + d).coerceIn(-400, 400); session.userOffset = nudgeMs / 1000.0 })

    if (showLoop) {
        val n = barCount(chart)
        var range by remember { mutableStateOf(0f..(n - 1).coerceAtLeast(1).toFloat()) }
        AlertDialog(onDismissRequest = { showLoop = false }, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
            title = { Text("🔁 A-B 循環（小節）", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text("第 ${range.start.toInt() + 1} 至 ${range.endInclusive.toInt() + 1} 小節", style = MaterialTheme.typography.titleSmall)
                    RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..(n - 1).coerceAtLeast(1).toFloat(),
                        steps = (n - 2).coerceAtLeast(0), colors = appSliderColors())
                    Hint("揀咗之後會由 A 開始，彈到 B 自動返轉頭；咁樣可以集中練難嘅轉和弦位。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = barTime(chart, range.start.toInt())
                    val b = barTime(chart, range.endInclusive.toInt() + 1) - 1e-3
                    session.stop()
                    req = req.copy(loop = a..b)
                    showLoop = false; paused = false
                    sessionKey++
                }) { Text("開始循環", color = Palette.Accent, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (req.loop != null) { session.stop(); req = req.copy(loop = null); sessionKey++ }
                    showLoop = false
                }) { Text(if (req.loop != null) "取消循環" else "取消", color = Palette.Text, style = MaterialTheme.typography.labelLarge) }
            })
    }
}

/** Everything the game screen shows for one frame (lets the layout be previewed without a live session). */
data class GameHud(
    val title: String, val sub: String, val acc: Double, val combo: Int, val progress: Float, val input: Float,
    val aiText: String? = null, val error: String? = null, val countIn: Int = 0, val feedback: Feedback? = null,
    val feedbackAgeMs: Long = Long.MAX_VALUE, val waitHint: Boolean = false, val loops: Int? = null, val paused: Boolean = false,
)

@Composable
fun GameContent(chart: Chart, results: Array<EventResult>, t: Double, tempo: Double, mode: String, h: GameHud,
                onPause: () -> Unit, onLoop: () -> Unit, onFinish: () -> Unit,
                nudgeMs: Int? = null, onNudge: (Int) -> Unit = {}) {
    val isTab = remember(chart, mode) { mode == "finger" || chart.events.any { it.type == "note" } }
    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // ---------------- HUD
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.sub, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            HudPill("準確", "%.0f%%".format(h.acc), Tone.MINT)
            Spacer(Modifier.width(6.dp))
            HudPill("Combo", "${h.combo}", if (h.combo >= 10) Tone.CORAL else Tone.SUN, bump = h.combo)
        }
        Spacer(Modifier.height(6.dp))
        LevelBar(h.progress, height = 8.dp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎤", fontSize = 12.sp); Spacer(Modifier.width(6.dp))
            LevelBar(h.input, Modifier.weight(1f), color = Palette.Mint, height = 6.dp)
            h.aiText?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
        h.error?.let { Text(it, color = Palette.Miss, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))

        // ---------------- current / next chord
        val cur = chart.chords.lastOrNull { it.start <= t + 0.05 }
        val next = chart.chords.firstOrNull { it.start > t + 0.05 }
        // big enough to read at arm's length on a music stand (64 sp for short names)
        Row(Modifier.fillMaxWidth().height(152.dp).card(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val label = cur?.label ?: "—"
                Text(label, style = MaterialTheme.typography.displayLarge, fontSize = chordFontSize(label), lineHeight = chordFontSize(label),
                    color = Palette.Accent, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (next != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下一個 ", style = MaterialTheme.typography.labelLarge, color = Palette.Muted, maxLines = 1)
                    Text(next.label, style = MaterialTheme.typography.headlineSmall, color = Palette.Accent2, maxLines = 1)
                    Text(" · ${"%.1f".format((next.start - t) / tempo)}s", style = MaterialTheme.typography.labelLarge, color = Palette.Muted, maxLines = 1)
                }
                val sec = chart.sections.lastOrNull { it.start <= t + 0.05 }
                if (sec != null) Text("段落：${sec.label}", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
            }
            cur?.label?.let { ChordLibrary.get(it) }?.let { ChordDiagram(it, Modifier.height(128.dp)) }
            Spacer(Modifier.width(8.dp))
            next?.label?.let { ChordLibrary.get(it) }?.let {
                Box(Modifier.alpha(0.6f)) { ChordDiagram(it, Modifier.height(88.dp), showFingers = false, color = Palette.Muted) }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---------------- lane / tab
        Box(Modifier.fillMaxWidth().weight(1f).card(RoundedCornerShape(26.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                if (isTab) drawTab(chart, results, t, tempo) else drawLane(chart, results, t, tempo)
            }
            // count-in
            run {
                val left = h.countIn
                if (left > 0) Box(Modifier.align(Alignment.Center).size(118.dp).clip(CircleShape).background(Brushes.SunriseV),
                    contentAlignment = Alignment.Center) {
                    AnimatedContent(left, transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith fadeOut() }, label = "count") { n ->
                        Text("$n", style = MaterialTheme.typography.displayLarge, color = Palette.Text)
                    }
                }
            }
            // feedback popup
            val f = h.feedback
            val age = h.feedbackAgeMs
            if (f != null && age < 900) {
                val tone = when (f.kind) {
                    Feedback.Kind.PERFECT -> Tone.MINT; Feedback.Kind.GOOD -> Tone.SUN; Feedback.Kind.MISS -> Tone.ROSE
                    Feedback.Kind.WRONG_CHORD, Feedback.Kind.WRONG_DIR, Feedback.Kind.WRONG_NOTE -> Tone.PEACH
                    Feedback.Kind.EXTRA, Feedback.Kind.INFO -> Tone.LILAC
                }
                val pop = 1f + 0.22f * (1f - (age.coerceAtMost(160L) / 160f))
                val fade = (1f - age / 900f).coerceIn(0.2f, 1f)
                Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).graphicsLayer { scaleX = pop; scaleY = pop; alpha = fade }
                    .clip(CircleShape).background(tone.soft).padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(f.text, style = MaterialTheme.typography.titleLarge, color = tone.deep)
                }
            }
            if (h.waitHint) Box(Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(Palette.SunSoft)
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("⏳ 等你彈啱", style = MaterialTheme.typography.labelMedium, color = Palette.SunDeep)
            }
            if (h.loops != null) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(Palette.SkySoft)
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("🔁 循環 ${h.loops}", style = MaterialTheme.typography.labelMedium, color = Palette.Accent2)
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---------------- song mode: arrows vs music
        if (nudgeMs != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton("拍子早咗", { onNudge(-20) }, Modifier.weight(1f), tone = Tone.SKY, emoji = "⏪", height = 40.dp)
                Text(when { nudgeMs > 0 -> "譜提早 ${nudgeMs}ms"; nudgeMs < 0 -> "譜推遲 ${-nudgeMs}ms"; else -> "對拍 ±0" },
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1)
                SoftButton("拍子遲咗", { onNudge(20) }, Modifier.weight(1f), tone = Tone.PEACH, emoji = "⏩", height = 40.dp)
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---------------- controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (h.paused) "繼續" else "暫停", onPause, Modifier.weight(1f), emoji = if (h.paused) "▶️" else "⏸️")
            GhostButton("A-B", onLoop, Modifier.weight(1f), emoji = "🔁")
            PillButton("完成", onFinish, Modifier.weight(1f), emoji = "🏁", height = 48.dp)
        }
    }
}

@Composable
private fun HudPill(label: String, value: String, tone: Tone, bump: Int = 0) {
    val s = remember { Animatable(1f) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(bump) { if (bump > 0 && !reduce) { s.snapTo(1.18f); s.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f)) } }
    Column(Modifier.width(78.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }.clip(RoundedCornerShape(18.dp)).background(tone.soft)
        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = tone.deep, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
    }
}

private val textPaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

private fun colorFor(r: EventResult): Color = when {
    r.grade == null -> Palette.Text
    r.grade == Grade.MISS -> Palette.Miss
    r.chordOk == false || r.dirOk == false || r.notesOk == false -> Palette.Wrong
    r.grade == Grade.PERFECT -> Palette.Perfect
    else -> Palette.Good
}

private fun softFor(r: EventResult): Color = when {
    r.grade == null -> Color.White
    r.grade == Grade.MISS -> Palette.RoseSoft
    r.chordOk == false || r.dirOk == false || r.notesOk == false -> Palette.PeachSoft
    r.grade == Grade.PERFECT -> Palette.MintSoft
    else -> Palette.SunSoft
}

/** Upcoming strums are colour-coded by direction (↓ coral, ↑ blue, ✕ lilac) so beginners can read them at a glance. */
private fun dirColor(dir: String?): Color = when (dir) { "D" -> Palette.Accent; "U" -> Palette.Accent2; else -> Palette.LilacDeep }

/** Space reserved at the top of the lane card for the feedback / loop chips (Compose overlays). */
private val OverlayStrip = 46.dp

/** Current-chord size: as big as fits next to the diagrams. */
internal fun chordFontSize(label: String) = when {
    label.length <= 3 -> 64.sp
    label.length == 4 -> 54.sp
    else -> 42.sp
}

/** Chord name pills above the lane; the current chord's pill sticks to the left edge until the next one arrives. */
private fun DrawScope.drawChordPills(chart: Chart, t: Double, hitX: Float, pxPerSec: Float, t0: Double, t1: Double, top: Float) {
    val padX = 12.dp.toPx(); val ph = 36.dp.toPx(); val edge = 10.dp.toPx()
    textPaint.textSize = 20.sp.toPx(); textPaint.textAlign = Paint.Align.LEFT
    val spans = chart.chords
    for ((k, c) in spans.withIndex()) {
        if (c.end < t0 || c.start > t1) continue
        val pw = textPaint.measureText(c.label) + padX * 2
        var x = hitX + ((c.start - t) * pxPerSec).toFloat()
        val current = c.start <= t && t < c.end
        if (current) {
            x = maxOf(x, edge)
            spans.getOrNull(k + 1)?.let { n -> x = minOf(x, hitX + ((n.start - t) * pxPerSec).toFloat() - pw - 6.dp.toPx()) }
        }
        drawRoundRect(if (current) Palette.Accent else Palette.CoralSoft, Offset(x, top), androidx.compose.ui.geometry.Size(pw, ph),
            androidx.compose.ui.geometry.CornerRadius(ph / 2))
        textPaint.color = (if (current) Color.White else Palette.Accent).toArgb()
        drawContext.canvas.nativeCanvas.drawText(c.label, x + padX, top + ph / 2 + textPaint.textSize * 0.36f, textPaint)
    }
    textPaint.textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawHitLine(hitX: Float, top: Float, bot: Float) {
    drawLine(Palette.Coral.copy(alpha = 0.22f), Offset(hitX, top), Offset(hitX, bot), strokeWidth = 14.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Palette.Accent, Offset(hitX, top), Offset(hitX, bot), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(Palette.Accent, 4.dp.toPx(), Offset(hitX, top)); drawCircle(Palette.Accent, 4.dp.toPx(), Offset(hitX, bot))
}

/** Strum lane: arrows travel right -> left to the hit line. */
private fun DrawScope.drawLane(chart: Chart, results: Array<EventResult>, t: Double, tempo: Double) {
    val w = size.width; val h = size.height
    val hitX = w * 0.2f
    val pxPerSec = (w * 0.27f / tempo).toFloat()     // ~3 s visible at normal speed
    val inset = 8.dp.toPx()
    val pillTop = OverlayStrip.toPx()
    val laneTop = pillTop + 48.dp.toPx(); val laneBot = h - 50.dp.toPx(); val cy = (laneTop + laneBot) / 2
    drawRoundRect(Palette.Bg, Offset(inset, laneTop), androidx.compose.ui.geometry.Size(w - inset * 2, laneBot - laneTop),
        androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))
    val spb = 60.0 / chart.bpm
    val t0 = t - hitX / pxPerSec; val t1 = t + (w - hitX) / pxPerSec
    // beat lines
    val beats: List<Double> = if (chart.beats.isNotEmpty()) chart.beats.filter { it in t0..t1 }
        else (floor(t0 / spb).toInt()..ceil(t1 / spb).toInt()).map { it * spb }
    val bpb = chart.beatsPerBar
    for (b in beats) {
        val x = hitX + ((b - t) * pxPerSec).toFloat()
        val idx = if (chart.beats.isNotEmpty()) chart.beats.indexOf(b) else Math.round(b / spb).toInt()
        val isBar = if (chart.downbeats.isNotEmpty()) chart.downbeats.any { kotlin.math.abs(it - b) < 0.02 } else idx % bpb == 0
        drawLine(if (isBar) Palette.Grid.copy(alpha = 0.6f) else Palette.Grid.copy(alpha = 0.25f), Offset(x, laneTop + 8.dp.toPx()),
            Offset(x, laneBot - 8.dp.toPx()), strokeWidth = if (isBar) 1.5.dp.toPx() else 1.dp.toPx())
    }
    drawChordPills(chart, t, hitX, pxPerSec, t0, t1, pillTop)
    drawHitLine(hitX, laneTop - 4.dp.toPx(), laneBot + 4.dp.toPx())
    val ah = minOf((laneBot - laneTop) * 0.58f, 128.dp.toPx())
    for ((i, e) in chart.events.withIndex()) {
        if (e.t < t0 - 0.2 || e.t > t1 + 0.2) continue
        val x = hitX + ((e.t - t) * pxPerSec).toFloat()
        val r = results[i]
        val col = if (r.grade == null) dirColor(e.dir) else colorFor(r)
        val c = if (r.grade == Grade.MISS) col.copy(alpha = 0.55f) else col
        val s = if (e.accent) 1.0f else 0.86f
        when (e.dir) {
            "D" -> drawArrow(x, cy, ah * s, true, c)
            "U" -> drawArrow(x, cy, ah * s * 0.8f, false, c)
            else -> {
                val d = 11.dp.toPx()
                drawLine(c, Offset(x - d, cy - d), Offset(x + d, cy + d), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c, Offset(x - d, cy + d), Offset(x + d, cy - d), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (r.grade != null && r.deltaMs != null && kotlin.math.abs(e.t - t) < 1.0) {
            textPaint.textSize = 11.sp.toPx(); textPaint.color = col.toArgb()
            val d = r.deltaMs!!
            drawContext.canvas.nativeCanvas.drawText(if (d < 0) "早${(-d).toInt()}" else "遲${d.toInt()}", x, laneBot + 18.dp.toPx(), textPaint)
        }
    }
    // colour-coded legend
    textPaint.textSize = 12.sp.toPx()
    val items = listOf("↓ 向下掃" to Palette.Accent, "↑ 向上掃" to Palette.Accent2, "✕ 悶音" to Palette.LilacDeep)
    val gap = 16.dp.toPx()
    val widths = items.map { textPaint.measureText(it.first) }
    var lx = (w - widths.sum() - gap * (items.size - 1)) / 2
    textPaint.textAlign = Paint.Align.LEFT
    items.forEachIndexed { k, (label, color) ->
        textPaint.color = color.toArgb()
        drawContext.canvas.nativeCanvas.drawText(label, lx, h - 12.dp.toPx(), textPaint)
        lx += widths[k] + gap
    }
    textPaint.textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawArrow(x: Float, cy: Float, len: Float, down: Boolean, col: Color) {
    val half = len / 2
    val headW = 17.dp.toPx(); val headH = minOf(20.dp.toPx(), len * 0.4f)
    val tipY = if (down) cy + half else cy - half
    val tailY = if (down) cy - half else cy + half
    val baseY = if (down) tipY - headH else tipY + headH
    drawLine(col, Offset(x, tailY), Offset(x, baseY), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
    val p = Path().apply { moveTo(x, tipY); lineTo(x - headW, baseY); lineTo(x + headW, baseY); close() }
    drawPath(p, col)
    drawPath(p, col, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))      // soft rounded corners
}

/** Fingerstyle tab: 6 lines (1st string on top), notes travel to the hit line, fret numbers on the line. */
private fun DrawScope.drawTab(chart: Chart, results: Array<EventResult>, t: Double, tempo: Double) {
    val w = size.width; val h = size.height
    val hitX = w * 0.2f
    val pxPerSec = (w * 0.3f / tempo).toFloat()
    val inset = 8.dp.toPx()
    val pillTop = OverlayStrip.toPx()
    val top = pillTop + 62.dp.toPx(); val bot = h - 58.dp.toPx()
    val gap = (bot - top) / 5f
    val pad = minOf(gap * 0.6f, 22.dp.toPx())
    fun yOf(string: Int) = top + (5 - string) * gap          // string 5 (1st, high e) on top
    drawRoundRect(Palette.Bg, Offset(inset, top - pad), androidx.compose.ui.geometry.Size(w - inset * 2, bot - top + pad * 2),
        androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))
    for (s in 0 until 6) drawLine(Palette.Grid.copy(alpha = 0.7f), Offset(inset + 22.dp.toPx(), yOf(s)), Offset(w - inset, yOf(s)),
        strokeWidth = (1f + (5 - s) * 0.25f).dp.toPx())
    textPaint.textAlign = Paint.Align.LEFT
    textPaint.textSize = 12.sp.toPx(); textPaint.color = Palette.Muted.toArgb()
    val names = listOf("E", "A", "D", "G", "B", "e")
    for (s in 0 until 6) {
        drawContext.canvas.nativeCanvas.drawText(names[s], inset + 6.dp.toPx(), yOf(s) + 4.dp.toPx(), textPaint)
    }
    textPaint.textAlign = Paint.Align.CENTER
    val spb = 60.0 / chart.bpm
    val t0 = t - hitX / pxPerSec; val t1 = t + (w - hitX) / pxPerSec
    val bpb = chart.beatsPerBar
    // bar lines on the song's real downbeats (a constant-BPM grid drifts away from the music)
    val bars: List<Double> = if (chart.downbeats.isNotEmpty()) chart.downbeats.filter { it in t0..t1 }
        else (floor(t0 / spb).toInt()..ceil(t1 / spb).toInt()).filter { it % bpb == 0 }.map { it * spb }
    for (b in bars) {
        val x = hitX + ((b - t) * pxPerSec).toFloat()
        drawLine(Palette.Grid.copy(alpha = 0.6f), Offset(x, top), Offset(x, bot), strokeWidth = 1.5.dp.toPx())
    }
    drawChordPills(chart, t, hitX, pxPerSec, t0, t1, pillTop)
    drawHitLine(hitX, top - pad, bot + pad)
    val rad = minOf(gap * 0.36f, 15.dp.toPx())
    for ((i, e) in chart.events.withIndex()) {
        if (e.t < t0 - 0.2 || e.t > t1 + 0.2) continue
        val x = hitX + ((e.t - t) * pxPerSec).toFloat()
        val r = results[i]
        val col = colorFor(r)
        for ((k, s) in e.strings.withIndex()) {
            val y = yOf(s)
            drawCircle(softFor(r), rad, Offset(x, y))
            drawCircle(col, rad, Offset(x, y), style = Stroke(2.dp.toPx()))
            textPaint.color = col.toArgb(); textPaint.textSize = rad * 1.15f
            drawContext.canvas.nativeCanvas.drawText("${e.frets.getOrElse(k) { 0 }}", x, y + rad * 0.42f, textPaint)
        }
        e.finger?.let {
            textPaint.color = (if (r.grade == null) Palette.Accent2 else col).toArgb(); textPaint.textSize = 14.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText(it, x, bot + pad + 22.dp.toPx(), textPaint)
        }
    }
}
