package com.boss.guitartrainer.music

import kotlinx.serialization.Serializable

/**
 * A playable chord voicing. frets: 6 values from 6th string to 1st string, -1 = muted, 0 = open.
 * fingers: 0 = none, 1-4 = index..pinky. baseFret for diagrams (1 = nut).
 */
@Serializable
data class ChordShape(
    val name: String,
    val frets: List<Int>,
    val fingers: List<Int> = List(6) { 0 },
    val level: Int = 1,
    val barre: Int = 0,          // fret of a barre (0 = none)
) {
    fun midis(tuning: Tuning = Tunings.STANDARD, capo: Int = 0): List<Int> =
        frets.mapIndexedNotNull { i, f -> if (f >= 0) tuning.midis[i] + f + capo else null }

    fun playedStrings(): List<Int> = frets.indices.filter { frets[it] >= 0 }

    /** Lowest played string = bass (string index, 0 = 6th). */
    fun bassString(): Int = playedStrings().firstOrNull() ?: 0

    /** Root string for fingerstyle 'p' (lowest string that sounds the root, else lowest string). */
    fun rootString(tuning: Tuning = Tunings.STANDARD): Int {
        val rootPc = ChordNames.rootPc(name) ?: return bassString()
        return playedStrings().firstOrNull { (tuning.midis[it] + frets[it]) % 12 == rootPc } ?: bassString()
    }

    fun midiOn(string: Int, tuning: Tuning = Tunings.STANDARD, capo: Int = 0): Int? =
        frets.getOrNull(string)?.takeIf { it >= 0 }?.let { tuning.midis[string] + it + capo }

    val minFret get() = frets.filter { it > 0 }.minOrNull() ?: 1
    val maxFret get() = frets.filter { it > 0 }.maxOrNull() ?: 1
}

object ChordLibrary {
    private fun c(name: String, frets: String, fingers: String = "000000", level: Int = 1, barre: Int = 0) =
        ChordShape(name, frets.map { if (it == 'x') -1 else it.digitToInt(16) }, fingers.map { it.digitToInt() }, level, barre)

    val ALL: List<ChordShape> = listOf(
        // ---- level 1: open major / minor
        c("C", "x32010", "032010"), c("D", "xx0232", "000132"), c("E", "022100", "023100"),
        c("G", "320003", "210003"), c("A", "x02220", "001230"), c("Am", "x02210", "002310"),
        c("Em", "022000", "023000"), c("Dm", "xx0231", "000231"),
        // easy variants for beginners
        c("Fmaj7", "xx3210", "003210"), c("G(easy)", "xxx003", "000003"), c("C(easy)", "xxx010", "000010"),
        c("Em7", "022030", "012030"), c("Asus2", "x02200", "001200"), c("Dsus2", "xx0230", "000130"),
        c("Asus4", "x02230", "001230"), c("Dsus4", "xx0233", "000134"), c("Cadd9", "x32033", "021034"),
        // ---- level 2: 7ths, F/B barre-lite
        c("G7", "320001", "320001", 2), c("C7", "x32310", "032410", 2), c("D7", "xx0212", "000213", 2),
        c("E7", "020100", "020100", 2), c("A7", "x02020", "002030", 2), c("B7", "x21202", "021304", 2),
        c("Am7", "x02010", "002010", 2), c("Dm7", "xx0211", "000211", 2), c("Cmaj7", "x32000", "032000", 2),
        c("Gmaj7", "320002", "210003", 2), c("Amaj7", "x02120", "002130", 2), c("Dmaj7", "xx0222", "000111", 2),
        c("F(small)", "xx3211", "003211", 2), c("Bm7", "x20202", "010203", 2), c("Esus4", "022200", "023400", 2),
        // ---- level 3: barre chords
        c("F", "133211", "134211", 3, 1), c("Bm", "x24432", "013421", 3, 2), c("B", "x24442", "012341", 3, 2),
        c("F#m", "244222", "134111", 3, 2), c("Gm", "355333", "134111", 3, 3), c("Cm", "x35543", "013421", 3, 3),
        c("Bb", "x13331", "012341", 3, 1), c("C#m", "x46654", "013421", 3, 4), c("F#", "244322", "134211", 3, 2),
        c("Fm", "133111", "134111", 3, 1), c("Ab", "466544", "134211", 3, 4), c("Eb", "x65343", "042131", 3, 3),
        c("Gm7", "353333", "131111", 3, 3), c("F#m7", "242222", "131111", 3, 2), c("Bdim", "x2343x", "012430", 3),
        c("E7sus4", "020200", "020300", 2), c("Bbmaj7", "x13231", "013241", 3, 1), c("Ebmaj7", "x65333", "032111", 3, 3),
    )

    private val byName = ALL.associateBy { it.name }

    fun get(name: String): ChordShape? = byName[name] ?: byName[ChordNames.normalize(name)]

    /** Find or synthesise a voicing for any chord symbol (falls back to simplified chord). */
    fun forSymbol(symbol: String, simplify: Boolean = false, level: Int = 3): ChordShape? {
        val n = ChordNames.normalize(symbol)
        val target = if (simplify) ChordNames.simplify(n, level) else n
        return get(target) ?: get(ChordNames.simplify(target, 1)) ?: get(ChordNames.triad(target))
    }

    fun byLevel(level: Int) = ALL.filter { it.level == level }
}
