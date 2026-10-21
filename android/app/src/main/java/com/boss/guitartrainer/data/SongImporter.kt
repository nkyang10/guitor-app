package com.boss.guitartrainer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.boss.guitartrainer.analysis.AudioDecoder
import com.boss.guitartrainer.analysis.SongAnalyzer
import com.boss.guitartrainer.music.Chart
import com.boss.guitartrainer.net.RemoteSong
import com.boss.guitartrainer.net.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Adds songs (local file analysis, PC analysis, PC download) in an app-wide scope, one job at a time.
 *
 * The work used to live in the Songs screen's composition scope: leaving the tab dropped the progress UI while the
 * CPU work kept running, and coming back and importing again ran two analyses at once (twice the memory). Now the
 * job survives tab switches / rotation, the screen just observes [state], and [cancel] really stops it.
 */
object SongImporter {
    data class State(val busy: Boolean = false, val progress: Double = 0.0, val status: String? = null, val canCancel: Boolean = false)

    /** Bigger files are refused (a 10-minute WAV at 48 kHz/16-bit stereo is ~110 MB, lossless hi-res can be more). */
    const val MAX_FILE_BYTES = 300L * 1024 * 1024
    const val MIN_SECONDS = 10.0
    const val LONG_NOTICE = "⚠️ 首歌長過 10 分鐘，只分析咗頭 10 分鐘。"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _urlDone = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits the link of every finished PC analysis (the Songs screen clears its text field). */
    val urlDone: SharedFlow<String> = _urlDone.asSharedFlow()

