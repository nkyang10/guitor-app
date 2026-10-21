package com.boss.guitartrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A 10-minute 48 kHz stereo song must decode + analyse inside a 128 MB heap (a typical budget phone app limit).
 * The pre-0.2.1 decoder needed ~130 MB for a 4-minute song and 200–300 MB for 10 minutes and crashed with OOM.
 */
class DecodeMemoryTest {
    @Test fun tenMinuteSongFitsIn128MbHeap() {
        val cp = System.getProperty("java.class.path") ?: ""
        Assume.assumeTrue("needs the test classpath", cp.split(File.pathSeparator).any { File(it).exists() } &&
            runCatching { Class.forName("com.boss.guitartrainer.MemoryHarness") }.isSuccess)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx128m", "-XX:+UseSerialGC", "-cp", cp, "com.boss.guitartrainer.MemoryHarness", "600", "48000", "2")
            .redirectErrorStream(true).start()
        val done = p.waitFor(240, TimeUnit.SECONDS)
        val out = p.inputStream.bufferedReader().readText()
        println(out)
        assertTrue("harness timed out:\n$out", done)
        assertEquals("harness failed:\n$out", 0, p.exitValue())
        assertTrue(out, out.startsWith("OK"))
    }
}
