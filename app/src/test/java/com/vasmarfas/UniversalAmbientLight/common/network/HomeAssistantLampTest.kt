package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantLampTest {

    @Test
    fun `lamps survive a serialize and parse round trip`() {
        val lamps = listOf(
            HomeAssistantLamp("light.left_floor", HomeAssistantZone.LEFT, "Торшер слева"),
            HomeAssistantLamp("light.tv_bias", HomeAssistantZone.AVERAGE, "За телевизором"),
        )
        assertEquals(lamps, HomeAssistantLamp.parseList(HomeAssistantLamp.serialize(lamps)))
    }

    @Test
    fun `an empty spec gives no lamps`() {
        assertTrue(HomeAssistantLamp.parseList(null).isEmpty())
        assertTrue(HomeAssistantLamp.parseList("").isEmpty())
    }

    @Test
    fun `a line with an unknown zone is skipped`() {
        val parsed = HomeAssistantLamp.parseList("light.a\tnowhere\tЛампа")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `a malformed line does not break its neighbours`() {
        val spec = "мусор\nlight.a\tleft\tЛампа"
        val parsed = HomeAssistantLamp.parseList(spec)
        assertEquals(listOf(HomeAssistantLamp("light.a", HomeAssistantZone.LEFT, "Лампа")), parsed)
    }

    @Test
    fun `separators inside a name do not break the format`() {
        val lamp = HomeAssistantLamp("light.a", HomeAssistantZone.TOP, "Имя\tс\nразделителями")
        val parsed = HomeAssistantLamp.parseList(HomeAssistantLamp.serialize(listOf(lamp)))
        assertEquals(1, parsed.size)
        assertEquals("light.a", parsed[0].entityId)
        assertEquals(HomeAssistantZone.TOP, parsed[0].zone)
    }

    @Test
    fun `the stored zone name is case insensitive`() {
        val parsed = HomeAssistantLamp.parseList("light.a\tLEFT\t")
        assertEquals(HomeAssistantZone.LEFT, parsed.single().zone)
    }
}
