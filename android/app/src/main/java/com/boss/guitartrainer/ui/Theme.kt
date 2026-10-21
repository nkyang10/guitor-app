package com.boss.guitartrainer.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boss.guitartrainer.R
import androidx.compose.material3.Slider
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/* ============================================================================================
 * 「晨光」Morning theme — bright cream paper, sunrise coral, mint / sky / butter / lilac pastels.
 * Text colours are AA (≥ 4.5:1) on white, cream and on their own soft tint (deep tones 4.7–5.5:1).
 * ========================================================================================== */
object Palette {
    // base
    val Bg = Color(0xFFFFF8F1)          // cream canvas
    val Surface = Color(0xFFFFFFFF)     // paper card
    val Surface2 = Color(0xFFFFF0E7)    // blush tile / track
    val Text = Color(0xFF3A2E39)        // ink (plum-brown)
    val Muted = Color(0xFF736470)       // secondary text
    val Line = Color(0xFFF1E4DA)        // dividers, empty tracks
    val Grid = Color(0xFFB8A59D)        // chord-box & lane lines
    val Outline = Color(0xFF958390)     // text-field outline (3.5:1)
    // accents: fill = pastel (ink text on top), soft = tinted background, deep = text-safe
    val Coral = Color(0xFFFF8A7A); val CoralSoft = Color(0xFFFFE6E0); val Accent = Color(0xFFB83A31)
    val Peach = Color(0xFFFFB48F); val PeachSoft = Color(0xFFFFEEE3); val PeachDeep = Color(0xFFA9502A)
    val Sun = Color(0xFFFFD36E); val SunSoft = Color(0xFFFFF5D9); val SunDeep = Color(0xFF8A5A00)
    val Mint = Color(0xFF6ED3B0); val MintSoft = Color(0xFFE2F6EE); val MintDeep = Color(0xFF0B7A55)
    val Sky = Color(0xFF7CC4F2); val SkySoft = Color(0xFFE4F2FC); val Accent2 = Color(0xFF20679C)
    val Lilac = Color(0xFFB9A5F2); val LilacSoft = Color(0xFFEFEAFD); val LilacDeep = Color(0xFF6E55C9)
    val Rose = Color(0xFFFF9FB5); val RoseSoft = Color(0xFFFFE9EF); val RoseDeep = Color(0xFFB8304F)
    // judgement (≥ 3:1 on the light lane)
    val Perfect = MintDeep
    val Good = Color(0xFFB07700)
    val Miss = RoseDeep
    val Wrong = Color(0xFFD9581C)
    val Shadow = Color(0xFFE0A58F)
}

/** Pastel families used for icon bubbles, tinted cards and tones. */
enum class Tone(val fill: Color, val soft: Color, val deep: Color) {
    CORAL(Palette.Coral, Palette.CoralSoft, Palette.Accent),
    PEACH(Palette.Peach, Palette.PeachSoft, Palette.PeachDeep),
    SUN(Palette.Sun, Palette.SunSoft, Palette.SunDeep),
    MINT(Palette.Mint, Palette.MintSoft, Palette.MintDeep),
    SKY(Palette.Sky, Palette.SkySoft, Palette.Accent2),
    LILAC(Palette.Lilac, Palette.LilacSoft, Palette.LilacDeep),
    ROSE(Palette.Rose, Palette.RoseSoft, Palette.RoseDeep),
}

object Brushes {
    val Sunrise = Brush.horizontalGradient(listOf(Color(0xFFFFC46B), Color(0xFFFF9E7A), Color(0xFFFF8A9B)))
    val SunriseV = Brush.verticalGradient(listOf(Color(0xFFFFC46B), Color(0xFFFF9E7A), Color(0xFFFF8A9B)))
    val Fresh = Brush.horizontalGradient(listOf(Color(0xFF8EE3C3), Color(0xFF8FD0F5)))
    val Dreamy = Brush.linearGradient(listOf(Color(0xFFD9CCFF), Color(0xFFFFC9D9), Color(0xFFFFD8C2)))
}

// ---------------------------------------------------------------- type
/** Nunito (rounded, friendly) for Latin & numbers; Chinese falls back to the system CJK font. */
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
)
/** Pacifico — decorative script, only for short English accents ("good morning"). */
val ScriptFont = FontFamily(Font(R.font.pacifico_regular))

private fun TextStyle.n(w: FontWeight, size: TextUnit) =
    copy(fontFamily = Nunito, fontWeight = w, fontSize = size, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)

val AppTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.n(FontWeight.Black, 60.sp),
        displayMedium = t.displayMedium.n(FontWeight.Black, 46.sp),
        displaySmall = t.displaySmall.n(FontWeight.ExtraBold, 36.sp),
        headlineLarge = t.headlineLarge.n(FontWeight.ExtraBold, 30.sp),
        headlineMedium = t.headlineMedium.n(FontWeight.ExtraBold, 26.sp),
        headlineSmall = t.headlineSmall.n(FontWeight.ExtraBold, 22.sp),
        titleLarge = t.titleLarge.n(FontWeight.ExtraBold, 21.sp),
        titleMedium = t.titleMedium.n(FontWeight.ExtraBold, 17.sp),
        titleSmall = t.titleSmall.n(FontWeight.Bold, 15.sp),
        bodyLarge = t.bodyLarge.n(FontWeight.Normal, 16.sp),
        bodyMedium = t.bodyMedium.n(FontWeight.Normal, 14.sp),
        bodySmall = t.bodySmall.n(FontWeight.Normal, 13.sp),
        labelLarge = t.labelLarge.n(FontWeight.Bold, 15.sp),
        labelMedium = t.labelMedium.n(FontWeight.Bold, 13.sp),
        labelSmall = t.labelSmall.n(FontWeight.SemiBold, 12.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val scheme = lightColorScheme(
    primary = Palette.Accent, onPrimary = Color.White,
    primaryContainer = Palette.CoralSoft, onPrimaryContainer = Palette.Text,
    secondary = Palette.Accent2, onSecondary = Color.White,
    secondaryContainer = Palette.PeachSoft, onSecondaryContainer = Palette.Text,
    tertiary = Palette.MintDeep, onTertiary = Color.White,
    tertiaryContainer = Palette.MintSoft, onTertiaryContainer = Palette.Text,
    background = Palette.Bg, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.Muted,
    surfaceTint = Palette.Peach,
    inverseSurface = Palette.Text, inverseOnSurface = Palette.Bg, inversePrimary = Palette.Coral,
    outline = Palette.Outline, outlineVariant = Palette.Line,
    error = Palette.Miss, onError = Color.White, errorContainer = Palette.RoseSoft, onErrorContainer = Palette.Text,
    surfaceBright = Color.White, surfaceDim = Palette.Surface2,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCF9), surfaceContainer = Color(0xFFFFFAF5),
    surfaceContainerHigh = Color(0xFFFFF7F1), surfaceContainerHighest = Palette.Surface2,
)

@Composable
fun GuitarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        Surface(color = Palette.Bg, contentColor = Palette.Text, modifier = Modifier.fillMaxSize()) { content() }
    }
}

// ---------------------------------------------------------------- motion helpers
/** Screenshot tests switch this on so every animation shows its final frame. */
object MotionPrefs { @Volatile var forceReduce = false }

/** True when the user turned animations off (Developer options / accessibility "Remove animations"). */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    if (MotionPrefs.forceReduce) return true
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Card-like surface with a soft peach shadow and a springy press. */
fun Modifier.card(shape: Shape = RoundedCornerShape(24.dp), color: Color = Palette.Surface, shadow: Boolean = true,
                  brush: Brush? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null && enabled) 0.965f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    var m = this.graphicsLayer { scaleX = scale; scaleY = scale }
    if (shadow) m = m.shadow(6.dp, shape, clip = false, ambientColor = Palette.Shadow.copy(alpha = 0.35f), spotColor = Palette.Shadow.copy(alpha = 0.45f))
    m = m.clip(shape).then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
    if (onClick != null) m = m.clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
    m
}

/** Springy press without ripple — for bubbles, emoji and small round things. */
fun Modifier.bounceClick(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f, spring(dampingRatio = 0.4f, stiffness = 800f), label = "bounce")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = role) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)      // light "tick" so taps feel alive
            onClick()
        }
}

/** Grey-out emoji / content (used for inactive tabs and locked badges). */
fun Modifier.desaturate(on: Boolean, alpha: Float = 0.55f): Modifier = if (!on) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }); this.alpha = alpha }
    drawIntoCanvas { c -> c.saveLayer(Rect(Offset.Zero, size), paint); drawContent(); c.restore() }
}

/** Soft background blobs shared by all inner screens ("fresh morning paper"). */
fun Modifier.morningPaper(): Modifier = this.background(Palette.Bg).drawBehind {
    val w = size.width
    drawCircle(Palette.PeachSoft, radius = w * 0.55f, center = Offset(w * 1.02f, -w * 0.12f))
    drawCircle(Palette.SunSoft.copy(alpha = 0.9f), radius = w * 0.22f, center = Offset(w * 0.62f, w * 0.06f))
    drawCircle(Palette.SkySoft.copy(alpha = 0.75f), radius = w * 0.38f, center = Offset(-w * 0.14f, size.height * 0.42f))
    drawCircle(Palette.MintSoft.copy(alpha = 0.6f), radius = w * 0.3f, center = Offset(w * 1.05f, size.height * 0.86f))
}

