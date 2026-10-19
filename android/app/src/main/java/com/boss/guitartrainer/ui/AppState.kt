package com.boss.guitartrainer.ui

import android.content.Context
import com.boss.guitartrainer.data.SessionRecord
import com.boss.guitartrainer.game.Achievement
import com.boss.guitartrainer.game.DifficultySettings
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.music.PatternLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class GameRequest(
    val chart: Chart,
    val settings: DifficultySettings,
    val mode: String,                     // strum | finger | song
    val title: String,
    val patternId: String? = null,
    val songId: String? = null,
    val audio: File? = null,
    val loop: ClosedFloatingPointRange<Double>? = null,
    val extraOffsetMs: Int = 0,
)

data class ResultPayload(
    val record: SessionRecord,
    val deltas: List<Double>,
    val newAchievements: List<Achievement>,
    val request: GameRequest,
)

/** Process-wide UI state shared between screens (navigation arguments that are too big for routes). */
object AppState {
    @Volatile var pendingGame: GameRequest? = null
    @Volatile var lastResult: ResultPayload? = null
    val sharedLink = MutableStateFlow<String?>(null)
    @Volatile private var lib: PatternLibrary? = null

    fun patterns(ctx: Context): PatternLibrary = lib ?: synchronized(this) {
        lib ?: PatternLibrary.parse(ctx.assets.open("patterns.json").bufferedReader().use { it.readText() }).also { lib = it }
    }

    fun extractUrl(text: String?): String? {
        if (text == null) return null
        return Regex("https?://\\S+").find(text)?.value?.trimEnd(')', ']', '>', '"', '\'', '.', ',')
    }
}
