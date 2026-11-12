package com.angel.minimal

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeTest {
    private fun open(pkg: String, t: Long) = ScreenTime.Ev(pkg, "Main", true, t)
    private fun close(pkg: String, t: Long) = ScreenTime.Ev(pkg, "Main", false, t)

    @Test
    fun addsUpEachAppsSessions() {
        val events = listOf(open("a", 100), close("a", 400), open("b", 400), close("b", 500), open("a", 600), close("a", 700))
        assertEquals(mapOf("a" to 400L, "b" to 100L), ScreenTime.totals(events, start = 0, now = 1000))
    }

    @Test
    fun appOpenAtMidnightCountsFromMidnight() {
        assertEquals(mapOf("a" to 50L), ScreenTime.totals(listOf(close("a", 50)), start = 0, now = 100))
    }

    @Test
    fun appStillOpenCountsUntilNow() {
        assertEquals(mapOf("a" to 20L), ScreenTime.totals(listOf(open("a", 80)), start = 0, now = 100))
    }

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("42m", ScreenTime.format(42 * 60_000L))
        assertEquals("1h", ScreenTime.format(60 * 60_000L))
        assertEquals("2h 14m", ScreenTime.format(134 * 60_000L))
    }
}
