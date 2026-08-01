package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorProcessorTest {

    @Test
    fun `neutral settings leave a color untouched`() {
        assertEquals(Triple(10, 128, 250), ColorProcessor.processColor(10, 128, 250))
    }

    @Test
    fun `brightness scales every channel`() {
        assertEquals(
            Triple(50, 100, 30),
            ColorProcessor.processColor(100, 200, 60, brightness = 50)
        )
    }

    @Test
    fun `per channel brightness scales only its own channel`() {
        assertEquals(
            Triple(50, 200, 60),
            ColorProcessor.processColor(100, 200, 60, brightnessR = 50)
        )
    }

    @Test
    fun `zero saturation collapses a color to its luminance`() {
        assertEquals(
            Triple(76, 76, 76),
            ColorProcessor.processColor(255, 0, 0, saturation = 0)
        )
    }

    @Test
    fun `a lowered white level stretches the range and clips the top`() {
        assertEquals(
            Triple(20, 100, 255),
            ColorProcessor.processColor(10, 50, 200, whiteLevel = 50)
        )
    }

    @Test
    fun `equal black and white levels blank the color`() {
        assertEquals(
            Triple(0, 0, 0),
            ColorProcessor.processColor(200, 200, 200, blackLevel = 50, whiteLevel = 50)
        )
    }

    @Test
    fun `gamma below one hundred darkens the midtones of its channel`() {
        assertEquals(
            Triple(64, 128, 128),
            ColorProcessor.processColor(128, 128, 128, gammaR = 50)
        )
    }

    @Test
    fun `the result never leaves the byte range`() {
        assertEquals(
            Triple(255, 255, 255),
            ColorProcessor.processColor(200, 200, 200, brightness = 500)
        )
    }

    @Test
    fun `a disabled pipeline leaves the frame untouched`() {
        val rgb = byteArrayOf(100, -56, -1)
        ColorProcessor.processRgbData(
            rgb,
            options(brightness = 50, colorProcessingEnabled = false)
        )
        assertArrayEquals(byteArrayOf(100, -56, -1), rgb)
    }

    @Test
    fun `neutral settings leave the frame untouched`() {
        val rgb = byteArrayOf(100, -56, -1)
        ColorProcessor.processRgbData(rgb, options())
        assertArrayEquals(byteArrayOf(100, -56, -1), rgb)
    }

    @Test
    fun `the lut path scales every channel of the frame`() {
        // 100, 200, 255 при яркости 50%; дробный остаток отбрасывается, поэтому 255 даёт 127
        val rgb = byteArrayOf(100, -56, -1)
        ColorProcessor.processRgbData(rgb, options(brightness = 50))
        assertArrayEquals(byteArrayOf(50, 100, 127), rgb)
    }

    @Test
    fun `the per pixel path collapses a desaturated frame to luminance`() {
        val rgb = byteArrayOf(-1, 0, 0)
        ColorProcessor.processRgbData(rgb, options(saturation = 0))
        assertArrayEquals(byteArrayOf(76, 76, 76), rgb)
    }

    @Test
    fun `a trailing incomplete pixel is left alone`() {
        val rgb = byteArrayOf(100, 100, 100, 100, 100)
        ColorProcessor.processRgbData(rgb, options(brightness = 50))
        assertArrayEquals(byteArrayOf(50, 50, 50, 100, 100), rgb)
    }

    private fun options(
        brightness: Int = 100,
        saturation: Int = 100,
        colorProcessingEnabled: Boolean = true,
    ) = AppOptions(
        horizontalLED = 20,
        verticalLED = 10,
        frameRate = 30,
        useAverageColor = false,
        captureQuality = 100,
        brightness = brightness,
        saturation = saturation,
        colorProcessingEnabled = colorProcessingEnabled,
    )
}
