package com.boss.guitartrainer.ui

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
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
    if (req0 == null) { Screen("練習", { nav.popBackStack() }) { Text("冇練習內容") }; return }
    var req by remember { mutableStateOf(req0) }
    var sessionKey by remember { mutableIntStateOf(0) }
    val settings = store.settings.value
    val session = remember(sessionKey) {
        GameSession(ctx, req.chart, req.settings, store.calibration.value, req.mode, req.audio,
            extraOffsetMs = req.extraOffsetMs + if (req.mode == "song") settings.songExtraOffsetMs else 0,
            useAi = settings.useBasicPitch, micSource = settings.micSource, loop = req.loop, metronomeVolume = settings.metronomeVolume)
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
        AppState.lastResult = ResultPayload(rec, session.judge.results.mapNotNull { it.deltaMs }, newOnes, req)
        nav.navigate("results") { popUpTo("game") { inclusive = true } }
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
    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(8.dp)) {
        // ---------------- HUD
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(req.title, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 15.sp)
                val tempoBpm = chart.bpm * req.settings.tempoFactor
                Text("%.0f BPM · %s%s".format(tempoBpm, if (req.settings.preset == "beginner") "初學" else "中級",
                    if (req.settings.waitMode) " · 等待模式" else ""), color = Palette.Muted, fontSize = 12.sp)
            }
            val acc = if (judge.maxScore() > 0) {
                val judged = judge.results.count { it.grade != null }
                if (judged == 0) 100.0 else 100.0 * judge.score() / (judged * 100.0)
            } else 0.0
            StatTile("準確", "%.0f%%".format(acc), Modifier.width(76.dp))
            Spacer(Modifier.width(6.dp))
            StatTile("Combo", "${judge.combo}", Modifier.width(76.dp), color = if (judge.combo >= 10) Palette.Accent else Palette.Text)
        }
        LevelBar(judge.progress().toFloat(), Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎤", fontSize = 12.sp); Spacer(Modifier.width(4.dp))
            LevelBar((session.inputLevel * 4).toFloat().coerceIn(0f, 1f), Modifier.weight(1f), color = Palette.Accent2)
            if (req.mode == "finger" && session.aiAvailable) {
                Spacer(Modifier.width(8.dp))
                Text("AI 驗證 ${session.aiConfirmed}/${session.aiChecked}", fontSize = 11.sp, color = Palette.Muted)
            }
        }
        error?.let { Text(it, color = Palette.Miss) }

        // ---------------- current / next chord
        val cur = chart.chords.lastOrNull { it.start <= t + 0.05 }
        val next = chart.chords.firstOrNull { it.start > t + 0.05 }
        Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cur?.label ?: "—", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                if (next != null) Text("下一個：${next.label}  (${"%.1f".format((next.start - t) / req.settings.tempoFactor)}s)", color = Palette.Muted, fontSize = 13.sp)
                val sec = chart.sections.lastOrNull { it.start <= t + 0.05 }
                if (sec != null) Text("段落：${sec.label}", color = Palette.Muted, fontSize = 12.sp)
            }
            cur?.label?.let { ChordLibrary.get(it) }?.let { ChordDiagram(it, Modifier.height(105.dp)) }
            Spacer(Modifier.width(8.dp))
            next?.label?.let { ChordLibrary.get(it) }?.let { ChordDiagram(it, Modifier.height(80.dp), showFingers = false, color = Palette.Muted) }
        }

        // ---------------- lane / tab
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val tempo = req.settings.tempoFactor
            Canvas(Modifier.fillMaxSize()) {
                val tt = t
                if (req.mode == "finger" || chart.events.any { it.type == "note" }) drawTab(chart, judge.results, tt, tempo) else drawLane(chart, judge.results, tt, tempo)
            }
            // count-in
            if (session.state == GameSession.State.COUNT_IN) {
                val spb = 60.0 / chart.bpm
                val left = ceil(((req.loop?.start ?: 0.0) - t) / spb).toInt()
                if (left > 0) Text("$left", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Palette.Accent.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.Center))
            }
            // feedback popup
            val f = lastFb
            val age = System.currentTimeMillis() - fbAt
            if (f != null && age < 900) {
                val col = when (f.kind) {
                    Feedback.Kind.PERFECT -> Palette.Perfect; Feedback.Kind.GOOD -> Palette.Good; Feedback.Kind.MISS -> Palette.Miss
                    Feedback.Kind.WRONG_CHORD, Feedback.Kind.WRONG_DIR, Feedback.Kind.WRONG_NOTE -> Palette.Wrong
                    Feedback.Kind.EXTRA, Feedback.Kind.INFO -> Palette.Muted
                }
                Text(f.text, color = col.copy(alpha = (1f - age / 900f).coerceIn(0.2f, 1f)), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            if (req.settings.waitMode && session.state == GameSession.State.PLAYING) {
                Text("等待模式：彈啱先會行落去", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (req.loop != null) Text("🔁 循環 ${session.loops}", color = Palette.Accent2, modifier = Modifier.align(Alignment.TopEnd))
        }

        // ---------------- controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (paused) { session.resume(); paused = false } else { session.pause(); paused = true }
            }, modifier = Modifier.weight(1f)) { Text(if (paused) "▶ 繼續" else "⏸ 暫停") }
            OutlinedButton(onClick = { showLoop = true }, modifier = Modifier.weight(1f)) { Text("🔁 A-B") }
            Button(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text("完成") }
        }
    }

    if (showLoop) {
        val n = barCount(chart)
        var range by remember { mutableStateOf(0f..(n - 1).coerceAtLeast(1).toFloat()) }
        AlertDialog(onDismissRequest = { showLoop = false },
            title = { Text("A-B 循環（小節）") },
            text = {
                Column {
                    Text("第 ${range.start.toInt() + 1} 至 ${range.endInclusive.toInt() + 1} 小節")
                    RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..(n - 1).coerceAtLeast(1).toFloat(),
                        steps = (n - 2).coerceAtLeast(0))
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
                }) { Text("開始循環") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (req.loop != null) { session.stop(); req = req.copy(loop = null); sessionKey++ }
                    showLoop = false
                }) { Text(if (req.loop != null) "取消循環" else "取消") }
            })
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

