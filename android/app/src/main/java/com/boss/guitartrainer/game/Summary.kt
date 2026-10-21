package com.boss.guitartrainer.game

import com.boss.guitartrainer.data.Days
import com.boss.guitartrainer.data.SessionRecord
import kotlin.math.sqrt

object SessionSummary {
    fun build(
        judge: Judge, mode: String, title: String, patternId: String?, bpm: Double, durationSec: Double, songId: String? = null,
    ): SessionRecord {
        val r = judge.results
        val ev = judge.events
        val n = r.size
        val perfect = r.count { it.grade == Grade.PERFECT }
        val good = r.count { it.grade == Grade.GOOD }
        val miss = r.count { it.grade == Grade.MISS || it.grade == null }
        val acc = if (n == 0) 0.0 else 100.0 * judge.score() / judge.maxScore()
        val chordJ = r.filter { it.chordOk != null }
        val dirJ = r.filter { it.dirOk != null }
        val noteJ = r.filter { it.notesOk != null }
        val deltas = r.mapNotNull { it.deltaMs }
        val mean = if (deltas.isEmpty()) null else deltas.average()
        val sd = if (deltas.size < 2) null else sqrt(deltas.sumOf { (it - mean!!) * (it - mean) } / (deltas.size - 1))
        val missedChords = HashMap<String, Int>()
        val missingStrings = HashMap<Int, Int>()
        var changeEvents = 0; var changeMiss = 0
        for (i in ev.indices) {
            val res = r[i]
            if (res.chordOk == false) ev[i].chord?.let { missedChords[it] = (missedChords[it] ?: 0) + 1 }
            for (s in res.missingStrings) missingStrings[s] = (missingStrings[s] ?: 0) + 1
            if (i > 0 && ev[i].chord != null && ev[i].chord != ev[i - 1].chord) {
                changeEvents++
                if (res.grade == Grade.MISS || res.grade == null || res.chordOk == false) changeMiss++
            }
        }
        return SessionRecord(
            id = System.currentTimeMillis(), at = System.currentTimeMillis(), mode = mode, title = title, patternId = patternId,
            bpm = bpm, tempoPercent = judge.settings.tempoPercent, preset = judge.settings.preset, waitMode = judge.settings.waitMode,
            events = n, perfect = perfect, good = good, miss = miss, extras = judge.extras, accuracy = acc, stars = stars(acc),
            maxCombo = judge.maxCombo,
            chordAcc = if (chordJ.isEmpty()) null else 100.0 * chordJ.count { it.chordOk == true } / chordJ.size,
            dirAcc = if (dirJ.isEmpty()) null else 100.0 * dirJ.count { it.dirOk == true } / dirJ.size,
            notesAcc = if (noteJ.isEmpty()) null else 100.0 * noteJ.count { it.notesOk == true } / noteJ.size,
            meanOffsetMs = mean, sdOffsetMs = sd, durationSec = durationSec,
            missedChords = missedChords, missingStrings = missingStrings,
            changeMissRate = if (changeEvents >= 4) changeMiss.toDouble() / changeEvents else null, songId = songId,
        )
    }

    fun stars(acc: Double) = when { acc >= 90 -> 3; acc >= 75 -> 2; acc >= 50 -> 1; else -> 0 }
}

/** Rule-based practice tips from one session (task: 練習建議). */
object Tips {
    fun forSession(s: SessionRecord): List<String> {
        val out = ArrayList<String>()
        val mean = s.meanOffsetMs; val sd = s.sdOffsetMs
        if (mean != null && mean > 25) out += "你平均慢咗 ${mean.toInt()} ms：試下預早少少落手，或者將速度調慢 10% 先跟穩節拍器。"
        if (mean != null && mean < -25) out += "你平均快咗 ${(-mean).toInt()} ms（搶拍）：專心聽節拍器，手腕放鬆唔好急。"
        if (sd != null && sd > 35) out += "節奏唔夠穩定（±${sd.toInt()} ms）：用『等待模式』熟手之後，再開節拍器以 60–70% 速度練。"
        if (s.events > 0 && s.miss.toDouble() / s.events > 0.2) out += "Miss 比較多（${s.miss}/${s.events}）：試下降低速度或者用初學程度。"
        if (s.extras > maxOf(4, s.events / 8)) out += "多咗 ${s.extras} 下額外掃弦：pattern 入面嘅空拍要『空揮』，手照郁但唔好掂弦。"
        s.chordAcc?.let { if (it < 80) {
            val worst = s.missedChords.entries.sortedByDescending { e -> e.value }.take(2).joinToString("、") { e -> e.key }
            out += "和弦準確度 ${it.toInt()}%：最常錯 $worst。慢慢按好每隻手指，逐條弦撥一次檢查有冇悶音。"
        } }
        if (s.missingStrings.isNotEmpty()) {
            val (str, cnt) = s.missingStrings.maxByOrNull { it.value }!!
            if (cnt >= 3) out += "第 ${6 - str} 弦經常無聲（$cnt 次）：檢查附近手指有冇掂到呢條弦，指尖要企直。"
        }
        s.dirAcc?.let { if (it < 75) out += "上下掃方向準確度 ${it.toInt()}%：上掃只需掃高音 3–4 條弦；未校準嘅話去『校準 → 上下掃』做個人校準。" }
        s.notesAcc?.let { if (it < 80) out += "指彈音準 ${it.toInt()}%：確認 p 用拇指彈根音弦，i/m/a 對應 3/2/1 弦，撥完唔好即刻消音。" }
        s.changeMissRate?.let { if (it > 0.3) out += "轉和弦位錯得多（${(it * 100).toInt()}%）：做『一分鐘轉和弦』練習，提早半拍準備下一個和弦。" }
        if (out.isEmpty()) {
            out += if (s.accuracy >= 90 && s.tempoPercent < 100) "好穩！試下將速度加 5–10%。"
            else if (s.accuracy >= 90) "好正！可以試難一級嘅 pattern 或者中級程度。"
            else "繼續保持，每日練 10–15 分鐘最有效。"
        }
        return out
    }
}

