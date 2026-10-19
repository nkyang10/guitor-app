package com.boss.guitartrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.game.DirectionWeight
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.FingerPattern
import com.boss.guitartrainer.music.StrumPattern
import com.boss.guitartrainer.music.Tunings

val PROGRESSIONS = listOf(
    "C Am F G", "G Em C D", "Am F C G", "C G Am F", "D A Bm G", "Em C G D", "Am Dm E Am", "G C D G", "A D E A", "Dm Am C G",
)

fun strumPreview(p: StrumPattern): String = p.steps.mapIndexed { i, c ->
    val sym = when (c) { 'D' -> "↓"; 'U' -> "↑"; 'x' -> "✕"; else -> "·" }
    if (i > 0 && i % p.subdivision == 0) " $sym" else sym
}.joinToString("")

fun fingerPreview(p: FingerPattern): String = p.steps.mapIndexed { i, t ->
    val sym = if (t == "-") "·" else t
    if (i > 0 && i % p.subdivision == 0) " | $sym" else sym
}.joinToString(" ")

@Composable
fun PracticeSetupScreen(nav: NavController, mode: String) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val lib = remember { AppState.patterns(ctx) }
    val isStrum = mode == "strum"
    var level by remember { mutableIntStateOf(1) }
    var patternId by remember { mutableStateOf(if (isStrum) lib.strum.first().id else lib.finger.first().id) }
    var progression by remember { mutableStateOf(settings.lastProgression) }
    var diff by remember { mutableStateOf(settings.difficulty) }
    val sp = lib.strum(patternId); val fp = lib.finger(patternId)
    val range = sp?.bpm ?: fp?.bpm ?: listOf(60, 120)
    var bpm by remember(patternId) { mutableIntStateOf(range.first()) }
    var bars by remember { mutableIntStateOf(8) }
    val chords = progression.trim().split(Regex("[\\s,|]+")).filter { it.isNotBlank() }
    val unknown = chords.filter { ChordLibrary.forSymbol(it, diff.simplifyChords, diff.simplifyLevel) == null }

    Screen(if (isStrum) "掃弦練習" else "指彈練習", { nav.popBackStack() }) {
        SectionCard("1. 揀節奏型") {
            ChipRow(listOf("1" to "初學", "2" to "進階", "3" to "挑戰"), level.toString()) { level = it.toInt() }
            if (isStrum) {
                lib.strum.filter { it.level == level }.forEach { p ->
                    PatternRow(p.name, strumPreview(p), p.tip, "${p.beatsPerBar}/4 · ${p.bpm.first()}-${p.bpm.last()} BPM", p.id == patternId) { patternId = p.id }
                }
            } else {
                lib.finger.filter { it.level == level }.forEach { p ->
                    PatternRow(p.name, fingerPreview(p), p.tip, "${p.beatsPerBar}/4 · ${p.bpm.first()}-${p.bpm.last()} BPM", p.id == patternId) { patternId = p.id }
                }
                Hint("p = 拇指（低音弦）· i 食指 = 3弦 · m 中指 = 2弦 · a 無名指 = 1弦")
            }
        }
        SectionCard("2. 和弦進行") {
            OutlinedTextField(progression, { progression = it }, label = { Text("例如：C Am F G") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PROGRESSIONS.size) { i ->
                    androidx.compose.material3.AssistChip(onClick = { progression = PROGRESSIONS[i] }, label = { Text(PROGRESSIONS[i]) })
                }
            }
            if (unknown.isNotEmpty()) Text("唔識嘅和弦：${unknown.joinToString()}", color = Palette.Miss)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chords.distinct().take(6).forEach { c ->
                    val shape = ChordLibrary.forSymbol(c, diff.simplifyChords, diff.simplifyLevel)
                    if (shape != null) Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(shape.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        ChordDiagram(shape, Modifier.fillMaxWidth(), showFingers = false)
                    }
                }
            }
        }
        SectionCard("3. 速度同長度") {
            Text("$bpm BPM（實際 ${(bpm * diff.tempoPercent / 100)} BPM @ ${diff.tempoPercent}%）", fontWeight = FontWeight.Bold)
            Slider(bpm.toFloat(), { bpm = it.toInt() }, valueRange = 40f..180f)
            ChipRow(listOf("4" to "4 小節", "8" to "8 小節", "16" to "16 小節", "32" to "32 小節"), bars.toString()) { bars = it.toInt() }
        }
        SectionCard("4. 難度") {
            ChipRow(listOf("beginner" to "初學（寬鬆）", "intermediate" to "中級（嚴格）"), diff.preset) {
                diff = DifficultySettings.preset(it).copy(tempoPercent = diff.tempoPercent)
            }
            Text("速度 ${diff.tempoPercent}%", color = Palette.Muted)
            Slider(diff.tempoPercent.toFloat(), { diff = diff.copy(tempoPercent = (it / 5).toInt() * 5) }, valueRange = 40f..120f)
            Toggle("等待模式（彈啱先行落去）", diff.waitMode) { diff = diff.copy(waitMode = it) }
            Toggle("簡化和弦（初學版本）", diff.simplifyChords) { diff = diff.copy(simplifyChords = it) }
            Toggle("檢查和弦", diff.chordCheck) { diff = diff.copy(chordCheck = it) }
            Toggle("節拍器聲", diff.metronome) { diff = diff.copy(metronome = it) }
            if (isStrum) {
                Text("掃弦方向判斷", color = Palette.Muted)
                ChipRow(DirectionWeight.entries.map { it.name to it.label }, diff.directionWeight.name) { diff = diff.copy(directionWeight = DirectionWeight.valueOf(it)) }
                Hint("方向判斷準確度有限（未校準約 75%），建議先做「校準 → 掃弦方向」。")
            }
            Text("判定窗：Perfect ±${diff.perfectMs}ms · Good ±${diff.goodMs}ms", color = Palette.Muted, fontSize = 12.sp)
        }
        val cal = store.calibration.collectAsState().value
        if (cal.latencyMs == null) SectionCard {
            Text("⚠️ 未做延遲校準：判定可能會偏快／偏慢。", color = Palette.Good)
            Button(onClick = { nav.navigate("calibration") }) { Text("去校準") }
        }
        Button(enabled = chords.isNotEmpty() && unknown.isEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
            store.updateSettings { it.copy(lastProgression = progression, difficulty = diff) }
            val tuning = Tunings.byId(settings.tuningId)
            val title = (sp?.name ?: fp?.name ?: "練習") + " · " + chords.joinToString(" ")
            val chart = if (isStrum) ChartBuilder.strumPractice(title, chords, sp!!, bpm.toDouble(), bars, diff.simplifyLevel, tuning)
            else ChartBuilder.fingerPractice(title, chords, fp!!, bpm.toDouble(), bars, diff.simplifyLevel, tuning)
            AppState.pendingGame = GameRequest(chart, diff, mode, title, patternId)
            nav.navigate("game")
        }) { Text("開始 ▶", fontSize = 18.sp) }
    }
}

@Composable
private fun PatternRow(name: String, preview: String, tip: String, meta: String, selected: Boolean, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "◉" else "○", color = if (selected) Palette.Accent else Palette.Muted, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(preview, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Palette.Accent2)
                Text(meta, fontSize = 12.sp, color = Palette.Muted)
                if (selected && tip.isNotBlank()) Text(tip, fontSize = 12.sp, color = Palette.Good)
            }
        }
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
