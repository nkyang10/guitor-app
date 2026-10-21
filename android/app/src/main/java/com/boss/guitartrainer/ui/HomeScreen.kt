package com.boss.guitartrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import com.boss.guitartrainer.data.Days
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.game.Progression
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.ChordShape
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField

// ============================================================================ state
data class WeekDay(val label: String, val practiced: Boolean, val isToday: Boolean, val future: Boolean)

data class PlanItem(val key: String, val emoji: String, val title: String, val sub: String, val tone: Tone, val done: Boolean,
                    val route: String, val chord: ChordShape? = null)

data class TodayState(
    val hour: Int,
    val dateLabel: String,
    val nickname: String,
    val todayMin: Int,
    val goalMin: Int,
    val streak: Int,
    val bestStreak: Int,
    val level: Int,
    val week: List<WeekDay>,
    val needsLatency: Boolean,
    val needsDirection: Boolean,
    val plan: List<PlanItem>,
    val last: SessionRecord?,
    val canReplay: Boolean,
    val tip: String,
    val firstTime: Boolean,
)

object TodayLogic {
    /** Friendly open chords for the "今日和弦" rotation. */
    val CHORD_POOL = listOf("Em", "Am", "C", "G", "D", "Em7", "Asus2", "Dm", "A", "E", "Fmaj7", "Cadd9")
    val TIPS = listOf(
        "每日 10 分鐘，好過一星期先練一次 2 個鐘。",
        "按弦用指尖，手指企直，就唔會掂到隔離條弦。",
        "手指痛係正常㗎！大約兩星期就會起繭，唔好放棄 💪",
        "練新節奏型時，先將速度調慢到 60–70%，穩咗先加速。",
        "掃弦靠手腕轉動，唔使成條手臂郁。",
        "轉和弦前半拍，就要開始準備下一個和弦嘅手指。",
        "彈之前先調音，耳仔會慢慢學識咩叫「準」。",
        "拇指放喺琴頸後面中間，手腕放鬆，唔好捏得太實。",
        "彈錯唔緊要，保持節奏繼續彈落去先係重點 🎶",
        "錄低自己彈嘅版本，過一個月再聽，會見到自己進步咗好多。",
        "坐低彈嘅時候，結他身貼住身體，琴頸微微向上。",
        "左手指甲要剪短，先按得實條弦。",
        "攰咗就休息一陣，伸展吓手指同手腕 🌿",
        "跟住節拍器練，節奏感會進步得好快 ⏱️",
    )
    private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

    fun chordOfDay(day: Long): ChordShape =
        ChordLibrary.get(CHORD_POOL[Math.floorMod(day, CHORD_POOL.size.toLong()).toInt()]) ?: ChordLibrary.ALL.first()

