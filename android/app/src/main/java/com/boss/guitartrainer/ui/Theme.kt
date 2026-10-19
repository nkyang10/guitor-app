package com.boss.guitartrainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF14110F)
    val Surface = Color(0xFF221D19)
    val Surface2 = Color(0xFF2E2722)
    val Accent = Color(0xFFFFB74D)
    val Accent2 = Color(0xFF4FC3F7)
    val Perfect = Color(0xFF66BB6A)
    val Good = Color(0xFFFFD54F)
    val Miss = Color(0xFFEF5350)
    val Wrong = Color(0xFFFF8A65)
    val Text = Color(0xFFF5EDE6)
    val Muted = Color(0xFFB0A49A)
    val Line = Color(0xFF5D524A)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent, onPrimary = Color(0xFF2B1A00),
    secondary = Palette.Accent2, onSecondary = Color(0xFF00222E),
    background = Palette.Bg, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.Muted,
    error = Palette.Miss,
)

@Composable
fun GuitarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}, scroll: Boolean = true,
           content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = { actions() },
            )
        }
    ) { pad ->
        val m = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)
        Column(if (scroll) m.verticalScroll(rememberScrollState()) else m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().let { if (onClick != null) it.clip(RoundedCornerShape(16.dp)).clickable { onClick() } else it }
    Card(m, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Accent)
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.Text) {
    Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Palette.Surface2)) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
            Text(label, fontSize = 12.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun BigTile(icon: String, label: String, sub: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clip(RoundedCornerShape(18.dp)).clickable { onClick() }, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(icon, fontSize = 26.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Palette.Muted, maxLines = 2)
        }
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(options.size) { i ->
            val (id, label) = options[i]
            FilterChip(selected = id == selected, onClick = { onSelect(id) }, label = { Text(label) })
        }
    }
}

@Composable
fun LevelBar(value: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = color, trackColor = Palette.Surface2)
}

@Composable
fun Hint(text: String, color: Color = Palette.Muted) { Text(text, fontSize = 13.sp, color = color) }

@Composable
fun Badge(text: String, color: Color = Palette.Accent) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Palette.Muted); Spacer(Modifier.width(8.dp)); Text(v, fontWeight = FontWeight.Medium)
    }
}

fun levelLabel(l: Int) = when (l) { 1 -> "初學"; 2 -> "進階"; else -> "挑戰" }
fun levelColor(l: Int) = when (l) { 1 -> Palette.Perfect; 2 -> Palette.Good; else -> Palette.Wrong }

val outlineAccent = BorderStroke(1.dp, Palette.Accent)