// ---------------------------------------------------------------- scaffolding
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}, scroll: Boolean = true,
           subtitle: String? = null, bottomBar: (@Composable () -> Unit)? = null,
           content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Column(if (onBack == null) Modifier.padding(start = 2.dp) else Modifier) {
                            Text(title, style = if (onBack == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
                        }
                    },
                    navigationIcon = { if (onBack != null) BackBubble(onBack) },
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                        titleContentColor = Palette.Text),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
            bottomBar = { bottomBar?.invoke() },
        ) { pad ->
            val m = Modifier.padding(pad).fillMaxSize().padding(horizontal = 18.dp)
            Column(if (scroll) m.verticalScroll(rememberScrollState()) else m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(2.dp))
                content()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Sticky bottom area for a screen's main call-to-action. */
@Composable
fun BottomCta(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Palette.Bg.copy(alpha = 0f), Palette.Bg, Palette.Bg)))
        .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun BackBubble(onBack: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(48.dp).bounceClick(onClick = onBack).semantics { contentDescription = "返回" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).card(CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(20.dp))
        }
    }
}

/** Round emoji button for the top bar (e.g. ⚙️ on 我的). */
@Composable
fun TopAction(emoji: String, description: String, onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp).size(48.dp).bounceClick(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).card(CircleShape), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 18.sp) }
    }
}

@Composable
fun SectionTitle(text: String, emoji: String? = null, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (emoji != null) { Text(emoji, fontSize = 18.sp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tone: Tone? = null,
                icon: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().card(RoundedCornerShape(24.dp), tone?.soft ?: Palette.Surface, shadow = tone == null, onClick = onClick)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Text(icon, fontSize = 18.sp); Spacer(Modifier.width(8.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        }
        content()
    }
}

@Composable
fun IconBubble(emoji: String, tone: Tone, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(tone.soft), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.46f).sp)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.Text, tone: Tone? = null) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(tone?.soft ?: Palette.Surface2).padding(vertical = 11.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun BigTile(icon: String, label: String, sub: String? = null, modifier: Modifier = Modifier, tone: Tone = Tone.PEACH, onClick: () -> Unit) {
    Column(modifier.card(RoundedCornerShape(24.dp), onClick = onClick).padding(14.dp), horizontalAlignment = Alignment.Start) {
        IconBubble(icon, tone)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2)
    }
}

/** Full-width row with an icon bubble, title, subtitle and a trailing element (chevron by default). */
@Composable
fun ActionRow(icon: String, title: String, sub: String? = null, tone: Tone = Tone.PEACH, modifier: Modifier = Modifier,
              trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().card(RoundedCornerShape(22.dp), onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tone)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) trailing() else Chevron()
    }
}

@Composable
fun Chevron(tone: Tone = Tone.PEACH) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(tone.soft), contentAlignment = Alignment.Center) {
        Text("›", style = MaterialTheme.typography.titleLarge, color = tone.deep)
    }
}

@Composable
fun DoneDot(done: Boolean) {
    val bg by animateColorAsState(if (done) Palette.MintDeep else Palette.Surface2, label = "done")
    Box(Modifier.size(30.dp).clip(CircleShape).background(bg).border(if (done) 0.dp else 1.5.dp, Palette.Grid.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (done) Text("✓", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- buttons
/** Primary call-to-action: sunrise gradient pill with ink text (AA ≥ 5.6:1). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, emoji: String? = null,
               brush: Brush = Brushes.Sunrise, height: Dp = 56.dp) {
    Box(modifier.heightIn(min = height).card(CircleShape, Palette.Line, shadow = enabled, brush = if (enabled) brush else SolidColor(Palette.Line),
        enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) { Text(emoji, fontSize = 18.sp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Palette.Text else Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}

/** Tonal pill: pastel tint with deep text. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Tone = Tone.CORAL, enabled: Boolean = true,
               emoji: String? = null, height: Dp = 48.dp) {
    Box(modifier.heightIn(min = height).card(CircleShape, if (enabled) tone.soft else Palette.Line, shadow = false, enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) { Text(emoji, fontSize = 16.sp); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) tone.deep else Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}

/** Quiet outlined pill. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Text,
                emoji: String? = null, height: Dp = 48.dp) {
    Box(modifier.heightIn(min = height).card(CircleShape, Palette.Surface, shadow = false, enabled = enabled, onClick = onClick)
        .border(BorderStroke(1.5.dp, Palette.Line), CircleShape).padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) { Text(emoji, fontSize = 16.sp); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- small pieces
@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(options.size) { i ->
            val (id, label) = options[i]
            val sel = id == selected
            FilterChip(
                selected = sel, onClick = { onSelect(id) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(containerColor = Palette.Surface, labelColor = Palette.Muted,
                    selectedContainerColor = Palette.Coral, selectedLabelColor = Palette.Text),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = sel, borderColor = Palette.Line,
                    selectedBorderColor = Color.Transparent, borderWidth = 1.5.dp, selectedBorderWidth = 0.dp),
            )
        }
    }
}

/** Rounded progress bar. Default fill = sunrise gradient. */
@Composable
fun LevelBar(value: Float, modifier: Modifier = Modifier, color: Color = Color.Unspecified, animate: Boolean = false, height: Dp = 10.dp,
             track: Color = Palette.Line) {
    val v = value.coerceIn(0f, 1f)
    val shown = if (animate) animateFloatAsState(v, tween(800), label = "bar").value else v
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        if (shown > 0f) Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape)
            .background(if (color == Color.Unspecified) Brushes.Sunrise else SolidColor(color)))
    }
}

@Composable
fun Hint(text: String, color: Color = Palette.Muted) { Text(text, style = MaterialTheme.typography.bodyMedium, color = color) }

@Composable
fun Badge(text: String, color: Color = Palette.Accent) {
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(v, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Switch) { onChange(!value) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(value, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Palette.MintDeep, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Palette.Grid, uncheckedTrackColor = Palette.Surface2, uncheckedBorderColor = Palette.Grid))
    }
}

