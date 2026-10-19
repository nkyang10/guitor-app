package com.boss.guitartrainer.music

/** Chord symbol parsing / normalisation / simplification (shared rules with the PC server). */
object ChordNames {
    private val PCS = mapOf("C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "F" to 5,
        "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11, "Cb" to 11)
    private val SHARP = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun rootPc(symbol: String): Int? {
        val s = symbol.trim()
        if (s.isEmpty() || s == "N") return null
        val r = if (s.length > 1 && (s[1] == '#' || s[1] == 'b')) s.substring(0, 2) else s.substring(0, 1)
        return PCS[r]
    }

    private fun splitRoot(s: String): Pair<String, String> {
        val r = if (s.length > 1 && (s[1] == '#' || s[1] == 'b')) s.substring(0, 2) else s.substring(0, 1)
        return r to s.substring(r.length)
    }

    /** Accepts "C:maj", "A:min7", "G:7", "Bb", "F#m", "C/E", "D:sus4(b7)" ... returns e.g. "C", "Am7", "G7". */
    fun normalize(symbol: String): String {
        var s = symbol.trim()
        if (s.isEmpty() || s == "N" || s == "X") return "N"
        s = s.substringBefore("/").substringBefore("(")
        val (root0, rest0) = if (s.contains(":")) s.substringBefore(":") to s.substringAfter(":") else splitRoot(s)
        val pc = PCS[root0] ?: return symbol
        val root = SHARP[pc]
        val q = when (rest0) {
            "", "maj", "M" -> ""
            "min", "m", "-" -> "m"
            "7", "dom7" -> "7"
            "maj7", "M7", "Δ7" -> "maj7"
            "min7", "m7", "-7" -> "m7"
            "dim", "o" -> "dim"
            "dim7", "o7" -> "dim7"
            "hdim7", "m7b5", "ø" -> "m7b5"
            "aug", "+" -> "aug"
            "sus2" -> "sus2"
            "sus4", "sus" -> "sus4"
            "maj6", "6" -> "6"
            "min6", "m6" -> "m6"
            "9" -> "9"
            "maj9" -> "maj9"
            "min9", "m9" -> "m9"
            "add9" -> "add9"
            "7sus4" -> "7sus4"
            else -> rest0
        }
        return root + q
    }

    /** Triad reduction: "Am7" -> "Am", "G7" -> "G", "Bdim" -> "Bdim"? -> "Bm" for playability. */
    fun triad(symbol: String): String {
        val n = normalize(symbol)
        if (n == "N") return n
        val (root, q) = splitRoot(n)
        val minor = q.startsWith("m") && !q.startsWith("maj") || q.startsWith("dim")
        return root + if (minor) "m" else ""
    }

    /**
     * Simplify by level. level 1 (beginner): triads, with easy substitutes (F -> Fmaj7, Bm -> Bm7,
     * B -> B7). level 2: keep 7/m7/maj7/sus. level 3: unchanged.
     */
    fun simplify(symbol: String, level: Int): String {
        val n = normalize(symbol)
        if (n == "N" || level >= 3) return n
        val (root, q) = splitRoot(n)
        val keep2 = setOf("", "m", "7", "m7", "maj7", "sus2", "sus4")
        val base = if (level == 2 && q in keep2) n else triad(n)
        if (level == 1) {
            return when (base) {
                "F" -> "Fmaj7"; "Bm" -> "Bm7"; "B" -> "B7"; "F#m" -> "F#m7"
                "Bb" -> "Bbmaj7"; "Gm" -> "Gm7"; "Eb" -> "Ebmaj7"; "Fm" -> "F(small)"
                else -> base
            }
        }
        return when (base) { "F" -> if (level == 2) "F(small)" else "F"; else -> base }
    }

    fun display(symbol: String) = normalize(symbol)

    /** Transpose a chord symbol by [semis] semitones ("Bb", -3 -> "G"). */
    fun transpose(symbol: String, semis: Int): String {
        val n = normalize(symbol)
        if (n == "N" || semis == 0) return n
        val (root, q) = splitRoot(n)
        val pc = PCS[root] ?: return n
        return SHARP[((pc + semis) % 12 + 12) % 12] + q
    }
}

/** Capo suggestion: pick the capo that makes the (simplified) chord shapes easiest. Same rule as the PC server. */
object Capo {
    data class Choice(val capo: Int, val cost: Double, val shapes: Map<String, String>)

    /** [durations]: sounding chord symbol -> total seconds. */
    fun suggest(durations: Map<String, Double>, level: Int, maxCapo: Int = 7): Choice {
        val total = durations.values.sum().coerceAtLeast(1e-6)
        var best: Choice? = null
        for (c in 0..maxCapo) {
            var cost = 0.0
            val shapes = HashMap<String, String>()
            for ((sym, d) in durations) {
                if (ChordNames.normalize(sym) == "N") continue
                val fingered = ChordNames.transpose(sym, -c)
                val shape = ChordLibrary.forSymbol(fingered, simplify = level < 3, level = level)
                val lv = shape?.level ?: 5
                val barre = if ((shape?.barre ?: 0) > 0) 0.2 else 0.0
                // shapes above the player's level cost most; easier shapes are still slightly preferred
                cost += d / total * (maxOf(0, lv - level) + 0.15 * (lv - 1) + barre)
                shapes[sym] = shape?.name ?: fingered
            }
            cost += 0.08 * c            // mild preference for no capo
            if (best == null || cost < best.cost - 1e-9) best = Choice(c, cost, shapes)
        }
        return best!!
    }
}
