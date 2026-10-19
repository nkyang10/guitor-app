package com.boss.guitartrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.game.Tips

@Composable
fun ResultsScreen(nav: NavController) {
    val res = AppState.lastResult
    if (res == null) { Screen("結果", { nav.popBackStack() }) { Text("冇結果") }; return }
    val r = res.record
    Screen("練習結果", { nav.popBackStack("home", false) }) {
        SectionCard {
            Text("★".repeat(r.stars) + "☆".repeat(3 - r.stars), fontSize = 44.sp, color = Palette.Accent,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("%.0f%%".format(r.accuracy), fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(r.title, color = Palette.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Perfect", "${r.perfect}", Modifier.weight(1f), Palette.Perfect)
            StatTile("Good", "${r.good}", Modifier.weight(1f), Palette.Good)
            StatTile("Miss", "${r.miss}", Modifier.weight(1f), Palette.Miss)
            StatTile("最高 Combo", "${r.maxCombo}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.chordAcc?.let { StatTile("和弦", "%.0f%%".format(it), Modifier.weight(1f)) }
            r.dirAcc?.let { StatTile("上下方向", "%.0f%%".format(it), Modifier.weight(1f)) }
            r.notesAcc?.let { StatTile("指彈音準", "%.0f%%".format(it), Modifier.weight(1f)) }
            StatTile("多咗嘅掃弦", "${r.extras}", Modifier.weight(1f))
        }
        SectionCard("節奏（落手時間 vs 拍子）") {
            val mean = r.meanOffsetMs; val sd = r.sdOffsetMs
            if (mean == null) Hint("冇足夠數據") else {
                Text(when {
                    mean > 15 -> "平均遲 %.0f ms".format(mean)
                    mean < -15 -> "平均早 %.0f ms".format(-mean)
                    else -> "平均準時（%+.0f ms）".format(mean)
                } + (sd?.let { " · 穩定度 ±%.0f ms".format(it) } ?: ""), fontWeight = FontWeight.Bold)
                Histogram(res.deltas)
                Hint("中線 = 準拍；左邊 = 搶拍，右邊 = 拖拍。")
            }
        }
        if (res.newAchievements.isNotEmpty()) SectionCard("🏆 新成就") {
            res.newAchievements.forEach { Text("${it.title} — ${it.desc}", color = Palette.Accent) }
        }
        SectionCard("練習建議") { Tips.forSession(r).forEach { Text("• $it") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { AppState.pendingGame = res.request; nav.navigate("game") { popUpTo("results") { inclusive = true } } },
                modifier = Modifier.weight(1f)) { Text("再嚟一次") }
            OutlinedButton(onClick = {
                val s = res.request.settings
                AppState.pendingGame = res.request.copy(settings = s.copy(tempoPercent = (s.tempoPercent + 5).coerceAtMost(150)))
                nav.navigate("game") { popUpTo("results") { inclusive = true } }
            }, modifier = Modifier.weight(1f)) { Text("加快 5%") }
        }
        OutlinedButton(onClick = { nav.popBackStack("home", false) }, modifier = Modifier.fillMaxWidth()) { Text("返主頁") }
    }
}

@Composable
fun Histogram(deltas: List<Double>) {
    val bins = IntArray(13)            // -195..+195 ms in 30 ms bins
    for (d in deltas) bins[((d + 195) / 30).toInt().coerceIn(0, 12)]++
    val mx = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val bw = size.width / bins.size
        val p = android.graphics.Paint().apply { color = Palette.Muted.toArgb(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in bins.indices) {
            val hgt = (size.height - 30) * bins[i] / mx
            val c = when (kotlin.math.abs(i - 6)) { 0, 1 -> Palette.Perfect; 2, 3 -> Palette.Good; else -> Palette.Miss }
            drawRect(c, Offset(i * bw + 3, size.height - 30 - hgt), Size(bw - 6, hgt))
        }
        drawLine(Palette.Text, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height - 30), strokeWidth = 2f)
        drawContext.canvas.nativeCanvas.drawText("早", bw, size.height - 4, p)
        drawContext.canvas.nativeCanvas.drawText("0", size.width / 2, size.height - 4, p)
        drawContext.canvas.nativeCanvas.drawText("遲", size.width - bw, size.height - 4, p)
    }
}
