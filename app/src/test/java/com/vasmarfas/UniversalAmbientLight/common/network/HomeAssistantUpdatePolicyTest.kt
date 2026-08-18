package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantUpdatePolicyTest {

    @Test
    fun `the first frame is always sent`() {
        val policy = policy()
        val updates = policy.plan(0, colors(average = rgb(100, 50, 20)), ZONES)
        assertEquals(1, updates.size)
        assertFalse(updates[0].turnOff)
    }

    @Test
    fun `a change below the threshold is kept back`() {
        val policy = policy(changeThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val updates = policy.plan(10_000, colors(average = rgb(105, 100, 100)), ZONES)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a change above the threshold goes out`() {
        val policy = policy(changeThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val updates = policy.plan(10_000, colors(average = rgb(120, 100, 100)), ZONES)
        assertEquals(1, updates.size)
    }

    @Test
    fun `updates are not sent more often than the interval`() {
        val policy = policy(intervalMs = 500)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val updates = policy.plan(300, colors(average = rgb(200, 200, 200)), ZONES)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `the interval counts from the last sent burst`() {
        val policy = policy(intervalMs = 500)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val updates = policy.plan(600, colors(average = rgb(200, 200, 200)), ZONES)
        assertEquals(1, updates.size)
    }

    @Test
    fun `a dark zone turns the lights off once`() {
        val policy = policy(darkThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val off = policy.plan(1_000, colors(average = rgb(5, 5, 5)), ZONES)
        assertEquals(1, off.size)
        assertTrue(off[0].turnOff)
        // Пока сцена тёмная, повторных команд нет
        val silent = policy.plan(2_000, colors(average = rgb(3, 3, 3)), ZONES)
        assertTrue(silent.isEmpty())
    }

    @Test
    fun `waking from dark needs to clear the hysteresis`() {
        val policy = policy(darkThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        policy.plan(1_000, colors(average = rgb(5, 5, 5)), ZONES)
        // Чуть выше порога, но в пределах гистерезиса — лампа остаётся выключенной
        assertTrue(policy.plan(2_000, colors(average = rgb(15, 15, 15)), ZONES).isEmpty())
        val on = policy.plan(3_000, colors(average = rgb(30, 30, 30)), ZONES)
        assertEquals(1, on.size)
        assertFalse(on[0].turnOff)
    }

    @Test
    fun `waking from dark ignores the change threshold`() {
        // Цвет до сна и после совпадает — но лампа выключена, включение обязано уйти
        val policy = policy(changeThreshold = 200, darkThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        policy.plan(1_000, colors(average = rgb(5, 5, 5)), ZONES)
        val on = policy.plan(2_000, colors(average = rgb(100, 100, 100)), ZONES)
        assertEquals(1, on.size)
        assertFalse(on[0].turnOff)
    }

    @Test
    fun `dark handling can be disabled`() {
        val policy = policy(darkOffEnabled = false, changeThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        val updates = policy.plan(1_000, colors(average = rgb(0, 0, 0)), ZONES)
        assertEquals(1, updates.size)
        assertFalse(updates[0].turnOff)
    }

    @Test
    fun `zones are planned independently`() {
        val zones = setOf(HomeAssistantZone.LEFT, HomeAssistantZone.RIGHT)
        val policy = policy(changeThreshold = 10)
        policy.plan(
            0,
            colors(left = rgb(100, 0, 0), right = rgb(0, 0, 100)),
            zones
        )
        val updates = policy.plan(
            10_000,
            colors(left = rgb(200, 0, 0), right = rgb(0, 0, 100)),
            zones
        )
        assertEquals(1, updates.size)
        assertEquals(HomeAssistantZone.LEFT, updates[0].zone)
    }

    @Test
    fun `reset makes the next frame go out again`() {
        val policy = policy(changeThreshold = 10)
        policy.plan(0, colors(average = rgb(100, 100, 100)), ZONES)
        policy.reset()
        val updates = policy.plan(1, colors(average = rgb(100, 100, 100)), ZONES)
        assertEquals(1, updates.size)
    }

    private fun policy(
        intervalMs: Long = 500,
        changeThreshold: Int = 10,
        darkOffEnabled: Boolean = true,
        darkThreshold: Int = 10,
    ) = HomeAssistantUpdatePolicy(intervalMs, changeThreshold, darkOffEnabled, darkThreshold)

    private fun rgb(r: Int, g: Int, b: Int) = intArrayOf(r, g, b)

    private fun colors(
        average: IntArray = rgb(0, 0, 0),
        left: IntArray = rgb(0, 0, 0),
        right: IntArray = rgb(0, 0, 0),
        top: IntArray = rgb(0, 0, 0),
        bottom: IntArray = rgb(0, 0, 0),
    ): IntArray {
        val out = IntArray(HomeAssistantZone.entries.size * 3)
        fun put(zone: HomeAssistantZone, value: IntArray) {
            value.copyInto(out, zone.ordinal * 3)
        }
        put(HomeAssistantZone.AVERAGE, average)
        put(HomeAssistantZone.LEFT, left)
        put(HomeAssistantZone.RIGHT, right)
        put(HomeAssistantZone.TOP, top)
        put(HomeAssistantZone.BOTTOM, bottom)
        return out
    }

    private companion object {
        val ZONES = setOf(HomeAssistantZone.AVERAGE)
    }
}
