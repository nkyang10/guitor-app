package com.boss.guitartrainer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.LiveAnalyzer
import com.boss.guitartrainer.audio.MicSession
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.dsp.ChordAnalyzer
import com.boss.guitartrainer.dsp.Notes
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.ChordShape
import com.boss.guitartrainer.music.Tunings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.min

/** Chord box diagram. [marks] per string: null = no mark, true = ok, false = missing. [color] = finger dots. */
@Composable
fun ChordDiagram(shape: ChordShape, modifier: Modifier = Modifier, showFingers: Boolean = true, marks: List<Boolean?>? = null,
                 color: Color = Palette.Accent) {
    Canvas(modifier.aspectRatio(0.82f).semantics { contentDescription = "${shape.name} 和弦指法圖" }) { drawChord(shape, showFingers, marks, color) }
}

fun DrawScope.drawChord(shape: ChordShape, showFingers: Boolean, marks: List<Boolean?>?, color: Color) {
    val w = size.width; val h = size.height
    val top = h * 0.2f; val bottom = h * 0.95f; val left = w * 0.16f; val right = w * 0.9f
    val nFrets = 5
    val maxF = shape.maxFret
    val startFret = if (maxF <= 5) 1 else shape.minFret
    val sx = (right - left) / 5f
    val fy = (bottom - top) / nFrets
    val unit = min(sx, fy)
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    // warm fretboard
    drawRoundRect(Palette.PeachSoft, Offset(left - sx * 0.35f, top), Size(right - left + sx * 0.7f, bottom - top), CornerRadius(unit * 0.25f))
    for (f in 0..nFrets) {
        val y = top + f * fy
        val nut = f == 0 && startFret == 1
        drawLine(if (nut) Palette.Text else Palette.Grid, Offset(left, y), Offset(right, y), strokeWidth = if (nut) unit * 0.16f else unit * 0.045f,
            cap = StrokeCap.Round)
    }
    for (s in 0 until 6) {
        val x = left + s * sx
        val mc = marks?.getOrNull(s)
        val c = when (mc) { true -> Palette.MintDeep; false -> Palette.RoseDeep; null -> Palette.Grid }
        drawLine(c, Offset(x, top), Offset(x, bottom), strokeWidth = if (mc != null) unit * 0.12f else unit * 0.05f + s * unit * 0.004f)
    }
    if (startFret > 1) {
        paint.color = Palette.Muted.toArgb(); paint.textSize = fy * 0.42f
        drawContext.canvas.nativeCanvas.drawText("${startFret}fr", left - sx * 0.95f, top + fy * 0.62f, paint)
    }
    if (shape.barre > 0) {
        val y = top + (shape.barre - startFret + 0.5f) * fy
        val strings = shape.frets.indices.filter { shape.frets[it] == shape.barre }
        if (strings.size >= 2) {
            val x0 = left + strings.first() * sx; val x1 = left + strings.last() * sx
            drawRoundRect(color, Offset(x0 - sx * 0.32f, y - fy * 0.24f), Size(x1 - x0 + sx * 0.64f, fy * 0.48f), CornerRadius(fy * 0.24f))
        }
    }
    val r = unit * 0.37f
    for (s in 0 until 6) {
        val f = shape.frets[s]
        val x = left + s * sx
        if (f < 0) {
            paint.color = Palette.RoseDeep.toArgb(); paint.textSize = r * 1.5f
            drawContext.canvas.nativeCanvas.drawText("✕", x, top - fy * 0.22f, paint)
        } else if (f == 0) {
            drawCircle(Palette.Text, r * 0.62f, Offset(x, top - fy * 0.4f), style = Stroke(unit * 0.07f))
        } else {
            val y = top + (f - startFret + 0.5f) * fy
            drawCircle(color, r, Offset(x, y))
            val fing = shape.fingers.getOrNull(s) ?: 0
            if (showFingers && fing > 0) {
                paint.color = android.graphics.Color.WHITE; paint.textSize = r * 1.25f
                drawContext.canvas.nativeCanvas.drawText("$fing", x, y + r * 0.44f, paint)
            }
        }
    }
}

