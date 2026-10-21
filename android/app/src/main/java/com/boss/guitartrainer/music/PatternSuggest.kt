package com.boss.guitartrainer.music

import com.boss.guitartrainer.analysis.OnsetEnvelope
import com.boss.guitartrainer.analysis.RhythmAnalyzer
import kotlin.math.pow
import kotlin.math.sqrt

/** The four per-song pattern levels shown in the 歌曲 tab. */
enum class PatternLevel(val id: String, val label: String, val emoji: String, val blurb: String) {
    EASY("easy", "簡單", "🌱", "得重拍，啱啱開始都跟到"),
    NORMAL("normal", "一般", "🌿", "常用掃法，最夾首歌"),
    ORIGINAL("original", "原曲", "🎸", "跟首歌錄音入面嘅節奏重音"),
    ADVANCED("advanced", "進階", "🔥", "多啲上掃、悶音或者十六分");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

data class SectionPick(val easy: String, val normal: String, val original: String, val advanced: String) {
    fun get(l: PatternLevel) = when (l) {
        PatternLevel.EASY -> easy; PatternLevel.NORMAL -> normal; PatternLevel.ORIGINAL -> original; PatternLevel.ADVANCED -> advanced
    }
}

data class Suggestions(val family: String, val sections: List<SectionPick>, val fromAudio: Boolean, val feel16: Boolean) {
    fun ids(level: PatternLevel): Map<Int, String> = sections.withIndex().associate { (i, s) -> i to s.get(level) }
    /** Most used pick of a level across sections (chip caption). */
    fun main(level: PatternLevel): String? = sections.groupingBy { it.get(level) }.eachCount().maxByOrNull { it.value }?.key
    fun allIds(): Set<String> = sections.flatMap { listOf(it.easy, it.normal, it.original, it.advanced) }.toSet()
}

/**
 * Per-song pattern suggestions. With the song's onset envelope it reads the strum rhythm of every section
 * (median over bars, 8th or 16th feel) → 原曲; 一般 = closest common library pattern; 簡單 = one strum per beat;
 * 進階 = 16th / golpe / Travis variants. Without audio it falls back to tempo rules + the analysed pattern.
 */
object PatternSuggest {
    fun strumId(bpb: Int, sub: Int, steps: String) = "orig:s:$bpb:$sub:$steps"
    fun fingerId(bpb: Int, sub: Int, steps: List<String>) = "orig:f:$bpb:$sub:" + steps.joinToString(".")

    fun customStrum(id: String): StrumPattern? {
        if (!id.startsWith("orig:s:")) return null
        val p = id.split(':'); if (p.size != 5) return null
        val bpb = p[2].toIntOrNull() ?: return null; val sub = p[3].toIntOrNull() ?: return null
        val steps = p[4]
        if (bpb !in 2..7 || sub !in 1..4 || steps.length != bpb * sub || steps.any { it !in "DUx-" }) return null
        return StrumPattern(id, "原曲節奏 " + steps.chunked(sub).joinToString(" "), level = 2, beatsPerBar = bpb, subdivision = sub,
            steps = steps, tip = "由首歌嘅節奏重音估出嚟：重音用下掃，反拍用上掃。")
    }

    fun customFinger(id: String): FingerPattern? {
        if (!id.startsWith("orig:f:")) return null
        val p = id.split(':'); if (p.size != 5) return null
        val bpb = p[2].toIntOrNull() ?: return null; val sub = p[3].toIntOrNull() ?: return null
        val steps = p[4].split('.')
        val ok = setOf("-", "p", "p2", "i", "m", "a", "im", "ima", "pa", "pi", "pm")
        if (bpb !in 2..7 || sub !in 1..4 || steps.size != bpb * sub || steps.any { it !in ok }) return null
        return FingerPattern(id, "原曲指法 " + steps.joinToString(" "), level = 2, beatsPerBar = bpb, subdivision = sub, steps = steps,
            tip = "低音跟首歌嘅 bass，高音跟節奏重音。")
    }

    /** Library + every custom pattern referenced by [ids] so ChartBuilder and the UI can resolve them. */
    fun augment(lib: PatternLibrary, ids: Collection<String>): PatternLibrary {
        val u = ids.toSet()
        val s = u.mapNotNull { customStrum(it) }.filter { lib.strum(it.id) == null }
        val f = u.mapNotNull { customFinger(it) }.filter { lib.finger(it.id) == null }
        return if (s.isEmpty() && f.isEmpty()) lib else lib.copy(strum = lib.strum + s, finger = lib.finger + f)
    }

