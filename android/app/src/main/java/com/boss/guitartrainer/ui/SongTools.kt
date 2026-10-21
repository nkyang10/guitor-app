package com.boss.guitartrainer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.boss.guitartrainer.analysis.EnvelopeDecoder
import com.boss.guitartrainer.analysis.OnsetEnvelope
import com.boss.guitartrainer.analysis.RhythmAnalyzer
import com.boss.guitartrainer.analysis.TapSync
import com.boss.guitartrainer.audio.ClickTrack
import com.boss.guitartrainer.data.Store
import com.boss.guitartrainer.music.BeatGrid
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChordNames
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.GridOps
import com.boss.guitartrainer.music.PatternLevel
import com.boss.guitartrainer.music.PatternLibrary
import com.boss.guitartrainer.music.PatternSuggest
import com.boss.guitartrainer.music.SongPrefs
import com.boss.guitartrainer.music.SongSetup
import com.boss.guitartrainer.music.Suggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color

// =====================================================================================================================
// State
// =====================================================================================================================

/** One song's detail page: analysis + the user's layers (key, capo, patterns, beat grid, offset) + the onset envelope. */
@Stable
class SongDesk(private val store: Store, val id: String, private val baseLib: PatternLibrary, private val chordLevel: Int) {
    val dir: File = store.songDir(id)
    var base by mutableStateOf(store.songChart(id)); private set
    var edited by mutableStateOf(File(dir, "chart_edited.json").exists()); private set
    var prefs by mutableStateOf(SongSetup.loadPrefs(dir)); private set
    var grid by mutableStateOf(SongSetup.loadGrid(dir)); private set
    var env by mutableStateOf<OnsetEnvelope?>(null); private set
    var envProgress by mutableStateOf<Float?>(null); private set
    var envError by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false); private set
    private var undoAction: (() -> Unit)? = null

    /** What the player sees and plays: analysis + chord edits → beat grid → key → capo → offset. */
    val chart: Chart? by derivedStateOf { base?.let { SongSetup.effective(it, prefs, grid, chordLevel) } }
    val level: PatternLevel get() = PatternLevel.of(prefs.level) ?: PatternLevel.NORMAL
    val suggestions: Suggestions? by derivedStateOf { chart?.let { PatternSuggest.suggest(it, baseLib, env, prefs.family, it.audioOffset) } }
    val lib: PatternLibrary by derivedStateOf { PatternSuggest.augment(baseLib, (suggestions?.allIds() ?: emptySet()) + prefs.overrides.values) }
    val autoCapo: Int by derivedStateOf { base?.let { SongSetup.effective(it, prefs.copy(capo = null), grid, chordLevel).capo } ?: 0 }

    /** Section index → pattern id for the chosen level (hand-picked sections win). */
    fun patterns(): Map<Int, String> {
        val c = chart ?: return emptyMap()
        return PatternSuggest.resolve(suggestions, level, prefs.overrides, PatternSuggest.sectionsOf(c), "s_folk")
    }

    fun updatePrefs(f: (SongPrefs) -> SongPrefs) { prefs = f(prefs); SongSetup.savePrefs(dir, prefs) }

    fun editChord(start: Double, end: Double, text: String, scope: String) {
        val b = base ?: return
        if (scope.startsWith("key:")) {
            val k = scope.removePrefix("key:").toIntOrNull() ?: return
            updatePrefs { it.copy(transpose = SongSetup.wrap(it.transpose + k)) }
            message = "全首和弦 ${SongSetup.signed(k)} 半音"
            return
        }
        val nb = SongSetup.editRange(b, start, end, text, prefs.transpose, scope)
        store.saveSongChart(id, nb, edited = true); base = nb; edited = true
    }

    fun resetChords() { store.resetSongEdits(id); base = store.songChart(id); edited = false }

    /** Replace the beat grid / offset and remember the previous one for「復原」. */
    fun changeBeat(g: BeatGrid?, offsetMs: Int, msg: String) {
        val oldG = grid; val oldO = prefs.offsetMs
        grid = g; SongSetup.saveGrid(dir, g)
        if (offsetMs != prefs.offsetMs) updatePrefs { it.copy(offsetMs = offsetMs.coerceIn(-1000, 1000)) }
        message = msg
        undoAction = { grid = oldG; SongSetup.saveGrid(dir, oldG); updatePrefs { it.copy(offsetMs = oldO) } }
        canUndo = true
    }

    fun undo() { undoAction?.invoke(); undoAction = null; canUndo = false; message = "已復原" }

    fun op(kind: String) {
        val c = chart ?: return
        val g = GridOps.from(c)
        val off = prefs.offsetMs
        when (kind) {
            "earlier" -> changeBeat(grid, off + 20, "譜提早 20ms")
            "later" -> changeBeat(grid, off - 20, "譜推遲 20ms")
            "half" -> changeBeat(GridOps.halfBeat(g), off, "成個節拍移咗半拍")
            "double" -> GridOps.double(g).let { changeBeat(it, off, "快一倍：${GridOps.describe(it)}") }
            "halve" -> GridOps.halve(g).let { changeBeat(it, off, "慢一半：${GridOps.describe(it)}") }
            "oneLeft" -> changeBeat(GridOps.rotate(g, -1), off, "「1」早咗一拍")
            "oneRight" -> changeBeat(GridOps.rotate(g, 1), off, "「1」遲咗一拍")
            "meter" -> GridOps.meter(g, if (g.beatsPerBar == 3) 4 else 3).let { changeBeat(it, off, "改做 ${it.beatsPerBar}/4") }
            "reset" -> changeBeat(null, 0, "用返原本分析嘅節拍")
        }
    }

    fun applyTaps(taps: List<Double>) {
        val c = chart ?: return
        val off = c.audioOffset
        val g0 = GridOps.from(c)
        val gA = g0.copy(beats = g0.beats.map { it - off }, downbeats = g0.downbeats.map { it - off })
        val end = env?.duration ?: (gA.beats.lastOrNull() ?: 0.0)
        val r = TapSync.apply(taps, gA, env, c.chords.map { it.copy(start = it.start - off, end = it.end - off) }, end)
        val g = r.grid ?: run { message = "👆 " + r.message; return }
        changeBeat(g.copy(beats = g.beats.map { it + off }, downbeats = g.downbeats.map { it + off }), prefs.offsetMs, "👆 " + r.message)
    }

    /** Cached envelope or one streaming decode of the song (~100 fps, a few hundred KB). */
    suspend fun loadEnvelope(audio: File?) {
        if (env != null) return
        val cached = withContext(Dispatchers.IO) { OnsetEnvelope.load(dir) }
        if (cached != null) { env = cached; autoCheck(); return }
        if (audio == null) { envError = "搵唔到首歌嘅音訊檔，節拍工具用唔到"; return }
        envProgress = 0f
        try {
            val e = withContext(Dispatchers.Default) { EnvelopeDecoder.decode(audio, progress = { p -> envProgress = p }) }
            withContext(Dispatchers.IO) { runCatching { OnsetEnvelope.save(dir, e) } }
            env = e
            autoCheck()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            envError = "節拍分析失敗：${t.message ?: t.javaClass.simpleName}"
        } finally {
            envProgress = null
        }
    }

    /** Once per song: re-track at the same tempo / fix a constant shift if that clearly sits better on the music. */
    suspend fun autoCheck() {
        if (prefs.autoChecked || grid != null) { if (!prefs.autoChecked) updatePrefs { it.copy(autoChecked = true) }; return }
        align(auto = true)
    }

    suspend fun align(auto: Boolean = false) {
        val e = env ?: return; val c = chart ?: return; val b = base ?: return
        val g = GridOps.from(c)
        if (g.beats.size < 8) return
        val off = prefs.offsetMs
        val r = try {
            withContext(Dispatchers.Default) { RhythmAnalyzer.autoAlign(e, g, c.chords, c.audioOffset, b.audioOffset, off) }
        } catch (x: CancellationException) {
            throw x
        } catch (t: Throwable) {
            if (auto) updatePrefs { it.copy(autoChecked = true) } else message = "🎯 對唔到拍：${t.message ?: t.javaClass.simpleName}"
            return
        }
        if (auto) updatePrefs { it.copy(autoChecked = true) }
        if (r.grid != null || r.offsetMs != off) changeBeat(r.grid ?: grid, r.offsetMs, (if (auto) "🎯 自動對準：" else "🎯 ") + r.message)
        else if (!auto) message = "🎯 " + r.message
    }
}