private val LEVELS = listOf("1" to "🌱 初學", "2" to "🌿 進階", "3" to "🌳 封閉／挑戰")

@Composable
fun ChordLibraryScreen(nav: NavController) {
    var level by rememberSaveable { mutableStateOf("1") }
    val daily by Store.get(LocalContext.current).daily.collectAsState()
    ChordLibraryContent(level, { level = it }, passed = daily.keys.filter { it.startsWith("chord_") }.map { it.removePrefix("chord_") }.toSet(),
        onBack = { nav.popBackStack() }) { nav.navigate("chord/${android.net.Uri.encode(it)}") }
}

@Composable
fun ChordLibraryContent(level: String, onLevel: (String) -> Unit, passed: Set<String>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Screen("和弦庫", onBack, scroll = false, subtitle = "撳一個和弦，App 會聽你彈啱未 👂") {
        ChipRow(LEVELS, level, onLevel)
        val list = ChordLibrary.byLevel(level.toInt())
        val tone = levelTone(level.toInt())
        LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp)) {
            items(list.size) { i ->
                val c = list[i]
                val ok = c.name in passed
                Column(Modifier.card(RoundedCornerShape(22.dp), onClick = { onOpen(c.name) }).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = tone.deep, modifier = Modifier.weight(1f), maxLines = 1)
                        if (ok) Box(Modifier.size(20.dp).clip(CircleShape).background(Palette.MintDeep), contentAlignment = Alignment.Center) {
                            Text("✓", fontSize = 11.sp, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    ChordDiagram(c, Modifier.fillMaxWidth(), showFingers = false)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Hint("✓ = 你已經彈過關嘅和弦。" + if (level == "3") "封閉和弦要時間練，手指痛就休息吓 🌿" else "")
            }
        }
    }
}

data class ChordCheck(val sim: Double, val marks: List<Boolean?>, val n: Int)

/** Live chord check: strum the chord, see similarity and which strings are not ringing. */
@Composable
fun ChordDetailScreen(nav: NavController, name: String) {
    val ctx = LocalContext.current
    val shape = ChordLibrary.get(name)
    if (shape == null) { Screen("和弦", { nav.popBackStack() }) { EmptyHint("🤔", "搵唔到和弦 $name") }; return }
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val tuning = Tunings.byId(settings.tuningId)
    val midis = shape.midis(tuning)
    val state = remember { MutableStateFlow<ChordCheck?>(null) }
    val check by state.collectAsState()
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val mic = remember { MicSession(ctx, settings.micSource) }
    var count by remember { mutableIntStateOf(0) }
    val analyzer = remember {
        LiveAnalyzer(mic.engine, object : LiveAnalyzer.Sink {
            override fun onOnset(id: Long, sample: Long, ratio: Double, weak: Boolean) {}
            override fun onAnalysis(id: Long, sample: Long, a: LiveAnalyzer.Analysis) {
                if (a.weak) return
                val sim = a.chord.similarity(midis)
                val marks = (0 until 6).map { s ->
                    val m = shape.midiOn(s, tuning) ?: return@map null
                    a.chord.presence(m) >= ChordAnalyzer.PRESENCE_DB
                }
                count++
                state.value = ChordCheck(sim, marks, count)
            }
        })
    }
    DisposableEffect(Unit) { onDispose { mic.stop() } }
    val passed = (check?.sim ?: 0.0) >= ChordAnalyzer.SIM_THRESHOLD
    LaunchedEffect(passed) { if (passed) store.markToday("chord_${shape.name}") }
    ChordDetailContent(shape, midis.map { Notes.name(it) }, check, listening, error, onBack = { nav.popBackStack() },
        onListen = { if (mic.start(analyzer)) { listening = true; error = null } else error = "開唔到咪高峰（請俾錄音權限）" },
        onStop = { mic.stop(); listening = false })
}