/** Strum lane: arrows travel right -> left to the hit line. */
private fun DrawScope.drawLane(chart: Chart, results: Array<EventResult>, t: Double, tempo: Double) {
    val w = size.width; val h = size.height
    val hitX = w * 0.2f
    val pxPerSec = (w * 0.27f / tempo).toFloat()     // ~3 s visible at normal speed
    val laneTop = h * 0.18f; val laneBot = h * 0.88f; val cy = (laneTop + laneBot) / 2
    drawRect(Palette.Surface, Offset(0f, laneTop), androidx.compose.ui.geometry.Size(w, laneBot - laneTop))
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
        drawLine(if (isBar) Palette.Line else Palette.Line.copy(alpha = 0.35f), Offset(x, laneTop), Offset(x, laneBot), strokeWidth = if (isBar) 3f else 1.5f)
    }
    // chord labels
    textPaint.textSize = h * 0.075f
    for (c in chart.chords) {
        if (c.end < t0 || c.start > t1) continue
        val x = hitX + ((c.start - t) * pxPerSec).toFloat()
        textPaint.color = Palette.Accent.toArgb()
        textPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(c.label, x + 6f, laneTop - h * 0.03f, textPaint)
    }
    textPaint.textAlign = Paint.Align.CENTER
    // hit line
    drawLine(Palette.Accent, Offset(hitX, laneTop - 6f), Offset(hitX, laneBot + 6f), strokeWidth = 6f)
    val ah = (laneBot - laneTop) * 0.62f
    for ((i, e) in chart.events.withIndex()) {
        if (e.t < t0 - 0.2 || e.t > t1 + 0.2) continue
        val x = hitX + ((e.t - t) * pxPerSec).toFloat()
        val r = results[i]
        val col = colorFor(r)
        val big = e.accent
        val s = if (big) 1.0f else 0.85f
        when (e.dir) {
            "D" -> drawArrow(x, cy, ah * s, true, col)
            "U" -> drawArrow(x, cy, ah * s * 0.8f, false, col)
            else -> {
                val d = ah * 0.25f
                drawLine(col, Offset(x - d, cy - d), Offset(x + d, cy + d), strokeWidth = 8f)
                drawLine(col, Offset(x - d, cy + d), Offset(x + d, cy - d), strokeWidth = 8f)
            }
        }
        if (r.grade != null && r.deltaMs != null && kotlin.math.abs(e.t - t) < 1.0) {
            textPaint.textSize = h * 0.05f; textPaint.color = col.toArgb()
            val d = r.deltaMs!!
            drawContext.canvas.nativeCanvas.drawText(if (d < 0) "早${(-d).toInt()}" else "遲${d.toInt()}", x, laneBot + h * 0.08f, textPaint)
        }
    }
    textPaint.textSize = h * 0.045f; textPaint.color = Palette.Muted.toArgb()
    drawContext.canvas.nativeCanvas.drawText("↓ 向下掃   ↑ 向上掃   ✕ 悶音", w / 2, h - 4f, textPaint)
}