/** 試聽: the song + a click on every beat of the current grid, so the ear can judge the grid. */
class BeatPreview(private val ctx: Context) {
    private var player: ExoPlayer? = null
    private var click: ClickTrack? = null
    var playing by mutableStateOf(false); private set
    var epoch by mutableIntStateOf(0); private set

    fun start(file: File, fromSec: Double) {
        stop()
        val p = ExoPlayer.Builder(ctx).build()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        if (fromSec > 0) p.seekTo((fromSec * 1000).toLong())
        p.play()
        player = p
        click = ClickTrack().also { it.volume = 0.9f; it.start() }
        playing = true; epoch++
    }

    fun skip(sec: Double) {
        val p = player ?: return
        click?.clearScheduled()
        p.seekTo((p.currentPosition + (sec * 1000).toLong()).coerceAtLeast(0))
        epoch++
    }

    fun stop() {
        player?.release(); player = null
        click?.stop(); click = null
        playing = false
    }

    /** Audio-file seconds being heard now; null while stopped / buffering. */
    fun position(): Double? = player?.takeIf { it.isPlaying }?.let { it.currentPosition / 1000.0 }

    fun clear() { click?.clearScheduled() }

    fun clickAt(nanos: Long, accent: Boolean) {
        val c = click ?: return
        c.schedule(c.nanosToFrame(nanos), if (accent) ClickTrack.Sound.ACCENT else ClickTrack.Sound.CLICK, if (accent) 1f else 0.7f)
    }
}

