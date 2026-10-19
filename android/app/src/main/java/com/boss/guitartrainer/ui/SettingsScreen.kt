package com.boss.guitartrainer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.AudioEngine
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
    var url by remember { mutableStateOf(s.serverUrl) }
    var test by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Screen("設定", { nav.popBackStack() }) {
        SectionCard("咪高峰") {
            Hint("如果唔用耳機、App 播聲時判定亂跳，試「回音消除」。UNPROCESSED 最準但部分手機唔支援。")
            AudioEngine.MicSource.entries.forEach { m ->
                Toggle(m.label, s.micSource == m) { if (it) store.updateSettings { st -> st.copy(micSource = m) } }
            }
            val eng = AudioEngine.get(ctx)
            if (eng.sourceInUse.isNotBlank()) Hint("上次使用：${eng.sourceInUse}${if (eng.aecActive) "（AEC 開咗）" else ""}")
        }
        SectionCard("調音") {
            Text("A4 = %.1f Hz".format(s.a4))
            Slider(s.a4.toFloat(), { v -> store.updateSettings { it.copy(a4 = (v * 2).toInt() / 2.0) } }, valueRange = 430f..450f)
        }
        SectionCard("指彈 AI 驗證（Basic Pitch）") {
            Toggle("開啟（每秒用 AI 再確認一次彈中嘅音）", s.useBasicPitch) { v -> store.updateSettings { it.copy(useBasicPitch = v) } }
            Hint(if (BasicPitch.available(ctx)) "模型已內置（Spotify Basic Pitch，Apache-2.0）。Phase 0：驗證準確度 88%（DSP 84%）。" else "搵唔到模型檔")
        }
        SectionCard("節拍器") {
            Text("音量 ${(s.metronomeVolume * 100).toInt()}%")
            Slider(s.metronomeVolume, { v -> store.updateSettings { it.copy(metronomeVolume = v) } }, valueRange = 0.1f..1f)
        }
        SectionCard("PC 分析伺服器") {
            OutlinedTextField(url, { url = it }, label = { Text("例如 http://192.168.1.10:8765 或 Tailscale 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                store.updateSettings { it.copy(serverUrl = url.trim()) }
                test = "測試中…"
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        runCatching { ServerApi(url).health() }.fold({ "✓ 連到（v${it.version}，Demucs：${if (it.demucs) "有" else "冇"}，yt-dlp：${if (it.ytdlp) "有" else "冇"}）" }, { "✗ ${it.message}" })
                    }
                }
            }) { Text("儲存並測試") }
            test?.let { Text(it, color = if (it.startsWith("✓")) Palette.Perfect else Palette.Miss) }
            Hint("屋企部電腦跑 server（睇 server/README.md）。出街用就裝 Tailscale，填 100.x.x.x 地址。")
        }
        SectionCard("其他") {
            Button(onClick = { nav.navigate("calibration") }) { Text("校準（延遲／上下掃）") }
            Hint("版本 1.0 · 資料只存喺本機（files/），解除安裝會清除。")
        }
    }
}
