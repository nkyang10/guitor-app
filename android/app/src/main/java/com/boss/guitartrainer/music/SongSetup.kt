package com.boss.guitartrainer.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-song play-along choices, saved as `song_prefs.json` next to chart.json. They are layered on top of the analysed
 * chart, so every change is reversible and the analysis itself is never rewritten.
 */
@Serializable
data class SongPrefs(
    /** Semitones applied to EVERY chord and the key at once (改 key 時成套和弦一齊轉). */
    val transpose: Int = 0,
    /** true = 升降 Key: the music is pitch-shifted by [transpose] as well; false = only the chord chart is corrected. */
    val pitchFollow: Boolean = false,
    /** null = automatic capo suggestion for the (transposed) chords. */
    val capo: Int? = null,
    /** Pattern level for the whole song: easy | normal | original | advanced (null = analysis default). */
    val level: String? = null,
    /** strum | finger */
    val family: String = "strum",
    /** Section index -> pattern id picked by hand (wins over [level]). */
    val overrides: Map<Int, String> = emptyMap(),
    /** Chart clock shift vs the music (ms): + = notes come earlier. Set by「提早／推遲」, 自動對準 and the in-game nudge. */
    val offsetMs: Int = 0,
    /** The one-time automatic beat check has run for this song. */
    val autoChecked: Boolean = false,
)

/** Beat grid chosen by the user's beat tools (`beat_grid.json`); replaces the analysed beats / downbeats when present. */
@Serializable
data class BeatGrid(
    val beats: List<Double>,
    val downbeats: List<Double>,
    val beatsPerBar: Int = 4,
    val bpm: Double,
    val note: String = "",
)