/** 1-based beat number inside its bar for every beat (downbeat = 1). */
fun beatNumbers(beats: List<Double>, downbeats: List<Double>): IntArray {
    val isDown = BooleanArray(beats.size)
    for (d in downbeats) if (beats.isNotEmpty()) { val i = SongSetup.nearest(beats, d); if (abs(beats[i] - d) < 0.06) isDown[i] = true }
    val out = IntArray(beats.size)
    var n = 0
    for (i in beats.indices) { n = if (isDown[i]) 1 else n + 1; out[i] = n }
    return out
}

// =====================================================================================================================
// Cards
// =====================================================================================================================

@Composable
fun KeyCapoCard(origKey: String?, key: String?, transpose: Int, pitchFollow: Boolean, capo: Int, capoAuto: Boolean,
                onTranspose: (Int) -> Unit, onPitch: (Boolean) -> Unit, onCapo: (Int?) -> Unit) {
    SectionCard("轉調 · Capo", icon = "🎚️") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SoftButton("−1", { onTranspose(transpose - 1) }, tone = Tone.LILAC, height = 44.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Key ${key ?: "?"}", style = MaterialTheme.typography.headlineSmall, color = Palette.LilacDeep, maxLines = 1)
                Text(if (transpose == 0) "原調" else "原本 ${origKey ?: "?"} · ${SongSetup.signed(transpose)} 半音",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
            }
            SoftButton("+1", { onTranspose(transpose + 1) }, tone = Tone.LILAC, height = 44.dp)
        }
        ChipRow(listOf("chart" to "只改和弦譜", "pitch" to "首歌一齊升降"), if (pitchFollow) "pitch" else "chart") { onPitch(it == "pitch") }
        Hint(if (pitchFollow) "首歌會跟住升／降 Key 播放，全部和弦同 Key 一齊轉。"
             else "分析錯咗 Key？撳 −1／+1，全部和弦一齊跟住轉，唔使逐粒改。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Capo", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SoftButton("−", { onCapo((capo - 1).coerceAtLeast(0)) }, tone = Tone.MINT, height = 40.dp)
            Text(if (capo == 0) "唔使" else "第 $capo 格", style = MaterialTheme.typography.titleMedium, color = Palette.MintDeep,
                modifier = Modifier.padding(horizontal = 10.dp))
            SoftButton("+", { onCapo((capo + 1).coerceAtMost(9)) }, tone = Tone.MINT, height = 40.dp)
            Spacer(Modifier.width(6.dp))
            SoftButton(if (capoAuto) "自動 ✓" else "自動", { onCapo(null) }, tone = if (capoAuto) Tone.SUN else Tone.PEACH, height = 40.dp)
        }
        if (transpose != 0) GhostButton("返原調", { onTranspose(0) }, Modifier.fillMaxWidth(), emoji = "↩️", height = 44.dp)
    }
}

