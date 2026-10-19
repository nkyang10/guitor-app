package com.boss.guitartrainer.game

import kotlinx.serialization.Serializable

enum class DirectionWeight(val label: String) { OFF("唔計"), LOW("低（只提示）"), NORMAL("正常（扣分）") }

@Serializable
data class DifficultySettings(
    val preset: String = "beginner",
    val perfectMs: Int = 70,
    val goodMs: Int = 140,
    val waitMode: Boolean = false,
    val tempoPercent: Int = 70,
    val simplifyChords: Boolean = true,
    val directionWeight: DirectionWeight = DirectionWeight.LOW,
    val chordCheck: Boolean = true,
    val breakComboOnWrongChord: Boolean = false,
    val countInBars: Int = 1,
    val metronome: Boolean = true,
) {
    val simplifyLevel get() = if (simplifyChords) (if (preset == "beginner") 1 else 2) else 3
    val tempoFactor get() = tempoPercent / 100.0

    companion object {
        val BEGINNER = DifficultySettings()
        val INTERMEDIATE = DifficultySettings(
            preset = "intermediate", perfectMs = 40, goodMs = 80, waitMode = false, tempoPercent = 100,
            simplifyChords = false, directionWeight = DirectionWeight.NORMAL, chordCheck = true,
            breakComboOnWrongChord = true,
        )
        fun preset(name: String) = if (name == "intermediate") INTERMEDIATE else BEGINNER
    }
}
