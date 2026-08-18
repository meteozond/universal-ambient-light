package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameDetectorTest {

    @Test
    fun `a lit rectangle in the middle of the frame is found`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(16, 12, 48, 36))
        assertArrayEquals(cornersOf(16, 12, 48, 36), detector.detect().corners, DELTA)
    }

    @Test
    fun `a dark scene with a small bright spot still gives the whole panel`() {
        // Фильм с тёмной картинкой: ярко светится лишь пятно в центре, сама панель тусклая,
        // но всё равно заметно светлее комнаты. Раньше такое пятно не проходило отсев по
        // площади и панель терялась целиком.
        val detector = CameraFrameDetector()
        repeat(8) {
            val cells = grid(16, 12, 48, 36, bright = 50)
            fill(cells, 30, 22, 36, 26, 210)
            detector.addFrame(cells)
        }
        assertArrayEquals(cornersOf(16, 12, 48, 36), detector.detect().corners, DELTA)
    }

    @Test
    fun `an off centre rectangle keeps its own corners`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(4, 6, 30, 26))
        assertArrayEquals(cornersOf(4, 6, 30, 26), detector.detect().corners, DELTA)
    }

    @Test
    fun `a screen filling almost the whole frame is accepted`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(2, 2, 62, 46))
        assertArrayEquals(cornersOf(2, 2, 62, 46), detector.detect().corners, DELTA)
    }

    @Test
    fun `a dim frame is rejected instead of guessed at`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(16, 12, 48, 36, bright = 35, background = 5))
        assertNull(detector.detect().corners)
    }

    @Test
    fun `a uniformly lit frame is rejected`() {
        val detector = CameraFrameDetector()
        detector.addFrame(IntArray(COLS * ROWS) { 200 })
        assertNull(detector.detect().corners)
    }

    @Test
    fun `too little contrast between screen and room is rejected`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(16, 12, 48, 36, bright = 60, background = 45))
        assertNull(detector.detect().corners)
    }

    @Test
    fun `a changing region wins over a bigger and brighter still one`() {
        // Лампа и ярче, и крупнее телевизора: выбрать его можно только по изменчивости
        val detector = CameraFrameDetector()
        repeat(4) { frame ->
            val cells = grid(2, 2, 28, 22, bright = 240)
            fill(cells, 34, 24, 58, 42, if (frame % 2 == 0) 150 else 210)
            detector.addFrame(cells)
        }
        assertArrayEquals(cornersOf(34, 24, 58, 42), detector.detect().corners, DELTA)
    }

    @Test
    fun `with nothing moving the largest bright region wins`() {
        // Тот же кадр, но телевизор на паузе: изменчивости нет, и выигрывает лампа
        val detector = CameraFrameDetector()
        repeat(4) {
            val cells = grid(2, 2, 28, 22, bright = 240)
            fill(cells, 34, 24, 58, 42, 200)
            detector.addFrame(cells)
        }
        assertArrayEquals(cornersOf(2, 2, 28, 22), detector.detect().corners, DELTA)
    }

    @Test
    fun `an L shaped region is rejected as not rectangular`() {
        val detector = CameraFrameDetector()
        val cells = grid(10, 35, 50, 40)
        fill(cells, 10, 10, 15, 40, 200)
        detector.addFrame(cells)
        assertNull(detector.detect().corners)
    }

    @Test
    fun `a tall narrow region is rejected as an implausible screen`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(20, 0, 30, 47))
        assertNull(detector.detect().corners)
    }

    @Test
    fun `a region too small to be a screen is rejected`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(30, 22, 34, 26))
        assertNull(detector.detect().corners)
    }

    @Test
    fun `nothing is reported before a single frame is sampled`() {
        assertNull(CameraFrameDetector().detect().corners)
    }

    @Test
    fun `reset forgets everything sampled so far`() {
        val detector = CameraFrameDetector()
        detector.addFrame(grid(16, 12, 48, 36))
        detector.reset()
        assertNull(detector.detect().corners)
    }

    @Test
    fun `a grid shorter than the detector expects is ignored`() {
        val detector = CameraFrameDetector()
        detector.addFrame(IntArray(10) { 200 })
        assertEquals(0, detector.frameCount)
    }

    @Test
    fun `the whole panel is found, not the bright window inside it`() {
        // Ровно случай с монитора: белый документ в середине, тёмный интерфейс по краям.
        // Порог по яркости сам по себе взял бы документ.
        val detector = CameraFrameDetector()
        val cells = grid(10, 8, 86, 64, bright = 55)
        fill(cells, 30, 24, 66, 48, 210)
        detector.addFrame(cells)
        assertArrayEquals(cornersOf(10, 8, 86, 64), detector.detect().corners, DELTA)
    }

    @Test
    fun `a bright wall around the screen does not swallow the region`() {
        // Комната немногим темнее тусклых частей экрана — расти нельзя, иначе уедем на стену
        val detector = CameraFrameDetector()
        val cells = grid(30, 24, 66, 48, bright = 210, background = 50)
        detector.addFrame(cells)
        assertArrayEquals(cornersOf(30, 24, 66, 48), detector.detect().corners, DELTA)
    }

    @Test
    fun `a tilted screen keeps its corners`() {
        // Камера на полке почти всегда стоит с наклоном — стороны ищутся прямыми именно ради этого
        val detector = CameraFrameDetector()
        detector.addFrame(quadGrid(TILTED))
        assertArrayEquals(normalized(TILTED), detector.detect().corners, TILT_DELTA)
    }

    @Test
    fun `stray bright cells outside the screen do not move its corners`() {
        val detector = CameraFrameDetector()
        val cells = grid(16, 12, 80, 60)
        // Блик на стене и тёмная точка внутри панели: обе выпадают из прямых
        cells[8 * COLS + 8] = 220
        cells[30 * COLS + 40] = 20
        detector.addFrame(cells)
        assertArrayEquals(cornersOf(16, 12, 80, 60), detector.detect().corners, DELTA)
    }

    @Test
    fun `a single bright frame is not taken for a screen`() {
        // Вспышка на один кадр: яркость ячейки берётся вторым замером, а он тёмный
        val detector = CameraFrameDetector()
        detector.addFrame(grid(16, 12, 80, 60))
        repeat(4) { detector.addFrame(grid(0, 0, 0, 0)) }
        assertNull(detector.detect().corners)
    }

    @Test
    fun `two runs of the same scene agree`() {
        val first = CameraFrameDetector()
        val second = CameraFrameDetector()
        first.addFrame(grid(16, 12, 80, 60))
        second.addFrame(grid(16, 12, 80, 60))
        assertTrue(
            CameraFrameDetector.agree(first.detect().corners, second.detect().corners, 0.03f)
        )
    }

    @Test
    fun `runs that found different screens do not agree`() {
        val first = CameraFrameDetector()
        val second = CameraFrameDetector()
        first.addFrame(grid(16, 12, 80, 60))
        second.addFrame(grid(20, 20, 84, 68))
        assertFalse(
            CameraFrameDetector.agree(first.detect().corners, second.detect().corners, 0.03f)
        )
    }

    @Test
    fun `a run that found nothing never agrees`() {
        val found = CameraFrameDetector()
        found.addFrame(grid(16, 12, 80, 60))
        assertFalse(CameraFrameDetector.agree(found.detect().corners, null, 0.03f))
    }

    @Test
    fun `otsu returns the low edge of the valley between the two classes`() {
        // Порог садится на тёмный класс, а не между ним и ярким: проверки детектора
        // опираются на средние по классам именно поэтому
        val values = IntArray(100) { if (it < 70) 20 else 200 }
        assertEquals(20, CameraFrameDetector.otsuThreshold(values))
    }

    /** Кадр с яркой областью произвольной формы: ячейка светлая, если её центр внутри. */
    private fun quadGrid(quad: FloatArray, bright: Int = 200, background: Int = 20): IntArray {
        val cells = IntArray(COLS * ROWS) { background }
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                if (inside(quad, x + 0.5f, y + 0.5f)) cells[y * COLS + x] = bright
            }
        }
        return cells
    }

    /** Точка внутри выпуклого четырёхугольника: со всех сторон повороты одного знака. */
    private fun inside(quad: FloatArray, x: Float, y: Float): Boolean {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (quad[j * 2] - quad[i * 2]) * (y - quad[i * 2 + 1]) -
                    (quad[j * 2 + 1] - quad[i * 2 + 1]) * (x - quad[i * 2])
            if (cross < 0f) return false
        }
        return true
    }

    /** Углы из координат ячеек в нормализованные. */
    private fun normalized(quad: FloatArray): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = quad[i * 2] / COLS
            out[i * 2 + 1] = quad[i * 2 + 1] / ROWS
        }
        return out
    }

    /** Кадр с одной яркой областью: [left]..[right) и [top]..[bottom) в ячейках. */
    private fun grid(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bright: Int = 200,
        background: Int = 20,
    ): IntArray {
        val cells = IntArray(COLS * ROWS) { background }
        fill(cells, left, top, right, bottom, bright)
        return cells
    }

    private fun fill(cells: IntArray, left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                cells[y * COLS + x] = value
            }
        }
    }

    /** Углы прямоугольника из ячеек в нормализованных координатах: центры крайних ячеек. */
    private fun cornersOf(left: Int, top: Int, right: Int, bottom: Int): FloatArray {
        val x0 = (left + 0.5f) / COLS
        val x1 = (right - 0.5f) / COLS
        val y0 = (top + 0.5f) / ROWS
        val y1 = (bottom - 0.5f) / ROWS
        return floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    }

    private companion object {
        const val COLS = CameraFrameDetector.DEFAULT_COLS
        const val ROWS = CameraFrameDetector.DEFAULT_ROWS
        const val DELTA = 0.01f

        /** Наклонная сетка попадает в ячейки не ровно; допуск — примерно одна ячейка. */
        const val TILT_DELTA = 0.01f

        /** Экран, повёрнутый примерно на 12°, в координатах ячеек: TL, TR, BR, BL. */
        val TILTED = floatArrayOf(
            22.8f, 10.2f,
            81.5f, 22.7f,
            73.2f, 61.8f,
            14.5f, 49.3f
        )
    }
}
