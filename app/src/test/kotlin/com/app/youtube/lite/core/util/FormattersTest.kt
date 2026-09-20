package com.app.youtube.lite.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formatters run once per feed row per scroll frame, and every one of them is hand-written rather
 * than delegated to `String.format`. These tests cover the boundaries where hand-written arithmetic
 * usually breaks: the 1000 → 1K transition, the hour rollover, and the decimal point in `1.2M`.
 */
class FormattersTest {

    @Test
    fun `duration renders minutes and hours correctly`() {
        assertEquals("0:00", Formatters.duration(0L))
        assertEquals("0:00", Formatters.duration(-5L))
        assertEquals("0:01", Formatters.duration(1_000L))
        assertEquals("0:59", Formatters.duration(59_000L))
        assertEquals("1:00", Formatters.duration(60_000L))
        assertEquals("9:59", Formatters.duration(599_000L))
        assertEquals("10:00", Formatters.duration(600_000L))
        assertEquals("59:59", Formatters.duration(3_599_000L))
        assertEquals("1:00:00", Formatters.duration(3_600_000L))
        assertEquals("1:02:05", Formatters.duration(3_725_000L))
    }

    @Test
    fun `clock never reports negative time`() {
        assertEquals("0:00", Formatters.clock(-1L))
        assertEquals("2:03", Formatters.clock(123_000L))
    }

    @Test
    fun `view counts use youtube abbreviation rules`() {
        assertEquals("0", Formatters.count(0L))
        assertEquals("999", Formatters.count(999L))
        assertEquals("1.0K", Formatters.count(1_000L))
        assertEquals("1.2K", Formatters.count(1_234L))
        assertEquals("9.9K", Formatters.count(9_999L))
        assertEquals("10K", Formatters.count(10_000L))
        assertEquals("999K", Formatters.count(999_999L))
        assertEquals("1.0M", Formatters.count(1_000_000L))
        assertEquals("1.2M", Formatters.count(1_234_567L))
        assertEquals("12M", Formatters.count(12_345_678L))
        assertEquals("1.0B", Formatters.count(1_000_000_000L))
    }

    @Test
    fun `parseCount inverts count`() {
        assertEquals(1_000L, Formatters.parseCount("1.0K views"))
        assertEquals(1_234_567L, Formatters.parseCount("1.2M views"))
        assertEquals(1_234L, Formatters.parseCount("1,234 views"))
        assertEquals(0L, Formatters.parseCount("No views"))
        assertEquals(0L, Formatters.parseCount(null))
        assertEquals(12_000_000_000L, Formatters.parseCount("12B"))
    }

    @Test
    fun `parseDuration inverts duration`() {
        assertEquals(754L, Formatters.parseDuration("12:34"))
        assertEquals(3_725L, Formatters.parseDuration("1:02:05"))
        assertEquals(0L, Formatters.parseDuration("LIVE"))
        assertEquals(0L, Formatters.parseDuration(""))
        assertEquals(0L, Formatters.parseDuration(null))
    }

    @Test
    fun `bytes use binary units with one decimal`() {
        assertEquals("512 B", Formatters.bytes(512L))
        assertEquals("1 KiB", Formatters.bytes(1024L))
        assertEquals("1.0 MiB", Formatters.bytes(1024L * 1024L))
        assertEquals("20.0 MiB", Formatters.bytes(20L * 1024L * 1024L))
        assertEquals("1 GiB", Formatters.bytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `quality labels include the frame rate only when it is high`() {
        assertEquals("Auto", Formatters.quality(0, 0))
        assertEquals("720p", Formatters.quality(720, 30))
        assertEquals("1080p60", Formatters.quality(1080, 60))
    }

    @Test
    fun `ellipsize cuts on a word boundary`() {
        assertEquals("hello", Formatters.ellipsize("hello", 10))
        val shortened = Formatters.ellipsize("the quick brown fox jumps", 12)
        assertTrue(shortened.endsWith("…"))
        assertTrue(shortened.length <= 13)
    }
}