    fun name(lib: PatternLibrary, id: String): String =
        lib.strum(id)?.name ?: lib.finger(id)?.name ?: customStrum(id)?.name ?: customFinger(id)?.name ?: id

    /** Sections the builder iterates (a whole-song pseudo section when the chart has none). */
    fun sectionsOf(c: Chart): List<Section> = c.sections.ifEmpty {
        listOf(Section(c.beats.firstOrNull() ?: 0.0, (c.beats.lastOrNull() ?: c.duration) + 60.0 / c.bpm.coerceAtLeast(30.0), "全首", null))
    }

    /**
     * [chart] = effective chart (chart clock). [audioOffset] = chart time − audio time. [env] = cached onset envelope or null.
     */
    fun suggest(chart: Chart, lib: PatternLibrary, env: OnsetEnvelope?, family: String, audioOffset: Double): Suggestions {
        val bpb = chart.beatsPerBar.coerceIn(2, 7)
        val bpm = chart.bpm
        val beatsA = chart.beats.map { it - audioOffset }
        val downsA = chart.downbeats.ifEmpty { GridOps.from(chart).downbeats }.map { it - audioOffset }
        var audio = false
        var votes16 = 0
        val picks = sectionsOf(chart).map { s ->
            val range = (s.start - audioOffset)..(s.end - audioOffset)
            val bars = if (env != null) RhythmAnalyzer.barSlots(env, env.rhythm, beatsA, downsA, bpb, 4, range) else emptyList()
            val lows = if (env != null && bars.size >= 2) RhythmAnalyzer.barSlots(env, env.low, beatsA, downsA, bpb, 4, range) else emptyList()
            val p16 = if (bars.size >= 2) profile(bars) else null
            val l16 = if (lows.size >= 2) profile(lows) else null
            if (p16 != null) audio = true
            val f16 = p16 != null && feel16(p16, bpm)
            if (f16) votes16++
            if (family == "finger") fingerPick(p16, l16, bpb, bpm, lib) else strumPick(p16, f16, bpb, bpm, lib, s.pattern)
        }
        return Suggestions(family, picks, audio, votes16 * 2 > picks.size)
    }

    // ------------------------------------------------------------------ strum

    private fun strumPick(p16: FloatArray?, f16: Boolean, bpb: Int, bpm: Double, lib: PatternLibrary, analysed: String?): SectionPick {
        val easy = easyStrum(bpb, bpm, lib)
        val p8 = p16?.let { eighths(it, bpb) }
        val normal = normalStrum(p8, bpb, bpm, lib, analysed, easy)
        val original = if (p16 != null) originalStrum(p16, f16, bpb, lib) else normal
        val advanced = advancedStrum(p16, f16, bpb, bpm, lib, original, normal)
        return SectionPick(easy, normal, original, advanced)
    }

    fun easyStrum(bpb: Int, bpm: Double, lib: PatternLibrary): String {
        if (bpb == 4 && bpm > 135) return strumId(4, 2, "D---D---")
        val steps = "D-".repeat(bpb)
        return lib.strum.firstOrNull { it.beatsPerBar == bpb && it.subdivision == 2 && it.steps == steps }?.id ?: strumId(bpb, 2, steps)
    }

    private fun normalStrum(p8: FloatArray?, bpb: Int, bpm: Double, lib: PatternLibrary, analysed: String?, easy: String): String {
        val cands = lib.strum.filter { it.beatsPerBar == bpb && it.subdivision == 2 && it.level <= 2 && it.id != easy && !it.id.startsWith("orig:") }
        if (cands.isEmpty()) return analysed?.takeIf { lib.strum(it)?.beatsPerBar == bpb } ?: easy
        return cands.maxByOrNull { sp ->
            val fit = p8?.let { corr(it, hits(sp.steps, 2)) } ?: 0.0
            val tempo = if (bpm < sp.bpm.first() - 5 || bpm > sp.bpm.last() + 5) -0.25 else 0.0
            val prior = (if (sp.id == analysed) 0.12 else 0.0) + (if (sp.id == "s_folk") 0.04 else 0.0) - (if (sp.level == 1) 0.08 else 0.0)
            fit + tempo + prior
        }!!.id
    }

