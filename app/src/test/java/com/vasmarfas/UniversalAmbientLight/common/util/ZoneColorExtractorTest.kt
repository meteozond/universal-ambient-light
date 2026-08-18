package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneColorExtractorTest {

    @Test
    fun `a uniform frame gives the same color to every zone`() {
        val out = extract(frame(10, 10) { _, _ -> rgb(40, 80, 120) })
        for (zone in HomeAssistantZone.entries) {
            assertArrayEquals(intArrayOf(40, 80, 120), zoneOf(out, zone))
        }
    }

    @Test
    fun `left and right zones read their own halves`() {
        val out = extract(frame(10, 10) { x, _ ->
            if (x < 5) rgb(200, 0, 0) else rgb(0, 0, 200)
        })
        assertArrayEquals(intArrayOf(200, 0, 0), zoneOf(out, HomeAssistantZone.LEFT))
        assertArrayEquals(intArrayOf(0, 0, 200), zoneOf(out, HomeAssistantZone.RIGHT))
    }

    @Test
    fun `top and bottom zones read their own halves`() {
        val out = extract(frame(10, 10) { _, y ->
            if (y < 5) rgb(0, 200, 0) else rgb(200, 200, 0)
        })
        assertArrayEquals(intArrayOf(0, 200, 0), zoneOf(out, HomeAssistantZone.TOP))
        assertArrayEquals(intArrayOf(200, 200, 0), zoneOf(out, HomeAssistantZone.BOTTOM))
    }

    @Test
    fun `the average zone mixes the whole frame`() {
        val out = extract(frame(10, 10) { x, _ ->
            if (x < 5) rgb(100, 0, 0) else rgb(0, 0, 100)
        })
        assertArrayEquals(intArrayOf(50, 0, 50), zoneOf(out, HomeAssistantZone.AVERAGE))
    }

    @Test
    fun `a corner blends its own pixels with the neighbouring edges`() {
        val out = extract(frame(10, 10) { x, y ->
            if (x < 3 && y < 3) rgb(101, 50, 20) else rgb(0, 0, 0)
        })
        assertArrayEquals(intArrayOf(79, 39, 15), zoneOf(out, HomeAssistantZone.TOP_LEFT))
    }

    @Test
    fun `each corner pairs with its own two edges, not a neighbour's`() {
        val out = extract(frame(10, 10) { x, y ->
            if (x >= 7 && y >= 7) rgb(101, 50, 20) else rgb(0, 0, 0)
        })
        assertArrayEquals(intArrayOf(79, 39, 15), zoneOf(out, HomeAssistantZone.BOTTOM_RIGHT))
    }

    @Test
    fun `a single pixel frame fills every zone`() {
        val out = extract(frame(1, 1) { _, _ -> rgb(7, 8, 9) }, width = 1, height = 1)
        for (zone in HomeAssistantZone.entries) {
            assertArrayEquals(intArrayOf(7, 8, 9), zoneOf(out, zone))
        }
    }

    @Test
    fun `a frame smaller than declared is refused`() {
        val out = IntArray(ZoneColorExtractor.ZONE_COUNT * 3)
        assertFalse(ZoneColorExtractor.extract(ByteArray(10), 10, 10, out))
    }

    private fun extract(data: ByteArray, width: Int = 10, height: Int = 10): IntArray {
        val out = IntArray(ZoneColorExtractor.ZONE_COUNT * 3)
        assertTrue(ZoneColorExtractor.extract(data, width, height, out))
        return out
    }

    private fun zoneOf(out: IntArray, zone: HomeAssistantZone): IntArray {
        val base = zone.ordinal * 3
        return intArrayOf(out[base], out[base + 1], out[base + 2])
    }

    private fun rgb(r: Int, g: Int, b: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte())

    private fun frame(width: Int, height: Int, pixel: (Int, Int) -> ByteArray): ByteArray {
        val data = ByteArray(width * height * 3)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixel(x, y)
                data[idx] = p[0]
                data[idx + 1] = p[1]
                data[idx + 2] = p[2]
                idx += 3
            }
        }
        return data
    }
}
