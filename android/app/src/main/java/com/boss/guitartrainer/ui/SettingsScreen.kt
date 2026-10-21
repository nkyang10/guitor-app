package com.boss.guitartrainer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.data.AppSettings
import com.boss.guitartrainer.data.CrashLog
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.dsp.BasicPitch
import com.boss.guitartrainer.net.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val s by store.settings.collectAsState()
    var test by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val eng = AudioEngine.get(ctx)
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
    SettingsContent(s, eng.sourceInUse, eng.aecActive, BasicPitch.available(ctx), version, test,
        onUpdate = { f -> store.updateSettings(f) },
        onTest = { url ->
            store.updateSettings { it.copy(serverUrl = url.trim()) }
            test = "測試中…"
            scope.launch {
                test = withContext(Dispatchers.IO) {
                    runCatching { ServerApi(url).health() }.fold({ "✓ 連到（v${it.version}，Demucs：${if (it.demucs) "有" else "冇"}，yt-dlp：${if (it.ytdlp) "有" else "冇"}）" }, { "✗ ${it.message}" })
                }
            }
        },
        onCalibrate = { nav.navigate("calibration") }, onBack = { nav.popBackStack() },
        crash = crash,
        onShareCrash = { crash?.let { shareCrash(ctx, it) } },
        onClearCrash = { CrashLog.clear(ctx); crash = null })
}

private fun shareCrash(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "結他練習 App 閃退紀錄")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { ctx.startActivity(Intent.createChooser(send, "分享閃退紀錄")) }
}

@Composable
fun SettingsContent(s: AppSettings, sourceInUse: String, aecActive: Boolean, basicPitch: Boolean, version: String, test: String?,
                    onUpdate: ((AppSettings) -> AppSettings) -> Unit, onTest: (String) -> Unit, onCalibrate: () -> Unit, onBack: () -> Unit,
                    crash: String? = null, onShareCrash: () -> Unit = {}, onClearCrash: () -> Unit = {}) {
    var name by remember { mutableStateOf(s.nickname) }
    var url by remember { mutableStateOf(s.serverUrl) }
    Screen("設定", onBack, subtitle = "按你鍾意嘅方式練 🌷") {
        if (crash != null) SectionCard("上次閃退紀錄", icon = "🧯") {
            val lines = crash.lines()
            fun field(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
            field("時間：")?.let { Text("時間：$it", style = MaterialTheme.typography.bodyMedium) }
            field("當時：")?.let { Text("當時：$it", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            field("錯誤：")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Miss, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Hint("撳「分享」將紀錄傳俾開發者（WhatsApp／電郵都得），方便搵出原因。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("分享", onShareCrash, Modifier.weight(1f), Tone.ROSE, emoji = "📤")
                GhostButton("清除", onClearCrash, Modifier.weight(1f))
            }
        }
        SectionCard("關於你", icon = "🌷") {
            OutlinedTextField(name, { v -> name = v.take(12); onUpdate { it.copy(nickname = name.trim()) } },
                label = { Text("你嘅名（主頁會同你打招呼）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), colors = softFieldColors())
            Text("每日目標", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(5, 10, 15, 20, 30).map { "$it" to "$it 分鐘" }, s.dailyGoalMin.toString()) { v -> onUpdate { it.copy(dailyGoalMin = v.toInt()) } }
            Hint("每日練少少好過一次過練好耐。初學建議 10 分鐘 🌱")
        }
        SectionCard("咪高峰", icon = "🎤") {
            Hint("如果唔用耳機、App 播聲時判定亂跳，試「回音消除」。UNPROCESSED 最準但部分手機唔支援。")
            AudioEngine.MicSource.entries.forEach { m ->
                RadioRow(m.label, s.micSource == m) { onUpdate { st -> st.copy(micSource = m) } }
            }
            if (sourceInUse.isNotBlank()) Hint("上次使用：$sourceInUse${if (aecActive) "（AEC 開咗）" else ""}")
        }
        SectionCard("調音", icon = "🎯") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A4 標準音", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("%.1f Hz".format(s.a4), style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
            }
            AppSlider(s.a4.toFloat(), { v -> onUpdate { it.copy(a4 = (v * 2).toInt() / 2.0) } }, valueRange = 430f..450f)
        }
        SectionCard("指彈 AI 驗證（Basic Pitch）", icon = "🤖") {
            Toggle("開啟（每秒用 AI 再確認一次彈中嘅音）", s.useBasicPitch) { v -> onUpdate { it.copy(useBasicPitch = v) } }
            Hint(if (basicPitch) "模型已內置（Spotify Basic Pitch，Apache-2.0）。Phase 0：驗證準確度 88%（DSP 84%）。" else "搵唔到模型檔")
        }
        SectionCard("節拍器", icon = "⏱️") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${(s.metronomeVolume * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
            }
            AppSlider(s.metronomeVolume, { v -> onUpdate { it.copy(metronomeVolume = v) } }, valueRange = 0.1f..1f)
        }
        SectionCard("PC 分析伺服器", icon = "💻") {
            OutlinedTextField(url, { url = it }, label = { Text("例如 http://192.168.1.10:8765 或 Tailscale 地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = softFieldColors())
            SoftButton("儲存並測試", { onTest(url) }, Modifier.fillMaxWidth(), Tone.SKY, emoji = "📡")
            test?.let { Text(it, color = if (it.startsWith("✓")) Palette.MintDeep else if (it.startsWith("✗")) Palette.Miss else Palette.Muted,
                style = MaterialTheme.typography.bodyMedium) }
            Hint("屋企部電腦跑 server（睇 server/README.md）。出街用就裝 Tailscale，填 100.x.x.x 地址。")
        }
        ActionRow("🎚️", "校準", "延遲／上下掃方向", Tone.PEACH, onClick = onCalibrate)
        Text("版本 $version · 資料只存喺本機，解除安裝會清除。", style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Text("strum, smile, repeat ♪", fontFamily = ScriptFont, fontSize = 17.sp, color = Palette.Muted.copy(alpha = 0.75f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
