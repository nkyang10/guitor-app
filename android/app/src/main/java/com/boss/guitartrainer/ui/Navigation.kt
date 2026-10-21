package com.boss.guitartrainer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

data class TabItem(val route: String, val emoji: String, val label: String, val tone: Tone)

val TABS = listOf(
    TabItem("home", "☀️", "今日", Tone.SUN),
    TabItem("hub", "🎸", "練習", Tone.CORAL),
    TabItem("songs", "🎵", "歌曲", Tone.SKY),
    TabItem("me", "🎧", "我的", Tone.ROSE),
)
/** Switch bottom tabs like a normal app: one copy of each tab, state kept per tab. */
fun NavController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

val TAB_ROUTES = TABS.map { it.route }.toSet()

/** Navigate anywhere: tab routes switch tabs, everything else is pushed. */
fun NavController.go(route: String) = if (route in TAB_ROUTES) navigateTab(route) else navigate(route)

/** Floating pill tab bar. Inactive emoji are greyed out; the active one pops a little. */
@Composable
fun BottomTabs(current: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
            .card(RoundedCornerShape(30.dp)).padding(6.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEach { t ->
            val sel = t.route == current
            val bg by animateColorAsState(if (sel) t.tone.soft else Color.Transparent, tween(250), label = "tabBg")
            val scale by animateFloatAsState(if (sel) 1.15f else 1f, spring(dampingRatio = 0.38f, stiffness = 500f), label = "tabPop")
            Column(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                    .selectable(selected = sel, role = Role.Tab, onClick = { if (!sel) onSelect(t.route) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(t.emoji, fontSize = 20.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.desaturate(!sel, 0.6f))
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (sel) Palette.Text else Palette.Muted,
                    fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Bold)
            }
        }
    }
}
