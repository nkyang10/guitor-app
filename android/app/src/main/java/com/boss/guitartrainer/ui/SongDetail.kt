package com.boss.guitartrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.SongEntry
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordNames
import com.boss.guitartrainer.music.PatternLevel
import com.boss.guitartrainer.music.PatternLibrary
import com.boss.guitartrainer.music.PatternSuggest
import com.boss.guitartrainer.music.SongPrefs
import com.boss.guitartrainer.music.SongSetup
import com.boss.guitartrainer.music.Suggestions
import com.boss.guitartrainer.music.Tunings
import java.io.File

@Composable
fun SongDetailScreen(nav: NavController, id: String) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val entry = store.songs.collectAsState().value.firstOrNull { it.id == id }
    val lib = remember { AppState.patterns(ctx) }
    val chordLevel = if (settings.difficulty.preset == "beginner") 1 else 2
    val desk = remember(id) { SongDesk(store, id, lib, chordLevel) }
    val preview = remember { BeatPreview(ctx) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    val mixAudio = remember(id) { songAudioFile(store, id, "mix") }
    LaunchedEffect(id) { desk.loadEnvelope(mixAudio) }
    val chart = desk.chart
    val base = desk.base
    if (entry == null || base == null || chart == null) { Screen("歌曲", { nav.popBackStack() }) { EmptyHint("🤔", "搵唔到首歌") }; return }
    SongDetailContent(entry, chart, base.key, desk.prefs, desk.lib, desk.suggestions, chordLevel, settings.songExtraOffsetMs, desk.edited,
        onBack = { preview.stop(); nav.popBackStack() },
        onPrefs = { f -> desk.updatePrefs(f) },
        onEditChord = { s, e, text, scope -> desk.editChord(s, e, text, scope) },
        onReset = { desk.resetChords() },
        onDelete = { preview.stop(); store.deleteSong(id); nav.popBackStack() },
        beatCard = { BeatCardLive(desk, preview, mixAudio) },
        onStart = { simplify, tempo, stem, loopSection, offset ->
            preview.stop()
            store.updateSettings { it.copy(songExtraOffsetMs = offset) }
            val tuning = Tunings.byId(settings.tuningId)
            // key / capo / beat grid / offset are already in `chart`; patterns follow the chosen level (hand picks win)
            val pats = desk.patterns()
            val lib2 = PatternSuggest.augment(desk.lib, pats.values)
            val default = pats[0] ?: chart.sections.firstOrNull()?.pattern ?: "s_folk"
            val built = ChartBuilder.song(chart, lib2, default, simplify, tuning, if (chart.sections.isEmpty()) emptyMap() else pats)
            val diff = settings.difficulty.copy(tempoPercent = tempo, simplifyChords = simplify < 3, waitMode = false, metronome = false)
            val loop = if (loopSection >= 0) chart.sections.getOrNull(loopSection)?.let { it.start..it.end } else null
            val audio = songAudioFile(store, id, stem) ?: mixAudio
            AppState.pendingGame = GameRequest(built, diff, "song", entry.title, null, id, audio, loop, 0)
            nav.navigate("game")
        })
}

/** Audio of a stem. Local imports keep the picked file's extension (mix.mp3, mix.flac …), so fall back to any "stem.*". */
fun songAudioFile(store: Store, id: String, stem: String): File? =
    store.songAudio(id, stem) ?: store.songDir(id).listFiles()?.firstOrNull { f ->
        f.isFile && f.name.startsWith("$stem.") && f.extension.lowercase() !in setOf("json", "tmp", "bin", "txt")
    }

private fun mmss(t: Double) = "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())