object SongSetup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    const val PREFS = "song_prefs.json"
    const val GRID = "beat_grid.json"

    fun loadPrefs(dir: File): SongPrefs = runCatching { json.decodeFromString(SongPrefs.serializer(), File(dir, PREFS).readText()) }
        .getOrDefault(SongPrefs())

    fun savePrefs(dir: File, p: SongPrefs) = writeAtomic(File(dir, PREFS), json.encodeToString(SongPrefs.serializer(), p))

    fun loadGrid(dir: File): BeatGrid? = runCatching { json.decodeFromString(BeatGrid.serializer(), File(dir, GRID).readText()) }.getOrNull()
        ?.takeIf { it.beats.size >= 2 }

    fun saveGrid(dir: File, g: BeatGrid?) {
        val f = File(dir, GRID)
        if (g == null) f.delete() else writeAtomic(f, json.encodeToString(BeatGrid.serializer(), g))
    }

    /** Add the in-game「拍子早咗／遲咗」shift (ms) to the song's saved offset. */
    fun nudge(dir: File, ms: Int) {
        val p = loadPrefs(dir)
        savePrefs(dir, p.copy(offsetMs = (p.offsetMs + ms).coerceIn(-1000, 1000)))
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /**
     * The chart the player actually sees: analysed chart (+ single-chord edits) -> user beat grid -> transposition -> capo.
     * [level] = the player's chord level (1 beginner, 2 intermediate) used for the automatic capo suggestion.
     */
    fun effective(base: Chart, prefs: SongPrefs, grid: BeatGrid?, level: Int): Chart {
        var c = if (grid != null) applyGrid(base, grid) else base
        if (prefs.transpose != 0) c = transpose(c, prefs.transpose)
        val capo = prefs.capo ?: if (prefs.transpose == 0) base.capo else Capo.suggest(durations(c), level).capo
        return c.copy(capo = capo.coerceIn(0, 9), pitchShift = if (prefs.pitchFollow) prefs.transpose else 0,
            audioOffset = c.audioOffset + prefs.offsetMs / 1000.0)
    }

    fun durations(c: Chart): Map<String, Double> {
        val d = HashMap<String, Double>()
        for (s in c.chords) { val n = ChordNames.normalize(s.original); if (n != "N") d[n] = (d[n] ?: 0.0) + (s.end - s.start) }
        return d
    }

    /** Every chord (sounding pitch) and the key move by [semis]; "N" stays "N". */
    fun transpose(c: Chart, semis: Int): Chart {
        if (semis == 0) return c
        val chords = c.chords.map { sp ->
            if (ChordNames.normalize(sp.original) == "N") sp.copy(original = "N", label = "N")
            else ChordNames.transpose(sp.original, semis).let { sp.copy(original = it, label = it) }
        }
        return c.copy(chords = chords, key = c.key?.let { transposeKey(it, semis) })
    }

    fun transposeKey(key: String, semis: Int): String {
        val minor = key.endsWith("m") && !key.endsWith("maj")
        val root = ChordNames.transpose(if (minor) key.dropLast(1) else key, semis)
        return root + if (minor) "m" else ""
    }

    /** Semitones (-6..+5) from key [from] to key [to] (roots only). */
    fun semitonesBetween(from: String, to: String): Int {
        val a = ChordNames.rootPc(from) ?: return 0
        val b = ChordNames.rootPc(to) ?: return 0
        return wrap(b - a)
    }

    fun wrap(semis: Int): Int { val m = Math.floorMod(semis, 12); return if (m > 6) m - 12 else m }

    fun signed(semis: Int) = if (semis > 0) "+$semis" else "$semis"

    /**
     * Change a chord in the ANALYSED (untransposed) chart: [displayed] is what the user typed in the transposed view.
     * scope: "one" = only span [index]; "same" = every span with the same chord.
     */
    fun editChord(base: Chart, index: Int, displayed: String, transpose: Int, scope: String): Chart {
        val n = ChordNames.normalize(displayed)
        val newBase = if (n == "N") "N" else ChordNames.transpose(n, -transpose)
        val old = ChordNames.normalize(base.chords[index].original)
        val chords = base.chords.mapIndexed { i, sp ->
            if (i == index || (scope == "same" && ChordNames.normalize(sp.original) == old)) sp.copy(original = newBase, label = newBase) else sp
        }
        return base.copy(chords = mergeSame(chords))
    }

    /**
     * Same as [editChord] but addressed by time, because the shown (grid-snapped, merged) spans do not map 1:1 to the
     * stored spans: "one" = every stored span whose middle lies in [start, end); "same" = every span with that chord.
     */
    fun editRange(base: Chart, start: Double, end: Double, displayed: String, transpose: Int, scope: String): Chart {
        val n = ChordNames.normalize(displayed)
        val newBase = if (n == "N") "N" else ChordNames.transpose(n, -transpose)
        val mid = (start + end) / 2
        val anchor = base.chords.indexOfFirst { mid >= it.start && mid < it.end }.takeIf { it >= 0 }
            ?: base.chords.indices.minByOrNull { abs((base.chords[it].start + base.chords[it].end) / 2 - mid) } ?: return base
        val old = ChordNames.normalize(base.chords[anchor].original)
        val chords = base.chords.mapIndexed { i, sp ->
            val m = (sp.start + sp.end) / 2
            val hit = if (scope == "same") ChordNames.normalize(sp.original) == old else (i == anchor || (m >= start - 0.05 && m < end + 0.05))
            if (hit) sp.copy(original = newBase, label = newBase) else sp
        }
        return base.copy(chords = mergeSame(chords))
    }

    /** Count of spans that carry the same chord as span [index]. */
    fun sameCount(c: Chart, index: Int): Int {
        val old = ChordNames.normalize(c.chords.getOrNull(index)?.original ?: return 0)
        return c.chords.count { ChordNames.normalize(it.original) == old }
    }

    /** When the user changes G -> A (same chord quality) the whole song is probably in another key: interval to offer. */
    fun keyShiftSuggestion(oldChord: String, newChord: String): Int? {
        val o = ChordNames.normalize(oldChord); val n = ChordNames.normalize(newChord)
        if (o == "N" || n == "N" || o == n) return null
        val ro = ChordNames.rootPc(o) ?: return null; val rn = ChordNames.rootPc(n) ?: return null
        if (quality(o) != quality(n)) return null
        return wrap(rn - ro).takeIf { it != 0 }
    }

    private fun quality(n: String): String = n.drop(if (n.length > 1 && (n[1] == '#' || n[1] == 'b')) 2 else 1)

    private fun mergeSame(spans: List<ChordSpan>): List<ChordSpan> {
        val out = ArrayList<ChordSpan>()
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && last.original == s.original && abs(last.end - s.start) < 1e-3) out[out.size - 1] = last.copy(end = s.end)
            else out += s
        }
        return out
    }

    /** Replace the beat grid; chord and section boundaries snap to the nearest new beat / downbeat. */
    fun applyGrid(c: Chart, g: BeatGrid): Chart {
        val beats = g.beats
        fun snap(t: Double, grid: List<Double>, maxDist: Double): Double {
            if (grid.isEmpty()) return t
            val i = nearest(grid, t)
            return if (abs(grid[i] - t) <= maxDist) grid[i] else t
        }
        val period = medianPeriod(beats)
        val spans = c.chords.map { sp -> sp.copy(start = snap(sp.start, beats, period * 0.45), end = snap(sp.end, beats, period * 0.45)) }
            .filter { it.end - it.start > 1e-3 }
        val barLen = period * g.beatsPerBar
        val sections = c.sections.map { s -> s.copy(start = snap(s.start, g.downbeats, barLen * 0.5), end = snap(s.end, g.downbeats, barLen * 0.5)) }
            .filter { it.end - it.start > 1e-3 }
        return c.copy(beats = beats, downbeats = g.downbeats, beatsPerBar = g.beatsPerBar, bpm = g.bpm, chords = mergeSame(spans), sections = sections)
    }

    fun nearest(list: List<Double>, t: Double): Int {
        var lo = 0; var hi = list.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (list[mid] < t) lo = mid + 1 else hi = mid }
        return if (lo > 0 && abs(list[lo - 1] - t) <= abs(list[lo] - t)) lo - 1 else lo
    }

    fun medianPeriod(beats: List<Double>): Double {
        if (beats.size < 2) return 0.5
        val d = beats.zipWithNext { a, b -> b - a }.sorted()
        return d[d.size / 2]
    }
}