@Composable
fun ChordDetailContent(shape: ChordShape, notes: List<String>, check: ChordCheck?, listening: Boolean, error: String?,
                       onBack: () -> Unit, onListen: () -> Unit, onStop: () -> Unit) {
    val tone = levelTone(shape.level)
    Screen("和弦：${shape.name}", onBack, subtitle = levelLabel(shape.level) + "和弦") {
        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChordDiagram(shape, Modifier.width(150.dp), marks = check?.marks)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(shape.name, style = MaterialTheme.typography.displaySmall, color = tone.deep)
                Badge(levelLabel(shape.level), levelColor(shape.level))
                Text("組成音", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    notes.distinctBy { it.trimEnd { c -> c.isDigit() } }.take(4).forEach { n ->
                        Box(Modifier.clip(CircleShape).background(tone.soft).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(n.trimEnd { it.isDigit() }, style = MaterialTheme.typography.labelMedium, color = tone.deep)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1" to "食指", "2" to "中指", "3" to "無名指", "4" to "尾指").forEach { (n, f) ->
                Row(Modifier.weight(1f).clip(CircleShape).background(Palette.Surface).padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                        Text(n, fontSize = 11.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(f, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        if (shape.barre > 0) Hint("第 ${shape.barre} 格封閉：食指要壓平、靠近琴格。", Palette.SunDeep)

        SectionCard("和弦檢查", icon = "👂") {
            Hint("撳「開始聽」，然後慢慢掃一下（全部弦）。App 會比較聽到嘅音，再話你知邊條弦好似冇聲。")
            if (!listening) PillButton("開始聽", onListen, Modifier.fillMaxWidth(), emoji = "🎤")
            else SoftButton("停止", onStop, Modifier.fillMaxWidth(), Tone.ROSE, emoji = "⏹️", height = 52.dp)
            error?.let { Text(it, color = Palette.Miss, style = MaterialTheme.typography.bodyMedium) }
            if (listening && check == null) Hint("聽緊… 掃一下啦 🎶", Palette.Accent2)
            if (check != null) AnimatedContent(check.n, transitionSpec = {
                (fadeIn() + scaleIn(spring(dampingRatio = 0.5f), initialScale = 0.85f)) togetherWith fadeOut()
            }, label = "check") { _ -> CheckResult(shape, check) }
        }
        SectionCard("練習小貼士", icon = "🎶", tone = Tone.MINT) {
            Hint("• 手指企直，用指尖按，貼近琴格（唔係格中間）。", Palette.Text)
            Hint("• 逐條弦撥一次聽下有冇悶音，再成個和弦掃。", Palette.Text)
            Hint("• 古典結他琴頸闊：拇指放喺琴頸後面中間，手腕微微向前。", Palette.Text)
        }
    }
}

@Composable
private fun CheckResult(shape: ChordShape, c: ChordCheck) {
    val thr = ChordAnalyzer.SIM_THRESHOLD
    val ok = c.sim >= thr
    val t = if (ok) Tone.MINT else Tone.ROSE
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(t.soft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "🎉" else "🤏", fontSize = 28.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ok) "聽落係 ${shape.name}！" else "未太似 ${shape.name}", style = MaterialTheme.typography.titleLarge, color = t.deep)
                Text("相似度 %.2f（合格線 %.2f）· 第 %d 次".format(c.sim, thr, c.n), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
        LevelBar((c.sim / 1.0).toFloat(), color = if (ok) Palette.Mint else Palette.Rose, track = Color.White)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in 0 until 6) {
                val m = c.marks.getOrNull(s)
                val bg = when (m) { true -> Palette.MintDeep; false -> Palette.RoseDeep; null -> Color.White }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        Text(when (m) { true -> "✓"; false -> "!"; null -> "✕" }, style = MaterialTheme.typography.labelLarge,
                            color = if (m == null) Palette.Muted else Color.White)
                    }
                    Text("${6 - s}弦", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
            }
        }
        val missing = c.marks.withIndex().filter { it.value == false }.map { "${6 - it.index}弦" }
        Text(when {
            missing.isNotEmpty() -> "可能冇聲／被悶住：${missing.joinToString("、")}。檢查下手指有冇掂到隔離條弦。"
            ok -> "全部弦都響到，好正 👍"
            else -> "慢慢再掃一次，每隻手指都要按實。"
        }, style = MaterialTheme.typography.bodyMedium, color = Palette.Text, textAlign = TextAlign.Start)
    }
}
