package com.boss.guitartrainer.game

import com.boss.guitartrainer.audio.LiveAnalyzer
import com.boss.guitartrainer.dsp.ChordAnalyzer
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.ChartEvent
import kotlin.math.abs
import kotlin.math.max

enum class Grade { PERFECT, GOOD, MISS }

class EventResult {
    @Volatile var grade: Grade? = null
    var deltaMs: Double? = null
    var chordSim: Double? = null
    var chordOk: Boolean? = null
    var missingStrings: List<Int> = emptyList()
    var dirProb: Double? = null
    var dirOk: Boolean? = null
    var notesOk: Boolean? = null
    var aiNotesOk: Boolean? = null
    var analysed = false
    var points = 0.0
    var onsetId = -1L
    var onsetSample = -1L
    var weak = false
    var ratio = 0.0
    var attempts = 0
}

data class Feedback(val text: String, val kind: Kind, val seq: Long, val eventIdx: Int = -1) {
    enum class Kind { PERFECT, GOOD, MISS, WRONG_CHORD, WRONG_DIR, WRONG_NOTE, EXTRA, INFO }
}

/**
 * Matches detected onsets to expected chart events and grades them.
 * Timing: nearest unjudged event within the Good window (windows are real-time ms; chart time is
 * scaled by the tempo factor). Weak onset candidates are only used if nothing stronger matched
 * before the window closed. Chord / direction / note checks arrive asynchronously (onAnalysis).
 */
class Judge(val chart: Chart, val settings: DifficultySettings, private val tempo: Double) {
    companion object {
        var DIR_GATE = 0.75          // judge direction only when confident (Phase 0: ~60 % of strums judged, ~82 % balanced acc.)
        const val POINTS_PERFECT = 100.0
        const val POINTS_GOOD = 60.0
    }

    val events: List<ChartEvent> = chart.events
    val results = Array(events.size) { EventResult() }
    private val onsetToEvent = HashMap<Long, Int>()
    private val weakBest = HashMap<Int, Triple<Long, Long, Double>>()      // event -> (id, sample, ratio)
    private val weakTime = HashMap<Long, Double>()
    private val analysisCache = HashMap<Long, Summary>()
    private val lock = Any()
    private var seq = 0L
    private var scanFrom = 0

    @Volatile var combo = 0; private set
    @Volatile var maxCombo = 0; private set
    @Volatile var extras = 0; private set
    @Volatile var feedback: Feedback? = null; private set
    @Volatile var lastAnalysedSim: Double? = null; private set

    private val goodWin get() = settings.goodMs / 1000.0 * tempo
    private val simThreshold = if (settings.preset == "intermediate") ChordAnalyzer.SIM_THRESHOLD_STRICT else ChordAnalyzer.SIM_THRESHOLD
    private val waitMode get() = settings.waitMode

    data class Summary(val chordSim: Double?, val missing: List<Int>, val probDown: Double?, val notePresence: List<Double>)

    private fun fb(text: String, kind: Feedback.Kind, idx: Int) { feedback = Feedback(text, kind, ++seq, idx) }

    /** First event that is not finished. */
    fun firstOpen(): Int = synchronized(lock) {
        var i = scanFrom
        while (i < events.size && results[i].grade != null && satisfied(i)) i++
        i
    }

    private fun satisfied(i: Int): Boolean {
        val r = results[i]
        if (r.grade == null) return false
        if (!waitMode) return true
        if (r.grade == Grade.MISS) return true
        return !needsAnalysis(i) || (r.analysed && r.chordOk != false && r.notesOk != false)
    }

    private fun needsAnalysis(i: Int): Boolean {
        val e = events[i]
        return (e.type == "strum" && e.dir != "X" && settings.chordCheck) || e.type == "note"
    }

