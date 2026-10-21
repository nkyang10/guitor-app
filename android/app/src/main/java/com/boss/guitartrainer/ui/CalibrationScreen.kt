package com.boss.guitartrainer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Calibration
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

    CalibrationContent(cal, busy, progress, msg, beat, prompt, dirMsg, onBack = { nav.popBackStack() },
        onAuto = {
            busy = "auto"; msg = null
            scope.launch {
                val r = calib.latencyAuto { progress = it }
                busy = null
                msg = if (r.ok) "✓ 延遲 %.0f ms（±%.0f，%d/%d）".format(r.medianMs, r.sdMs, r.matched, r.total) else "✗ ${r.message}"
                if (r.ok) store.updateCalibration { it.copy(latencyMs = r.medianMs, latencyMethod = "auto", latencySdMs = r.sdMs, latencyAt = System.currentTimeMillis()) }
            }
        },
        onManual = {
            busy = "manual"; msg = null
            scope.launch {
                val r = calib.latencyManual(80, 16) { beat = it }
                busy = null; beat = -99
                msg = if (r.ok) "✓ 延遲 %.0f ms（±%.0f，%d/%d）".format(r.medianMs, r.sdMs, r.matched, r.total) else "✗ ${r.message}"
                if (r.ok) store.updateCalibration { it.copy(latencyMs = r.medianMs, latencyMethod = "manual", latencySdMs = r.sdMs, latencyAt = System.currentTimeMillis()) }
            }
        },
        onClearLatency = { store.updateCalibration { it.copy(latencyMs = null, latencyMethod = null) } },
        onDirection = {
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
        },
        onResetDirection = { store.updateCalibration { it.copy(direction = null, directionSamples = 0) } })
}

@Composable
fun CalibrationContent(cal: Calibration, busy: String?, progress: Double, msg: String?, beat: Int, prompt: String, dirMsg: String?,
                       onBack: () -> Unit, onAuto: () -> Unit, onManual: () -> Unit, onClearLatency: () -> Unit,
                       onDirection: () -> Unit, onResetDirection: () -> Unit) {
    Screen("校準", onBack, subtitle = "做一次，判定就會準好多 🎚️") {
        StepCard(1, "延遲（喇叭／耳機 + 咪高峰）", trailing = { StatusBadge(cal.latencyMs != null) }) {
            Text(cal.latencyMs?.let { "目前：%.0f ms（%s，±%.0f ms）".format(it, if (cal.latencyMethod == "auto") "自動" else "手動", cal.latencySdMs ?: 0.0) } ?: "未校準",
                style = MaterialTheme.typography.titleSmall)
            Hint("自動：電話會播 12 下「嗒」聲再自己聽返，計出播放＋錄音延遲。請用電話喇叭（唔好戴耳機），音量 60% 以上，環境要靜。")
            PillButton("自動校準", onAuto, Modifier.fillMaxWidth(), enabled = busy == null, emoji = "🔊")
            if (busy == "auto") LevelBar(progress.toFloat(), color = Palette.Mint)
            Hint("手動：跟住節拍器喺每一下「嗒」同時掃一下弦（16 下）。藍牙耳機延遲大，一定要用呢個方法。")
            SoftButton("手動校準（跟拍掃弦）", onManual, Modifier.fillMaxWidth(), Tone.SKY, enabled = busy == null, emoji = "🥁")
            if (busy == "manual") BigPrompt(if (beat < 0) "預備" else "${beat + 1}", if (beat < 0) "準備好…" else "掃！ ${beat + 1}/16", Tone.CORAL)
            msg?.let { ResultLine(it) }
            if (cal.latencyMs != null) GhostButton("清除延遲設定", onClearLatency, Modifier.fillMaxWidth(), color = Palette.Muted)
        }
        StepCard(2, "上下掃方向（個人化）", trailing = { StatusBadge(cal.direction != null) }) {
            val d = cal.direction
            Text(if (d == null) "未校準（用內置通用模型，約 75% 準）" else
                "已校準：%s模型，你嘅掃弦準確度 %.0f%%（通用模型 %.0f%%），樣本 %d".format(if (d.kind == "personal") "個人" else "通用",
                    d.cvAccuracy * 100, d.genericAccuracy * 100, d.trainedOn), style = MaterialTheme.typography.titleSmall)
            Hint("跟住提示，用 C 和弦以 60 BPM 交替 ↓↑ 掃 8 小節（64 下）。正常力度、正常掃法就得。")
            if (busy == "dir") BigPrompt(prompt.ifBlank { "…" }, when (prompt) { "↓" -> "向下掃"; "↑" -> "向上掃"; else -> "預備…" },
                if (prompt == "↑") Tone.SKY else Tone.CORAL)
            PillButton("開始方向校準", onDirection, Modifier.fillMaxWidth(), enabled = busy == null, emoji = "↕️")
            dirMsg?.let { ResultLine(it) }
            if (d != null) GhostButton("用返通用模型", onResetDirection, Modifier.fillMaxWidth(), color = Palette.Muted)
            Hint("點解唔係 100%？手機咪高峰只聽到聲，向下掃（低音弦先響）同向上掃（高音弦先響）嘅時間差只有幾十毫秒。所以方向只喺模型有信心時先計分。")
        }
    }
}

@Composable
private fun StatusBadge(done: Boolean) = Badge(if (done) "✓ 已完成" else "未做", if (done) Palette.MintDeep else Palette.PeachDeep)

@Composable
private fun ResultLine(text: String) {
    val ok = text.startsWith("✓")
    Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(if (ok) Palette.MintSoft else Palette.RoseSoft)
        .then(Modifier)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) Palette.MintDeep else Palette.RoseDeep,
            modifier = Modifier.padding12())
    }
}

private fun Modifier.padding12() = this.then(Modifier.padding(12.dp))

@Composable
private fun BigPrompt(big: String, label: String, tone: Tone) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(130.dp).clip(CircleShape).background(SolidColor(tone.soft)), contentAlignment = Alignment.Center) {
            AnimatedContent(big, transitionSpec = { (scaleIn(initialScale = 1.4f) + fadeIn()) togetherWith fadeOut() }, label = "prompt") { b ->
                Text(b, style = if (b.length > 2) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge, color = tone.deep)
            }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = tone.deep)
    }
}
