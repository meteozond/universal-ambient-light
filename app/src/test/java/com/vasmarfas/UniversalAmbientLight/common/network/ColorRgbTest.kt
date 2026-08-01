package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ColorRgbTest {

    @Test
    fun `the constructor keeps only the low byte of every channel`() {
        assertEquals(ColorRgb(44, 255, 0), ColorRgb(300, -1, 256))
    }

    @Test
    fun `set masks the assigned channels the same way`() {
        val color = ColorRgb(0, 0, 0)
        color.set(-1, 256, 128)
        assertEquals(ColorRgb(255, 0, 128), color)
    }

    @Test
    fun `set copies the channels of another color`() {
        val color = ColorRgb(0, 0, 0)
        color.set(ColorRgb(10, 20, 30))
        assertEquals(ColorRgb(10, 20, 30), color)
    }

    @Test
    fun `set ignores a null source`() {
        val color = ColorRgb(10, 20, 30)
        color.set(null)
        assertEquals(ColorRgb(10, 20, 30), color)
    }

    @Test
    fun `clone is independent of the original`() {
        val original = ColorRgb(10, 20, 30)
        val copy = original.clone()
        original.set(200, 200, 200)
        assertEquals(ColorRgb(10, 20, 30), copy)
    }

    @Test
    fun `clone returns a new instance`() {
        val original = ColorRgb(10, 20, 30)
        assertNotSame(original, original.clone())
    }

    @Test
    fun `equal colors share a hash code`() {
        assertEquals(ColorRgb(1, 2, 3).hashCode(), ColorRgb(1, 2, 3).hashCode())
    }
}
