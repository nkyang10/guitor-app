package com.boss.guitartrainer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Achievement
import com.boss.guitartrainer.game.Progression
import com.boss.guitartrainer.game.Tips
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun ResultsScreen(nav: NavController) {
    val res = AppState.lastResult
    if (res == null) { Screen("結果", { nav.popBackStack() }) { EmptyHint("🤔", "冇結果") }; return }
    val store = Store.get(LocalContext.current)
    val saved = remember(res) { store.history.value.any { it.id == res.record.id } }
    ResultsContent(res.record, res.deltas, res.newAchievements, saved,
        onBack = { nav.popBackStack() },
        onAgain = { AppState.pendingGame = res.request; nav.navigate("game") { popUpTo("results") { inclusive = true } } },
        onFaster = {
            val s = res.request.settings
            AppState.pendingGame = res.request.copy(settings = s.copy(tempoPercent = (s.tempoPercent + 5).coerceAtMost(150)))
            nav.navigate("game") { popUpTo("results") { inclusive = true } }
        },
        onHome = { nav.popBackStack("home", false) })
}

private data class Cheer(val script: String, val zh: String, val brush: Brush)

private fun cheer(stars: Int) = when (stars) {
    3 -> Cheer("amazing!", "超正！完美演出 🎉", Brush.linearGradient(listOf(Color(0xFFFFD58A), Color(0xFFFFA98F), Color(0xFFFF9DB4))))
    2 -> Cheer("great job!", "好叻！再穩少少就 3 粒星 ✨", Brush.linearGradient(listOf(Color(0xFFFFD9A0), Color(0xFFA9E5CB), Color(0xFF9FD3F5))))
    1 -> Cheer("nice try!", "有進步呀！慢慢嚟 🌱", Brushes.Dreamy)
    else -> Cheer("keep going!", "唔緊要，調慢速度再試吓 💪", Brush.linearGradient(listOf(Color(0xFFFFE3CF), Color(0xFFFFD0DA), Color(0xFFE4DAFF))))
}

@Composable
fun ResultsContent(r: SessionRecord, deltas: List<Double>, newAchievements: List<Achievement>, saved: Boolean,
                   onBack: () -> Unit, onAgain: () -> Unit, onFaster: () -> Unit, onHome: () -> Unit) {
    val c = cheer(r.stars)
    Screen("練習結果", onBack) {
        Box(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp), brush = c.brush)) {
            if (r.stars >= 2) Confetti(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.script, fontFamily = ScriptFont, fontSize = 36.sp, color = Color.White,
                    style = TextStyle(shadow = Shadow(Color(0x66B8453B), Offset(0f, 3f), 10f)))
                Text(c.zh, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = "${r.stars} 粒星" }) {
                    for (i in 0 until 3) PopStar(i, i < r.stars)
                }
                Spacer(Modifier.height(10.dp))
                Text("%.0f%%".format(r.accuracy), style = MaterialTheme.typography.displayMedium)
                Text(r.title, style = MaterialTheme.typography.bodySmall, color = Palette.Text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (saved) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("+${Progression.xpOf(r)} XP 🌟", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Perfect", "${r.perfect}", Modifier.weight(1f), Palette.MintDeep, Tone.MINT)
            StatTile("Good", "${r.good}", Modifier.weight(1f), Palette.SunDeep, Tone.SUN)
            StatTile("Miss", "${r.miss}", Modifier.weight(1f), Palette.RoseDeep, Tone.ROSE)
            StatTile("最高 Combo", "${r.maxCombo}", Modifier.weight(1f), Palette.LilacDeep, Tone.LILAC)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.chordAcc?.let { StatTile("和弦", "%.0f%%".format(it), Modifier.weight(1f)) }
            r.dirAcc?.let { StatTile("上下方向", "%.0f%%".format(it), Modifier.weight(1f)) }
            r.notesAcc?.let { StatTile("指彈音準", "%.0f%%".format(it), Modifier.weight(1f)) }
            StatTile("多咗嘅掃弦", "${r.extras}", Modifier.weight(1f))
        }
        SectionCard("節奏（落手時間 vs 拍子）", icon = "🥁") {
            val mean = r.meanOffsetMs; val sd = r.sdOffsetMs
            if (mean == null) Hint("冇足夠數據") else {
                Text(when {
                    mean > 15 -> "平均遲 %.0f ms".format(mean)
                    mean < -15 -> "平均早 %.0f ms".format(-mean)
                    else -> "平均準時（%+.0f ms）👌".format(mean)
                } + (sd?.let { " · 穩定度 ±%.0f ms".format(it) } ?: ""), style = MaterialTheme.typography.titleSmall)
                Histogram(deltas)
                Hint("中線 = 準拍；左邊 = 搶拍，右邊 = 拖拍。")
            }
        }
        if (newAchievements.isNotEmpty()) SectionCard("新成就解鎖！", icon = "🏅", tone = Tone.SUN) {
            newAchievements.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Text(achEmoji(a.id), fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(a.title, style = MaterialTheme.typography.titleSmall, color = Palette.SunDeep)
                        Text(a.desc, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
                    }
                }
            }
        }
        SectionCard("練習建議", icon = "💡", tone = Tone.LILAC) {
            Tips.forSession(r).forEach { t ->
                Row { Text("•", style = MaterialTheme.typography.bodyMedium, color = Palette.LilacDeep); Spacer(Modifier.width(8.dp))
                    Text(t, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        PillButton("再嚟一次", onAgain, Modifier.fillMaxWidth(), emoji = "🔁")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("加快 5%", onFaster, Modifier.weight(1f), Tone.SKY, emoji = "🚀")
            GhostButton("返主頁", onHome, Modifier.weight(1f), emoji = "🏠")
        }
    }
}

/** A star in a white bubble that pops in one after another. */
@Composable
private fun PopStar(i: Int, filled: Boolean) {
    val reduce = rememberReduceMotion()
    val s = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) { delay(200L + i * 220L); s.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 320f)) } }
    Box(Modifier.size(58.dp).graphicsLayer { scaleX = s.value; scaleY = s.value; rotationZ = (1f - s.value) * -40f }
        .clip(CircleShape).background(Color.White.copy(alpha = if (filled) 0.95f else 0.55f)), contentAlignment = Alignment.Center) {
        Text("★", fontSize = 34.sp, color = if (filled) Color(0xFFFFB627) else Palette.Line)
    }
}

