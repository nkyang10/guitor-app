package com.boss.guitartrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Progression

@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val hist by store.history.collectAsState()
    val cal by store.calibration.collectAsState()
    val xp = Progression.xp(hist)
    val lvl = Progression.level(xp)
    val today = System.currentTimeMillis() / 86_400_000L
    val todayMin = hist.filter { it.at / 86_400_000L == today }.sumOf { it.durationSec } / 60
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("🎸 結他練習", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("等級", "Lv.$lvl", Modifier.weight(1f), Palette.Accent)
            StatTile("連續日數", "🔥${Progression.streakDays(hist)}", Modifier.weight(1f))
            StatTile("今日分鐘", "%.0f".format(todayMin), Modifier.weight(1f))
        }
        if (cal.latencyMs == null || cal.direction == null) SectionCard(onClick = { nav.navigate("calibration") }) {
            Text("⚙️ 第一次用？先做校準", fontWeight = FontWeight.Bold, color = Palette.Good)
            Text((if (cal.latencyMs == null) "• 延遲校準（令判定準時）\n" else "") + (if (cal.direction == null) "• 上下掃方向校準（個人化）" else ""),
                color = Palette.Muted, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("🎯", "調音器", "自動認弦", Modifier.weight(1f)) { nav.navigate("tuner") }
            BigTile("⏱", "節拍器", "Tap tempo", Modifier.weight(1f)) { nav.navigate("metronome") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("⬇⬆", "掃弦練習", "13 個節奏型", Modifier.weight(1f)) { nav.navigate("practice/strum") }
            BigTile("🖐", "指彈練習", "10 個指法", Modifier.weight(1f)) { nav.navigate("practice/finger") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("🎵", "歌曲", "YouTube 連結／本機檔", Modifier.weight(1f)) { nav.navigate("songs") }
            BigTile("🎼", "和弦庫", "指法圖＋和弦檢查", Modifier.weight(1f)) { nav.navigate("chords") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("📈", "進度", "統計、成就、紀錄", Modifier.weight(1f)) { nav.navigate("stats") }
            BigTile("⚙️", "設定", "咪高峰、伺服器、校準", Modifier.weight(1f)) { nav.navigate("settings") }
        }
        hist.lastOrNull()?.let { last ->
            SectionCard("上次練習") {
                Text(last.title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("%s · %.0f%% · %s".format(modeLabel(last.mode), last.accuracy, "★".repeat(last.stars)), color = Palette.Muted)
                AppState.lastResult?.request?.let { req ->
                    Button(onClick = { AppState.pendingGame = req; nav.navigate("game") }) { Text("再嚟一次") }
                }
            }
        }
    }
}
