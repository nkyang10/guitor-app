package com.boss.guitartrainer.music

import kotlinx.serialization.Serializable

@Serializable
data class Tuning(val id: String, val name: String, val midis: List<Int>) {
    // index 0 = 6th string (lowest)
    fun stringName(i: Int) = com.boss.guitartrainer.dsp.Notes.pcName(midis[i]) + (midis[i] / 12 - 1)
}

object Tunings {
    val STANDARD = Tuning("std", "標準 EADGBE", listOf(40, 45, 50, 55, 59, 64))
    val ALL = listOf(
        STANDARD,
        Tuning("dropd", "Drop D (DADGBE)", listOf(38, 45, 50, 55, 59, 64)),
        Tuning("half", "降半音 (Eb)", listOf(39, 44, 49, 54, 58, 63)),
        Tuning("dadgad", "DADGAD", listOf(38, 45, 50, 55, 57, 62)),
        Tuning("openg", "Open G (DGDGBD)", listOf(38, 43, 50, 55, 59, 62)),
        Tuning("opend", "Open D (DADF#AD)", listOf(38, 45, 50, 54, 57, 62)),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: STANDARD
}

/** Guitar string index helpers: 0 = 6th string (low E) ... 5 = 1st string (high e). */
object Strings {
    fun label(i: Int) = "${6 - i}弦"
}