    fun onOnset(id: Long, sample: Long, chartTime: Double, ratio: Double, weak: Boolean) = synchronized(lock) {
        if (waitMode) {
            val i = firstOpen()
            if (i >= events.size) return@synchronized
            val r = results[i]
            if (abs(events[i].t - chartTime) > max(0.6, 2 * goodWin)) { if (!weak) { extras++; fb("等緊你…", Feedback.Kind.INFO, i) }; return@synchronized }
            if (r.grade != null && !r.analysed) return@synchronized   // still checking previous attempt
            r.grade = Grade.GOOD; r.deltaMs = null; r.onsetId = id; r.onsetSample = sample; r.weak = weak
            r.attempts++; r.analysed = false; r.chordOk = null; r.notesOk = null
            r.points = if (r.attempts == 1) POINTS_PERFECT else max(40.0, POINTS_PERFECT - 20.0 * (r.attempts - 1))
            onsetToEvent[id] = i
            if (!needsAnalysis(i)) { combo++; maxCombo = max(maxCombo, combo); fb("OK", Feedback.Kind.GOOD, i) }
            return@synchronized
        }
        var best = -1
        var bestD = Double.MAX_VALUE
        var i = scanFrom
        while (i < events.size) {
            val e = events[i]
            if (e.t > chartTime + goodWin) break
            if (results[i].grade == null) {
                val d = abs(e.t - chartTime)
                if (d <= goodWin && d < bestD) { best = i; bestD = d }
            }
            i++
        }
        if (best < 0 && !weak) {
            // a stronger / better-timed onset may replace an earlier spurious one (fret noise etc.)
            var j = scanFrom
            while (j < events.size && events[j].t <= chartTime + goodWin) {
                val r = results[j]
                val d = abs(events[j].t - chartTime)
                if (r.grade != null && r.grade != Grade.MISS && r.deltaMs != null && d <= goodWin &&
                    d * 1000 / tempo < abs(r.deltaMs!!) - 15 && ratio >= 0.8 * r.ratio) {
                    onsetToEvent.remove(r.onsetId)
                    val keep = r.grade
                    results[j] = EventResult()
                    if (keep != null) combo = (combo - 1).coerceAtLeast(0)
                    extras++
                    assign(j, id, sample, chartTime, false); results[j].ratio = ratio
                    return@synchronized
                }
                j++
            }
        }
        if (best < 0) {
            if (!weak) { extras++; fb("多咗一下", Feedback.Kind.EXTRA, -1) }
            return@synchronized
        }
        if (weak) {
            val cur = weakBest[best]
            if (cur == null || ratio > cur.third) weakBest[best] = Triple(id, sample, ratio)
            weakTime[id] = chartTime
            return@synchronized
        }
        assign(best, id, sample, chartTime, false)
        results[best].ratio = ratio
    }

    private fun assign(i: Int, id: Long, sample: Long, chartTime: Double, weak: Boolean) {
        val r = results[i]
        val dms = (chartTime - events[i].t) / tempo * 1000.0
        r.deltaMs = dms; r.onsetId = id; r.onsetSample = sample; r.weak = weak
        r.grade = if (abs(dms) <= settings.perfectMs) Grade.PERFECT else Grade.GOOD
        r.points = if (r.grade == Grade.PERFECT) POINTS_PERFECT else POINTS_GOOD
        combo++; maxCombo = max(maxCombo, combo)
        onsetToEvent[id] = i
        weakBest.remove(i)
        val early = if (dms < -settings.perfectMs) " (早)" else if (dms > settings.perfectMs) " (遲)" else ""
        fb(if (r.grade == Grade.PERFECT) "Perfect" else "Good$early",
            if (r.grade == Grade.PERFECT) Feedback.Kind.PERFECT else Feedback.Kind.GOOD, i)
        analysisCache.remove(id)?.let { apply(i, it) }
    }

    fun onAnalysis(id: Long, a: LiveAnalyzer.Analysis) = synchronized(lock) {
        val idx = onsetToEvent[id]
        val target = idx ?: weakBest.entries.firstOrNull { it.value.first == id }?.key ?: run {
            // unmatched strong onset: still report what we heard (useful in chord check mode)
            return@synchronized
        }
        val s = summarize(target, a)
        if (idx == null) { analysisCache[id] = s; return@synchronized }
        apply(idx, s)
    }

    private fun summarize(i: Int, a: LiveAnalyzer.Analysis): Summary {
        val e = events[i]
        return when (e.type) {
            "strum" -> {
                val sim = if (e.midis.isNotEmpty()) a.chord.similarity(e.midis) else null
                // up-strums usually only hit the treble strings: check those
                val strings = if (e.dir == "U") e.strings.filter { it >= 2 } else e.strings
                val missing = strings.filter { s ->
                    val m = e.midis.getOrNull(e.strings.indexOf(s)) ?: return@filter false
                    a.chord.presence(m) < ChordAnalyzer.PRESENCE_DB
                }
                lastAnalysedSim = sim
                Summary(sim, missing, a.probDown, emptyList())
            }
            else -> Summary(null, emptyList(), null, e.midis.map { a.chord.presence(it) })
        }
    }