    fun build(hist: List<SessionRecord>, daily: Map<String, Long>, needsLatency: Boolean, needsDirection: Boolean,
              nickname: String, goalMin: Int, canReplay: Boolean, now: LocalDateTime = LocalDateTime.now()): TodayState {
        val todayDate = now.toLocalDate()
        val today = todayDate.toEpochDay()
        val days = hist.map { Days.of(it.at) }.toSet()
        val todayMin = (hist.filter { Days.of(it.at) == today }.sumOf { it.durationSec } / 60.0).let { Math.round(it).toInt() }
        val monday = todayDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = (0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            WeekDay(DAY_LABELS[i], d.toEpochDay() in days, d == todayDate, d.isAfter(todayDate))
        }
        val chord = chordOfDay(today)
        val tuned = daily["tuner"] == today
        val chordOk = daily["chord_${chord.name}"] == today
        val played = today in days
        val plan = listOf(
            PlanItem("tuner", "🎯", "調好音", if (tuned) "今日已經調好音 ✓" else "練之前調一調，耳仔會越嚟越靈", Tone.SKY, tuned, "tuner"),
            PlanItem("chord", "🎼", "今日和弦：${chord.name}", if (chordOk) "${chord.name} 過關！聽落好正 ✓" else "跟指法圖按，App 會聽你彈啱未",
                Tone.LILAC, chordOk, "chord/${android.net.Uri.encode(chord.name)}", chord),
            PlanItem("play", "🎸", "掃弦練 5 分鐘", if (played) "今日已經練咗 ✓ 好叻" else "跟住箭咀掃，拎星星 ⭐", Tone.CORAL, played, "practice/strum"),
        )
        val dow = todayDate.dayOfWeek.value   // 1 = Monday
        return TodayState(
            hour = now.hour, dateLabel = "${todayDate.monthValue}月${todayDate.dayOfMonth}日 · 星期${DAY_LABELS[dow - 1]}",
            nickname = nickname.trim(), todayMin = todayMin, goalMin = goalMin.coerceAtLeast(1),
            streak = Progression.streakDays(hist), bestStreak = Progression.bestStreak(hist), level = Progression.level(Progression.xp(hist)),
            week = week, needsLatency = needsLatency, needsDirection = needsDirection, plan = plan,
            last = hist.lastOrNull(), canReplay = canReplay, tip = TIPS[Math.floorMod(today, TIPS.size.toLong()).toInt()],
            firstTime = hist.isEmpty(),
        )
    }
}

private data class Greeting(val zh: String, val script: String, val emoji: String, val idle: String, val brush: Brush)

private fun greeting(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting("早晨呀", "good morning", "☀️", "新一日，彈返幾分鐘提提神 🎶",
        Brush.linearGradient(listOf(Color(0xFFFFB98A), Color(0xFFFFA0A8), Color(0xFFFFD79A))))
    in 12..17 -> Greeting("午安呀", "good afternoon", "🌤️", "抖抖氣，彈幾分鐘放鬆吓 🍃",
        Brush.linearGradient(listOf(Color(0xFFFFC98F), Color(0xFFA9E5CB), Color(0xFF9FD6F5))))
    in 18..22 -> Greeting("晚上好", "good evening", "🌙", "收工啦，彈下結他 chill 吓 🌙",
        Brush.linearGradient(listOf(Color(0xFFFFB9A0), Color(0xFFF5A9C6), Color(0xFFC6B5F5))))
    else -> Greeting("夜貓子你好", "sweet night", "✨", "夜深啦，輕輕彈幾分鐘就好 ✨",
        Brush.linearGradient(listOf(Color(0xFFC6B5F5), Color(0xFFA9C9F5), Color(0xFFF5BCD6))))
}

fun modeEmoji(m: String) = when (m) { "strum" -> "🎸"; "finger" -> "🖐️"; "song" -> "🎵"; else -> "🎼" }
fun modeTone(m: String) = when (m) { "strum" -> Tone.CORAL; "finger" -> Tone.MINT; "song" -> Tone.SKY; else -> Tone.LILAC }

// ============================================================================ screen
@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val hist by store.history.collectAsState()
    val cal by store.calibration.collectAsState()
    val settings by store.settings.collectAsState()
    val daily by store.daily.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose { } }     // refresh greeting / day after coming back
    val state = remember(hist, cal, settings, daily, resumed) {
        TodayLogic.build(hist, daily, cal.latencyMs == null, cal.direction == null, settings.nickname, settings.dailyGoalMin,
            AppState.lastResult != null)
    }
    TodayContent(state, go = { nav.go(it) }, onReplay = {
        AppState.lastResult?.request?.let { AppState.pendingGame = it; nav.navigate("game") }
    }, onName = { n -> store.updateSettings { it.copy(nickname = n) } })
}

