package com.boss.guitartrainer.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable data class Health(val ok: Boolean = false, val version: String = "", val demucs: Boolean = false, val ytdlp: Boolean = false)
@Serializable data class AnalyzeRequest(val url: String, val separate: Boolean = true, val level: Int = 1)
@Serializable data class AnalyzeResponse(val job_id: String)
@Serializable data class JobStatus(
    val id: String = "", val status: String = "", val progress: Double = 0.0, val message: String = "",
    val song_id: String? = null, val error: String? = null,
)
@Serializable data class RemoteSong(
    val id: String, val title: String, val bpm: Double = 0.0, val key: String? = null, val duration: Double = 0.0,
    val has_no_guitar: Boolean = false, val source: String? = null,
)

/** Minimal client for the home-PC analysis server (server/README.md). */
class ServerApi(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun open(path: String, method: String = "GET", timeoutMs: Int = 15000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs * 2
        return c
    }

    private fun text(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: ${body.take(200)}")
        return body
    }

    fun health(): Health = json.decodeFromString(Health.serializer(), text(open("/health", timeoutMs = 4000)))

    fun analyze(url: String, separate: Boolean, level: Int): String {
        val c = open("/analyze", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.encodeToString(AnalyzeRequest.serializer(), AnalyzeRequest(url, separate, level)).toByteArray()) }
        return json.decodeFromString(AnalyzeResponse.serializer(), text(c)).job_id
    }

    fun job(id: String): JobStatus = json.decodeFromString(JobStatus.serializer(), text(open("/jobs/" + enc(id))))

    fun songs(): List<RemoteSong> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RemoteSong.serializer()), text(open("/songs")))

    fun chart(songId: String): String = text(open("/songs/${enc(songId)}/chart"))

    /** Downloads a stem (mix | no_guitar) to [dest]. Returns false when the stem does not exist. */
    fun download(songId: String, stem: String, dest: File, progress: (Double) -> Unit = {}): Boolean {
        val c = open("/songs/${enc(songId)}/audio?stem=${enc(stem)}", timeoutMs = 30000)
        if (c.responseCode == 404) return false
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toDouble() / total)
                }
            }
        }
        tmp.renameTo(dest)
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
