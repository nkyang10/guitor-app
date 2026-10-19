package com.boss.guitartrainer.audio

/** Single-writer float ring buffer addressed by absolute sample index. */
class AudioRing(val capacity: Int) {
    private val data = FloatArray(capacity)
    @Volatile var written = 0L
        private set

    @Synchronized
    fun write(src: FloatArray, n: Int) {
        var w = written
        for (i in 0 until n) {
            data[(w % capacity).toInt()] = src[i]
            w++
        }
        written = w
    }

    /** Copies [len] samples starting at absolute index [start] into dst (zero-filled where unavailable). */
    @Synchronized
    fun read(start: Long, dst: FloatArray, len: Int = dst.size, dstOffset: Int = 0): Boolean {
        val oldest = written - capacity
        var ok = true
        for (i in 0 until len) {
            val idx = start + i
            dst[dstOffset + i] = if (idx < oldest || idx >= written || idx < 0) { ok = false; 0f } else data[(idx % capacity).toInt()]
        }
        return ok
    }

    fun available(start: Long, len: Int) = start >= written - capacity && start + len <= written && start >= 0
}