@Composable
fun TodayContent(s: TodayState, go: (String) -> Unit, onReplay: () -> Unit, onName: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.dateLabel, style = MaterialTheme.typography.labelLarge, color = Palette.Muted, modifier = Modifier.weight(1f).padding(start = 2.dp))
            TopAction("⚙️", "設定") { go("settings") }
        }
        TodayHero(s) { go(s.plan.firstOrNull { !it.done }?.route ?: "practice/strum") }
        if (s.firstTime && s.nickname.isBlank()) WelcomeCard(onName)
        WeekCard(s)
        if (s.needsLatency || s.needsDirection) CalibrationNudge(s) { go("calibration") }

        SectionTitle("今日小目標", "🌈", trailing = "${s.plan.count { it.done }}/${s.plan.size} 完成")
        s.plan.forEach { p -> PlanRow(p) { go(p.route) } }

        SectionTitle("隨手工具", "🧰")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("🎯", "調音器", Tone.SKY, Modifier.weight(1f)) { go("tuner") }
            MiniTile("⏱️", "節拍器", Tone.SUN, Modifier.weight(1f)) { go("metronome") }
            MiniTile("🎼", "和弦庫", Tone.LILAC, Modifier.weight(1f)) { go("chords") }
        }

        s.last?.let { last ->
            SectionTitle("上次練習", "🎧")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(modeEmoji(last.mode), modeTone(last.mode))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(last.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${modeLabel(last.mode)} · 準確度 %.0f%%".format(last.accuracy), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    Stars(last.stars, 16)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.canReplay) SoftButton("再嚟一次", onReplay, Modifier.weight(1f), Tone.CORAL, emoji = "🔁")
                    GhostButton("睇紀錄", { go("me") }, Modifier.weight(1f), emoji = "📒")
                }
            }
        }

        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp), Palette.LilacSoft, shadow = false).padding(16.dp)) {
            Text("💡", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("今日小貼士", style = MaterialTheme.typography.titleSmall, color = Palette.LilacDeep)
                Spacer(Modifier.height(2.dp))
                Text(s.tip, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("keep strumming ♪", fontFamily = ScriptFont, fontSize = 18.sp, color = Palette.Muted.copy(alpha = 0.75f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
    }
}

@Composable
private fun TodayHero(s: TodayState, onStart: () -> Unit) {
    val g = greeting(s.hour)
    val reached = s.todayMin >= s.goalMin
    val line = when {
        s.firstTime -> "第一次見面！跟住下面嘅小目標，一步一步嚟 🌱"
        reached -> "今日目標達成！你好叻呀 🎉"
        s.todayMin > 0 -> "再練 ${s.goalMin - s.todayMin} 分鐘就達標，加油 💪"
        else -> g.idle
    }
    Box(Modifier.fillMaxWidth().card(RoundedCornerShape(30.dp), brush = g.brush)
        .drawBehind {
            drawCircle(Color.White.copy(alpha = 0.22f), radius = size.width * 0.34f, center = Offset(size.width * 0.92f, size.height * 0.02f))
            drawCircle(Color.White.copy(alpha = 0.16f), radius = size.width * 0.16f, center = Offset(size.width * 0.06f, size.height * 0.98f))
        }) {
    if (reached) Confetti(Modifier.matchParentSize())          // little reward once today's goal is met
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.script, fontFamily = ScriptFont, fontSize = 28.sp, color = Color.White,
                    style = TextStyle(shadow = Shadow(Color(0x66B8453B), Offset(0f, 3f), 10f)), maxLines = 1)
                Text(g.zh + (if (s.nickname.isNotBlank()) "，${s.nickname}" else "") + " " + g.emoji,
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(104.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f))
                .semantics { contentDescription = "今日練咗 ${s.todayMin} 分鐘，目標 ${s.goalMin} 分鐘" }, contentAlignment = Alignment.Center) {
                ProgressRing(s.todayMin / s.goalMin.toFloat(), Modifier.fillMaxSize().padding(7.dp), stroke = 10.dp, track = Palette.Surface2) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (reached) "🎉" else "${s.todayMin}", style = MaterialTheme.typography.headlineSmall)
                        Text(if (reached) "達標" else "/ ${s.goalMin} 分鐘", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroPill(if (s.streak > 0) "🔥 連續 ${s.streak} 日" else "🌱 今日開始")
            HeroPill("Lv.${s.level} ${rankName(s.level)}")
        }
        Spacer(Modifier.height(14.dp))
        PillButton(if (s.plan.all { it.done }) "再練多陣" else "開始今日練習", onStart, Modifier.fillMaxWidth(),
            emoji = if (s.plan.all { it.done }) "🎶" else "▶️", brush = SolidColor(Color.White))
    }
    }
}