    private val guard = CoroutineExceptionHandler { _, e ->
        _state.update { it.copy(busy = false, canCancel = false, status = "✗ 出錯：${e.message ?: e.javaClass.simpleName}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + guard)
    @Volatile private var job: Job? = null

    /** A problem the user can act on; the message is shown as is. */
    private class UserError(msg: String) : Exception(msg)

    fun cancel() { job?.cancel() }

    /** Analyses an audio file on the phone. Returns false when another job is still running. */
    fun importLocal(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        return start { runLocal(app, uri) }
    }

    /** Sends [url] to the PC server, waits for the result and downloads it. */
    fun analyzeUrl(ctx: Context, serverUrl: String, url: String, separate: Boolean, level: Int): Boolean {
        val app = ctx.applicationContext
        return start {
            CrashLog.activity = "PC 分析：$url"
            var downloading = false
            try {
                val api = ServerApi(serverUrl)
                set("送出分析…", 0.0)
                val jobId = withContext(Dispatchers.IO) { api.analyze(url, separate, level) }
                while (true) {
                    val st = withContext(Dispatchers.IO) { api.job(jobId) }
                    set(st.message.ifBlank { st.status }, st.progress)
                    if (st.status == "done" && st.song_id != null) {
                        downloading = true
                        importRemote(app, api, st.song_id, url)
                        set("✓ 完成", 1.0)
                        _urlDone.tryEmit(url)
                        break
                    }
                    if (st.status == "error") { set("✗ ${st.error ?: st.message}"); break }
                    delay(1500)
                }
            } catch (e: CancellationException) {
                set(if (downloading) "已取消下載" else "已停止等候（PC 仍然會繼續分析，完成後可以喺「伺服器上已分析」下載）")
                throw e
            } catch (e: Throwable) {
                set("✗ ${e.message ?: e.javaClass.simpleName}（檢查伺服器地址／同一 Wi-Fi／Tailscale）")
            }
        }
    }

    /** Downloads a song the PC server has already analysed. */
    fun downloadRemote(ctx: Context, serverUrl: String, r: RemoteSong): Boolean {
        val app = ctx.applicationContext
        return start {
            CrashLog.activity = "下載：${r.title}"
            try {
                importRemote(app, ServerApi(serverUrl), r.id, r.source)
                set("✓ 已下載 ${r.title}", 1.0)
            } catch (e: CancellationException) {
                set("已取消下載"); throw e
            } catch (e: Throwable) {
                set("✗ ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ jobs

    private fun start(block: suspend CoroutineScope.() -> Unit): Boolean {
        if (job?.isActive == true) return false
        _state.value = State(busy = true, progress = 0.0, status = "準備緊…", canCancel = true)
        job = scope.launch {
            try { block() } finally {
                CrashLog.activity = null
                _state.update { it.copy(busy = false, canCancel = false) }
            }
        }
        return true
    }

    private suspend fun runLocal(ctx: Context, uri: Uri) {
        val store = Store.get(ctx)
        val me = coroutineContext[Job]!!
        val id = "local-" + System.currentTimeMillis()
        var dir: File? = null
        var saved = false
        try {
            set("讀取檔案…", 0.0)
            val (display, size) = withContext(Dispatchers.IO) { meta(ctx, uri) }
            val name = display?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "本機歌曲"
            CrashLog.activity = "分析本機歌：${display ?: uri}（${size?.let { "%.1f MB".format(it / MB) } ?: "大小不明"}）"
            if (size != null && size > MAX_FILE_BYTES)
                throw UserError("檔案太大（%.0f MB）。手機最多處理 300 MB，試下 MP3 / M4A，或者用 PC 分析。".format(size / MB))
            val d = store.songDir(id).also { dir = it }
            val free = d.usableSpace
            if (size != null && free > 0 && free < size + 30L * 1024 * 1024)
                throw UserError("手機儲存空間唔夠（要大約 %.0f MB）。".format(size / MB + 30))
            val f = File(d, "mix." + extension(ctx, uri, display))
            set("複製檔案…", 0.0)
            withContext(Dispatchers.IO) { copy(ctx, uri, f, size) { me.ensureActive(); progress(it * 0.05) } }

            val total = AudioDecoder.probeSeconds(f)
            val long = total > AudioDecoder.MAX_SECONDS + 0.5
            val pre = if (long) "首歌有 ${mmss(total)}，手機只分析頭 10 分鐘。\n" else ""
            set(pre + "解碼…", 0.05)
            val dec = AudioDecoder.decode(f) { me.ensureActive(); progress(0.05 + it * 0.25) }
            if (dec.seconds < MIN_SECONDS) throw UserError("首歌太短（%.0f 秒），最少要 10 秒。".format(dec.seconds))

            set(pre + "分析節拍同和弦（手機版，冇分離音軌）…", 0.3)
            val chart = SongAnalyzer.analyze(dec.data, name, display ?: "本機檔案", 1, dec.size) { me.ensureActive(); progress(0.3 + it * 0.7) }
            if (chart.chords.none { it.original != "N" })
                throw UserError("聽唔到和弦（可能係純人聲／鼓聲／太細聲）。試下另一首歌，或者用 PC 分析。")
            store.saveSongChart(id, chart.copy(id = id), edited = false)
            store.upsertSong(SongEntry(id, name, "本機檔案", System.currentTimeMillis(), chart.bpm, chart.key, false,
                local = true, partial = dec.truncated))
            saved = true
            set("✓ 完成：$name（%.0f BPM，%s 調%s）".format(chart.bpm, chart.key ?: "?", if (chart.capo > 0) "，建議 Capo ${chart.capo}" else "") +
                if (dec.truncated) "\n$LONG_NOTICE" else "", 1.0)
        } catch (e: CancellationException) {
            set("已取消"); throw e
        } catch (e: OutOfMemoryError) {
            set("✗ 手機記憶體唔夠分析呢首歌。試下短啲嘅歌，或者用 PC 分析。")
        } catch (e: UserError) {
            set("✗ ${e.message}")
        } catch (e: AudioDecoder.UnsupportedFormat) {
            set("✗ ${e.message}")
        } catch (e: Throwable) {
            set("✗ 分析失敗：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}")
        } finally {
            if (!saved) dir?.let { runCatching { it.deleteRecursively() } }
        }
    }

    /** Chart → mix (0–70 %) → no-guitar stem (70–100 %). A new song's folder is removed again when this fails. */
    private suspend fun importRemote(ctx: Context, api: ServerApi, songId: String, source: String?) {
        val store = Store.get(ctx)
        val me = coroutineContext[Job]!!
        val existed = store.songs.value.any { it.id == songId }
        set("下載譜同音訊…", 0.0)
        try {
            val chart = Chart.parse(withContext(Dispatchers.IO) { api.chart(songId) })
            val dir = store.songDir(songId)
            withContext(Dispatchers.IO) { api.download(songId, "mix", File(dir, "mix.m4a")) { me.ensureActive(); progress(it * 0.7) } }
            val hasNg = withContext(Dispatchers.IO) {
                try { api.download(songId, "no_guitar", File(dir, "no_guitar.m4a")) { me.ensureActive(); progress(0.7 + it * 0.3) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            }
            store.saveSongChart(songId, chart.copy(id = songId), edited = false)
            store.upsertSong(SongEntry(songId, chart.title, source ?: chart.source, System.currentTimeMillis(), chart.bpm, chart.key, hasNg))
        } catch (e: Throwable) {
            if (!existed) runCatching { store.songDir(songId).deleteRecursively() }
            throw e
        }
    }

    // ------------------------------------------------------------------ helpers

    private const val MB = 1024.0 * 1024.0

    private fun set(status: String?, progress: Double? = null) =
        _state.update { it.copy(status = status, progress = (progress ?: it.progress).coerceIn(0.0, 1.0)) }

    /** Progress only (skips steps under 0.2 % so the UI is not flooded). */
    private fun progress(p: Double) {
        val v = p.coerceIn(0.0, 1.0)
        if (abs(v - _state.value.progress) < 0.002 && v < 1.0) return
        _state.update { it.copy(progress = v) }
    }

    private fun mmss(t: Double) = "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())

    /** Display name and size from the document provider (both may be missing). */
    private fun meta(ctx: Context, uri: Uri): Pair<String?, Long?> {
        val fromProvider = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                (if (ni >= 0 && !c.isNull(ni)) c.getString(ni) else null) to (if (si >= 0 && !c.isNull(si)) c.getLong(si) else null)
            }
        }.getOrNull()
        val name = fromProvider?.first?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }
        return name to fromProvider?.second?.takeIf { it > 0 }
    }

    private fun extension(ctx: Context, uri: Uri, display: String?): String {
        display?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.let { return it }
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        return when (mime) {
            null -> "m4a"
            "audio/mpeg" -> "mp3"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
        }
    }

    /** Copies the picked document into the song folder; enforces [MAX_FILE_BYTES] even when the size was unknown. */
    private fun copy(ctx: Context, uri: Uri, dest: File, expected: Long?, progress: (Double) -> Unit) {
        val input = try { ctx.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: throw UserError("開唔到個檔案（可能已經刪除或者冇權限）。")
        input.use { i ->
            dest.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (done > MAX_FILE_BYTES) throw UserError("檔案太大，手機最多處理 300 MB。試下 MP3 / M4A，或者用 PC 分析。")
                    progress(if (expected != null) (done.toDouble() / expected).coerceAtMost(1.0) else 0.0)
                }
            }
        }
    }
}