private fun DrawScope.drawArrow(x: Float, cy: Float, len: Float, down: Boolean, col: Color) {
    val half = len / 2
    val headW = len * 0.28f; val headH = len * 0.32f
    val tipY = if (down) cy + half else cy - half
    val tailY = if (down) cy - half else cy + half
    val baseY = if (down) tipY - headH else tipY + headH
    drawLine(col, Offset(x, tailY), Offset(x, baseY), strokeWidth = len * 0.12f)
    val p = Path().apply { moveTo(x, tipY); lineTo(x - headW, baseY); lineTo(x + headW, baseY); close() }
    drawPath(p, col)
}

/** Fingerstyle tab: 6 lines (1st string on top), notes travel to the hit line, fret numbers on the line. */
private fun DrawScope.drawTab(chart: Chart, results: Array<EventResult>, t: Double, tempo: Double) {
    val w = size.width; val h = size.height
    val hitX = w * 0.2f
    val pxPerSec = (w * 0.3f / tempo).toFloat()
    val top = h * 0.16f; val bot = h * 0.82f
    val gap = (bot - top) / 5f
    fun yOf(string: Int) = top + (5 - string) * gap          // string 5 (1st, high e) on top
    drawRect(Palette.Surface, Offset(0f, top - gap * 0.6f), androidx.compose.ui.geometry.Size(w, bot - top + gap * 1.2f))
    for (s in 0 until 6) drawLine(Palette.Line, Offset(0f, yOf(s)), Offset(w, yOf(s)), strokeWidth = 2f + (5 - s) * 0.0f + s * 0.4f)
    textPaint.textAlign = Paint.Align.LEFT
    textPaint.textSize = gap * 0.45f; textPaint.color = Palette.Muted.toArgb()
    val names = listOf("E", "A", "D", "G", "B", "e")
    for (s in 0 until 6) drawContext.canvas.nativeCanvas.drawText(names[s], 4f, yOf(s) + gap * 0.16f, textPaint)
    textPaint.textAlign = Paint.Align.CENTER
    val spb = 60.0 / chart.bpm
    val t0 = t - hitX / pxPerSec; val t1 = t + (w - hitX) / pxPerSec
    val bpb = chart.beatsPerBar
    for (b in floor(t0 / spb).toInt()..ceil(t1 / spb).toInt()) {
        if (b % bpb != 0) continue
        val x = hitX + ((b * spb - t) * pxPerSec).toFloat()
        drawLine(Palette.Line, Offset(x, top), Offset(x, bot), strokeWidth = 3f)
    }
    textPaint.textSize = h * 0.07f
    for (c in chart.chords) {
        if (c.end < t0 || c.start > t1) continue
        val x = hitX + ((c.start - t) * pxPerSec).toFloat()
        textPaint.color = Palette.Accent.toArgb(); textPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(c.label, x + 6f, top - gap * 0.75f, textPaint)
    }
    textPaint.textAlign = Paint.Align.CENTER
    drawLine(Palette.Accent, Offset(hitX, top - gap * 0.6f), Offset(hitX, bot + gap * 0.6f), strokeWidth = 6f)
    val rad = gap * 0.38f
    for ((i, e) in chart.events.withIndex()) {
        if (e.t < t0 - 0.2 || e.t > t1 + 0.2) continue
        val x = hitX + ((e.t - t) * pxPerSec).toFloat()
        val col = colorFor(results[i])
        for ((k, s) in e.strings.withIndex()) {
            val y = yOf(s)
            drawCircle(Palette.Bg, rad, Offset(x, y))
            drawCircle(col, rad, Offset(x, y), style = Stroke(4f))
            textPaint.color = col.toArgb(); textPaint.textSize = rad * 1.25f
            drawContext.canvas.nativeCanvas.drawText("${e.frets.getOrElse(k) { 0 }}", x, y + rad * 0.45f, textPaint)
        }
        e.finger?.let {
            textPaint.color = Palette.Accent2.toArgb(); textPaint.textSize = gap * 0.42f
            drawContext.canvas.nativeCanvas.drawText(it, x, bot + gap * 0.95f, textPaint)
        }
    }
}
