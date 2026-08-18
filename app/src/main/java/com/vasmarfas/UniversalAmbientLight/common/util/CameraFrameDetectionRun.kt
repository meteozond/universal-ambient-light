package com.vasmarfas.UniversalAmbientLight.common.util

import java.nio.ByteBuffer

/**
 * Один прогон поиска экрана: копит кадры окно [windowMs] и отдаёт результат, когда окно
 * закрылось. Окно делится пополам на два независимых детектора, и ответ принимается, только
 * если половины сошлись, — иначе он считается случайным, а прежние углы остаются как были.
 *
 * Время приходит снаружи, зависимостей от Android нет: и энкодер, и кнопка автоподстройки
 * в UI гоняют один и тот же код, а проверяется он юнит-тестами.
 */
class CameraFrameDetectionRun(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    cols: Int = CameraFrameDetector.DEFAULT_COLS,
    rows: Int = CameraFrameDetector.DEFAULT_ROWS,
) {

    private val mFirstHalf = CameraFrameDetector(cols, rows)
    private val mSecondHalf = CameraFrameDetector(cols, rows)

    // Тот же разбор, но по всем кадрам окна: половины нужны для сверки, а решение
    // принимается по полному окну — на живом видео половины расходятся чаще, чем ошибаются
    private val mWhole = CameraFrameDetector(cols, rows)

    private var mHalfMs = 0L
    private var mDeadlineMs = 0L

    @Volatile
    var isRunning = false
        private set

    val cols: Int get() = mFirstHalf.cols
    val rows: Int get() = mFirstHalf.rows
    val cellCount: Int get() = mFirstHalf.cellCount

    /** Начинает новое окно, отбрасывая всё, что накопилось раньше. */
    fun start(nowMs: Long) {
        mFirstHalf.reset()
        mSecondHalf.reset()
        mWhole.reset()
        mHalfMs = nowMs + windowMs / 2
        mDeadlineMs = nowMs + windowMs
        isRunning = true
    }

    fun cancel() {
        isRunning = false
    }

    /**
     * Принимает очередную сетку яркости.
     *
     * @return результат, когда окно закрылось; null, пока замеры продолжаются
     */
    fun addFrame(luma: IntArray, nowMs: Long): CameraFrameDetector.Detection? {
        if (!isRunning) return null

        mWhole.addFrame(luma)
        if (nowMs < mHalfMs) mFirstHalf.addFrame(luma) else mSecondHalf.addFrame(luma)
        if (nowMs < mDeadlineMs) return null

        isRunning = false
        return combine(mWhole.detect(), mFirstHalf.detect(), mSecondHalf.detect())
    }

    /**
     * Решение принимает разбор всего окна: он прошёл все геометрические проверки, а значит
     * это не догадка. Половины лишь уточняют: сошлись — берём среднее по ним, оно чуть
     * устойчивее; разошлись — остаётся результат полного окна. Раньше расхождение половин
     * отменяло находку целиком, и на живом видео, где яркие области дышат от кадра к кадру,
     * поиск не срабатывал почти никогда.
     */
    private fun combine(
        whole: CameraFrameDetector.Detection,
        first: CameraFrameDetector.Detection,
        second: CameraFrameDetector.Detection,
    ): CameraFrameDetector.Detection {
        val wholeCorners = whole.corners ?: return whole

        val firstCorners = first.corners ?: return whole
        val secondCorners = second.corners ?: return whole
        if (!CameraFrameDetector.agree(firstCorners, secondCorners, AGREE_TOLERANCE)) {
            return whole
        }

        // Половины сошлись — берём среднее: оно чуть точнее любой из них по отдельности.
        // Если оно уехало от результата полного окна, доверяем полному окну.
        val averaged = FloatArray(8) { (firstCorners[it] + secondCorners[it]) / 2f }
        return if (CameraFrameDetector.agree(averaged, wholeCorners, HALF_DRIFT_TOLERANCE)) {
            CameraFrameDetector.Detection.found(averaged)
        } else {
            whole
        }
    }

    companion object {
        /**
         * Окно делится пополам, и обе половины должны дать один и тот же четырёхугольник,
         * поэтому оно длинное: за две секунды на половину набирается достаточно кадров,
         * чтобы отличить меняющуюся картинку от неподвижной лампы.
         */
        const val DEFAULT_WINDOW_MS = 4000L

        /** Насколько половинам окна разрешено разойтись, чтобы усреднять их, в долях кадра. */
        private const val AGREE_TOLERANCE = 0.03f

        /** Насколько среднее по половинам может отойти от разбора всего окна. */
        private const val HALF_DRIFT_TOLERANCE = 0.05f

        /**
         * Снимает сетку яркости [cols] × [rows] по всему кадру: экран ищем где угодно в поле
         * зрения, а не внутри уже настроенного четырёхугольника. Буфер — RGBA_8888 от
         * ImageAnalysis, со своим шагом строки.
         */
        fun sampleGrid(
            buffer: ByteBuffer,
            rowStride: Int,
            width: Int,
            height: Int,
            cols: Int,
            rows: Int,
            out: IntArray,
        ) {
            val limit = buffer.limit()
            var i = 0
            for (row in 0 until rows) {
                val y = (height * (row + 0.5f) / rows).toInt().coerceIn(0, height - 1)
                val rowOffset = y * rowStride
                for (col in 0 until cols) {
                    val x = (width * (col + 0.5f) / cols).toInt().coerceIn(0, width - 1)
                    val index = rowOffset + x * 4
                    // На части устройств строки дополнены и выходят за конец буфера.
                    out[i++] = if (index >= 0 && index + 2 < limit) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF
                        (r * 77 + g * 150 + b * 29) shr 8
                    } else {
                        0
                    }
                }
            }
        }
    }
}
