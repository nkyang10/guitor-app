package com.boss.guitartrainer.data

import android.content.Context
import com.boss.guitartrainer.audio.AudioEngine
import com.boss.guitartrainer.dsp.DirectionModel
import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.music.Chart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val a4: Double = 440.0,
    val tuningId: String = "std",
    val micSource: AudioEngine.MicSource = AudioEngine.MicSource.AUTO,
    val difficulty: DifficultySettings = DifficultySettings.BEGINNER,
    val serverUrl: String = "http://192.168.1.10:8765",
    val useBasicPitch: Boolean = true,
    val metronomeVolume: Float = 0.8f,
    val songExtraOffsetMs: Int = 0,
    val lastProgression: String = "C Am F G",
    val lastBpm: Int = 80,
)

@Serializable
data class Calibration(
    val latencyMs: Double? = null,          // round-trip offset (mic onset - click presentation)
    val latencyMethod: String? = null,      // "auto" | "manual"
    val latencySdMs: Double? = null,
    val latencyAt: Long = 0,
    val direction: DirectionModel? = null,
    val directionSamples: Int = 0,
)

@Serializable
data class SessionRecord(
    val id: Long,
    val at: Long,
    val mode: String,                       // strum | finger | song | chord
    val title: String,
    val patternId: String? = null,
    val bpm: Double = 0.0,
    val tempoPercent: Int = 100,
    val preset: String = "beginner",
    val waitMode: Boolean = false,
    val events: Int = 0,
    val perfect: Int = 0,
    val good: Int = 0,
    val miss: Int = 0,
    val extras: Int = 0,
    val accuracy: Double = 0.0,             // 0..100
    val stars: Int = 0,
    val maxCombo: Int = 0,
    val chordAcc: Double? = null,
    val dirAcc: Double? = null,
    val notesAcc: Double? = null,
    val meanOffsetMs: Double? = null,
    val sdOffsetMs: Double? = null,
    val durationSec: Double = 0.0,
    val missedChords: Map<String, Int> = emptyMap(),
    val missingStrings: Map<Int, Int> = emptyMap(),
    val changeMissRate: Double? = null,     // miss rate on chord-change events
    val songId: String? = null,
)

@Serializable
data class SongEntry(
    val id: String, val title: String, val source: String? = null, val addedAt: Long,
    val bpm: Double = 0.0, val key: String? = null, val hasNoGuitarStem: Boolean = false,
    val bestAccuracy: Double? = null, val local: Boolean = false,
)

/** Simple JSON-file persistence (instead of Room: tiny data, no queries needed). */
class Store private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    }

    private val dir = ctx.filesDir
    private fun <T> load(name: String, ser: KSerializer<T>, def: T): T = try {
        val f = File(dir, name); if (f.exists()) json.decodeFromString(ser, f.readText()) else def
    } catch (e: Exception) { def }

    private fun <T> save(name: String, ser: KSerializer<T>, v: T) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(json.encodeToString(ser, v)); tmp.renameTo(File(dir, name))
    }

    private val _settings = MutableStateFlow(load("settings.json", AppSettings.serializer(), AppSettings()))
    val settings: StateFlow<AppSettings> = _settings
    fun updateSettings(f: (AppSettings) -> AppSettings) { val v = f(_settings.value); _settings.value = v; save("settings.json", AppSettings.serializer(), v) }

    private val _cal = MutableStateFlow(load("calibration.json", Calibration.serializer(), Calibration()))
    val calibration: StateFlow<Calibration> = _cal
    fun updateCalibration(f: (Calibration) -> Calibration) { val v = f(_cal.value); _cal.value = v; save("calibration.json", Calibration.serializer(), v) }

    private val histSer = ListSerializer(SessionRecord.serializer())
    private val _history = MutableStateFlow(load("history.json", histSer, emptyList()))
    val history: StateFlow<List<SessionRecord>> = _history
    fun addSession(r: SessionRecord) { val v = _history.value + r; _history.value = v; save("history.json", histSer, v) }
    fun deleteSession(id: Long) { val v = _history.value.filter { it.id != id }; _history.value = v; save("history.json", histSer, v) }

    private val counterSer = MapSerializer(String.serializer(), Int.serializer())
    private val _counters = MutableStateFlow(load("counters.json", counterSer, emptyMap()))
    val counters: StateFlow<Map<String, Int>> = _counters
    fun bump(key: String) { val v = _counters.value + (key to ((_counters.value[key] ?: 0) + 1)); _counters.value = v; save("counters.json", counterSer, v) }

    // ---------------- songs
    val songsDir: File get() = File(dir, "songs").also { it.mkdirs() }
    fun songDir(id: String) = File(songsDir, id).also { it.mkdirs() }
    private val songSer = ListSerializer(SongEntry.serializer())
    private val _songs = MutableStateFlow(load("songs.json", songSer, emptyList()))
    val songs: StateFlow<List<SongEntry>> = _songs
    fun upsertSong(e: SongEntry) { val v = _songs.value.filter { it.id != e.id } + e; _songs.value = v.sortedByDescending { it.addedAt }; save("songs.json", songSer, _songs.value) }
    fun deleteSong(id: String) { _songs.value = _songs.value.filter { it.id != id }; save("songs.json", songSer, _songs.value); songDir(id).deleteRecursively() }
    fun songChart(id: String): Chart? = try {
        val edited = File(songDir(id), "chart_edited.json"); val f = if (edited.exists()) edited else File(songDir(id), "chart.json")
        Chart.parse(f.readText())
    } catch (e: Exception) { null }
    fun saveSongChart(id: String, c: Chart, edited: Boolean) = File(songDir(id), if (edited) "chart_edited.json" else "chart.json").writeText(c.toJson())
    fun resetSongEdits(id: String) { File(songDir(id), "chart_edited.json").delete() }
    fun songAudio(id: String, stem: String): File? = File(songDir(id), "$stem.m4a").takeIf { it.exists() }
        ?: songDir(id).listFiles()?.firstOrNull { it.nameWithoutExtension == stem }
}