    /** Strummed slots of the section → D on the beat-side slots, U on the off-beats (natural down-up hand motion). */
    fun originalSteps(p16: FloatArray, f16: Boolean, bpb: Int): String {
        val v = if (f16) p16 else eighths(p16, bpb)
        val thr = threshold(v)
        val sb = StringBuilder()
        for (k in v.indices) sb.append(if (k == 0 || v[k] >= thr) (if (k % 2 == 0) 'D' else 'U') else '-')
        return sb.toString()
    }

    private fun originalStrum(p16: FloatArray, f16: Boolean, bpb: Int, lib: PatternLibrary): String {
        val sub = if (f16) 4 else 2
        val steps = originalSteps(p16, f16, bpb)
        val same = lib.strum.filter { it.beatsPerBar == bpb && it.subdivision == sub && !it.id.startsWith("orig:") }
            .map { it to rhythmDistance(it.steps, steps) }.filter { it.second == 0 }.minByOrNull { it.first.level }?.first
        return same?.id ?: strumId(bpb, sub, steps)
    }

    private fun advancedStrum(p16: FloatArray?, f16: Boolean, bpb: Int, bpm: Double, lib: PatternLibrary, original: String, normal: String): String {
        if (bpb == 4) {
            if (f16 || bpm <= 95) {
                val c = lib.strum.filter { it.beatsPerBar == 4 && it.subdivision == 4 && it.level >= 3 }
                if (c.isNotEmpty()) return (if (p16 != null) c.maxByOrNull { corr(p16, hits(it.steps, 4)) } else c.first())!!.id
            }
            if (bpm in 70.0..130.0) lib.strum("s_rumba")?.let { return it.id }
            val base = (lib.strum(original) ?: customStrum(original))?.takeIf { it.subdivision == 2 }?.steps ?: "D-DU-UDU"
            return strumId(4, 2, chucks(base, bpb))
        }
        if (bpb == 3) return strumId(3, 2, if (bpm <= 150) "D-xUDU" else "D-xU-U")
        val base = (lib.strum(normal) ?: customStrum(normal))?.takeIf { it.subdivision == 2 }?.steps ?: "D-".repeat(bpb)
        return strumId(bpb, 2, chucks(base, bpb))
    }

    /** Golpe / chuck on beats 2 and 4 (backbeat) of an 8th-note pattern. */
    fun chucks(steps: String, bpb: Int): String {
        val c = steps.toCharArray()
        for (b in 1 until bpb step 2) if (b * 2 < c.size) c[b * 2] = 'x'
        return String(c)
    }

    // ------------------------------------------------------------------ finger

    private fun fingerPick(p16: FloatArray?, l16: FloatArray?, bpb: Int, bpm: Double, lib: PatternLibrary): SectionPick {
        fun has(id: String) = lib.finger(id) != null
        val easy = if (bpb == 3) "f_waltz" else "f_block"
        val normal = when { bpb == 3 -> "f_pimami"; bpm >= 100 && has("f_alberti") -> "f_alberti"; else -> "f_pima" }
        val advanced = when {
            bpb == 3 -> if (bpm <= 100 && has("f_romance")) "f_romance" else "f_pimami"
            bpm <= 110 && has("f_travis") -> "f_travis"
            else -> "f_pinch"
        }
        val original = if (p16 != null) originalFinger(p16, l16, bpb, lib) ?: normal else normal
        fun ok(id: String, fb: String) = if (lib.finger(id) != null || customFinger(id) != null) id else fb
        val first = lib.finger.firstOrNull { it.beatsPerBar == bpb }?.id ?: lib.finger.first().id
        return SectionPick(ok(easy, first), ok(normal, first), ok(original, first), ok(advanced, first))
    }

    /** Bass "p" / "p2" where the low band hits on a beat, treble i-m-a-m on the other attacked 8ths. */
    fun originalFingerSteps(p16: FloatArray, l16: FloatArray?, bpb: Int): List<String> {
        val v = eighths(p16, bpb)
        val low = l16?.let { eighths(it, bpb) }
        val thr = threshold(v)
        val treble = listOf("i", "m", "a", "m")
        var t = 0; var bass = 0
        val out = ArrayList<String>()
        for (k in v.indices) {
            val onBeat = k % 2 == 0
            val isBass = k == 0 || (bpb == 4 && k == 4 && (low == null || low[4] >= 0.4f)) || (onBeat && low != null && low[k] >= 0.85f)
            out += when {
                isBass -> if (bass++ % 2 == 0) "p" else "p2"
                v[k] >= thr -> treble[t++ % treble.size]
                else -> "-"
            }
        }
        if (out.count { it != "-" } < bpb) for (k in out.indices) if (out[k] == "-") out[k] = treble[t++ % treble.size]
        return out
    }

