package com.boss.guitartrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.audio.ClickTrack
import com.boss.guitartrainer.data.Store
import kotlinx.coroutines.delay

@Composable
fun MetronomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    var bpm by remember { mutableIntStateOf(store.settings.value.lastBpm) }
    var bpb by remember { mutableIntStateOf(4) }
    var running by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(-1) }
    val click = remember { ClickTrack() }
    val taps = remember { ArrayList<Long>() }
    DisposableEffect(Unit) { onDispose { click.stop(); store.updateSettings { it.copy(lastBpm = bpm) } } }
    LaunchedEffect(running, bpm, bpb) {
        if (!running) { click.stop(); beat = -1; return@LaunchedEffect }
        click.volume = store.settings.value.metronomeVolume
        click.start()
        click.clearScheduled()
        val sr = AudioEngine.SR
        val spbFrames = (60.0 / bpm * sr).toLong()
        var next = click.futureFrame(0.15)
        var k = 0L
        val scheduled = ArrayList<Pair<Long, Int>>()
        while (true) {
            val horizon = click.futureFrame(0.6)
            while (next < horizon) {
                val b = (k % bpb).toInt()
                click.schedule(next, if (b == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK)
                scheduled.add(next to b)
                next += spbFrames; k++
            }
            val nowFrame = click.nanosToFrame(System.nanoTime())
            scheduled.lastOrNull { it.first <= nowFrame }?.let { beat = it.second }
            scheduled.removeAll { it.first < nowFrame - spbFrames * 2 }
            delay(15)
        }
    }
    Screen("節拍器", { nav.popBackStack() }) {
        SectionCard {
            Text("$bpm BPM", fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Slider(value = bpm.toFloat(), onValueChange = { bpm = it.toInt() }, valueRange = 30f..220f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { bpm = (bpm - 5).coerceAtLeast(30) }) { Text("−5") }
                OutlinedButton(onClick = { bpm = (bpm - 1).coerceAtLeast(30) }) { Text("−1") }
                OutlinedButton(onClick = { bpm = (bpm + 1).coerceAtMost(220) }) { Text("+1") }
                OutlinedButton(onClick = { bpm = (bpm + 5).coerceAtMost(220) }) { Text("+5") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (b in 0 until bpb) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(
                        if (b == beat) (if (b == 0) Palette.Accent else Palette.Accent2) else Palette.Surface2))
                    if (b < bpb - 1) androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                }
            }
            Button(onClick = { running = !running }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "停止" else "開始", fontSize = 18.sp) }
        }
        SectionCard("拍號") { ChipRow(listOf("2" to "2/4", "3" to "3/4", "4" to "4/4", "6" to "6/8"), bpb.toString()) { bpb = it.toInt() } }
        SectionCard("Tap tempo") {
            Hint("跟住首歌連續撳幾下，自動計 BPM。")
            Button(onClick = {
                val now = System.currentTimeMillis()
                if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                taps.add(now)
                if (taps.size >= 3) {
                    val iv = taps.zipWithNext { a, b -> b - a }.takeLast(6).sorted()
                    val med = iv[iv.size / 2]
                    bpm = (60000.0 / med).toInt().coerceIn(30, 220)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("TAP") }
        }
        Hint("練習建議：先用慢 20% 嘅速度彈到 3 粒星，再每次加 5 BPM。")
    }
}
