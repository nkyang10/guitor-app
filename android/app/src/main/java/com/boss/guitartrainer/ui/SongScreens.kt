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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.SongEntry
import com.boss.guitartrainer.data.SongImporter
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordNames
import com.boss.guitartrainer.music.Tunings
import com.boss.guitartrainer.net.RemoteSong
import com.boss.guitartrainer.net.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SongsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val settings by store.settings.collectAsState()
    val songs by store.songs.collectAsState()
    val shared by AppState.sharedLink.collectAsState()
    // the import job lives in SongImporter (app scope): switching tabs keeps it running and shows it again on return
    val imp by SongImporter.state.collectAsState()
    var link by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<RemoteSong>>(emptyList()) }
    var separate by remember { mutableStateOf(true) }

    LaunchedEffect(shared) { shared?.let { link = it; AppState.sharedLink.value = null } }
    LaunchedEffect(Unit) { SongImporter.urlDone.collect { if (link.trim() == it) link = "" } }
    LaunchedEffect(settings.serverUrl) {
        server = "檢查緊…"
        server = withContext(Dispatchers.IO) {
            runCatching { ServerApi(settings.serverUrl).health() }.fold(
                { h -> if (h.ok) "✓ 已連線（v${h.version}${if (h.demucs) "，有 Demucs 分離" else "，冇 Demucs"}）" else "✗ 伺服器回應異常" },
                { "✗ 連唔到 ${settings.serverUrl}" })
        }
    }
    // server song list: when connected, and again after each finished job
    LaunchedEffect(server, imp.busy) {
        if (server?.startsWith("✓") == true && !imp.busy)
            remote = withContext(Dispatchers.IO) { runCatching { ServerApi(settings.serverUrl).songs() }.getOrDefault(remote) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) SongImporter.importLocal(ctx, uri)
    }
    val level = if (settings.difficulty.preset == "beginner") 1 else 2
    val notLocal = remote.filter { r -> songs.none { it.id == r.id } }
    SongsContent(server, link, { link = it }, separate, { separate = it }, imp.busy, imp.progress, imp.status, songs, notLocal,
        onAnalyze = { SongImporter.analyzeUrl(ctx, settings.serverUrl, link.trim(), separate, level) },
        onPick = { picker.launch(arrayOf("audio/*")) },
        onOpen = { nav.navigate("song/${Uri.encode(it)}") },
        onDownload = { r -> SongImporter.downloadRemote(ctx, settings.serverUrl, r) },
        onSettings = { nav.navigate("settings") },
        onCancel = { SongImporter.cancel() })
}

fun songTone(title: String): Tone = Tone.entries[Math.floorMod(title.hashCode(), Tone.entries.size)]

@Composable
fun SongsContent(server: String?, link: String, onLink: (String) -> Unit, separate: Boolean, onSeparate: (Boolean) -> Unit, busy: Boolean,
                 prog: Double, status: String?, songs: List<SongEntry>, remote: List<RemoteSong>, onAnalyze: () -> Unit, onPick: () -> Unit,
                 onOpen: (String) -> Unit, onDownload: (RemoteSong) -> Unit, onSettings: () -> Unit, onCancel: () -> Unit = {}) {
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
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBar(prog.toFloat(), Modifier.weight(1f), color = Palette.Mint)
                Spacer(Modifier.width(10.dp))
                Text("%.0f%%".format(prog * 100), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Spacer(Modifier.width(8.dp))
                GhostButton("取消", onCancel, height = 40.dp)
            }
            status?.let { Text(it, color = if (it.startsWith("✗")) Palette.Miss else if (it.startsWith("✓")) Palette.MintDeep else Palette.Text,
                style = MaterialTheme.typography.bodyMedium) }
            Hint("手機離線分析最長 10 分鐘（檔案 300 MB 以下），長啲嘅歌只會分析頭 10 分鐘。\n" +
                "提示：喺 YouTube App 撳「分享 → 結他練習」都可以直接貼連結。只供個人練習用。")
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
