package com.boss.guitartrainer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var tick by remember { mutableIntStateOf(0) }
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
        var lastShown = -1L
        while (true) {
            val horizon = click.futureFrame(0.6)
            while (next < horizon) {
                val b = (k % bpb).toInt()
                click.schedule(next, if (b == 0) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK)
                scheduled.add(next to b)
                next += spbFrames; k++
            }
            val nowFrame = click.nanosToFrame(System.nanoTime())
            scheduled.lastOrNull { it.first <= nowFrame }?.let { if (it.first != lastShown) { lastShown = it.first; beat = it.second; tick++ } }
            scheduled.removeAll { it.first < nowFrame - spbFrames * 2 }
            delay(15)
        }
    }
    MetronomeContent(bpm, bpb, running, beat, tick, onBack = { nav.popBackStack() }, onBpm = { bpm = it.coerceIn(30, 220) },
        onBpb = { bpb = it }, onToggle = { running = !running }, onTap = {
            val now = System.currentTimeMillis()
            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
            taps.add(now)
            if (taps.size >= 3) {
                val iv = taps.zipWithNext { a, b -> b - a }.takeLast(6).sorted()
                val med = iv[iv.size / 2]
                bpm = (60000.0 / med).toInt().coerceIn(30, 220)
            }
        })
}

fun tempoName(bpm: Int) = when {
    bpm < 60 -> "Largo · 好慢"
    bpm < 76 -> "Adagio · 慢板"
    bpm < 108 -> "Andante · 行板"
    bpm < 120 -> "Moderato · 中板"
    bpm < 156 -> "Allegro · 快板"
    bpm < 176 -> "Vivace · 活潑"
    else -> "Presto · 急板"
}

@Composable
fun MetronomeContent(bpm: Int, bpb: Int, running: Boolean, beat: Int, tick: Int, onBack: () -> Unit, onBpm: (Int) -> Unit,
                     onBpb: (Int) -> Unit, onToggle: () -> Unit, onTap: () -> Unit) {
    val reduce = rememberReduceMotion()
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (tick > 0 && !reduce) { pulse.snapTo(if (beat == 0) 1.09f else 1.05f); pulse.animateTo(1f, tween(220)) }
    }
    Screen("節拍器", onBack, subtitle = "跟住拍子，節奏感會好穩 ⏱️") {
        Column(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp)).padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(212.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(212.dp).scale(pulse.value).clip(CircleShape).background(if (beat == 0 && running) Palette.CoralSoft else Palette.SunSoft))
                Box(Modifier.size(172.dp).scale(pulse.value).clip(CircleShape)
                    .background(if (running) Brushes.SunriseV else SolidColor(Color.White))
                    .border(if (running) 0.dp else 3.dp, Palette.Line, CircleShape)
                    .semantics { contentDescription = "$bpm BPM" }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$bpm", style = MaterialTheme.typography.displayLarge, color = Palette.Text)
                        Text("BPM", style = MaterialTheme.typography.labelLarge, color = Palette.Text.copy(alpha = 0.75f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(tempoName(bpm), style = MaterialTheme.typography.titleSmall, color = Palette.Muted)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (b in 0 until bpb) {
                    val on = running && b == beat
                    val d by animateDpAsState(if (on) 24.dp else 16.dp, tween(90), label = "dot")
                    val c by animateColorAsState(if (on) (if (b == 0) Palette.Accent else Palette.Sky) else Palette.Line, tween(90), label = "dotc")
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(d).clip(CircleShape).background(c)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            AppSlider(value = bpm.toFloat(), onValueChange = { onBpm(it.toInt()) }, valueRange = 30f..220f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (d in listOf(-5, -1, 1, 5)) RoundStep(if (d > 0) "+$d" else "−${-d}") { onBpm(bpm + d) }
            }
            Spacer(Modifier.height(16.dp))
            if (running) SoftButton("停止", onToggle, Modifier.fillMaxWidth(), Tone.ROSE, emoji = "⏸️", height = 56.dp)
            else PillButton("開始", onToggle, Modifier.fillMaxWidth(), emoji = "▶️")
        }
        SectionCard("拍號", icon = "🎼") {
            ChipRow(listOf("2" to "2/4", "3" to "3/4", "4" to "4/4", "6" to "6/8"), bpb.toString()) { onBpb(it.toInt()) }
        }
        SectionCard("Tap tempo", icon = "👆") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp).clip(CircleShape).background(Palette.LilacSoft).bounceClick(onClick = onTap)
                    .border(3.dp, Palette.Lilac, CircleShape).semantics { contentDescription = "Tap tempo" }, contentAlignment = Alignment.Center) {
                    Text("TAP", style = MaterialTheme.typography.titleLarge, color = Palette.LilacDeep)
                }
                Spacer(Modifier.width(16.dp))
                Hint("跟住首歌嘅拍子連續撳幾下，自動幫你計 BPM。")
            }
        }
        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), Palette.MintSoft, shadow = false).padding(14.dp)) {
            Text("🌱", fontSize = 18.sp); Spacer(Modifier.width(8.dp))
            Text("練習建議：先用慢 20% 嘅速度彈到 3 粒星，再每次加 5 BPM。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RoundStep(label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.PeachSoft).bounceClick(onClick = onClick)
        .semantics { contentDescription = "$label BPM" }, contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.PeachDeep)
    }
}
