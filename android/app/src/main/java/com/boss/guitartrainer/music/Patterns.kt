package com.boss.guitartrainer.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pattern library (assets/patterns.json, shared with the PC server).
 * Strum steps: one char per subdivision step: 'D' down, 'U' up, 'x' muted chuck (golpe), '-' rest.
 * Finger steps: one token per step, e.g. "p", "i", "m", "a", "p2" (alternate bass), "pa" (pinch), "ima", "-".
 */
@Serializable
data class StrumPattern(
    val id: String, val name: String, val level: Int, val beatsPerBar: Int = 4, val subdivision: Int = 2,
    val steps: String, val bpm: List<Int> = listOf(60, 120), val tip: String = "", val styles: List<String> = emptyList(),
) {
    val stepsPerBar get() = beatsPerBar * subdivision
}

@Serializable
data class FingerPattern(
    val id: String, val name: String, val level: Int, val beatsPerBar: Int = 4, val subdivision: Int = 2,
    val steps: List<String>, val bpm: List<Int> = listOf(50, 100), val tip: String = "", val styles: List<String> = emptyList(),
) {
    val stepsPerBar get() = beatsPerBar * subdivision
}

@Serializable
data class PatternLibrary(val version: Int = 1, val strum: List<StrumPattern>, val finger: List<FingerPattern>) {
    fun strum(id: String) = strum.firstOrNull { it.id == id }
    fun finger(id: String) = finger.firstOrNull { it.id == id }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
        fun parse(text: String): PatternLibrary = json.decodeFromString(serializer(), text)
    }
}

/** Finger token -> strings (0 = 6th) for a given chord shape. */
object FingerMap {
    fun strings(token: String, shape: ChordShape, tuning: Tuning = Tunings.STANDARD): List<Int> {
        if (token == "-" || token.isBlank()) return emptyList()
        val root = shape.rootString(tuning)
        val alt = when (root) { 0 -> 2; 1 -> 2; 2 -> 1; else -> (root - 1).coerceAtLeast(0) }
        val out = mutableListOf<Int>()
        var t = token
        if (t.startsWith("p2")) { out += alt; t = t.removePrefix("p2") }
        else if (t.startsWith("p")) { out += root; t = t.removePrefix("p") }
        for (ch in t) when (ch) {
            'i' -> out += 3
            'm' -> out += 4
            'a' -> out += 5
            'c' -> out += 5   // little finger rarely used; map to 1st string
        }
        // skip muted strings in the voicing
        return out.filter { shape.frets.getOrNull(it)?.let { f -> f >= 0 } == true }.distinct()
    }
}
