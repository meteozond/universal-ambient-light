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
    fun `a side taken off the strip is excluded from the count`() {
        // Разбор кадра выбрасывает снятые стороны целиком — счётчик обязан совпадать,
        // иначе clear() и живые кадры расходятся по длине
        val layout = LedLayout(topLed = 10, bottomLed = 10, sideBottom = "not_installed")
        assertEquals(10, layout.ledCount())
    }

    @Test
    fun `a disabled side still counts towards the controller`() {
        val layout = LedLayout(topLed = 10, bottomLed = 10, sideBottom = "disabled")
        assertEquals(20, layout.ledCount())
    }

    @Test
    fun `the configured count accepts per-side counts with zeroed legacy values`() {
        // Сценарий issue #45: стороны заданы, а в старых ключах остался ноль
        val layout = LedLayout(topLed = 92, rightLed = 54, bottomLed = 92, leftLed = 54)
        assertEquals(292, layout.configuredLedCount())
    }

    @Test
    fun `the configured count reports zero for an empty layout`() {
        assertEquals(0, LedLayout().configuredLedCount())
    }
}
