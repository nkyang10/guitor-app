package com.boss.guitartrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.game.DirectionWeight
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.FingerPattern
import com.boss.guitartrainer.music.PatternLibrary
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

/** Colour the pattern symbols: ↓ coral, ↑ sky, ✕ rose; p/i/m/a each get their own pastel ink. */
fun colorizePattern(text: String): AnnotatedString = buildAnnotatedString {
    for (ch in text) {
        val c = when (ch) {
            '↓', 'p' -> Palette.Accent; '↑', 'm' -> Palette.Accent2; '✕' -> Palette.RoseDeep; 'i' -> Palette.MintDeep; 'a' -> Palette.LilacDeep
            '·', '|' -> Palette.Grid; else -> Palette.Text
        }
        withStyle(SpanStyle(color = c)) { append(ch) }
    }
}

data class SetupState(val level: Int, val patternId: String, val progression: String, val diff: DifficultySettings, val bpm: Int, val bars: Int)

fun parseChords(progression: String) = progression.trim().split(Regex("[\\s,|]+")).filter { it.isNotBlank() }

@Composable
fun PracticeSetupScreen(nav: NavController, mode: String) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val cal by store.calibration.collectAsState()
    val lib = remember { AppState.patterns(ctx) }
    val isStrum = mode == "strum"
    var st by remember {
        val pid = if (isStrum) lib.strum.first().id else lib.finger.first().id
        val range = lib.strum(pid)?.bpm ?: lib.finger(pid)?.bpm ?: listOf(60, 120)
        mutableStateOf(SetupState(1, pid, settings.lastProgression, settings.difficulty, range.first(), 8))
    }
    PracticeSetupContent(mode, lib, st, latencyMissing = cal.latencyMs == null,
        onChange = { n ->
            st = if (n.patternId != st.patternId) n.copy(bpm = (lib.strum(n.patternId)?.bpm ?: lib.finger(n.patternId)?.bpm ?: listOf(60, 120)).first()) else n
        },
        onBack = { nav.popBackStack() }, onCalibrate = { nav.navigate("calibration") },
        onStart = {
            val chords = parseChords(st.progression)
            val sp = lib.strum(st.patternId); val fp = lib.finger(st.patternId)
            store.updateSettings { it.copy(lastProgression = st.progression, difficulty = st.diff) }
            val tuning = Tunings.byId(settings.tuningId)
            val title = (sp?.name ?: fp?.name ?: "練習") + " · " + chords.joinToString(" ")
            val chart = if (isStrum) ChartBuilder.strumPractice(title, chords, sp!!, st.bpm.toDouble(), st.bars, st.diff.simplifyLevel, tuning)
            else ChartBuilder.fingerPractice(title, chords, fp!!, st.bpm.toDouble(), st.bars, st.diff.simplifyLevel, tuning)
            AppState.pendingGame = GameRequest(chart, st.diff, mode, title, st.patternId)
            nav.navigate("game")
        })
}

