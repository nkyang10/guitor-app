package com.boss.guitartrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.AudioEngine
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

/** Chord box diagram. [marks] per string: null = no mark, true = ok, false = missing. */
@Composable
fun ChordDiagram(shape: ChordShape, modifier: Modifier = Modifier, showFingers: Boolean = true, marks: List<Boolean?>? = null,
                 color: Color = Palette.Text) {
    Canvas(modifier.aspectRatio(0.82f)) { drawChord(shape, showFingers, marks, color) }
}

fun DrawScope.drawChord(shape: ChordShape, showFingers: Boolean, marks: List<Boolean?>?, color: Color) {
    val w = size.width; val h = size.height
    val top = h * 0.2f; val bottom = h * 0.95f; val left = w * 0.16f; val right = w * 0.9f
    val nFrets = 5
    val maxF = shape.maxFret
    val startFret = if (maxF <= 5) 1 else shape.minFret
    val sx = (right - left) / 5f
    val fy = (bottom - top) / nFrets
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
    // nut / frets
    for (f in 0..nFrets) {
        val y = top + f * fy
        drawLine(Palette.Line, Offset(left, y), Offset(right, y), strokeWidth = if (f == 0 && startFret == 1) 7f else 2f)
    }
    for (s in 0 until 6) {
        val x = left + s * sx
        val mc = marks?.getOrNull(s)
        val c = when (mc) { true -> Palette.Perfect; false -> Palette.Miss; null -> Palette.Line }
        drawLine(c, Offset(x, top), Offset(x, bottom), strokeWidth = if (mc != null) 5f else 2.5f)
    }
    if (startFret > 1) {
        paint.color = Palette.Muted.toArgb(); paint.textSize = fy * 0.45f
        drawContext.canvas.nativeCanvas.drawText("${startFret}", left - sx * 0.55f, top + fy * 0.65f, paint)
    }
    // barre
    if (shape.barre > 0) {
        val y = top + (shape.barre - startFret + 0.5f) * fy
        val strings = shape.frets.indices.filter { shape.frets[it] == shape.barre }
        if (strings.size >= 2) {
            val x0 = left + strings.first() * sx; val x1 = left + strings.last() * sx
            drawRoundRect(color, Offset(x0 - sx * 0.3f, y - fy * 0.22f), Size(x1 - x0 + sx * 0.6f, fy * 0.44f), CornerRadius(fy * 0.22f))
        }
    }
    val r = min(sx, fy) * 0.36f
    for (s in 0 until 6) {
        val f = shape.frets[s]
        val x = left + s * sx
        paint.textSize = r * 1.6f
        if (f < 0) {
            paint.color = Palette.Miss.toArgb()
            drawContext.canvas.nativeCanvas.drawText("✕", x, top - fy * 0.25f, paint)
        } else if (f == 0) {
            drawCircle(color, r * 0.7f, Offset(x, top - fy * 0.42f), style = Stroke(3f))
        } else {
            val y = top + (f - startFret + 0.5f) * fy
            drawCircle(color, r, Offset(x, y))
            val fing = shape.fingers.getOrNull(s) ?: 0
            if (showFingers && fing > 0) {
                paint.color = Palette.Bg.toArgb(); paint.textSize = r * 1.3f; paint.isFakeBoldText = true
                drawContext.canvas.nativeCanvas.drawText("$fing", x, y + r * 0.45f, paint)
                paint.isFakeBoldText = false
            }
        }
    }
}

@Composable
fun ChordLibraryScreen(nav: NavController) {
    var level by remember { mutableStateOf("1") }
    Screen("和弦庫", { nav.popBackStack() }, scroll = false) {
        ChipRow(listOf("1" to "初學", "2" to "進階", "3" to "封閉/挑戰"), level) { level = it }
        val list = ChordLibrary.byLevel(level.toInt())
        LazyVerticalGrid(GridCells.Adaptive(96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list.size) { i ->
                val c = list[i]
                SectionCard(onClick = { nav.navigate("chord/${android.net.Uri.encode(c.name)}") }) {
                    Text(c.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    ChordDiagram(c, Modifier.fillMaxWidth(), showFingers = false)
                }
            }
        }
    }
}

/** Live chord check: strum the chord, see similarity and which strings are not ringing. */
@Composable
fun ChordDetailScreen(nav: NavController, name: String) {
    val ctx = LocalContext.current
    val shape = ChordLibrary.get(name) ?: return Text("搵唔到和弦 $name")
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val tuning = Tunings.byId(settings.tuningId)
    val midis = shape.midis(tuning)
    data class Check(val sim: Double, val marks: List<Boolean?>, val n: Int)
    val state = remember { MutableStateFlow<Check?>(null) }
    val check by state.collectAsState()
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val mic = remember { MicSession(ctx, settings.micSource) }
    var count by remember { mutableStateOf(0) }
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
                state.value = Check(sim, marks, count)
            }
        })
    }
    DisposableEffect(Unit) { onDispose { mic.stop() } }
    Screen("和弦：${shape.name}", { nav.popBackStack() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChordDiagram(shape, Modifier.width(170.dp), marks = check?.marks)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(levelLabel(shape.level), levelColor(shape.level))
                Text("組成音：" + midis.joinToString(" ") { Notes.name(it) }, fontSize = 13.sp, color = Palette.Muted)
                Text("指法數字：1 食指 2 中指 3 無名指 4 尾指", fontSize = 12.sp, color = Palette.Muted)
                if (shape.barre > 0) Text("第 ${shape.barre} 格封閉：食指要壓平、靠近琴格。", fontSize = 12.sp, color = Palette.Good)
            }
        }
        SectionCard("和弦檢查") {
            Hint("撳「開始聽」，然後慢慢掃一下（全部弦）。App 會比較聽到嘅音同呢個和弦，再標示邊條弦好似無聲。")
            if (!listening) Button(onClick = {
                if (mic.start(analyzer)) { listening = true; error = null } else error = "開唔到咪高峰（請俾錄音權限）"
            }) { Text("開始聽") } else OutlinedButton(onClick = { mic.stop(); listening = false }) { Text("停止") }
            error?.let { Text(it, color = Palette.Miss) }
            val c = check
            if (c != null) {
                val thr = ChordAnalyzer.SIM_THRESHOLD
                val ok = c.sim >= thr
                Text(if (ok) "✓ 聽落係 ${shape.name}" else "✗ 唔太似 ${shape.name}", color = if (ok) Palette.Perfect else Palette.Miss,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("相似度 %.2f（合格線 %.2f）· 第 %d 次".format(c.sim, thr, c.n), color = Palette.Muted, fontSize = 13.sp)
                val missing = c.marks.withIndex().filter { it.value == false }.map { "${6 - it.index}弦" }
                if (missing.isNotEmpty()) Text("可能無聲／被悶住：${missing.joinToString("、")}（檢查手指有冇掂到隔離弦）", color = Palette.Wrong)
                else if (ok) Text("全部弦都響到 👍", color = Palette.Perfect)
            }
        }
        SectionCard("練習小貼士") {
            Hint("• 手指企直，用指尖按，貼近琴格（唔係格中間）。")
            Hint("• 逐條弦撥一次聽下有冇悶音，再成個和弦掃。")
            Hint("• 古典結他弦距闊、琴頸闊：拇指放喺琴頸後面中間，手腕微微向前。")
        }
    }
}
