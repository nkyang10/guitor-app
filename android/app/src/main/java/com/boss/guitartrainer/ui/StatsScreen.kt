package com.boss.guitartrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Achievement
import com.boss.guitartrainer.game.Progression
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(nav: NavController, asTab: Boolean = false) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val hist by store.history.collectAsState()
    val counters by store.counters.collectAsState()
    val settings by store.settings.collectAsState()
    var tab by rememberSaveable { mutableStateOf("stats") }
    StatsContent(hist, counters, settings.nickname, asTab, tab, onTab = { tab = it }, onBack = { nav.popBackStack() },
        go = { nav.go(it) }, onDelete = { store.deleteSession(it) })
}

@Composable
fun StatsContent(hist: List<SessionRecord>, counters: Map<String, Int>, nickname: String, asTab: Boolean, tab: String,
                 onTab: (String) -> Unit, onBack: () -> Unit, go: (String) -> Unit, onDelete: (Long) -> Unit) {
    val xp = Progression.xp(hist)
    val lvl = Progression.level(xp)
    var confirm by rememberSaveable { mutableStateOf<Long?>(null) }
    Screen(if (asTab) "我的" else "進度同成就", if (asTab) null else onBack, actions = { TopAction("⚙️", "設定") { go("settings") } }) {
        ProfileCard(nickname, lvl, xp) { go("settings") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("連續日數", "${Progression.streakDays(hist)}", Modifier.weight(1f), Palette.Accent, Tone.CORAL)
            StatTile("最長連續", "${Progression.bestStreak(hist)}", Modifier.weight(1f), Palette.SunDeep, Tone.SUN)
            StatTile("練習次數", "${hist.size}", Modifier.weight(1f), Palette.MintDeep, Tone.MINT)
            StatTile("總分鐘", "${(hist.sumOf { it.durationSec } / 60).toInt()}", Modifier.weight(1f), Palette.Accent2, Tone.SKY)
        }
        ChipRow(listOf("stats" to "📈 統計", "ach" to "🏅 成就", "hist" to "📒 紀錄"), tab, onTab)
        when (tab) {
            "stats" -> StatsTab(hist, go)
            "ach" -> AchievementsTab(Progression.achievements(hist, counters))
            else -> HistoryTab(hist) { confirm = it }
        }
    }
    confirm?.let { id ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
            title = { Text("刪除呢次紀錄？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("刪咗就搵唔返㗎啦。", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) },
            confirmButton = { TextButton(onClick = { onDelete(id); confirm = null }) { Text("刪除", color = Palette.Miss, style = MaterialTheme.typography.labelLarge) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("唔好啦", color = Palette.Text, style = MaterialTheme.typography.labelLarge) } })
    }
}

@Composable
private fun ProfileCard(nickname: String, lvl: Int, xp: Int, onEdit: () -> Unit) {
    val a = Progression.xpForLevel(lvl); val b = Progression.xpForLevel(lvl + 1)
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp), brush = Brushes.Dreamy).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(66.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Text("🎧", fontSize = 34.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(nickname.ifBlank { "結他新手" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Lv.$lvl · ${rankName(lvl)}", style = MaterialTheme.typography.labelMedium, color = Palette.LilacDeep)
                }
            }
            Box(Modifier.size(48.dp).bounceClick(onClick = onEdit).semantics { contentDescription = "改名" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                    Text("✏️", fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LevelBar((xp - a).toFloat() / (b - a).coerceAtLeast(1), height = 12.dp, track = Color.White.copy(alpha = 0.75f), animate = true)
        Spacer(Modifier.height(6.dp))
        Row {
            Text("XP $xp", style = MaterialTheme.typography.labelMedium, color = Palette.Text, modifier = Modifier.weight(1f))
            Text("仲差 ${b - xp} XP 升 Lv.${lvl + 1}", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
        }
    }
}

@Composable
private fun StatsTab(hist: List<SessionRecord>, go: (String) -> Unit) {
    SectionCard("準確度走勢", icon = "📈") {
        val pts = hist.takeLast(30).map { it.accuracy }
        if (pts.size < 2) EmptyHint("🌱", "練多兩次，就會見到你嘅進步曲線。") else {
            TrendChart(pts)
            Hint("最近 ${pts.size} 次 · 虛線 = 50 / 75 / 90%（1 / 2 / 3 粒星）")
        }
    }
    SectionCard("每種練習最佳成績", icon = "🏆") {
        for ((mode, label) in listOf("strum" to "掃弦", "finger" to "指彈", "song" to "歌曲")) {
            val best = hist.filter { it.mode == mode }.maxByOrNull { it.accuracy }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(modeEmoji(mode), modeTone(mode), size = 38.dp)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (best == null) Text("未試過", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                else { Text("%.0f%%".format(best.accuracy), style = MaterialTheme.typography.titleSmall); Spacer(Modifier.width(8.dp)); Stars(best.stars, 15) }
            }
        }
    }
    val missed = HashMap<String, Int>()
    hist.takeLast(20).forEach { r -> r.missedChords.forEach { (k, v) -> missed[k] = (missed[k] ?: 0) + v } }
    if (missed.isNotEmpty()) SectionCard("最近最常錯嘅和弦", icon = "🧩") {
        missed.entries.sortedByDescending { it.value }.take(5).forEach { (c, n) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.LilacSoft).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(c, style = MaterialTheme.typography.titleSmall, color = Palette.LilacDeep)
                }
                Spacer(Modifier.width(10.dp))
                Text("錯咗 $n 次", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
                SoftButton("去練", { go("chord/${android.net.Uri.encode(c)}") }, tone = Tone.LILAC, height = 40.dp)
            }
        }
    }
    val tempo = hist.filter { it.mode == "strum" && it.stars >= 2 }.maxByOrNull { it.bpm * it.tempoPercent / 100 }
    if (tempo != null) SectionCard("速度紀錄", icon = "🚀") {
        Text("2 星以上最快：%.0f BPM".format(tempo.bpm * tempo.tempoPercent / 100), style = MaterialTheme.typography.titleSmall)
        Hint(tempo.title)
    }
}

val ACH_EMOJI = mapOf(
    "first" to "🌱", "ten" to "💪", "fifty" to "🎸", "streak3" to "🔥", "streak7" to "🌈", "min60" to "⏰", "min600" to "🏔️",
    "star3" to "⭐", "combo50" to "⚡", "combo100" to "💫", "dir90" to "↕️", "finger90" to "🖐️", "song1" to "🎵",
    "fullspeed" to "🚀", "intermediate" to "🎓", "tuner10" to "🎯",
)
fun achEmoji(id: String) = ACH_EMOJI[id] ?: "🏅"

@Composable
private fun AchievementsTab(list: List<Achievement>) {
    val n = list.count { it.unlocked }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("已解鎖 $n/${list.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(if (n == list.size) "全部集齊！👑" else "繼續加油 ✨", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
    LevelBar(n / list.size.toFloat().coerceAtLeast(1f), animate = true)
    val tones = Tone.entries
    list.chunked(3).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { c, a ->
                val tone = tones[(r * 3 + c) % tones.size]
                Column(Modifier.weight(1f).fillMaxHeight().card(RoundedCornerShape(22.dp), if (a.unlocked) Palette.Surface else Palette.Surface.copy(alpha = 0.7f), shadow = a.unlocked)
                    .padding(horizontal = 8.dp, vertical = 12.dp).semantics(mergeDescendants = true) { },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(if (a.unlocked) tone.soft else Palette.Surface2), contentAlignment = Alignment.Center) {
                        Text(achEmoji(a.id), fontSize = 26.sp, modifier = Modifier.desaturate(!a.unlocked, 0.45f))
                        if (!a.unlocked) Text("🔒", fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(a.title, style = MaterialTheme.typography.labelLarge, color = if (a.unlocked) Palette.Text else Palette.Muted,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.desc, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))          // cards in a row share one height; progress sits at the bottom
                    if (!a.unlocked && a.progress.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(a.progress, style = MaterialTheme.typography.labelSmall, color = tone.deep)
                    }
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HistoryTab(hist: List<SessionRecord>, onDelete: (Long) -> Unit) {
    val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    if (hist.isEmpty()) SectionCard { EmptyHint("📒", "未有紀錄。完成第一次練習，就會喺度見到 🎶") }
    hist.reversed().take(100).forEach { r ->
        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp)).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconBubble(modeEmoji(r.mode), modeTone(r.mode), size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmt.format(Date(r.at))} · %.0f BPM".format(r.bpm) + if (r.tempoPercent != 100) " × ${r.tempoPercent}%" else "",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%.0f%%".format(r.accuracy), style = MaterialTheme.typography.titleMedium)
                Stars(r.stars, 12)
            }
            Box(Modifier.size(44.dp).bounceClick { onDelete(r.id) }.semantics { contentDescription = "刪除紀錄" }, contentAlignment = Alignment.Center) {
                Text("🗑️", fontSize = 16.sp, modifier = Modifier.desaturate(true, 0.7f))
            }
        }
    }
}

@Composable
fun EmptyHint(emoji: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center)
    }
}

