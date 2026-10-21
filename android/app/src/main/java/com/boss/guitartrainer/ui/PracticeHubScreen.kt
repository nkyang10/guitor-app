package com.boss.guitartrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.music.ChordLibrary

data class RoadStep(val emoji: String, val title: String, val sub: String, val done: Boolean, val route: String)

data class HubState(
    val steps: List<RoadStep>,
    val strumCount: Int,
    val fingerCount: Int,
    val chordCount: Int,
    val latencyOk: Boolean,
    val directionOk: Boolean,
)

object HubLogic {
    fun steps(hist: List<SessionRecord>, counters: Map<String, Int>, daily: Map<String, Long>): List<RoadStep> {
        val chordsOk = daily.keys.count { it.startsWith("chord_") }      // distinct chords passed in 和弦檢查
        return listOf(
            RoadStep("🎯", "調好第一次音", "用調音器，6 條弦逐條調準", (counters["tuner"] ?: 0) >= 1 || "tuner" in daily, "tuner"),
            RoadStep("🎼", "學識 3 個和弦", "和弦庫 → 開始聽 → 彈到過關（${minOf(chordsOk, 3)}/3）", chordsOk >= 3, "chords"),
            RoadStep("🎸", "第一次掃弦練習", "揀「初學」節奏型，跟住箭咀掃", hist.any { it.mode == "strum" }, "practice/strum"),
            RoadStep("⭐", "拎到 3 粒星", "慢慢嚟，用 70% 速度都得㗎", hist.any { it.stars == 3 }, "practice/strum"),
            RoadStep("🎵", "彈第一首歌", "加一首歌，跟住和弦譜彈", hist.any { it.mode == "song" }, "songs"),
        )
    }
}

@Composable
fun PracticeHubScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val hist by store.history.collectAsState()
    val counters by store.counters.collectAsState()
    val daily by store.daily.collectAsState()
    val cal by store.calibration.collectAsState()
    val lib = remember { AppState.patterns(ctx) }
    val state = HubState(HubLogic.steps(hist, counters, daily), lib.strum.size, lib.finger.size, ChordLibrary.ALL.size,
        cal.latencyMs != null, cal.direction != null)
    PracticeHubContent(state) { nav.go(it) }
}

@Composable
fun PracticeHubContent(s: HubState, go: (String) -> Unit) {
    Screen("練習", null, subtitle = "今日想練啲咩？🎶", actions = { TopAction("⚙️", "設定") { go("settings") } }) {
        Roadmap(s.steps, go)

        SectionTitle("節奏遊戲", "🎮")
        ActionRow("🎸", "掃弦練習", "${s.strumCount} 個節奏型 · 跟住箭咀上下掃", Tone.CORAL) { go("practice/strum") }
        ActionRow("🖐️", "指彈練習", "${s.fingerCount} 個指法 · p i m a 輪住彈", Tone.MINT) { go("practice/finger") }

        SectionTitle("基本功", "🌱")
        ActionRow("🎼", "和弦庫", "${s.chordCount} 個和弦指法圖 · 即時聽你彈啱未", Tone.LILAC) { go("chords") }
        ActionRow("🎯", "調音器", "自動認弦，指針去到中間就啱", Tone.SKY) { go("tuner") }
        ActionRow("⏱️", "節拍器", "Tap tempo · 2/4 3/4 4/4 6/8", Tone.SUN) { go("metronome") }

        SectionTitle("準備", "🧺")
        val calDone = s.latencyOk && s.directionOk
        ActionRow("🎚️", "校準", if (calDone) "延遲同上下掃都校好咗" else "令判定更準時（2 分鐘）", Tone.PEACH,
            trailing = { Badge(if (calDone) "已完成" else if (s.latencyOk || s.directionOk) "做咗一半" else "未做", if (calDone) Palette.MintDeep else Palette.PeachDeep) }) {
            go("calibration")
        }
        ActionRow("⚙️", "設定", "咪高峰、A4、每日目標、伺服器", Tone.ROSE) { go("settings") }
    }
}

@Composable
private fun Roadmap(steps: List<RoadStep>, go: (String) -> Unit) {
    val done = steps.count { it.done }
    val current = steps.indexOfFirst { !it.done }
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(28.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🗺️", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("新手路線圖", style = MaterialTheme.typography.titleMedium)
                Text(if (current < 0) "全部完成！你已經係結他手 🎉" else "一步一步嚟，唔使急 🌱", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            Text("$done/${steps.size}", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
        }
        Spacer(Modifier.height(10.dp))
        LevelBar(done / steps.size.toFloat(), animate = true)
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, st ->
            val isCur = i == current
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(18.dp))
                .then(if (isCur) Modifier.background(Palette.CoralSoft) else Modifier)
                .card(RoundedCornerShape(18.dp), Color.Transparent, shadow = false, onClick = { go(st.route) })
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(34.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(2.dp).height(10.dp).background(if (i == 0) Color.Transparent else Palette.Line))
                    val bg = when { st.done -> Palette.MintDeep; isCur -> Palette.Coral; else -> Palette.Surface2 }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(bg)
                        .then(if (!st.done && !isCur) Modifier.border(1.5.dp, Palette.Line, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Text(if (st.done) "✓" else "${i + 1}", style = MaterialTheme.typography.labelLarge,
                            color = if (st.done) Color.White else if (isCur) Palette.Text else Palette.Muted)
                    }
                    Box(Modifier.width(2.dp).weight(1f).background(if (i == steps.lastIndex) Color.Transparent else Palette.Line))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text("${st.emoji} ${st.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (st.done) Palette.Muted else Palette.Text)
                    Text(st.sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2)
                }
                if (isCur) { Spacer(Modifier.width(6.dp)); Badge("下一步", Palette.Accent) }
            }
        }
    }
}