/** First launch: ask for a nickname so the morning greeting feels personal. */
@Composable
private fun WelcomeCard(onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp), Palette.PeachSoft, shadow = false).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👋", fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("你好！點稱呼你好？", style = MaterialTheme.typography.titleSmall, color = Palette.PeachDeep)
                Text("每次打開 App 都會叫你個名打招呼 ☀️", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(12) }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("例如：阿晴", color = Palette.Muted) }, shape = RoundedCornerShape(16.dp), colors = softFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name.trim()) }))
            Spacer(Modifier.width(10.dp))
            PillButton("好", { onSave(name.trim()) }, enabled = name.isNotBlank(), emoji = "🌷", height = 52.dp)
        }
    }
}

@Composable
private fun HeroPill(text: String) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.62f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1)
    }
}

@Composable
private fun WeekCard(s: TodayState) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本星期", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (s.bestStreak > 0) "最長連續 ${s.bestStreak} 日" else "練一日就有朵花 🌼", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            s.week.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.label, style = MaterialTheme.typography.labelMedium, color = if (d.isToday) Palette.Accent else Palette.Muted)
                    Spacer(Modifier.height(4.dp))
                    val bg = when { d.practiced -> Palette.SunSoft; d.future -> Color.Transparent; else -> Palette.Surface2 }
                    Box(Modifier.size(38.dp).clip(CircleShape).background(bg)
                        .then(if (d.isToday) Modifier.border(2.dp, Palette.Coral, CircleShape)
                              else if (d.future) Modifier.border(1.5.dp, Palette.Line, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (d.practiced) Text("🌼", fontSize = 18.sp)
                        else if (!d.future) Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Grid.copy(alpha = 0.6f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationNudge(s: TodayState, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp), Palette.SunSoft, shadow = false, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble("🎚️", Tone.SUN, modifier = Modifier.background(Color.White, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("第一次用？花 2 分鐘校準", style = MaterialTheme.typography.titleSmall, color = Palette.SunDeep)
            Text(listOfNotNull(if (s.needsLatency) "延遲（令判定準時）" else null, if (s.needsDirection) "上下掃方向" else null).joinToString("・"),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Chevron(Tone.SUN)
    }
}

@Composable
private fun PlanRow(p: PlanItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), if (p.done) Palette.MintSoft else Palette.Surface, shadow = !p.done, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(p.emoji, p.tone, modifier = if (p.done) Modifier.background(Color.White, CircleShape) else Modifier)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.sub, style = MaterialTheme.typography.bodySmall, color = if (p.done) Palette.MintDeep else Palette.Muted, maxLines = 2)
        }
        if (p.chord != null) {
            Spacer(Modifier.width(6.dp))
            ChordDiagram(p.chord, Modifier.width(44.dp), showFingers = false)
        }
        Spacer(Modifier.width(8.dp))
        DoneDot(p.done)
    }
}

@Composable
private fun MiniTile(emoji: String, label: String, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.card(RoundedCornerShape(22.dp), onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(emoji, tone, size = 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** ★★☆ with filled stars in sun yellow. */
@Composable
fun Stars(n: Int, size: Int = 18, max: Int = 3) {
    Row(Modifier.semantics { contentDescription = "$n 粒星" }) {
        repeat(max) { i -> Text("★", fontSize = size.sp, color = if (i < n) Palette.Sun else Palette.Line) }
    }
}