    private fun apply(i: Int, s: Summary) {
        val e = events[i]
        val r = results[i]
        r.analysed = true
        if (e.type == "strum") {
            r.chordSim = s.chordSim
            if (settings.chordCheck && e.dir != "X" && s.chordSim != null) {
                val ok = s.chordSim >= simThreshold
                r.chordOk = ok
                r.missingStrings = s.missing
                if (!ok) {
                    r.points *= 0.5
                    val miss = if (s.missing.isNotEmpty()) "（${s.missing.joinToString("、") { "${6 - it}弦" }}無聲？）" else ""
                    fb("和弦唔啱$miss", Feedback.Kind.WRONG_CHORD, i)
                    if (settings.breakComboOnWrongChord || waitMode) combo = 0
                    if (waitMode) { r.grade = null; return }   // try again
                }
            }
            if (settings.directionWeight != DirectionWeight.OFF && (e.dir == "D" || e.dir == "U") && s.probDown != null) {
                r.dirProb = s.probDown
                val conf = max(s.probDown, 1 - s.probDown)
                if (conf >= DIR_GATE) {
                    val ok = (s.probDown > 0.5) == (e.dir == "D")
                    r.dirOk = ok
                    if (!ok) {
                        r.points *= if (settings.directionWeight == DirectionWeight.LOW) 0.9 else 0.7
                        fb(if (e.dir == "D") "應該係下掃 ↓" else "應該係上掃 ↑", Feedback.Kind.WRONG_DIR, i)
                        if (settings.directionWeight == DirectionWeight.NORMAL && settings.breakComboOnWrongChord) combo = 0
                    }
                }
            }
            if (waitMode && r.grade != null) { combo++; maxCombo = max(maxCombo, combo); if (r.chordOk != false) fb("啱！", Feedback.Kind.GOOD, i) }
        } else {
            val pres = s.notePresence
            val okCount = pres.count { it >= ChordAnalyzer.PRESENCE_DB }
            val ok = pres.isEmpty() || okCount == pres.size || (pres.size >= 3 && okCount >= pres.size - 1)
            r.notesOk = ok
            if (!ok) {
                r.points *= 0.5
                fb("音唔啱 / 冇聲", Feedback.Kind.WRONG_NOTE, i)
                if (settings.breakComboOnWrongChord || waitMode) combo = 0
                if (waitMode) { r.grade = null; return }
            } else if (waitMode) { combo++; maxCombo = max(maxCombo, combo); fb("啱！", Feedback.Kind.GOOD, i) }
        }
    }

    /** AI (Basic Pitch) refinement of note correctness. */
    fun onAiNotes(eventIdx: Int, ok: Boolean) = synchronized(lock) {
        val r = results.getOrNull(eventIdx) ?: return@synchronized
        if (r.grade == null || r.grade == Grade.MISS) return@synchronized
        val before = r.aiNotesOk
        r.aiNotesOk = ok
        if (before == null && r.notesOk != null && ok != r.notesOk) {
            // AI verdict overrides the quick DSP check
            if (ok) r.points = (r.points * 2).coerceAtMost(POINTS_PERFECT) else r.points *= 0.5
            r.notesOk = ok
        }
    }

    /** Close windows that have passed; resolve weak candidates; mark misses. */
    fun update(chartTime: Double) = synchronized(lock) {
        if (waitMode) { scanFrom = firstOpenUnlocked(); return@synchronized }
        var i = scanFrom
        while (i < events.size && events[i].t + goodWin < chartTime) {
            val r = results[i]
            if (r.grade == null) {
                val w = weakBest.remove(i)
                if (w != null) assign(i, w.first, w.second, weakTime[w.first] ?: events[i].t, true)
                else {
                    r.grade = Grade.MISS; r.points = 0.0; combo = 0
                    fb("Miss", Feedback.Kind.MISS, i)
                }
            }
            i++
        }
        while (scanFrom < events.size && results[scanFrom].grade != null && events[scanFrom].t + goodWin < chartTime) scanFrom++
    }

    private fun firstOpenUnlocked(): Int {
        var i = scanFrom
        while (i < events.size && results[i].grade != null && satisfied(i)) i++
        return i
    }

    /** Reset a time range (A-B loop) so it can be played again. */
    fun resetRange(t0: Double, t1: Double) = synchronized(lock) {
        for (i in events.indices) if (events[i].t >= t0 - 1e-6 && events[i].t < t1) {
            results[i] = EventResult()
        }
        scanFrom = events.indexOfFirst { it.t >= t0 - 1e-6 }.coerceAtLeast(0)
        combo = 0
    }

    fun isFinished(chartTime: Double): Boolean =
        if (waitMode) firstOpen() >= events.size else events.isEmpty() || chartTime > events.last().t + goodWin + 0.3

    fun progress(): Double = if (events.isEmpty()) 1.0 else results.count { it.grade != null }.toDouble() / events.size

    fun score(): Double = results.sumOf { it.points }
    fun maxScore(): Double = events.size * POINTS_PERFECT
}