/** Level + family chips at the top of the「段落同節奏型」card. */
@Composable
fun PatternLevelPicker(level: PatternLevel, family: String, sugg: Suggestions?, lib: PatternLibrary,
                       onLevel: (PatternLevel) -> Unit, onFamily: (String) -> Unit) {
    Text("建議節奏型（跟首歌）", style = MaterialTheme.typography.titleSmall)
    LevelSegments(level, onLevel)
    ChipRow(listOf("strum" to "🎸 掃弦", "finger" to "🎼 指彈"), family, onFamily)
    Text("${level.emoji} ${level.label}：${level.blurb}", style = MaterialTheme.typography.bodyMedium)
    sugg?.main(level)?.let { Text("主要用：" + PatternSuggest.name(lib, it), style = MaterialTheme.typography.labelMedium, color = Palette.Accent2, maxLines = 2) }
    if (sugg != null && !sugg.fromAudio) Hint("未讀到錄音節奏，暫時用速度估；分析完會自動更新。")
}

/** 4 equal segments (fits any phone width, unlike a scrolling chip row that hides「進階」). */
@Composable
private fun LevelSegments(level: PatternLevel, onLevel: (PatternLevel) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in PatternLevel.entries) {
            val sel = l == level
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).clip(shape).background(if (sel) Palette.Coral else Palette.Surface)
                    .border(1.5.dp, if (sel) Color.Transparent else Palette.Line, shape)
                    .selectable(selected = sel, role = Role.Tab) { onLevel(l) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(l.emoji, fontSize = 18.sp)
                Text(l.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (sel) Palette.Text else Palette.Muted)
            }
        }
    }
}

@Composable
fun BeatDots(bpb: Int, beatNo: Int, pulse: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..bpb) {
            val on = i == beatNo
            val scale = if (on) 1f + 0.45f * pulse else 1f
            Box(Modifier.size(if (i == 1) 16.dp else 12.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                .background(when { on && i == 1 -> Palette.Accent; on -> Palette.Accent2; i == 1 -> Palette.CoralSoft; else -> Palette.Line }))
        }
    }
}

