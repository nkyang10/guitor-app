package com.boss.guitartrainer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.boss.guitartrainer.music.PatternLibrary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.analysis.AudioDecoder
import com.boss.guitartrainer.analysis.SongAnalyzer
import com.boss.guitartrainer.data.SongEntry
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordNames
import com.boss.guitartrainer.music.Tunings
import com.boss.guitartrainer.net.RemoteSong
import com.boss.guitartrainer.net.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SongsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val songs by store.songs.collectAsState()
    val shared by AppState.sharedLink.collectAsState()
    var link by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var prog by remember { mutableDoubleStateOf(0.0) }
    var server by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<RemoteSong>>(emptyList()) }
    var separate by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(shared) { shared?.let { link = it; AppState.sharedLink.value = null } }
    LaunchedEffect(settings.serverUrl) {
        server = "檢查緊…"
        server = withContext(Dispatchers.IO) {
            runCatching { ServerApi(settings.serverUrl).health() }.fold(
                { h -> if (h.ok) "✓ 已連線（v${h.version}${if (h.demucs) "，有 Demucs 分離" else "，冇 Demucs"}）" else "✗ 伺服器回應異常" },
                { "✗ 連唔到 ${settings.serverUrl}" })
        }
        if (server?.startsWith("✓") == true) remote = withContext(Dispatchers.IO) { runCatching { ServerApi(settings.serverUrl).songs() }.getOrDefault(emptyList()) }
    }

    suspend fun importRemote(api: ServerApi, songId: String, source: String?) {
        status = "下載譜同音訊…"
        val chartText = withContext(Dispatchers.IO) { api.chart(songId) }
        val chart = Chart.parse(chartText)
        store.saveSongChart(songId, chart.copy(id = songId), edited = false)
        val dir = store.songDir(songId)
        withContext(Dispatchers.IO) { api.download(songId, "mix", File(dir, "mix.m4a")) { prog = it * 0.7 } }
        val hasNg = withContext(Dispatchers.IO) { runCatching { api.download(songId, "no_guitar", File(dir, "no_guitar.m4a")) { prog = 0.7 + it * 0.3 } }.getOrDefault(false) }
        store.upsertSong(SongEntry(songId, chart.title, source ?: chart.source, System.currentTimeMillis(), chart.bpm, chart.key, hasNg))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "讀取檔案…"; prog = 0.0
        scope.launch {
            try {
                val id = "local-" + System.currentTimeMillis()
                val dir = store.songDir(id)
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "本機歌曲"
                val ext = ctx.contentResolver.getType(uri)?.substringAfter('/')?.let { if (it == "mpeg") "mp3" else it } ?: "m4a"
                val f = File(dir, "mix.$ext")
                withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                status = "解碼…"
                val x = withContext(Dispatchers.Default) { AudioDecoder.decodeMono(f) { prog = it * 0.3 } }
                status = "分析節拍同和弦（手機版，冇分離音軌）…"
                val chart = withContext(Dispatchers.Default) { SongAnalyzer.analyze(x, name, uri.toString(), 1) { prog = 0.3 + it * 0.7 } }
                store.saveSongChart(id, chart.copy(id = id), edited = false)
                store.upsertSong(SongEntry(id, name, "本機檔案", System.currentTimeMillis(), chart.bpm, chart.key, false, local = true))
                status = "✓ 完成：$name（%.0f BPM，%s 調%s）".format(chart.bpm, chart.key, if (chart.capo > 0) "，建議 Capo ${chart.capo}" else "")
            } catch (e: Exception) { status = "✗ 分析失敗：${e.message}" }
            busy = false
        }
    }

    val notLocal = remote.filter { r -> songs.none { it.id == r.id } }
    SongsContent(server, link, { link = it }, separate, { separate = it }, busy, prog, status, songs, notLocal,
        onAnalyze = {
            busy = true; status = "送出分析…"; prog = 0.0
            scope.launch {
                try {
                    val api = ServerApi(settings.serverUrl)
                    val job = withContext(Dispatchers.IO) { api.analyze(link.trim(), separate, if (settings.difficulty.preset == "beginner") 1 else 2) }
                    while (true) {
                        val st = withContext(Dispatchers.IO) { api.job(job) }
                        prog = st.progress; status = st.message.ifBlank { st.status }
                        if (st.status == "done" && st.song_id != null) { importRemote(api, st.song_id, link.trim()); status = "✓ 完成"; link = ""; break }
                        if (st.status == "error") { status = "✗ ${st.error ?: st.message}"; break }
                        delay(1500)
                    }
                } catch (e: Exception) { status = "✗ ${e.message}（檢查伺服器地址／同一 Wi-Fi／Tailscale）" }
                busy = false
            }
        },
        onPick = { picker.launch(arrayOf("audio/*")) },
        onOpen = { nav.navigate("song/${Uri.encode(it)}") },
        onDownload = { r ->
            busy = true
            scope.launch {
                try { importRemote(ServerApi(settings.serverUrl), r.id, r.source); status = "✓ 已下載 ${r.title}" } catch (e: Exception) { status = "✗ ${e.message}" }
                busy = false
            }
        },
        onSettings = { nav.navigate("settings") })
}