fun modeLabel(m: String) = when (m) { "strum" -> "掃弦"; "finger" -> "指彈"; "song" -> "歌曲"; "chord" -> "和弦"; else -> m }

/** Accuracy trend: soft coral area + line, guide lines at the star thresholds. */
@Composable
fun TrendChart(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = values.size
        val padL = 8.dp.toPx(); val padR = 30.dp.toPx()
        val w = size.width - padL - padR
        val dx = w / (n - 1).coerceAtLeast(1)
        val top = 10.dp.toPx(); val bottom = size.height - 6.dp.toPx()
        fun x(i: Int) = padL + i * dx
        fun y(v: Double) = (top + (bottom - top) * (1 - v.coerceIn(0.0, 100.0) / 100.0)).toFloat()
        val lbl = android.graphics.Paint().apply { isAntiAlias = true; color = Palette.Muted.toArgb(); textSize = 11.sp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT }
        for (g in listOf(50.0, 75.0, 90.0)) {
            drawLine(Palette.Grid.copy(alpha = 0.45f), Offset(padL, y(g)), Offset(padL + w, y(g)), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            drawContext.canvas.nativeCanvas.drawText("${g.toInt()}", size.width, y(g) + 4.dp.toPx(), lbl)
        }
        val line = Path(); val area = Path()
        values.forEachIndexed { i, v ->
            val yy = y(v)
            if (i == 0) { line.moveTo(x(i), yy); area.moveTo(x(i), bottom); area.lineTo(x(i), yy) } else { line.lineTo(x(i), yy); area.lineTo(x(i), yy) }
        }
        area.lineTo(x(n - 1), bottom); area.close()
        drawPath(area, Brush.verticalGradient(listOf(Palette.Coral.copy(alpha = 0.38f), Palette.Coral.copy(alpha = 0.02f)), startY = top, endY = bottom))
        drawPath(line, Palette.Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        values.forEachIndexed { i, v ->
            val c = Offset(x(i), y(v))
            drawCircle(Color.White, 3.5.dp.toPx(), c); drawCircle(Palette.Accent, 3.5.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}
