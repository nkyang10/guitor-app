package com.boss.guitartrainer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Calibrator
import com.boss.guitartrainer.game.DirectionWeight
import kotlinx.coroutines.launch

@Composable
fun CalibrationScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val cal by store.calibration.collectAsState()
    val settings by store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val calib = remember { Calibrator(ctx, settings.micSource) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var beat by remember { mutableIntStateOf(-99) }
    var prompt by remember { mutableStateOf("") }
    var dirMsg by remember { mutableStateOf<String?>(null) }

    Screen("校準", { nav.popBackStack() }) {
        SectionCard("1. 延遲（耳機／喇叭 + 咪高峰）") {
            Text(cal.latencyMs?.let { "目前：%.0f ms（%s，±%.0f ms）".format(it, if (cal.latencyMethod == "auto") "自動" else "手動", cal.latencySdMs ?: 0.0) } ?: "未校準",
                fontWeight = FontWeight.Bold)
            Hint("自動：電話會播 12 下「嗒」聲再自己聽返，計出播放＋錄音延遲。請用電話喇叭（唔好戴耳機），音量 60% 以上，環境要靜。")
            Button(enabled = busy == null, onClick = {
                busy = "auto"; msg = null
                scope.launch {
                    val r = calib.latencyAuto { progress = it }
                    busy = null
                    msg = if (r.ok) "✓ 延遲 %.0f ms（±%.0f，%d/%d）".format(r.medianMs, r.sdMs, r.matched, r.total) else "✗ ${r.message}"
                    if (r.ok) store.updateCalibration { it.copy(latencyMs = r.medianMs, latencyMethod = "auto", latencySdMs = r.sdMs, latencyAt = System.currentTimeMillis()) }
                }
            }) { Text("自動校準") }
            Hint("手動：跟住節拍器喺每一下「嗒」同時掃一下弦（16 下）。藍牙耳機延遲大，一定要用呢個方法。")
            OutlinedButton(enabled = busy == null, onClick = {
                busy = "manual"; msg = null
                scope.launch {
                    val r = calib.latencyManual(80, 16) { beat = it }
                    busy = null; beat = -99
                    msg = if (r.ok) "✓ 延遲 %.0f ms（±%.0f，%d/%d）".format(r.medianMs, r.sdMs, r.matched, r.total) else "✗ ${r.message}"
                    if (r.ok) store.updateCalibration { it.copy(latencyMs = r.medianMs, latencyMethod = "manual", latencySdMs = r.sdMs, latencyAt = System.currentTimeMillis()) }
                }
            }) { Text("手動校準（跟拍掃弦）") }
            if (busy == "auto") LevelBar(progress.toFloat())
            if (busy == "manual") Text(if (beat < 0) "預備…" else "掃！ ${beat + 1}/16", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Accent,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            msg?.let { Text(it, color = if (it.startsWith("✓")) Palette.Perfect else Palette.Miss) }
            if (cal.latencyMs != null) OutlinedButton(onClick = { store.updateCalibration { it.copy(latencyMs = null, latencyMethod = null) } }) { Text("清除延遲設定") }
        }
        SectionCard("2. 上下掃方向（個人化）") {
            val d = cal.direction
            Text(if (d == null) "未校準（用內置通用模型，約 75% 準）" else
                "已校準：%s模型，你嘅掃弦準確度 %.0f%%（通用模型 %.0f%%），樣本 %d".format(if (d.kind == "personal") "個人" else "通用",
                    d.cvAccuracy * 100, d.genericAccuracy * 100, d.trainedOn), fontWeight = FontWeight.Bold)
            Hint("跟住提示，用 C 和弦以 60 BPM 交替 ↓↑ 掃 8 小節（64 下）。正常力度、正常掃法就得。")
            if (busy == "dir") Text(prompt, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = if (prompt == "↓") Palette.Accent else Palette.Accent2,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Button(enabled = busy == null, onClick = {
                busy = "dir"; dirMsg = null
                scope.launch {
                    val r = calib.direction(60, 8) { i, dir -> prompt = if (i < 0) "${-i}" else if (dir == "D") "↓" else "↑" }
                    busy = null
                    if (!r.ok || r.model == null) dirMsg = "✗ ${r.message}" else {
                        store.updateCalibration { it.copy(direction = r.model, directionSamples = r.nDown + r.nUp) }
                        val best = r.model.cvAccuracy
                        dirMsg = "✓ ↓${r.nDown} ↑${r.nUp}：通用 %.0f%% → 個人 %.0f%%".format(r.genericAcc * 100, r.adaptedAcc * 100) +
                            when {
                                best >= 0.85 -> "\n可靠，可以用「正常（扣分）」。"
                                best >= 0.7 -> "\n一般，建議用「低（只提示）」。"
                                else -> "\n唔可靠，建議方向判斷設為「唔計」。"
                            }
                        if (best < 0.7) store.updateSettings { it.copy(difficulty = it.difficulty.copy(directionWeight = if (best < 0.6) DirectionWeight.OFF else DirectionWeight.LOW)) }
                    }
                }
            }) { Text("開始方向校準") }
            dirMsg?.let { Text(it, color = if (it.startsWith("✓")) Palette.Perfect else Palette.Miss) }
            if (d != null) OutlinedButton(onClick = { store.updateCalibration { it.copy(direction = null, directionSamples = 0) } }) { Text("用返通用模型") }
            Hint("點解唔係 100%？手機咪高峰只聽到聲，向下掃（低音弦先響）同向上掃（高音弦先響）嘅時間差只有幾十毫秒。所以方向只喺模型有信心時先計分。")
        }
    }
}
