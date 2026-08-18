package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraGeometryTest {

    @Test
    fun `corners without sensor rotation are just scaled to the buffer`() {
        val out = FloatArray(8)
        CameraGeometry.mapCornersToRaw(CORNERS, out, width = 1000, height = 500, rotation = 0)
        assertArrayEquals(
            floatArrayOf(100f, 100f, 900f, 100f, 900f, 400f, 100f, 400f),
            out,
            0.001f
        )
    }

    @Test
    fun `corners come back unchanged when the sensor is not rotated`() {
        val out = FloatArray(8)
        CameraGeometry.rawToDisplayCorners(MAPPED_NORMALIZED, out, rotation = 0)
        assertArrayEquals(MAPPED_NORMALIZED, out, 0.001f)
    }

    @Test
    fun `a quarter turn of the sensor rotates the corners and renames them`() {
        val out = FloatArray(8)
        CameraGeometry.rawToDisplayCorners(MAPPED_NORMALIZED, out, rotation = 90)
        assertArrayEquals(
            floatArrayOf(0.2f, 0.1f, 0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.9f),
            out,
            0.001f
        )
    }

    @Test
    fun `a half turn of the sensor mirrors the corners`() {
        val out = FloatArray(8)
        CameraGeometry.rawToDisplayCorners(SKEWED, out, rotation = 180)
        assertArrayEquals(
            floatArrayOf(0.2f, 0.4f, 0.8f, 0.5f, 0.9f, 0.9f, 0.3f, 0.8f),
            out,
            0.001f
        )
    }

    @Test
    fun `three quarter turns land on the same quad as one`() {
        val out = FloatArray(8)
        CameraGeometry.rawToDisplayCorners(MAPPED_NORMALIZED, out, rotation = 270)
        assertArrayEquals(
            floatArrayOf(0.2f, 0.1f, 0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.9f),
            out,
            0.001f
        )
    }

    @Test
    fun `corners arriving out of order are sorted into TL TR BR BL`() {
        val out = FloatArray(8)
        val shuffled = floatArrayOf(0.9f, 0.8f, 0.1f, 0.8f, 0.1f, 0.2f, 0.9f, 0.2f)
        CameraGeometry.rawToDisplayCorners(shuffled, out, rotation = 0)
        assertArrayEquals(MAPPED_NORMALIZED, out, 0.001f)
    }

    @Test
    fun `the idle box is inset from the corner quad`() {
        val out = IntArray(4)
        CameraGeometry.computeIdleBounds(MAPPED, out, width = 1000, height = 500, inset = 0.1f)
        assertArrayEquals(intArrayOf(180, 130, 820, 370), out)
    }

    @Test
    fun `the idle box is clamped to the frame`() {
        val out = IntArray(4)
        val outside = floatArrayOf(-50f, -50f, 1200f, -50f, 1200f, 900f, -50f, 900f)
        CameraGeometry.computeIdleBounds(outside, out, width = 1000, height = 500, inset = 0f)
        assertArrayEquals(intArrayOf(0, 0, 999, 499), out)
    }

    @Test
    fun `an inset eating the whole box never inverts it`() {
        val out = IntArray(4)
        CameraGeometry.computeIdleBounds(MAPPED, out, width = 1000, height = 500, inset = 0.5f)
        assertArrayEquals(intArrayOf(500, 250, 500, 250), out)
    }

    @Test
    fun `mean deviation averages the absolute difference`() {
        assertEquals(
            3,
            CameraGeometry.meanDeviation(
                intArrayOf(10, 20, 30, 40),
                intArrayOf(12, 18, 30, 50)
            )
        )
    }

    @Test
    fun `mean deviation of identical grids is zero`() {
        val samples = intArrayOf(10, 20, 30, 40)
        assertEquals(0, CameraGeometry.meanDeviation(samples, samples))
    }

    private companion object {
        /** Нормализованные углы телевизора: TL, TR, BR, BL. */
        val CORNERS = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f)

        /** Те же углы в пикселях буфера 1000×500. */
        val MAPPED = floatArrayOf(100f, 100f, 900f, 100f, 900f, 400f, 100f, 400f)

        /** Прямоугольник в нормализованных координатах, TL, TR, BR, BL. */
        val MAPPED_NORMALIZED = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f)

        /** Несимметричный четырёхугольник: на нём видно, что поворот не совпал сам с собой. */
        val SKEWED = floatArrayOf(0.1f, 0.1f, 0.7f, 0.2f, 0.8f, 0.6f, 0.2f, 0.5f)
    }
}