/** Stateless「節拍對準」card (the live version wires it to [BeatPreview] / [SongDesk]). */
@Composable
fun BeatCard(bpm: Double, bpb: Int, offsetMs: Int, custom: Boolean, analysing: Float?, envReady: Boolean, error: String?,
             message: String?, canUndo: Boolean, playing: Boolean, beatNo: Int, pulse: Float, taps: Int, expanded: Boolean,
             hasAudio: Boolean, onExpand: () -> Unit, onPlay: () -> Unit, onSkip: () -> Unit, onTap: () -> Unit, onTapApply: () -> Unit,
             onTapClear: () -> Unit, onOp: (String) -> Unit, onUndo: () -> Unit) {
    SectionCard("節拍對準", icon = "🥁") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("%.0f BPM · %d/4".format(bpm, bpb), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Badge(if (custom) "已調整" else "自動分析", if (custom) Palette.Accent2 else Palette.Muted)
            if (offsetMs != 0) Badge(if (offsetMs > 0) "譜提早 ${offsetMs}ms" else "譜推遲 ${-offsetMs}ms", Palette.LilacDeep)
        }
        if (analysing != null) {
            Text("分析緊首歌嘅節拍… ${(analysing * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            LevelBar(analysing, Modifier.fillMaxWidth(), height = 6.dp)
        }
        error?.let { Hint(it, Palette.Miss) }
        Hint("撳「試聽」：首歌會加埋「嗒」聲，聽下啱唔啱拍。唔啱就撳「修正」。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(if (playing) "停" else "試聽", onPlay, Modifier.weight(1f), tone = Tone.SKY, enabled = hasAudio,
                emoji = if (playing) "⏹️" else "▶️", height = 44.dp)
            if (playing) SoftButton("+10 秒", onSkip, tone = Tone.LILAC, emoji = "⏩", height = 44.dp)
            BeatDots(bpb, beatNo, pulse)
        }
        if (playing) {
            SoftButton("跟住首歌嘅拍子撳（$taps 下）", onTap, Modifier.fillMaxWidth(), tone = Tone.SUN, emoji = "👆", height = 64.dp)
            if (taps >= 6) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("用我嘅拍子", onTapApply, Modifier.weight(1f), tone = Tone.MINT, emoji = "✅", height = 44.dp)
                GhostButton("清除", onTapClear, emoji = "🧹", height = 44.dp)
            } else Hint("撳 6 下以上：app 會跟你嘅速度重新對拍（快一倍／慢一半／移半拍都識改）。")
        }
        message?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Accent2, modifier = Modifier.weight(1f))
                if (canUndo) GhostButton("復原", onUndo, emoji = "↩️", height = 40.dp)
            }
        }
        GhostButton(if (expanded) "收起修正" else "唔啱拍？修正", onExpand, Modifier.fillMaxWidth(), emoji = "🛠️", height = 44.dp)
        if (expanded) {
            @Composable fun Pair(a: String, ea: String, oa: String, b: String, eb: String, ob: String, enabledA: Boolean = true) =
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(a, { onOp(oa) }, Modifier.weight(1f), tone = Tone.PEACH, enabled = enabledA, emoji = ea, height = 44.dp)
                    SoftButton(b, { onOp(ob) }, Modifier.weight(1f), tone = Tone.PEACH, emoji = eb, height = 44.dp)
                }
            Pair("嗒聲遲咗→提早", "⏪", "earlier", "嗒聲早咗→推遲", "⏩", "later")
            Pair("太慢：快一倍", "🐇", "double", "太快：慢一半", "🐢", "halve")
            Pair("喺兩拍中間", "↔️", "half", if (bpb == 3) "改做 4/4" else "改做 3/4", "🔢", "meter")
            Pair("「1」早一拍", "◀️", "oneLeft", "「1」遲一拍", "▶️", "oneRight")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("自動對準", { onOp("auto") }, Modifier.weight(1f), tone = Tone.MINT, enabled = envReady, emoji = "🎯", height = 44.dp)
                GhostButton("還原分析", { onOp("reset") }, Modifier.weight(1f), emoji = "↩️", height = 44.dp)
            }
            Hint("「1」＝每小節第一拍（重拍），和弦通常喺度轉。")
        }
    }
}