    private fun originalFinger(p16: FloatArray, l16: FloatArray?, bpb: Int, lib: PatternLibrary): String? {
        val steps = originalFingerSteps(p16, l16, bpb)
        lib.finger.firstOrNull { it.beatsPerBar == bpb && it.subdivision == 2 && it.steps == steps }?.let { return it.id }
        return fingerId(bpb, 2, steps)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Per-slot median over bars, scaled so the strongest slot = 1, then log-compressed: light up-strums under a loud
     * snare (≈0.2) stay well above the rests (≈0.05) → 0.45 vs 0.16.
     */
    fun profile(bars: List<FloatArray>): FloatArray {
        val n = bars[0].size
        val p = FloatArray(n) { i ->
            val v = bars.map { it[i] }.sorted()
            if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        }
        val mx = p.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return p
        val k = Math.log(10.0)
        return FloatArray(n) { (Math.log(1.0 + 9.0 * (p[it] / mx).coerceAtLeast(0f)) / k).toFloat() }
    }

    /** 16-slot profile → 8th slots. */
    fun eighths(p16: FloatArray, bpb: Int): FloatArray = FloatArray(bpb * 2) { p16.getOrElse(it * 2) { 0f } }

    /** At least two "e" / "a" slots (odd 16ths) are played in every bar → 16th-note feel (only below ~115 BPM). */
    fun feel16(p16: FloatArray, bpm: Double): Boolean {
        if (bpm > 115 || p16.size < 8) return false
        val thr = threshold(p16)
        val odd = p16.indices.filter { it % 2 == 1 && p16[it] >= thr }
        return odd.size >= 2 && odd.map { p16[it] }.average() >= 0.4
    }

    /** Otsu split of the slot strengths, kept in 0.3..0.7; a flat profile means every slot is played. */
    fun threshold(v: FloatArray): Float {
        val s = v.sorted()
        if (s.isEmpty()) return 0.5f
        if (s.last() - s.first() < 0.25f) return s.first()
        var best = 0.5f; var bestVar = -1.0
        for (i in 1 until s.size) {
            val a = s.subList(0, i); val b = s.subList(i, s.size)
            val w = a.size.toDouble() / s.size
            val bv = w * (1 - w) * (b.average() - a.average()).pow(2)
            if (bv > bestVar) { bestVar = bv; best = (s[i - 1] + s[i]) / 2 }
        }
        return best.coerceIn(0.3f, 0.7f)
    }

    /** Hit (D/U/x) vs rest per step, ignoring direction. */
    fun rhythmDistance(a: String, b: String): Int = if (a.length != b.length) 99 else a.indices.count { (a[it] == '-') != (b[it] == '-') }

    /** Pattern hits on the 16-slot (sub 4) or 8-slot (sub 2) grid as 1 / 0, downbeat slightly accented. */
    @Suppress("UNUSED_PARAMETER")
    fun hits(steps: String, sub: Int): FloatArray = FloatArray(steps.length) { if (steps[it] == '-') 0f else if (it == 0) 1.1f else 1f }

    /** Pearson correlation (0 when either side is flat). */
    fun corr(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size); if (n < 2) return 0.0
        val ma = (0 until n).sumOf { a[it].toDouble() } / n; val mb = (0 until n).sumOf { b[it].toDouble() } / n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
        return if (da < 1e-9 || db < 1e-9) 0.0 else num / sqrt(da * db)
    }

    /** Pattern per section for the chosen level; hand-picked overrides win. */
    fun resolve(sugg: Suggestions?, level: PatternLevel, overrides: Map<Int, String>, sections: List<Section>, fallback: String): Map<Int, String> =
        sections.indices.associateWith { i -> overrides[i] ?: sugg?.sections?.getOrNull(i)?.get(level) ?: sections[i].pattern ?: fallback }
}
