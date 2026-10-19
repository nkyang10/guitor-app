package com.boss.guitartrainer.music

/**
 * Builds playable charts:
 *  - practice: chord progression (one chord per bar, or "C C G G" style) x pattern x BPM
 *  - song: beat grid + chord spans + per-section pattern from the server analysis
 */
object ChartBuilder {

    fun strumPractice(
        title: String, progression: List<String>, pattern: StrumPattern, bpm: Double, bars: Int,
        simplifyLevel: Int = 3, tuning: Tuning = Tunings.STANDARD,
    ): Chart {
        val spb = 60.0 / bpm
        val stepDur = spb / pattern.subdivision
        val events = ArrayList<ChartEvent>()
        val spans = ArrayList<ChordSpan>()
        for (bar in 0 until bars) {
            val sym = progression[bar % progression.size]
            val shape = ChordLibrary.forSymbol(sym, simplify = simplifyLevel < 3, level = simplifyLevel)
            val label = shape?.name ?: sym
            val barStart = bar * pattern.beatsPerBar * spb
            spans += ChordSpan(barStart, barStart + pattern.beatsPerBar * spb, label, sym)
            pattern.steps.forEachIndexed { i, ch ->
                if (ch == 'D' || ch == 'U' || ch == 'x') {
                    val strings = shape?.playedStrings() ?: (0..5).toList()
                    events += ChartEvent(
                        t = barStart + i * stepDur, type = "strum", chord = label,
                        dir = if (ch == 'x') "X" else ch.toString(),
                        strings = strings, midis = shape?.midis(tuning) ?: emptyList(),
                        bar = bar, beat = i.toDouble() / pattern.subdivision, accent = i == 0,
                    )
                }
            }
        }
        return Chart(id = "practice-${pattern.id}", title = title, bpm = bpm, beatsPerBar = pattern.beatsPerBar,
            chords = spans, events = events, patternId = pattern.id, kind = "practice")
    }

    fun fingerPractice(
        title: String, progression: List<String>, pattern: FingerPattern, bpm: Double, bars: Int,
        simplifyLevel: Int = 3, tuning: Tuning = Tunings.STANDARD,
    ): Chart {
        val spb = 60.0 / bpm
        val stepDur = spb / pattern.subdivision
        val events = ArrayList<ChartEvent>()
        val spans = ArrayList<ChordSpan>()
        for (bar in 0 until bars) {
            val sym = progression[bar % progression.size]
            val shape = ChordLibrary.forSymbol(sym, simplify = simplifyLevel < 3, level = simplifyLevel) ?: continue
            val barStart = bar * pattern.beatsPerBar * spb
            spans += ChordSpan(barStart, barStart + pattern.beatsPerBar * spb, shape.name, sym)
            pattern.steps.forEachIndexed { i, tok ->
                val strings = FingerMap.strings(tok, shape, tuning)
                if (strings.isNotEmpty()) {
                    events += ChartEvent(
                        t = barStart + i * stepDur, type = "note", chord = shape.name,
                        strings = strings, midis = strings.map { shape.midiOn(it, tuning)!! },
                        frets = strings.map { shape.frets[it] }, finger = tok,
                        bar = bar, beat = i.toDouble() / pattern.subdivision, accent = i == 0,
                    )
                }
            }
        }
        return Chart(id = "practice-${pattern.id}", title = title, bpm = bpm, beatsPerBar = pattern.beatsPerBar,
            chords = spans, events = events, patternId = pattern.id, kind = "practice")
    }

    /**
     * Song chart: uses the analysed beat grid. For every bar (downbeat to next downbeat) take the chord at
     * each step time and the section's pattern (strum or finger). Missing patterns fall back to [defaultPattern].
     */
    fun song(
        base: Chart, lib: PatternLibrary, defaultPattern: String, simplifyLevel: Int, tuning: Tuning = Tunings.STANDARD,
        patternOverride: Map<Int, String> = emptyMap(),
    ): Chart {
        val beats = base.beats
        if (beats.size < 2) return base
        val bpb = base.beatsPerBar
        val downs = if (base.downbeats.isNotEmpty()) base.downbeats else beats.filterIndexed { i, _ -> i % bpb == 0 }
        val events = ArrayList<ChartEvent>()
        // span.original = sounding chord; with a capo the player fingers the chord transposed down by the capo.
        val spans = base.chords.map { span ->
            if (ChordNames.normalize(span.original) == "N") return@map span.copy(label = "N")
            val fingered = ChordNames.transpose(span.original, -base.capo)
            val shape = ChordLibrary.forSymbol(fingered, simplify = simplifyLevel < 3, level = simplifyLevel)
            span.copy(label = shape?.name ?: ChordNames.simplify(fingered, simplifyLevel))
        }
        fun chordAt(t: Double) = spans.firstOrNull { t >= it.start - 0.05 && t < it.end - 0.05 }?.label
        for ((barIdx, d) in downs.withIndex()) {
            val sectionIdx = base.sections.indexOfFirst { d >= it.start - 0.05 && d < it.end - 0.05 }
            val pid = patternOverride[sectionIdx] ?: base.sections.getOrNull(sectionIdx)?.pattern ?: defaultPattern
            val sp = lib.strum(pid)
            val fp = lib.finger(pid)
            val bi = beats.indexOfFirst { kotlin.math.abs(it - d) < 0.03 }.let { if (it < 0) beats.indexOfFirst { b -> b >= d } else it }
            if (bi < 0) continue
            val stepsPerBeat = sp?.subdivision ?: fp?.subdivision ?: 2
            val nSteps = sp?.stepsPerBar ?: fp?.stepsPerBar ?: (bpb * 2)
            val beatsInPattern = sp?.beatsPerBar ?: fp?.beatsPerBar ?: bpb
            for (s in 0 until nSteps) {
                val beatPos = s.toDouble() / stepsPerBeat
                val b0 = bi + beatPos.toInt()
                if (b0 >= beats.size) break
                if (beatPos >= beatsInPattern) break
                val nextBeat = if (b0 + 1 < beats.size) beats[b0 + 1] else beats[b0] + (beats[b0] - beats[(b0 - 1).coerceAtLeast(0)])
                val t = beats[b0] + (nextBeat - beats[b0]) * (beatPos - beatPos.toInt())
                val sym = chordAt(t) ?: continue
                if (sym == "N") continue
                val shape = ChordLibrary.get(sym) ?: ChordLibrary.forSymbol(sym, true, 1) ?: continue
                if (sp != null) {
                    val ch = sp.steps[s]
                    if (ch == 'D' || ch == 'U' || ch == 'x') events += ChartEvent(t, "strum", shape.name,
                        if (ch == 'x') "X" else ch.toString(), shape.playedStrings(), shape.midis(tuning, base.capo),
                        bar = barIdx, beat = beatPos, accent = s == 0)
                } else if (fp != null) {
                    val strings = FingerMap.strings(fp.steps[s], shape, tuning)
                    if (strings.isNotEmpty()) events += ChartEvent(t, "note", shape.name, null, strings,
                        strings.map { shape.midiOn(it, tuning, base.capo)!! }, strings.map { shape.frets[it] },
                        fp.steps[s], barIdx, beatPos, s == 0)
                }
            }
        }
        return base.copy(events = events, chords = spans)
    }
}
