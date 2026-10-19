package com.boss.guitartrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Progression
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val hist by store.history.collectAsState()
    val counters by store.counters.collectAsState()
    var tab by remember { mutableStateOf("stats") }
    val xp = Progression.xp(hist)
    val lvl = Progression.level(xp)
    Screen("進度同成就", { nav.popBackStack() }) {
        ChipRow(listOf("stats" to "統計", "ach" to "成就", "hist" to "紀錄"), tab) { tab = it }
        when (tab) {
            "stats" -> {
                SectionCard {
                    Text("Lv.$lvl", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                    val a = Progression.xpForLevel(lvl); val b = Progression.xpForLevel(lvl + 1)
                    LevelBar(((xp - a).toFloat() / (b - a).coerceAtLeast(1)))
                    Hint("XP $xp · 下一級 $b")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("連續日數", "${Progression.streakDays(hist)}", Modifier.weight(1f), Palette.Accent)
                    StatTile("最長連續", "${Progression.bestStreak(hist)}", Modifier.weight(1f))
                    StatTile("練習次數", "${hist.size}", Modifier.weight(1f))
                    StatTile("總分鐘", "${(hist.sumOf { it.durationSec } / 60).toInt()}", Modifier.weight(1f))
                }
                SectionCard("準確度走勢（最近 30 次）") {
                    val pts = hist.takeLast(30).map { it.accuracy }
                    if (pts.size < 2) Hint("練多幾次就會見到走勢圖。") else TrendChart(pts)
                }
                SectionCard("每種練習最佳成績") {
                    for ((mode, label) in listOf("strum" to "掃弦", "finger" to "指彈", "song" to "歌曲")) {
                        val best = hist.filter { it.mode == mode }.maxByOrNull { it.accuracy }
                        KeyValue(label, best?.let { "%.0f%% ★%d".format(it.accuracy, it.stars) } ?: "—")
                    }
                }
                val missed = HashMap<String, Int>()
                hist.takeLast(20).forEach { r -> r.missedChords.forEach { (k, v) -> missed[k] = (missed[k] ?: 0) + v } }
                if (missed.isNotEmpty()) SectionCard("最近最常錯嘅和弦") {
                    missed.entries.sortedByDescending { it.value }.take(5).forEach { (c, n) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("$c  ×$n"); TextButton(onClick = { nav.navigate("chord/${android.net.Uri.encode(c)}") }) { Text("去練") }
                        }
                    }
                }
                val tempo = hist.filter { it.mode == "strum" && it.stars >= 2 }.maxByOrNull { it.bpm * it.tempoPercent / 100 }
                if (tempo != null) SectionCard("速度紀錄") { Text("2 星以上最快：%.0f BPM（%s）".format(tempo.bpm * tempo.tempoPercent / 100, tempo.title)) }
            }
            "ach" -> {
                val list = Progression.achievements(hist, counters)
                Hint("已解鎖 ${list.count { it.unlocked }}/${list.size}")
                list.forEach { a ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (a.unlocked) "🏆" else "🔒", fontSize = 26.sp)
                            Column(Modifier.weight(1f).padding8()) {
                                Text(a.title, fontWeight = FontWeight.Bold, color = if (a.unlocked) Palette.Accent else Palette.Text)
                                Text(a.desc, color = Palette.Muted, fontSize = 13.sp)
                            }
                            if (!a.unlocked && a.progress.isNotBlank()) Text(a.progress, color = Palette.Muted)
                        }
                    }
                }
            }
            else -> {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                if (hist.isEmpty()) Hint("未有紀錄")
                hist.reversed().take(100).forEach { r ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("${fmt.format(Date(r.at))} · ${modeLabel(r.mode)} · %.0f BPM×%d%% · %s".format(r.bpm, r.tempoPercent,
                                    if (r.preset == "beginner") "初學" else "中級"), color = Palette.Muted, fontSize = 12.sp)
                            }
                            Text("%.0f%%".format(r.accuracy), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { store.deleteSession(r.id) }) { Text("刪", color = Palette.Miss) }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.padding8() = this.padding(horizontal = 10.dp)

fun modeLabel(m: String) = when (m) { "strum" -> "掃弦"; "finger" -> "指彈"; "song" -> "歌曲"; else -> m }

@Composable
fun TrendChart(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val n = values.size
        val dx = size.width / (n - 1).coerceAtLeast(1)
        fun y(v: Double) = (size.height * (1 - v / 100.0)).toFloat()
        for (g in listOf(50.0, 75.0, 90.0)) drawLine(Palette.Line.copy(alpha = 0.5f), Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1.5f)
        val p = Path()
        values.forEachIndexed { i, v -> if (i == 0) p.moveTo(0f, y(v)) else p.lineTo(i * dx, y(v)) }
        drawPath(p, Palette.Accent, style = Stroke(5f))
        values.forEachIndexed { i, v -> drawCircle(Palette.Accent, 6f, Offset(i * dx, y(v))) }
    }
}