data class Achievement(val id: String, val title: String, val desc: String, val unlocked: Boolean, val progress: String = "")

/** XP / level / achievements derived from history (no extra state needed). */
object Progression {
    fun xpOf(r: SessionRecord) = (r.accuracy / 10.0 * (1 + r.events / 40.0)).toInt() + 5
    fun xp(h: List<SessionRecord>) = h.sumOf { xpOf(it) }
    fun level(xp: Int): Int = 1 + kotlin.math.sqrt(xp / 30.0).toInt()
    fun xpForLevel(l: Int) = ((l - 1) * (l - 1)) * 30

    fun streakDays(h: List<SessionRecord>): Int {
        if (h.isEmpty()) return 0
        val days = h.map { Days.of(it.at) }.toSortedSet()
        var d = Days.today()
        if (!days.contains(d)) d -= 1
        var n = 0
        while (days.contains(d)) { n++; d-- }
        return n
    }

    fun bestStreak(h: List<SessionRecord>): Int {
        val days = h.map { Days.of(it.at) }.toSortedSet().toList()
        var best = 0; var cur = 0; var prev = Long.MIN_VALUE
        for (d in days) { cur = if (d == prev + 1) cur + 1 else 1; best = maxOf(best, cur); prev = d }
        return best
    }

    fun achievements(h: List<SessionRecord>, counters: Map<String, Int>): List<Achievement> {
        val minutes = h.sumOf { it.durationSec } / 60
        val best = bestStreak(h)
        fun a(id: String, t: String, d: String, ok: Boolean, p: String = "") = Achievement(id, t, d, ok, p)
        return listOf(
            a("first", "第一步", "完成第一次練習", h.isNotEmpty()),
            a("ten", "勤力", "完成 10 次練習", h.size >= 10, "${minOf(h.size, 10)}/10"),
            a("fifty", "結他人", "完成 50 次練習", h.size >= 50, "${minOf(h.size, 50)}/50"),
            a("streak3", "三日不斷", "連續 3 日練習", best >= 3, "$best/3"),
            a("streak7", "一星期", "連續 7 日練習", best >= 7, "$best/7"),
            a("min60", "一個鐘", "累積練習 60 分鐘", minutes >= 60, "${minutes.toInt()}/60"),
            a("min600", "十個鐘", "累積練習 600 分鐘", minutes >= 600, "${minutes.toInt()}/600"),
            a("star3", "三星", "任何練習拎到 3 星", h.any { it.stars == 3 }),
            a("combo50", "Combo 50", "一次過連續 50 個無 Miss", h.any { it.maxCombo >= 50 }),
            a("combo100", "Combo 100", "一次過連續 100 個無 Miss", h.any { it.maxCombo >= 100 }),
            a("dir90", "上下分明", "上下掃方向準確度 ≥ 90%（≥ 30 下）", h.any { (it.dirAcc ?: 0.0) >= 90 && it.events >= 30 }),
            a("finger90", "指彈入門", "指彈音準 ≥ 90%", h.any { it.mode == "finger" && (it.notesAcc ?: 0.0) >= 90 }),
            a("song1", "第一首歌", "完成一首歌曲模式", h.any { it.mode == "song" }),
            a("fullspeed", "原速", "以 100% 速度拎到 ≥ 2 星", h.any { it.tempoPercent >= 100 && it.stars >= 2 }),
            a("intermediate", "升級", "用中級程度拎到 ≥ 2 星", h.any { it.preset == "intermediate" && it.stars >= 2 }),
            a("tuner10", "準音", "用調音器 10 次", (counters["tuner"] ?: 0) >= 10, "${minOf(counters["tuner"] ?: 0, 10)}/10"),
        )
    }
}
