package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LedLayoutTest {

    @Test
    fun `the led count sums the four sides`() {
        val layout = LedLayout(topLed = 10, rightLed = 5, bottomLed = 10, leftLed = 5)
        assertEquals(30, layout.ledCount())
    }

    @Test
    fun `the led count falls back to the horizontal and vertical counts`() {
        // Так выглядят настройки, где стороны по отдельности никто не задавал
        assertEquals(30, LedLayout(xLed = 10, yLed = 5).ledCount())
    }

    @Test
    fun `the led count never drops to zero`() {
        assertEquals(1, LedLayout().ledCount())
    }

    @Test
    fun `sides taken off the strip still count towards the controller`() {
        val layout = LedLayout(topLed = 10, bottomLed = 10, sideBottom = "not_installed")
        assertEquals(20, layout.ledCount())
    }
}
