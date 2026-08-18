package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CameraFrameDetectionRunTest {

    @Test
    fun `frames inside the window give no answer yet`() {
        val run = newRun()
        run.start(0)
        assertNull(run.addFrame(screen(), 100))
    }

    @Test
    fun `the window closes on the first frame past the deadline`() {
        val run = newRun()
        run.start(0)
        run.addFrame(screen(), 100)
        assertNotNull(run.addFrame(screen(), WINDOW_MS))
    }

    @Test
    fun `a scene that stayed put is accepted`() {
        val run = newRun()
        run.start(0)
        run.addFrame(screen(), 100)
        run.addFrame(screen(), 600)
        val detection = run.addFrame(screen(), WINDOW_MS)
        assertArrayEquals(cornersOf(8, 6, 24, 18), detection?.corners, 0.02f)
    }

    @Test
    fun `when the halves disagree the whole window decides`() {
        val run = newRun()
        run.start(0)
        run.addFrame(screen(8, 6, 24, 18), 100)
        run.addFrame(screen(4, 4, 20, 16), 600)
        val detection = run.addFrame(screen(4, 4, 20, 16), WINDOW_MS)
        // Панель, простоявшая на месте, и есть ответ: расхождение половин само по себе
        // находку не отменяет, иначе живое видео не находилось бы никогда
        assertArrayEquals(cornersOf(4, 4, 20, 16), detection?.corners, 0.02f)
    }

    @Test
    fun `a frame without a lit screen is still rejected`() {
        val run = newRun()
        run.start(0)
        val dark = IntArray(COLS * ROWS) { 20 }
        run.addFrame(dark, 100)
        assertNull(run.addFrame(dark, WINDOW_MS)?.corners)
    }

    @Test
    fun `a cancelled run ignores the frames that follow`() {
        val run = newRun()
        run.start(0)
        run.cancel()
        assertNull(run.addFrame(screen(), WINDOW_MS))
    }

    @Test
    fun `frames before the first start are ignored`() {
        assertNull(newRun().addFrame(screen(), 0))
    }

    @Test
    fun `the grid is sampled from a padded rgba buffer`() {
        // Левая половина кадра белая, правая чёрная; строка длиннее кадра, как у ImageReader
        val width = 8
        val height = 4
        val rowStride = width * 4 + 8
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x < width / 2) 255.toByte() else 0
                val offset = y * rowStride + x * 4
                buffer.put(offset, value)
                buffer.put(offset + 1, value)
                buffer.put(offset + 2, value)
                buffer.put(offset + 3, -1)
            }
        }

        val out = IntArray(8)
        CameraFrameDetectionRun.sampleGrid(buffer, rowStride, width, height, 4, 2, out)
        assertArrayEquals(intArrayOf(255, 255, 0, 0, 255, 255, 0, 0), out)
    }

    private fun newRun() = CameraFrameDetectionRun(WINDOW_MS, COLS, ROWS)

    /** Сетка с яркой панелью; по умолчанию — примерно четверть кадра по центру. */
    private fun screen(left: Int = 8, top: Int = 6, right: Int = 24, bottom: Int = 18): IntArray {
        val cells = IntArray(COLS * ROWS) { 20 }
        for (y in top until bottom) {
            for (x in left until right) {
                cells[y * COLS + x] = 200
            }
        }
        return cells
    }

    private fun cornersOf(left: Int, top: Int, right: Int, bottom: Int): FloatArray {
        val x0 = (left + 0.5f) / COLS
        val x1 = (right - 0.5f) / COLS
        val y0 = (top + 0.5f) / ROWS
        val y1 = (bottom - 0.5f) / ROWS
        return floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val COLS = 32
        const val ROWS = 24
    }
}