@Composable
fun SongDetailContent(entry: SongEntry, chart: Chart, origKey: String?, prefs: SongPrefs, lib: PatternLibrary, sugg: Suggestions?,
                      defaultSimplify: Int, defaultOffset: Int, edited: Boolean,
                      onBack: () -> Unit, onPrefs: ((SongPrefs) -> SongPrefs) -> Unit,
                      onEditChord: (start: Double, end: Double, text: String, scope: String) -> Unit,
                      onReset: () -> Unit, onDelete: () -> Unit, beatCard: @Composable () -> Unit = {},
                      onStart: (simplify: Int, tempo: Int, stem: String, loopSection: Int, offset: Int) -> Unit) {
    var simplify by remember { mutableIntStateOf(defaultSimplify) }
    var tempo by remember { mutableIntStateOf(80) }
    var stem by remember { mutableStateOf(if (entry.hasNoGuitarStem) "no_guitar" else "mix") }
    var loopSection by remember { mutableIntStateOf(-1) }
    var editSpan by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableIntStateOf(defaultOffset) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tone = songTone(entry.title)
    val level = PatternLevel.of(prefs.level) ?: PatternLevel.NORMAL
    val picks = remember(sugg, level, prefs.overrides, chart.sections) {
        PatternSuggest.resolve(sugg, level, prefs.overrides, PatternSuggest.sectionsOf(chart), "s_folk")
    }

    Screen(entry.title, onBack, subtitle = entry.source ?: "本機歌曲", bottomBar = {
        BottomCta { PillButton("開始跟彈", { onStart(simplify, tempo, stem, loopSection, offset) }, Modifier.fillMaxWidth(), emoji = "▶️") }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("速度", "%.0f".format(chart.bpm), Modifier.weight(1f), tone.deep, tone)
            StatTile("調性", chart.key ?: "?", Modifier.weight(1f), Palette.LilacDeep, Tone.LILAC)
            StatTile("Capo", if (chart.capo > 0) "${chart.capo}" else "唔使", Modifier.weight(1f), Palette.MintDeep, Tone.MINT)
            StatTile("最佳", entry.bestAccuracy?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f), Palette.SunDeep, Tone.SUN)
        }
        if (chart.capo > 0) Hint("Capo 夾第 ${chart.capo} 格，和弦按 capo 後嘅指法顯示。", Palette.SunDeep)
        if (entry.partial) Hint("⚠️ 首歌長過 10 分鐘，只分析咗頭 10 分鐘；之後嘅部分冇和弦。", Palette.SunDeep)
        KeyCapoCard(origKey, chart.key, prefs.transpose, prefs.pitchFollow, chart.capo, prefs.capo == null,
            onTranspose = { v -> onPrefs { it.copy(transpose = SongSetup.wrap(v)) } },
            onPitch = { v -> onPrefs { it.copy(pitchFollow = v) } },
            onCapo = { v -> onPrefs { it.copy(capo = v) } })
        beatCard()
        SectionCard("段落同節奏型", icon = "🧩") {
            PatternLevelPicker(level, prefs.family, sugg, lib,
                onLevel = { l -> onPrefs { it.copy(level = l.id, overrides = emptyMap()) } },
                onFamily = { f -> onPrefs { it.copy(family = f, overrides = emptyMap()) } })
            if (chart.sections.isEmpty()) Hint("冇段落資料，會全首用同一個節奏型。")
            chart.sections.forEachIndexed { i, s ->
                var open by remember { mutableStateOf(false) }
                val pid = picks[i] ?: s.pattern ?: "s_folk"
                val manual = prefs.overrides.containsKey(i)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (loopSection == i) Palette.SkySoft else Palette.Bg)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.label}  ·  ${mmss(s.start)}", style = MaterialTheme.typography.titleSmall)
                        Text((if (manual) "✋ " else "${level.emoji} ") + PatternSuggest.name(lib, pid), style = MaterialTheme.typography.labelSmall,
                            color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sp = lib.strum(pid)
                        val fp = lib.finger(pid)
                        if (sp != null) Text(colorizePattern(strumPreview(sp)), fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
                        else if (fp != null) Text(colorizePattern(fingerPreview(fp)), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
                    }
                    Box {
                        SoftButton("改", { open = true }, tone = Tone.PEACH, height = 40.dp)
                        DropdownMenu(open, { open = false }, containerColor = Palette.Surface) {
                            val pick = sugg?.sections?.getOrNull(i)
                            val suggested = if (pick == null) emptyList() else PatternLevel.entries.map { l ->
                                pick.get(l) to "${l.emoji} ${l.label}：${PatternSuggest.name(lib, pick.get(l))}"
                            }
                            val others = lib.strum.filter { !it.id.startsWith("orig:") }.map { it.id to "掃：${it.name}" } +
                                lib.finger.filter { !it.id.startsWith("orig:") }.map { it.id to "指彈：${it.name}" }
                            (suggested + others).forEach { (pidOpt, label) ->
                                DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { onPrefs { p -> p.copy(overrides = p.overrides + (i to pidOpt)) }; open = false })
                            }
                            if (manual) DropdownMenuItem(text = { Text("↩️ 跟返「${level.label}」建議", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { onPrefs { p -> p.copy(overrides = p.overrides - i) }; open = false })
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    SoftButton(if (loopSection == i) "🔁 ✓" else "🔁", { loopSection = if (loopSection == i) -1 else i },
                        tone = if (loopSection == i) Tone.SKY else Tone.LILAC, height = 40.dp)
                }
            }
            if (loopSection >= 0) chart.sections.getOrNull(loopSection)?.let { Hint("🔁 會循環練「${it.label}」。", Palette.Accent2) }
        }
        SectionCard("和弦譜（撳和弦可以改）", icon = "🎼") {
            chart.chords.chunked(4).forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { ci, sp ->
                        val idx = ri * 4 + ci
                        val fingered = if (sp.original == "N") "N" else ChordNames.transpose(sp.original, -chart.capo)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Palette.Bg)
                            .border(1.5.dp, Palette.Line, RoundedCornerShape(14.dp)).clickable { editSpan = idx }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(ChordNames.simplify(fingered, simplify), style = MaterialTheme.typography.titleSmall, color = Palette.Accent, maxLines = 1)
                            Text(mmss(sp.start), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (edited) GhostButton("還原自動分析結果", onReset, Modifier.fillMaxWidth(), emoji = "↩️")
        }
        SectionCard("跟彈設定", icon = "🎚️") {
            ChipRow(listOf("1" to "🌱 簡化（初學）", "2" to "🌿 中等", "3" to "🌳 原和弦"), simplify.toString()) { simplify = it.toInt() }
            if (entry.hasNoGuitarStem) ChipRow(listOf("no_guitar" to "去結他伴奏", "mix" to "原曲"), stem) { stem = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$tempo%", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
            }
            AppSlider(tempo.toFloat(), { tempo = (it / 5).toInt() * 5 }, valueRange = 50f..100f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("對拍微調", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$offset ms", style = MaterialTheme.typography.titleMedium, color = Palette.Accent2)
            }
            AppSlider(offset.toFloat(), { offset = (it / 10).toInt() * 10 }, valueRange = -200f..200f)
            Hint("彈得準但成日判遲／早，就調一調「對拍微調」。箭咀同首歌唔夾，就用上面「節拍對準」。")
        }
        GhostButton("刪除首歌", { confirmDelete = true }, Modifier.fillMaxWidth(), color = Palette.Miss, emoji = "🗑️")
    }

    if (editSpan >= 0) chart.chords.getOrNull(editSpan)?.let { sp ->
        val same = chart.chords.count { ChordNames.normalize(it.original) == ChordNames.normalize(sp.original) }
        ChordEditDialog(sp, chart.capo, same, onDismiss = { editSpan = -1 },
            onSave = { text, scope -> onEditChord(sp.start, sp.end, text, scope); editSpan = -1 })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
        title = { Text("刪除 ${entry.title}？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("首歌嘅譜同音訊都會刪除。", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("刪除", color = Palette.Miss, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Palette.Text, style = MaterialTheme.typography.labelLarge) } })
}