@Composable
fun appSliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Accent, activeTrackColor = Palette.Coral, inactiveTrackColor = Palette.Line,
    activeTickColor = Color.White.copy(alpha = 0.7f), inactiveTickColor = Palette.Grid.copy(alpha = 0.5f),
)

/** Soft slider: chunky rounded track + white "bead" thumb that grows while dragging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
              valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true) {
    val colors = appSliderColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val reduce = rememberReduceMotion()
    val grow by animateFloatAsState(if ((pressed || dragged) && !reduce) 1.18f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "thumb")
    Slider(value, onValueChange, modifier, enabled, interactionSource = interaction, steps = steps, valueRange = valueRange, colors = colors,
        thumb = {
            Box(Modifier.size(26.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                .shadow(4.dp, CircleShape, ambientColor = Palette.Shadow, spotColor = Palette.Shadow)
                .clip(CircleShape).background(Color.White).border(3.dp, if (enabled) Palette.Coral else Palette.Line, CircleShape))
        },
        track = { st ->
            SliderDefaults.Track(st, Modifier.height(10.dp), enabled = enabled, colors = colors, drawStopIndicator = null,
                drawTick = { _, _ -> }, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 5.dp)
        })
}

/** Exclusive choice row (radio). */
@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Palette.CoralSoft else Color.Transparent)
        .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Palette.Accent else Palette.Outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Numbered step header used by setup screens. */
@Composable
fun StepCard(n: Int, title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
             content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().card(RoundedCornerShape(26.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Brushes.Sunrise), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
fun softFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline, focusedLabelColor = Palette.Accent,
    unfocusedLabelColor = Palette.Muted, cursorColor = Palette.Accent, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
)

/** Ring progress (daily goal). Animates from 0 the first time it is shown. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 12.dp, track: Color = Color.White.copy(alpha = 0.6f),
                 content: @Composable () -> Unit = {}) {
    val reduce = rememberReduceMotion()
    val anim = remember { Animatable(if (reduce) progress.coerceIn(0f, 1f) else 0f) }
    LaunchedEffect(progress) { anim.animateTo(progress.coerceIn(0f, 1f), tween(if (reduce) 0 else 1100)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            if (anim.value > 0f) drawArc(
                Brush.sweepGradient(listOf(Color(0xFFFFC46B), Color(0xFFFF9E7A), Color(0xFFFF7F96), Color(0xFFFFC46B)), center),
                -90f, 360f * anim.value, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

fun levelLabel(l: Int) = when (l) { 1 -> "初學"; 2 -> "進階"; else -> "挑戰" }
fun levelColor(l: Int) = when (l) { 1 -> Palette.Perfect; 2 -> Palette.SunDeep; else -> Palette.Accent }
fun levelTone(l: Int) = when (l) { 1 -> Tone.MINT; 2 -> Tone.SUN; else -> Tone.CORAL }

/** Friendly rank names for player levels. */
fun rankName(level: Int) = when (level) {
    1 -> "結他 BB 🐣"; 2 -> "初心者 🌱"; 3 -> "練習生 🌼"; 4 -> "小樂手 🎶"; 5 -> "結他手 🎸"; 6 -> "台上新星 ⭐"; else -> "結他達人 👑"
}

val outlineAccent = BorderStroke(1.5.dp, Palette.Accent)
