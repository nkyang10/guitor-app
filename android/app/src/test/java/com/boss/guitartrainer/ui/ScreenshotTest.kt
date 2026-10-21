package com.boss.guitartrainer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.boss.guitartrainer.R
import com.boss.guitartrainer.data.AppSettings
import com.boss.guitartrainer.data.Calibration
import com.boss.guitartrainer.data.Days
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.data.SongEntry
import com.boss.guitartrainer.dsp.Notes
import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.game.EventResult
import com.boss.guitartrainer.game.Feedback
import com.boss.guitartrainer.game.Grade
import com.boss.guitartrainer.game.Progression
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartBuilder
import com.boss.guitartrainer.music.ChordLibrary
import com.boss.guitartrainer.music.ChordSpan
import com.boss.guitartrainer.music.PatternLibrary
import com.boss.guitartrainer.music.Section
import com.boss.guitartrainer.music.Tunings
import com.boss.guitartrainer.net.RemoteSong
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Paparazzi screenshots of every redesigned screen with realistic sample data.
 * Record:  ./gradlew :app:recordPaparazziDebug   → app/src/test/snapshots/images/
 */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.5, useDeviceResolution = true)

    @Before fun setup() { MotionPrefs.forceReduce = true }

    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private val lib by lazy { PatternLibrary.parse(File("src/main/assets/patterns.json").readText()) }

    private fun rec(daysAgo: Int, mode: String, title: String, acc: Double, stars: Int, min: Double, bpm: Double = 80.0,
                    missed: Map<String, Int> = emptyMap()) = SessionRecord(
        id = now - daysAgo * day - 3_600_000L, at = now - daysAgo * day - 3_600_000L, mode = mode, title = title, bpm = bpm,
        events = 64, perfect = (64 * acc / 100 * 0.7).toInt(), good = (64 * acc / 100 * 0.3).toInt(), miss = (64 * (100 - acc) / 100).toInt(),
        accuracy = acc, stars = stars, maxCombo = 18 + stars * 4, chordAcc = acc - 4, dirAcc = acc + 2, meanOffsetMs = 14.0, sdOffsetMs = 31.0,
        durationSec = min * 60, missedChords = missed)

    private val hist by lazy {
        listOf(
            rec(6, "strum", "掃弦：民謠基本", 71.0, 1, 6.0),
            rec(4, "strum", "掃弦：民謠基本", 80.0, 2, 8.0, 84.0),
            rec(2, "finger", "指彈：p-i-m-a", 76.0, 2, 7.0, 70.0),
            rec(1, "chord", "和弦檢查：C", 90.0, 2, 3.0),
            rec(1, "strum", "掃弦：八分音符", 86.0, 2, 9.0, 88.0, mapOf("F" to 5, "G" to 2)),
            rec(0, "strum", "掃弦：民謠基本", 93.0, 3, 6.0, 92.0, mapOf("F" to 2)),
        )
    }
    private val counters = mapOf("tuner" to 3, "loops" to 1)

    private fun screen(tab: String? = null, content: @Composable () -> Unit) {
        paparazzi.snapshot {
            GuitarTheme {
                Box(Modifier.fillMaxSize().morningPaper()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        if (tab != null) BottomTabs(tab) {}
                    }
                }
            }
        }
    }

    private fun tall() = paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenHeight = 2340 * 2))

    // ---------------------------------------------------------------- tabs
    private fun today(hour: Int, h: List<SessionRecord>, daily: Map<String, Long>, name: String = "阿晴") =
        TodayLogic.build(h, daily, needsLatency = true, needsDirection = false, nickname = name, goalMin = 10, canReplay = true).copy(hour = hour)

    @Test fun today_morning() = screen("home") { TodayContent(today(8, hist, mapOf("tuner" to Days.today())), {}, {}) }
    @Test fun today_morning_full() { tall(); screen("home") { TodayContent(today(8, hist, mapOf("tuner" to Days.today())), {}, {}) } }
    @Test fun today_first_time() = screen("home") { TodayContent(today(9, emptyList(), emptyMap(), ""), {}, {}) }
    @Test fun today_goal_reached() = screen("home") {
        val h = hist + rec(0, "finger", "指彈：p-i-m-a", 88.0, 2, 8.0, 70.0)
        val chord = TodayLogic.chordOfDay(Days.today()).name
        TodayContent(today(10, h, mapOf("tuner" to Days.today(), "chord_$chord" to Days.today())), {}, {})
    }
    @Test fun today_night() = screen("home") { TodayContent(today(23, hist, emptyMap()), {}, {}) }

    private fun hub(h: List<SessionRecord>, daily: Map<String, Long>) = HubState(HubLogic.steps(h, counters, daily), lib.strum.size,
        lib.finger.size, ChordLibrary.ALL.size, latencyOk = false, directionOk = false)
    @Test fun hub() = screen("hub") { PracticeHubContent(hub(hist, mapOf("tuner" to Days.today(), "chord_C" to Days.today()))) {} }
    @Test fun hub_full() { tall(); screen("hub") { PracticeHubContent(hub(hist, mapOf("tuner" to Days.today(), "chord_C" to Days.today()))) {} } }

    @Test fun me_stats() = screen("me") { StatsContent(hist, counters, "阿晴", true, "stats", {}, {}, {}, {}) }
    @Test fun me_stats_full() { tall(); screen("me") { StatsContent(hist, counters, "阿晴", true, "stats", {}, {}, {}, {}) } }
    @Test fun me_achievements() { tall(); screen("me") { StatsContent(hist, counters, "阿晴", true, "ach", {}, {}, {}, {}) } }
    @Test fun me_history() = screen("me") { StatsContent(hist, counters, "阿晴", true, "hist", {}, {}, {}, {}) }
    @Test fun me_empty() = screen("me") { StatsContent(emptyList(), emptyMap(), "", true, "stats", {}, {}, {}, {}) }

    private val songs = listOf(
        SongEntry("s1", "小幸運", "https://youtu.be/demo1", now - 3 * day, 92.0, "C", true, 78.0, false),
        SongEntry("s2", "海闊天空", "https://youtu.be/demo2", now - day, 76.0, "F", false, null, true),
    )
    @Test fun songs() = screen("songs") {
        SongsContent("✓ 已連線（v0.3，有 Demucs 分離）", "", {}, true, {}, false, 0.0, null, songs,
            listOf(RemoteSong("r1", "Lemon", 87.0, "B", 255.0, true)), {}, {}, {}, {}, {})
    }
    @Test fun songs_busy_offline() = screen("songs") {
        SongsContent("✗ 連唔到 http://192.168.1.10:8765", "https://youtu.be/abc", {}, false, {}, true, 0.42, "分析緊：分離結他音軌…", emptyList(), emptyList(),
            {}, {}, {}, {}, {})
    }

    @Test fun songs_local_long() { tall(); screen("songs") {
        SongsContent("✗ 連唔到 http://192.168.1.10:8765", "", {}, true, {}, true, 0.37,
            "首歌有 12:34，手機只分析頭 10 分鐘。\n分析節拍同和弦（手機版，冇分離音軌）…", songs, emptyList(), {}, {}, {}, {}, {})
    } }
    @Test fun songs_local_done_partial() = screen("songs") {
        SongsContent("✗ 連唔到 http://192.168.1.10:8765", "", {}, true, {}, false, 1.0,
            "✓ 完成：Hotel California（74 BPM，Bm 調，建議 Capo 2）\n⚠️ 首歌長過 10 分鐘，只分析咗頭 10 分鐘。", songs, emptyList(), {}, {}, {}, {}, {})
    }

    // ---------------------------------------------------------------- pushed screens
    private fun songChart(): Chart {
        val prog = listOf("C", "G", "Am", "F")
        val spb = 60.0 / 92.0 * 4
        val spans = (0 until 40).map { i -> ChordSpan(i * spb, (i + 1) * spb, prog[i % 4]) }
        return Chart(id = "s1", title = "小幸運", bpm = 92.0, chords = spans, kind = "song",
            sections = listOf(Section(0.0, 8 * spb, "前奏", "s_folk"), Section(8 * spb, 24 * spb, "主歌", "s_folk"), Section(24 * spb, 40 * spb, "副歌", "s_8th")))
    }
    @Test fun song_detail_partial() = screen {
        SongDetailContent(songs[1].copy(title = "Hotel California", source = "本機檔案", partial = true), songChart().copy(capo = 2), lib, 1, 0, false,
            {}, { _, _ -> }, {}, {}) { _, _, _, _, _, _ -> }
    }
    @Test fun song_detail() { tall(); screen { SongDetailContent(songs[0], songChart(), lib, 1, 0, false, {}, { _, _ -> }, {}, {}) { _, _, _, _, _, _ -> } } }

    private fun tunerUi(cents: Double?, note: String?, tuned: Set<Int>) = TunerUi(Tunings.ALL.map { it.id to it.name }, "std", Tunings.STANDARD.name,
        note, note?.let { 110.4 }, cents, if (note != null) 1 else null, -1, Tunings.STANDARD.midis.indices.map { Tunings.STANDARD.stringName(it) },
        tuned, 0.55f, 440.0, null)
    @Test fun tuner_in_tune() = screen { TunerContent(tunerUi(2.0, "A2", setOf(0, 1)), {}, {}, {}) }
    @Test fun tuner_flat() = screen { TunerContent(tunerUi(-24.0, "A2", setOf(0)), {}, {}, {}) }
    @Test fun tuner_idle() = screen { TunerContent(tunerUi(null, null, emptySet()), {}, {}, {}) }

    @Test fun metronome() = screen { MetronomeContent(88, 4, true, 0, 1, {}, {}, {}, {}, {}) }

    @Test fun chord_library() = screen { ChordLibraryContent("1", {}, setOf("Em", "Am", "C"), {}, {}) }
    @Test fun chord_detail() = screen {
        val shape = ChordLibrary.get("Am")!!
        ChordDetailContent(shape, shape.midis(Tunings.STANDARD).map { Notes.name(it) },
            ChordCheck(0.84, listOf(null, true, true, false, true, true), 3), true, null, {}, {}, {})
    }
    @Test fun chord_detail_idle() = screen {
        val shape = ChordLibrary.get("G")!!
        ChordDetailContent(shape, shape.midis(Tunings.STANDARD).map { Notes.name(it) }, null, false, null, {}, {}, {})
    }

    @Test fun practice_setup() { tall(); screen {
        PracticeSetupContent("strum", lib, SetupState(1, lib.strum.first().id, "C Am F G", DifficultySettings.BEGINNER, 80, 8), true, {}, {}, {}, {})
    } }
    @Test fun practice_setup_finger() = screen {
        PracticeSetupContent("finger", lib, SetupState(1, lib.finger.first().id, "C G Am F", DifficultySettings.BEGINNER, 70, 8), false, {}, {}, {}, {})
    }

    private val deltas by lazy { val r = Random(7); List(60) { r.nextDouble(-60.0, 70.0) * r.nextDouble() + 8 } }
    @Test fun results_three_stars() { tall(); screen {
        val ach = Progression.achievements(hist, counters).filter { it.unlocked }.take(2)
        ResultsContent(hist.last(), deltas, ach, true, {}, {}, {}, {})
    } }
    @Test fun results_one_star() = screen { ResultsContent(hist.first(), deltas, emptyList(), true, {}, {}, {}, {}) }

    @Test fun settings() { tall(); screen {
        SettingsContent(AppSettings(nickname = "阿晴"), "UNPROCESSED", false, true, "0.2.2", null, {}, {}, {}, {})
    } }
    @Test fun settings_crash() = screen {
        SettingsContent(AppSettings(nickname = "阿晴"), "UNPROCESSED", false, true, "0.2.1", null, {}, {}, {}, {},
            crash = "時間：2025-06-01 21:14:05\nApp：0.2.0 · Android 14 (API 34) · samsung SM-A346E\n記憶體：heap 用咗 250 MB / 上限 256 MB\n" +
                "當時：分析本機歌：Hotel California (Live).flac（48.2 MB）\nThread：DefaultDispatcher-worker-3\n" +
                "錯誤：java.lang.OutOfMemoryError: Failed to allocate a 134217744 byte allocation with 25165824 free bytes and 94MB until OOM\n\n" +
                "java.lang.OutOfMemoryError: …\n\tat java.util.Arrays.copyOf(Arrays.java:3585)")
    }
    @Test fun calibration() { tall(); screen {
        CalibrationContent(Calibration(142.0, "auto", 6.0, now), null, 0.0, null, 0, "", null, {}, {}, {}, {}, {}, {})
    } }
    @Test fun calibration_running() = screen {
        CalibrationContent(Calibration(), "manual", 0.45, null, 2, "", null, {}, {}, {}, {}, {}, {})
    }

    private fun graded(chart: Chart, t: Double): Array<EventResult> = Array(chart.events.size) { i ->
        EventResult().also { r ->
            if (chart.events[i].t < t - 0.15) r.grade = when (i % 7) { 3 -> Grade.GOOD; 5 -> Grade.MISS; else -> Grade.PERFECT }
        }
    }
    @Test fun game_strum() = screen {
        val chart = ChartBuilder.strumPractice("掃弦：民謠基本", listOf("C", "Am", "F", "G"), lib.strum.first(), 80.0, 8)
        GameContent(chart, graded(chart, 4.1), 4.1, 1.0, "strum",
            GameHud("掃弦：民謠基本", "80 BPM · 初學", 91.0, 12, 0.28f, 0.5f, feedback = Feedback("Perfect!", Feedback.Kind.PERFECT, 1), feedbackAgeMs = 120),
            {}, {}, {})
    }
    @Test fun game_count_in() = screen {
        val chart = ChartBuilder.strumPractice("掃弦：民謠基本", listOf("C", "Am", "F", "G"), lib.strum.first(), 80.0, 8)
        GameContent(chart, graded(chart, -1.4), -1.4, 1.0, "strum", GameHud("掃弦：民謠基本", "80 BPM · 初學", 100.0, 0, 0f, 0.2f, countIn = 2), {}, {}, {})
    }
    @Test fun game_finger() = screen {
        val chart = ChartBuilder.fingerPractice("指彈：p-i-m-a", listOf("C", "G", "Am", "F"), lib.finger.first(), 70.0, 8)
        GameContent(chart, graded(chart, 3.0), 3.0, 1.0, "finger",
            GameHud("指彈：p-i-m-a", "70 BPM · 初學 · 等待模式", 84.0, 6, 0.2f, 0.4f, aiText = "AI 驗證 9/11", waitHint = true, loops = 2,
                feedback = Feedback("早咗少少", Feedback.Kind.GOOD, 2), feedbackAgeMs = 300), {}, {}, {})
    }

    // ---------------------------------------------------------------- launcher icon (adaptive layers, two masks)
    @Test fun launcher_icon() = paparazzi.snapshot {
        Row(Modifier.fillMaxSize().background(Color(0xFFF4EFE9)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top) {
            for (shape in listOf(CircleShape, RoundedCornerShape(30))) {
                // 72dp of the 108dp layers is visible → layer = 1.5 × mask size
                Box(Modifier.size(100.dp).clip(shape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(150.dp))
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(150.dp))
                }
            }
            Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF3A2E39)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(150.dp),
                    colorFilter = ColorFilter.tint(Color(0xFFFFD9CF)))
            }
        }
    }
}
