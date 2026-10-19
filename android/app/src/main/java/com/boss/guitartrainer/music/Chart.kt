package com.boss.guitartrainer.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChartEvent(
    val t: Double,                         // seconds from chart start (at 100 % tempo)
    val type: String,                      // "strum" | "note"
    val chord: String? = null,
    val dir: String? = null,               // "D" / "U" / "X" (muted chuck) for strums
    val strings: List<Int> = emptyList(),  // string indices (0 = 6th) for notes; played strings for strums
    val midis: List<Int> = emptyList(),
    val frets: List<Int> = emptyList(),    // same length as strings, for tab display
    val finger: String? = null,            // "p", "i", "pa" ... for fingerstyle
    val bar: Int = 0,
    val beat: Double = 0.0,
    val accent: Boolean = false,
)

@Serializable
data class ChordSpan(val start: Double, val end: Double, val label: String, val original: String = label)

@Serializable
data class Section(val start: Double, val end: Double, val label: String, val pattern: String? = null)

@Serializable
data class Chart(
    val id: String = "",
    val title: String,
    val bpm: Double,
    val beatsPerBar: Int = 4,
    val beats: List<Double> = emptyList(),      // beat times (s) – song mode
    val downbeats: List<Double> = emptyList(),
    val chords: List<ChordSpan> = emptyList(),
    val sections: List<Section> = emptyList(),
    val events: List<ChartEvent> = emptyList(),
    val key: String? = null,
    val capo: Int = 0,
    val source: String? = null,                  // URL / file
    val audioOffset: Double = 0.0,               // seconds: shift between audio and chart
    val kind: String = "practice",              // practice | song
    val patternId: String? = null,
) {
    val duration get() = (events.lastOrNull()?.t ?: 0.0) + 60.0 / bpm * beatsPerBar

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        fun parse(text: String): Chart = json.decodeFromString(serializer(), text)
    }
    fun toJson(): String = json.encodeToString(serializer(), this)
}
