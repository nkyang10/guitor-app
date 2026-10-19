package com.boss.guitartrainer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
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

    Screen("歌曲（YouTube／本機）", { nav.popBackStack() }) {
        SectionCard("加歌") {
            Text("PC 伺服器：${server ?: "—"}", fontSize = 13.sp, color = if (server?.startsWith("✓") == true) Palette.Perfect else Palette.Muted)
            OutlinedTextField(link, { link = it }, label = { Text("貼上 YouTube 連結") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Toggle("分離結他音軌（Demucs，較慢）", separate) { separate = it }
            Button(enabled = !busy && link.startsWith("http"), modifier = Modifier.fillMaxWidth(), onClick = {
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
            }) { Text("用 PC 分析") }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { picker.launch(arrayOf("audio/*")) }) { Text("揀手機入面嘅音樂檔（離線分析）") }
            if (busy) LevelBar(prog.toFloat())
            status?.let { Text(it, color = if (it.startsWith("✗")) Palette.Miss else Palette.Text) }
            Hint("提示：喺 YouTube App 撳「分享 → 結他練習」都可以直接貼連結。只供個人練習用。")
        }
        if (songs.isNotEmpty()) SectionCard("我嘅歌") {
            songs.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("%.0f BPM · %s%s%s".format(s.bpm, s.key ?: "?", if (s.hasNoGuitarStem) " · 有去結他伴奏" else "",
                            s.bestAccuracy?.let { " · 最佳 %.0f%%".format(it) } ?: ""), fontSize = 12.sp, color = Palette.Muted)
                    }
                    TextButton(onClick = { nav.navigate("song/${Uri.encode(s.id)}") }) { Text("打開") }
                }
            }
        }
        val notLocal = remote.filter { r -> songs.none { it.id == r.id } }
        if (notLocal.isNotEmpty()) SectionCard("伺服器上已分析（未下載）") {
            notLocal.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, Modifier.weight(1f), maxLines = 1)
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { importRemote(ServerApi(settings.serverUrl), r.id, r.source); status = "✓ 已下載 ${r.title}" } catch (e: Exception) { status = "✗ ${e.message}" }
                            busy = false
                        }
                    }) { Text("下載") }
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
    if (entry == null || base == null) { Screen("歌曲", { nav.popBackStack() }) { Text("搵唔到首歌") }; return }
    val lib = remember { AppState.patterns(ctx) }
    var simplify by remember { mutableIntStateOf(if (settings.difficulty.preset == "beginner") 1 else 2) }
    var tempo by remember { mutableIntStateOf(80) }
    var stem by remember { mutableStateOf(if (entry.hasNoGuitarStem) "no_guitar" else "mix") }
    val overrides = remember { mutableStateMapOf<Int, String>() }
    var loopSection by remember { mutableIntStateOf(-1) }
    var editSpan by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableIntStateOf(settings.songExtraOffsetMs) }
    var confirmDelete by remember { mutableStateOf(false) }

    Screen(entry.title, { nav.popBackStack() }) {
        SectionCard {
            KeyValue("速度", "%.0f BPM".format(base.bpm))
            KeyValue("調性", base.key ?: "?")
            KeyValue("Capo", if (base.capo > 0) "第 ${base.capo} 格（和弦按 capo 後指法顯示）" else "唔使")
            KeyValue("來源", entry.source ?: "—")
        }
        SectionCard("段落同節奏型（可改）") {
            if (base.sections.isEmpty()) Hint("冇段落資料，會全首用同一個節奏型。")
            base.sections.forEachIndexed { i, s ->
                var open by remember { mutableStateOf(false) }
                val pid = overrides[i] ?: s.pattern ?: "s_folk"
                val pname = lib.strum(pid)?.name ?: lib.finger(pid)?.name ?: pid
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.label}  %d:%02d".format((s.start / 60).toInt(), (s.start % 60).toInt()), fontWeight = FontWeight.Bold)
                        Text(pname + (lib.strum(pid)?.let { "  " + strumPreview(it) } ?: ""), fontSize = 12.sp, color = Palette.Accent2, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { open = true }) { Text("改") }
                    TextButton(onClick = { loopSection = if (loopSection == i) -1 else i }) { Text(if (loopSection == i) "🔁✓" else "🔁") }
                    DropdownMenu(open, { open = false }) {
                        (lib.strum.map { it.id to "掃：${it.name}" } + lib.finger.map { it.id to "指彈：${it.name}" }).forEach { (pidOpt, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { overrides[i] = pidOpt; open = false })
                        }
                    }
                }
            }
        }
        SectionCard("和弦譜（撳和弦可以改）") {
            val spans = base.chords
            val rows = spans.chunked(4)
            rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { ci, sp ->
                        val idx = ri * 4 + ci
                        val fingered = if (sp.original == "N") "N" else ChordNames.transpose(sp.original, -base.capo)
                        OutlinedButton(onClick = { editSpan = idx }, modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(ChordNames.simplify(fingered, simplify), fontWeight = FontWeight.Bold)
                                Text("%d:%02d".format((sp.start / 60).toInt(), (sp.start % 60).toInt()), fontSize = 10.sp, color = Palette.Muted)
                            }
                        }
                    }
                    repeat(4 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
            if (java.io.File(store.songDir(id), "chart_edited.json").exists())
                TextButton(onClick = { store.resetSongEdits(id); version++ }) { Text("還原自動分析結果") }
        }
        SectionCard("跟彈設定") {
            ChipRow(listOf("1" to "簡化（初學）", "2" to "中等", "3" to "原和弦"), simplify.toString()) { simplify = it.toInt() }
            if (entry.hasNoGuitarStem) ChipRow(listOf("no_guitar" to "去結他伴奏", "mix" to "原曲"), stem) { stem = it }
            Text("速度 $tempo%", color = Palette.Muted)
            Slider(tempo.toFloat(), { tempo = (it / 5).toInt() * 5 }, valueRange = 50f..100f)
            Text("對拍微調 ${offset} ms（彈得準但成日判遲／早就調呢度）", color = Palette.Muted, fontSize = 12.sp)
            Slider(offset.toFloat(), { offset = (it / 10).toInt() * 10 }, valueRange = -200f..200f)
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            store.updateSettings { it.copy(songExtraOffsetMs = offset) }
            val tuning = Tunings.byId(settings.tuningId)
            val chart = ChartBuilder.song(base, lib, base.sections.firstOrNull()?.pattern ?: "s_folk", simplify, tuning, overrides.toMap())
            val diff = settings.difficulty.copy(tempoPercent = tempo, simplifyChords = simplify < 3, waitMode = false, metronome = false)
            val loop = if (loopSection >= 0) base.sections[loopSection].let { it.start..it.end } else null
            val audio = store.songAudio(id, stem) ?: store.songAudio(id, "mix")
            AppState.pendingGame = GameRequest(chart, diff, "song", entry.title, null, id, audio, loop, 0)
            nav.navigate("game")
        }) { Text("開始跟彈 ▶", fontSize = 18.sp) }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("刪除首歌", color = Palette.Miss) }
    }

    if (editSpan >= 0) {
        val sp = base.chords[editSpan]
        var text by remember(editSpan) { mutableStateOf(sp.original) }
        AlertDialog(onDismissRequest = { editSpan = -1 }, title = { Text("改和弦（實際音高）") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("例如 G、Em、D7、N（無和弦）") })
                    if (base.capo > 0) Hint("Capo ${base.capo}：顯示會自動轉做 capo 後指法。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = ChordNames.normalize(text)
                    val chords = base.chords.toMutableList()
                    chords[editSpan] = sp.copy(original = n, label = n)
                    store.saveSongChart(id, base.copy(chords = chords), edited = true)
                    editSpan = -1; version++
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { editSpan = -1 }) { Text("取消") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("刪除 ${entry.title}？") },
        confirmButton = { TextButton(onClick = { store.deleteSong(id); confirmDelete = false; nav.popBackStack() }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