fun songTone(title: String): Tone = Tone.entries[Math.floorMod(title.hashCode(), Tone.entries.size)]

@Composable
fun SongsContent(server: String?, link: String, onLink: (String) -> Unit, separate: Boolean, onSeparate: (Boolean) -> Unit, busy: Boolean,
                 prog: Double, status: String?, songs: List<SongEntry>, remote: List<RemoteSong>, onAnalyze: () -> Unit, onPick: () -> Unit,
                 onOpen: (String) -> Unit, onDownload: (RemoteSong) -> Unit, onSettings: () -> Unit) {
    val ok = server?.startsWith("✓") == true
    Screen("歌曲", null, subtitle = "跟住真歌彈，最開心 🎶", actions = { TopAction("⚙️", "設定", onSettings) }) {
        Row(Modifier.clip(CircleShape).background(if (ok) Palette.MintSoft else Palette.Surface).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Palette.MintDeep else Palette.Grid))
            Spacer(Modifier.width(8.dp))
            Text(when { server == null -> "PC 伺服器：—"; ok -> "PC 伺服器" + server.removePrefix("✓ "); else -> "PC 伺服器未連線（仍然可以用手機入面嘅歌）" },
                style = MaterialTheme.typography.labelMedium, color = if (ok) Palette.MintDeep else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SectionTitle("我嘅歌", "🎧", trailing = if (songs.isNotEmpty()) "${songs.size} 首" else null)
        if (songs.isEmpty()) SectionCard { EmptyHint("🎵", "未有歌。喺下面貼一條 YouTube 連結，\n或者揀手機入面嘅音樂檔。") }
        songs.forEach { s ->
            val tone = songTone(s.title)
            ActionRow(s.title.take(1).uppercase(), s.title,
                "%.0f BPM · %s 調%s%s".format(s.bpm, s.key ?: "?", if (s.hasNoGuitarStem) " · 有去結他伴奏" else "",
                    s.bestAccuracy?.let { " · 最佳 %.0f%%".format(it) } ?: ""), tone) { onOpen(s.id) }
        }
        SectionTitle("加一首歌", "✨")
        SectionCard {
            OutlinedTextField(link, onLink, label = { Text("貼上 YouTube 連結") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp), colors = softFieldColors())
            Toggle("分離結他音軌（Demucs，較慢）", separate, onSeparate)
            PillButton("用 PC 分析", onAnalyze, Modifier.fillMaxWidth(), enabled = !busy && link.startsWith("http"), emoji = "🪄")
            GhostButton("揀手機入面嘅音樂檔（離線分析）", onPick, Modifier.fillMaxWidth(), enabled = !busy, emoji = "📁")
            if (busy) LevelBar(prog.toFloat(), color = Palette.Mint)
            status?.let { Text(it, color = if (it.startsWith("✗")) Palette.Miss else if (it.startsWith("✓")) Palette.MintDeep else Palette.Text,
                style = MaterialTheme.typography.bodyMedium) }
            Hint("提示：喺 YouTube App 撳「分享 → 結他練習」都可以直接貼連結。只供個人練習用。")
        }
        if (remote.isNotEmpty()) {
            SectionTitle("伺服器上已分析（未下載）", "☁️")
            SectionCard {
                remote.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(r.title.take(1).uppercase(), songTone(r.title), size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SoftButton("下載", { onDownload(r) }, tone = Tone.SKY, enabled = !busy, height = 40.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun SongDetailScreen(nav: NavController, id: String) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val entry = store.songs.collectAsState().value.firstOrNull { it.id == id }
    var version by remember { mutableIntStateOf(0) }
    val base = remember(version) { store.songChart(id) }
    if (entry == null || base == null) { Screen("歌曲", { nav.popBackStack() }) { EmptyHint("🤔", "搵唔到首歌") }; return }
    val lib = remember { AppState.patterns(ctx) }
    val edited = remember(version) { java.io.File(store.songDir(id), "chart_edited.json").exists() }
    SongDetailContent(entry, base, lib, if (settings.difficulty.preset == "beginner") 1 else 2, settings.songExtraOffsetMs, edited,
        onBack = { nav.popBackStack() },
        onSaveChord = { idx, text ->
            val n = ChordNames.normalize(text)
            val chords = base.chords.toMutableList()
            chords[idx] = base.chords[idx].copy(original = n, label = n)
            store.saveSongChart(id, base.copy(chords = chords), edited = true)
            version++
        },
        onReset = { store.resetSongEdits(id); version++ },
        onDelete = { store.deleteSong(id); nav.popBackStack() },
        onStart = { simplify, tempo, stem, overrides, loopSection, offset ->
            store.updateSettings { it.copy(songExtraOffsetMs = offset) }
            val tuning = Tunings.byId(settings.tuningId)
            val chart = ChartBuilder.song(base, lib, base.sections.firstOrNull()?.pattern ?: "s_folk", simplify, tuning, overrides)
            val diff = settings.difficulty.copy(tempoPercent = tempo, simplifyChords = simplify < 3, waitMode = false, metronome = false)
            val loop = if (loopSection >= 0) base.sections[loopSection].let { it.start..it.end } else null
            val audio = store.songAudio(id, stem) ?: store.songAudio(id, "mix")
            AppState.pendingGame = GameRequest(chart, diff, "song", entry.title, null, id, audio, loop, 0)
            nav.navigate("game")
        })
}

private fun mmss(t: Double) = "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())

@Composable
fun SongDetailContent(entry: SongEntry, base: Chart, lib: PatternLibrary, defaultSimplify: Int, defaultOffset: Int, edited: Boolean,
                      onBack: () -> Unit, onSaveChord: (Int, String) -> Unit, onReset: () -> Unit, onDelete: () -> Unit,
                      onStart: (simplify: Int, tempo: Int, stem: String, overrides: Map<Int, String>, loopSection: Int, offset: Int) -> Unit) {
    var simplify by remember { mutableIntStateOf(defaultSimplify) }
    var tempo by remember { mutableIntStateOf(80) }
    var stem by remember { mutableStateOf(if (entry.hasNoGuitarStem) "no_guitar" else "mix") }
    val overrides = remember { mutableStateMapOf<Int, String>() }
    var loopSection by remember { mutableIntStateOf(-1) }
    var editSpan by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableIntStateOf(defaultOffset) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tone = songTone(entry.title)

    Screen(entry.title, onBack, subtitle = entry.source ?: "本機歌曲", bottomBar = {
        BottomCta { PillButton("開始跟彈", { onStart(simplify, tempo, stem, overrides.toMap(), loopSection, offset) }, Modifier.fillMaxWidth(), emoji = "▶️") }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("速度", "%.0f".format(base.bpm), Modifier.weight(1f), tone.deep, tone)
            StatTile("調性", base.key ?: "?", Modifier.weight(1f), Palette.LilacDeep, Tone.LILAC)
            StatTile("Capo", if (base.capo > 0) "${base.capo}" else "唔使", Modifier.weight(1f), Palette.MintDeep, Tone.MINT)
            StatTile("最佳", entry.bestAccuracy?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f), Palette.SunDeep, Tone.SUN)
        }
        if (base.capo > 0) Hint("Capo 夾第 ${base.capo} 格，和弦按 capo 後嘅指法顯示。", Palette.SunDeep)
        SectionCard("段落同節奏型", icon = "🧩") {
            if (base.sections.isEmpty()) Hint("冇段落資料，會全首用同一個節奏型。")
            base.sections.forEachIndexed { i, s ->
                var open by remember { mutableStateOf(false) }
                val pid = overrides[i] ?: s.pattern ?: "s_folk"
                val pname = lib.strum(pid)?.name ?: lib.finger(pid)?.name ?: pid
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (loopSection == i) Palette.SkySoft else Palette.Bg)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.label}  ·  ${mmss(s.start)}", style = MaterialTheme.typography.titleSmall)
                        Text(pname, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
                        lib.strum(pid)?.let { Text(colorizePattern(strumPreview(it)), fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1) }
                    }
                    Box {
                        SoftButton("改", { open = true }, tone = Tone.PEACH, height = 40.dp)
                        DropdownMenu(open, { open = false }, containerColor = Palette.Surface) {
                            (lib.strum.map { it.id to "掃：${it.name}" } + lib.finger.map { it.id to "指彈：${it.name}" }).forEach { (pidOpt, label) ->
                                DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.bodyMedium) }, onClick = { overrides[i] = pidOpt; open = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    SoftButton(if (loopSection == i) "🔁 ✓" else "🔁", { loopSection = if (loopSection == i) -1 else i },
                        tone = if (loopSection == i) Tone.SKY else Tone.LILAC, height = 40.dp)
                }
            }
            if (loopSection >= 0) Hint("🔁 會循環練「${base.sections[loopSection].label}」。", Palette.Accent2)
        }
        SectionCard("和弦譜（撳和弦可以改）", icon = "🎼") {
            base.chords.chunked(4).forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { ci, sp ->
                        val idx = ri * 4 + ci
                        val fingered = if (sp.original == "N") "N" else ChordNames.transpose(sp.original, -base.capo)
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
            Hint("彈得準但成日判遲／早，就調一調「對拍微調」。")
        }
        GhostButton("刪除首歌", { confirmDelete = true }, Modifier.fillMaxWidth(), color = Palette.Miss, emoji = "🗑️")
    }

    if (editSpan >= 0) {
        val sp = base.chords[editSpan]
        var text by remember(editSpan) { mutableStateOf(sp.original) }
        AlertDialog(onDismissRequest = { editSpan = -1 }, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
            title = { Text("改和弦（實際音高）", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("例如 G、Em、D7、N（無和弦）") },
                        shape = RoundedCornerShape(16.dp), colors = softFieldColors())
                    if (base.capo > 0) Hint("Capo ${base.capo}：顯示會自動轉做 capo 後指法。")
                }
            },
            confirmButton = { TextButton(onClick = { onSaveChord(editSpan, text); editSpan = -1 }) { Text("儲存", color = Palette.Accent, style = MaterialTheme.typography.labelLarge) } },
            dismissButton = { TextButton(onClick = { editSpan = -1 }) { Text("取消", color = Palette.Text, style = MaterialTheme.typography.labelLarge) } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
        title = { Text("刪除 ${entry.title}？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("首歌嘅譜同音訊都會刪除。", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("刪除", color = Palette.Miss, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Palette.Text, style = MaterialTheme.typography.labelLarge) } })
}
