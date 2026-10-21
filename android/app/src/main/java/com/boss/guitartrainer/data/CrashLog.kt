package com.boss.guitartrainer.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash (stack trace + memory numbers + what the app was doing) in a file, so it can be shared from
 * Settings without adb. Only Java/Kotlin crashes are caught; native crashes leave no file.
 */
object CrashLog {
    private const val FILE = "crash_last.txt"
    @Volatile private var installed = false
    /** Short description of the current long-running job (e.g. "分析本機歌：xxx.mp3"); written into the report. */
    @Volatile var activity: String? = null

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file(app).writeText(report(app, thread, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)
    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    fun clear(ctx: Context) { file(ctx).delete() }

    fun memoryLine(ctx: Context): String {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return "heap 用咗 ${(rt.totalMemory() - rt.freeMemory()) / mb} MB / 上限 ${rt.maxMemory() / mb} MB" +
            (am?.let { " · memoryClass ${it.memoryClass} MB / large ${it.largeMemoryClass} MB" } ?: "")
    }

    private fun report(ctx: Context, thread: Thread, e: Throwable): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lineSequence().take(120).joinToString("\n")
        return buildString {
            append("時間：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("App：").append(version).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("記憶體：").append(runCatching { memoryLine(ctx) }.getOrDefault("?")).append('\n')
            activity?.let { append("當時：").append(it).append('\n') }
            append("Thread：").append(thread.name).append('\n')
            append("錯誤：").append(e.javaClass.name).append(": ").append(e.message ?: "").append("\n\n")
            append(trace)
        }
    }
}