/** Beat-grid fixes for「節拍唔跟首歌」: shift, half / double tempo, half-beat shift, move the「1」, 3/4 vs 4/4. */
object GridOps {
    fun from(c: Chart): BeatGrid {
        val bpb = c.beatsPerBar.coerceAtLeast(2)
        val downs = if (c.downbeats.isNotEmpty()) c.downbeats else c.beats.filterIndexed { i, _ -> i % bpb == 0 }
        return BeatGrid(c.beats, downs, bpb, c.bpm)
    }

    /** Index of the first downbeat inside [beats] (0..bpb-1). */
    fun phase(g: BeatGrid): Int {
        val d0 = g.downbeats.firstOrNull() ?: return 0
        return Math.floorMod(SongSetup.nearest(g.beats, d0), g.beatsPerBar)
    }

    fun withPhase(beats: List<Double>, bpb: Int, phase: Int, note: String): BeatGrid {
        val p = Math.floorMod(phase, bpb)
        val downs = beats.filterIndexed { i, _ -> i >= p && (i - p) % bpb == 0 }
        return BeatGrid(beats, downs, bpb, bpmOf(beats), note)
    }

    fun bpmOf(beats: List<Double>): Double {
        if (beats.size < 2) return 120.0
        // least-squares slope of beat time vs index (robust to ±1 frame jitter)
        val n = beats.size; val mi = (n - 1) / 2.0; val mt = beats.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - mi) * (beats[i] - mt); den += (i - mi) * (i - mi) }
        val slope = num / den
        return if (slope > 0) 60.0 / slope else 120.0
    }

    fun shift(g: BeatGrid, sec: Double): BeatGrid =
        g.copy(beats = g.beats.map { it + sec }, downbeats = g.downbeats.map { it + sec }, note = "shift")

    /** Beats sit on the「&」: move every beat half a beat later. */
    fun halfBeat(g: BeatGrid): BeatGrid {
        val b = g.beats
        if (b.size < 2) return g
        val nb = b.indices.map { i -> if (i + 1 < b.size) (b[i] + b[i + 1]) / 2 else b[i] + (b[i] - b[i - 1]) / 2 }
        return withPhase(nb, g.beatsPerBar, phase(g), "half-beat")
    }

    /** Grid is half as fast as the song: add a beat between every two. */
    fun double(g: BeatGrid): BeatGrid {
        val b = g.beats
        if (b.size < 2) return g
        val nb = ArrayList<Double>(b.size * 2)
        for (i in b.indices) { nb += b[i]; if (i + 1 < b.size) nb += (b[i] + b[i + 1]) / 2 }
        return withPhase(nb, g.beatsPerBar, 2 * phase(g), "x2")
    }

    /** Grid is twice as fast as the song: keep every other beat ([keep] = 0 keeps the beats on the current downbeats). */
    fun halve(g: BeatGrid, keep: Int = 0): BeatGrid {
        val i0 = g.downbeats.firstOrNull()?.let { SongSetup.nearest(g.beats, it) } ?: 0
        val parity = Math.floorMod(i0 + keep, 2)
        val nb = g.beats.filterIndexed { i, _ -> i % 2 == parity }
        if (nb.size < 2) return g
        val d0 = g.downbeats.firstOrNull() ?: nb.first()
        val np = Math.floorMod(SongSetup.nearest(nb, d0), g.beatsPerBar)
        return withPhase(nb, g.beatsPerBar, np, "/2")
    }

    /** Move「1」(the downbeat) by [beats] beats. */
    fun rotate(g: BeatGrid, beats: Int): BeatGrid = withPhase(g.beats, g.beatsPerBar, phase(g) + beats, "rotate")

    fun meter(g: BeatGrid, bpb: Int): BeatGrid = withPhase(g.beats, bpb, phase(g), "meter")

    /** Downbeat phase that puts the most chord changes on「1」(same idea as the analyser). */
    fun phaseFromChords(beats: List<Double>, chords: List<ChordSpan>, bpb: Int): Int {
        val score = DoubleArray(bpb)
        val period = SongSetup.medianPeriod(beats)
        for (sp in chords.drop(1)) {
            val i = SongSetup.nearest(beats, sp.start)
            if (abs(beats[i] - sp.start) < period * 0.3) score[i % bpb] += 1.0
        }
        return (0 until bpb).maxByOrNull { score[it] } ?: 0
    }

    fun describe(g: BeatGrid): String = "%.0f BPM · %d/4".format(g.bpm, g.beatsPerBar)

    fun ms(sec: Double) = (sec * 1000).roundToInt()
}
