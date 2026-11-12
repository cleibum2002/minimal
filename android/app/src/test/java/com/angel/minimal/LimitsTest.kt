package com.angel.minimal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitsTest {
    private val min = 60_000L

    @Test
    fun activeBlockStopsTheApp() {
        val block = Rule(apps = listOf("ig"), until = 1_000)
        assertEquals(Hit(block, blocked = true, used = 0), Limits.check(listOf(block), "ig", emptyMap(), now = 500))
    }

    @Test
    fun finishedBlockDoesNothing() {
        assertNull(Limits.check(listOf(Rule(apps = listOf("ig"), until = 1_000)), "ig", emptyMap(), now = 2_000))
    }

    @Test
    fun groupLimitCountsAllItsApps() {
        val social = Rule("Social", listOf("ig", "fb"), mins = 60)
        val usage = mapOf("ig" to 40 * min, "fb" to 25 * min)
        assertEquals(Hit(social, blocked = false, used = 65 * min), Limits.check(listOf(social), "fb", usage, now = 0))
    }

    @Test
    fun underTheLimitOpensNormally() {
        val social = Rule("Social", listOf("ig", "fb"), mins = 60)
        assertNull(Limits.check(listOf(social), "ig", mapOf("ig" to 30 * min), now = 0))
    }

    @Test
    fun blockTimeLeftReadsInDaysOrHours() {
        assertEquals("2d 5h", Limits.left((53 * 60 + 30) * min))
        assertEquals("3h 10m", Limits.left(190 * min))
    }
}
