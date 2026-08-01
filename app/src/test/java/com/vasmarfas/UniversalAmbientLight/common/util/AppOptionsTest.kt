package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOptionsTest {

    @Test
    fun `full hd is scaled down to the largest divisor that still fills the packet`() {
        // 20x10 светодиодов = 600 байт минимума; делитель 120 даёт 16x9x3 = 432 и не проходит
        assertEquals(60, options(20, 10).findDivisor(1920, 1080))
    }

    @Test
    fun `a divisor giving exactly the minimum packet size is accepted`() {
        assertEquals(80, options(16, 9).findDivisor(1280, 720))
    }

    @Test
    fun `four k is scaled down for the same led count`() {
        assertEquals(120, options(20, 10).findDivisor(3840, 2160))
    }

    @Test
    fun `the divisor falls back to one when no scaling fits the led count`() {
        assertEquals(1, options(4000, 4000).findDivisor(1920, 1080))
    }

    @Test
    fun `sides without a common divisor are left as they are`() {
        assertEquals(1, options(4, 4).findDivisor(1921, 1080))
    }

    private fun options(horizontalLED: Int, verticalLED: Int) = AppOptions(
        horizontalLED = horizontalLED,
        verticalLED = verticalLED,
        frameRate = 30,
        useAverageColor = false,
        captureQuality = 100,
    )
}