/** [BeatCard] wired to the preview player, the tap pad and [SongDesk]. */
@Composable
fun BeatCardLive(desk: SongDesk, preview: BeatPreview, audio: File?) {
    val chart = desk.chart ?: return
    val off = chart.audioOffset
    val beatsA = remember(chart.beats, off) { chart.beats.map { it - off } }
    val numbers = remember(chart.beats, chart.downbeats) { beatNumbers(chart.beats, chart.downbeats) }
    var beatNo by remember { mutableIntStateOf(0) }
    var pulse by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    val taps = remember { mutableStateListOf<Double>() }
    val scope = rememberCoroutineScope()

    // clicks: schedule ~0.7 s ahead on the click track, re-synced to the player's position every 40 ms
    LaunchedEffect(preview.playing, preview.epoch, beatsA) {
        if (!preview.playing) return@LaunchedEffect
        preview.clear()
        var next = -1
        while (isActive && preview.playing) {
            val pos = preview.position()
            if (pos == null) next = -1 else {
                if (next < 0) next = beatsA.indexOfFirst { it > pos + 0.1 }.let { if (it < 0) beatsA.size else it }
                val now = System.nanoTime()
                while (next < beatsA.size && beatsA[next] < pos + 0.7) {
                    val b = beatsA[next]
                    if (b > pos + 0.06) preview.clickAt(now + ((b - pos) * 1e9).toLong(), numbers.getOrElse(next) { 0 } == 1)
                    next++
                }
            }
            delay(40)
        }
    }
    // beat dots
    LaunchedEffect(preview.playing, beatsA) {
        while (isActive && preview.playing) {
            withFrameNanos { }
            val pos = preview.position() ?: continue
            var lo = 0; var hi = beatsA.size - 1; var i = -1
            while (lo <= hi) { val m = (lo + hi) / 2; if (beatsA[m] <= pos) { i = m; lo = m + 1 } else hi = m - 1 }
            if (i >= 0) { beatNo = numbers[i]; pulse = exp(-(pos - beatsA[i]) / 0.12).toFloat() }
        }
        beatNo = 0; pulse = 0f
    }

    BeatCard(bpm = chart.bpm, bpb = chart.beatsPerBar, offsetMs = desk.prefs.offsetMs, custom = desk.grid != null,
        analysing = desk.envProgress, envReady = desk.env != null, error = desk.envError, message = desk.message, canUndo = desk.canUndo,
        playing = preview.playing, beatNo = beatNo, pulse = pulse, taps = taps.size, expanded = expanded, hasAudio = audio != null,
        onExpand = { expanded = !expanded },
        onPlay = {
            if (preview.playing) preview.stop() else if (audio != null) {
                taps.clear()
                val first = chart.chords.firstOrNull { ChordNames.normalize(it.original) != "N" }?.start ?: 0.0
                preview.start(audio, (first - off - 2.0).coerceAtLeast(0.0))
            }
        },
        onSkip = { taps.clear(); preview.skip(10.0) },
        onTap = { preview.position()?.let { taps += it } },
        onTapApply = { desk.applyTaps(taps.toList()); taps.clear() },
        onTapClear = { taps.clear() },
        onOp = { k -> if (k == "auto") scope.launch { desk.align() } else desk.op(k) },
        onUndo = { desk.undo() })
}

/** Chord edit: this box only / every box with that chord / whole song to another key. */
@Composable
fun ChordEditDialog(span: ChordSpan, capo: Int, sameCount: Int, onDismiss: () -> Unit, onSave: (text: String, scope: String) -> Unit) {
    var text by remember(span) { mutableStateOf(span.original) }
    var scope by remember(span) { mutableStateOf("one") }
    val shift = SongSetup.keyShiftSuggestion(span.original, text)
    AlertDialog(onDismissRequest = onDismiss, containerColor = Palette.Surface, shape = RoundedCornerShape(28.dp),
        title = { Text("改和弦（實際音高）", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("例如 G、Em、D7、N（無和弦）") },
                    shape = RoundedCornerShape(16.dp), colors = softFieldColors())
                RadioRow("只改呢一格", scope == "one") { scope = "one" }
                RadioRow("改晒全部「${span.original}」（$sameCount 格）", scope == "same") { scope = "same" }
                if (shift != null) RadioRow("成首歌轉調 ${SongSetup.signed(shift)}（全部和弦一齊變）", scope == "key") { scope = "key" }
                if (shift != null && scope != "key") Hint("如果係成首歌嘅 Key 錯咗，揀「成首歌轉調」最快。", Palette.Accent2)
                if (capo > 0) Hint("Capo $capo：顯示會自動轉做 capo 後指法。")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, if (scope == "key") shift?.let { "key:$it" } ?: "one" else scope) }) {
                Text("儲存", color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.Text, style = MaterialTheme.typography.labelLarge) } })
}
