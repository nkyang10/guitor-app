package com.boss.guitartrainer.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local calendar days (so "today" flips at local midnight, not at 08:00 in Hong Kong). */
object Days {
    fun of(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    fun today(): Long = of(System.currentTimeMillis())
    fun date(day: Long): LocalDate = LocalDate.ofEpochDay(day)
}