/** Pastel confetti falling once (static sprinkles when animations are off). */
@Composable
internal fun Confetti(modifier: Modifier) {
    val reduce = rememberReduceMotion()
    val p = remember { Animatable(if (reduce) 0.42f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, tween(2600, easing = LinearEasing)) }
    val pieces = remember {
        val rnd = Random(7)
        val cols = listOf(Palette.Coral, Palette.Sun, Palette.Mint, Palette.Sky, Palette.Lilac, Palette.Rose, Color.White)
        List(34) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.9f, 0.55f + rnd.nextFloat() * 0.6f, rnd.nextFloat() * 360f, it % cols.size.toFloat(), rnd.nextFloat()) }
            .map { it to cols[it[4].toInt()] }
    }
    if (!reduce && p.value >= 1f) return
    Canvas(modifier) {
        val t = p.value
        for ((f, col) in pieces) {
            val x = f[0] * size.width + kotlin.math.sin((t * 6 + f[5] * 6).toDouble()).toFloat() * 10f
            val y = (f[1] - 0.35f + t * f[2] * 1.4f) * size.height
            if (y < -20f || y > size.height + 20f) continue
            val a = if (t > 0.8f) (1f - t) / 0.2f else 1f
            rotate(f[3] + t * 360f * f[2], Offset(x, y)) {
                if (f[5] > 0.5f) drawRoundRect(col.copy(alpha = 0.9f * a), Offset(x - 5f, y - 9f), Size(10f, 18f), CornerRadius(4f))
                else drawCircle(col.copy(alpha = 0.9f * a), 6f, Offset(x, y))
            }
        }
    }
}

@Composable
fun Histogram(deltas: List<Double>) {
    val bins = IntArray(13)            // -195..+195 ms in 30 ms bins
    for (d in deltas) bins[((d + 195) / 30).toInt().coerceIn(0, 12)]++
    val mx = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(116.dp)) {
        val bw = size.width / bins.size
        val base = size.height - 32
        val p = android.graphics.Paint().apply { isAntiAlias = true; color = Palette.Muted.toArgb(); textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in bins.indices) {
            val hgt = (base - 6) * bins[i] / mx
            val c = when (kotlin.math.abs(i - 6)) { 0, 1 -> Palette.Mint; 2, 3 -> Palette.Sun; else -> Palette.Rose }
            drawRoundRect(c.copy(alpha = 0.22f), Offset(i * bw + 4, 6f), Size(bw - 8, base - 6), CornerRadius(10f))
            if (hgt > 0) drawRoundRect(c, Offset(i * bw + 4, base - hgt), Size(bw - 8, hgt), CornerRadius(10f))
        }
        drawLine(Palette.Text.copy(alpha = 0.6f), Offset(size.width / 2, 0f), Offset(size.width / 2, base), strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f)))
        drawContext.canvas.nativeCanvas.drawText("早", bw, size.height - 4, p)
        drawContext.canvas.nativeCanvas.drawText("準", size.width / 2, size.height - 4, p)
        drawContext.canvas.nativeCanvas.drawText("遲", size.width - bw, size.height - 4, p)
    }
}