@Composable
fun PracticeSetupContent(mode: String, lib: PatternLibrary, st: SetupState, latencyMissing: Boolean, onChange: (SetupState) -> Unit,
                         onBack: () -> Unit, onCalibrate: () -> Unit, onStart: () -> Unit) {
    val isStrum = mode == "strum"
    val diff = st.diff
    val chords = parseChords(st.progression)
    val unknown = chords.filter { ChordLibrary.forSymbol(it, diff.simplifyChords, diff.simplifyLevel) == null }
    val canStart = chords.isNotEmpty() && unknown.isEmpty()
    Screen(if (isStrum) "掃弦練習" else "指彈練習", onBack, subtitle = if (isStrum) "揀節奏 → 揀和弦 → 開始 🎸" else "揀指法 → 揀和弦 → 開始 🖐️",
        bottomBar = {
            BottomCta {
                PillButton(if (canStart) "開始練習" else "先揀好和弦", onStart, Modifier.fillMaxWidth(), enabled = canStart, emoji = if (canStart) "▶️" else null)
            }
        }) {
        StepCard(1, if (isStrum) "揀節奏型" else "揀指法") {
            ChipRow(listOf("1" to "🌱 初學", "2" to "🌿 進階", "3" to "🔥 挑戰"), st.level.toString()) { onChange(st.copy(level = it.toInt())) }
            if (isStrum) {
                lib.strum.filter { it.level == st.level }.forEach { p ->
                    PatternOption(p.name, colorizePattern(strumPreview(p)), p.tip, "${p.beatsPerBar}/4 · ${p.bpm.first()}–${p.bpm.last()} BPM",
                        p.id == st.patternId) { onChange(st.copy(patternId = p.id)) }
                }
            } else {
                lib.finger.filter { it.level == st.level }.forEach { p ->
                    PatternOption(p.name, colorizePattern(fingerPreview(p)), p.tip, "${p.beatsPerBar}/4 · ${p.bpm.first()}–${p.bpm.last()} BPM",
                        p.id == st.patternId) { onChange(st.copy(patternId = p.id)) }
                }
                Hint("p = 拇指（低音弦）· i 食指 = 3弦 · m 中指 = 2弦 · a 無名指 = 1弦")
            }
        }
        StepCard(2, "和弦進行") {
            OutlinedTextField(st.progression, { onChange(st.copy(progression = it)) }, label = { Text("例如：C Am F G") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), colors = softFieldColors(),
                textStyle = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(PROGRESSIONS.size) { i ->
                    val p = PROGRESSIONS[i]
                    val sel = p == st.progression.trim()
                    Box(Modifier.clip(CircleShape).background(if (sel) Palette.LilacSoft else Palette.Surface)
                        .border(1.5.dp, if (sel) Palette.Lilac else Palette.Line, CircleShape)
                        .selectable(sel, role = Role.RadioButton) { onChange(st.copy(progression = p)) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(p, style = MaterialTheme.typography.labelLarge, color = if (sel) Palette.LilacDeep else Palette.Text)
                    }
                }
            }
            if (unknown.isNotEmpty()) Text("唔識嘅和弦：${unknown.joinToString()}", color = Palette.Miss, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chords.distinct().take(8).forEach { c ->
                    val shape = ChordLibrary.forSymbol(c, diff.simplifyChords, diff.simplifyLevel)
                    if (shape != null) Column(Modifier.width(54.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Bg).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(shape.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        ChordDiagram(shape, Modifier.fillMaxWidth().padding(horizontal = 2.dp), showFingers = false)
                    }
                }
            }
        }
        StepCard(3, "速度同長度") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${st.bpm}", style = MaterialTheme.typography.headlineLarge, color = Palette.Accent)
                Spacer(Modifier.width(6.dp))
                Text("BPM", style = MaterialTheme.typography.labelLarge, color = Palette.Muted, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text("實際 ${st.bpm * diff.tempoPercent / 100} BPM（${diff.tempoPercent}%）", style = MaterialTheme.typography.labelMedium,
                    color = Palette.Muted, modifier = Modifier.padding(bottom = 5.dp))
            }
            AppSlider(st.bpm.toFloat(), { onChange(st.copy(bpm = it.toInt())) }, valueRange = 40f..180f)
            ChipRow(listOf("4" to "4 小節", "8" to "8 小節", "16" to "16 小節", "32" to "32 小節"), st.bars.toString()) { onChange(st.copy(bars = it.toInt())) }
        }
        StepCard(4, "難度") {
            ChipRow(listOf("beginner" to "🌱 初學（寬鬆）", "intermediate" to "🔥 中級（嚴格）"), diff.preset) {
                onChange(st.copy(diff = DifficultySettings.preset(it).copy(tempoPercent = diff.tempoPercent)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${diff.tempoPercent}%", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
            }
            AppSlider(diff.tempoPercent.toFloat(), { onChange(st.copy(diff = diff.copy(tempoPercent = (it / 5).toInt() * 5))) }, valueRange = 40f..120f)
            Toggle("⏳ 等待模式（彈啱先行落去）", diff.waitMode) { onChange(st.copy(diff = diff.copy(waitMode = it))) }
            Toggle("🪶 簡化和弦（初學版本）", diff.simplifyChords) { onChange(st.copy(diff = diff.copy(simplifyChords = it))) }
            Toggle("👂 檢查和弦", diff.chordCheck) { onChange(st.copy(diff = diff.copy(chordCheck = it))) }
            Toggle("⏱️ 節拍器聲", diff.metronome) { onChange(st.copy(diff = diff.copy(metronome = it))) }
            if (isStrum) {
                Text("掃弦方向判斷", style = MaterialTheme.typography.titleSmall)
                ChipRow(DirectionWeight.entries.map { it.name to it.label }, diff.directionWeight.name) {
                    onChange(st.copy(diff = diff.copy(directionWeight = DirectionWeight.valueOf(it))))
                }
                Hint("方向判斷準確度有限（未校準約 75%），建議先做「校準 → 上下掃方向」。")
            }
            Text("判定窗：Perfect ±${diff.perfectMs}ms · Good ±${diff.goodMs}ms", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        }
        if (latencyMissing) Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), Palette.SunSoft, shadow = false).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text("未做延遲校準：判定可能會偏快／偏慢。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SoftButton("去校準", onCalibrate, tone = Tone.SUN, height = 40.dp)
        }
    }
}

@Composable
private fun PatternOption(name: String, preview: AnnotatedString, tip: String, meta: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) Palette.CoralSoft else Palette.Bg)
        .border(if (selected) 2.dp else 1.5.dp, if (selected) Palette.Coral else Palette.Line, RoundedCornerShape(18.dp))
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).border(2.dp, if (selected) Palette.Accent else Palette.Outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(preview, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            if (selected && tip.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("💡 $tip", style = MaterialTheme.typography.bodySmall, color = Palette.PeachDeep)
            }
        }
    }
}

