package com.vasmarfas.UniversalAmbientLight.common.util

import android.graphics.Matrix

/**
 * Геометрия камерного захвата, вынесенная из энкодера: чистые преобразования координат
 * без обращения к камере и кадрам. Результат пишется в переданные массивы — на каждом
 * кадре геометрия одна и та же, и выделять новые массивы незачем.
 */
object CameraGeometry {

    /**
     * Переводит углы телевизора из нормализованных экранных координат в координаты сырого
     * буфера камеры. [corners] — восемь чисел 0..1 (TL, TR, BR, BL), [out] — тот же формат
     * в пикселях буфера; [rotation] — поворот сенсора относительно экрана, градусы.
     */
    fun mapCornersToRaw(
        corners: FloatArray,
        out: FloatArray,
        width: Int,
        height: Int,
        rotation: Int,
    ) {
        // Экранные размеры после поворота сенсора
        val displayWidth: Int
        val displayHeight: Int
        if (rotation == 90 || rotation == 270) {
            displayWidth = height
            displayHeight = width
        } else {
            displayWidth = width
            displayHeight = height
        }

        out[0] = corners[0] * displayWidth  // top-left
        out[1] = corners[1] * displayHeight
        out[2] = corners[2] * displayWidth  // top-right
        out[3] = corners[3] * displayHeight
        out[4] = corners[4] * displayWidth  // bottom-right
        out[5] = corners[5] * displayHeight
        out[6] = corners[6] * displayWidth  // bottom-left
        out[7] = corners[7] * displayHeight

        if (rotation == 0) return

        // Прямая матрица (буфер → экран) строится по повороту, а нам нужна обратная
        val rawToDisplay = Matrix()
        rawToDisplay.postRotate(rotation.toFloat())
        when (rotation) {
            90 -> rawToDisplay.postTranslate(height.toFloat(), 0f)
            180 -> rawToDisplay.postTranslate(width.toFloat(), height.toFloat())
            270 -> rawToDisplay.postTranslate(0f, width.toFloat())
        }
        val displayToRaw = Matrix()
        rawToDisplay.invert(displayToRaw)
        displayToRaw.mapPoints(out)
    }

    /**
     * Обратное к [mapCornersToRaw] для готовых углов: переводит четыре точки из
     * нормализованных координат сырого буфера в нормализованные экранные, в которых хранится
     * настройка углов. Поворот берётся тот же, но применяется к четырём точкам, а не к сетке
     * замеров, — это дешевле и не требует Matrix.
     *
     * Порядок TL, TR, BR, BL восстанавливается заново: после поворота бывший левый верхний
     * угол оказывается, например, правым верхним, и переписать координаты на месте мало.
     */
    fun rawToDisplayCorners(corners: FloatArray, out: FloatArray, rotation: Int) {
        for (i in 0 until 4) {
            val u = corners[i * 2]
            val v = corners[i * 2 + 1]
            when (rotation) {
                90 -> {
                    out[i * 2] = 1f - v
                    out[i * 2 + 1] = u
                }

                180 -> {
                    out[i * 2] = 1f - u
                    out[i * 2 + 1] = 1f - v
                }

                270 -> {
                    out[i * 2] = v
                    out[i * 2 + 1] = 1f - u
                }

                else -> {
                    out[i * 2] = u
                    out[i * 2 + 1] = v
                }
            }
        }
        sortCorners(out)
    }

    /** Раскладывает четыре точки в порядке TL, TR, BR, BL по диагоналям x+y и x−y. */
    private fun sortCorners(points: FloatArray) {
        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        val sorted = FloatArray(8)

        for (i in 0 until 4) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            val sum = x + y
            val diff = x - y
            if (sum < minSum) {
                minSum = sum; sorted[0] = x; sorted[1] = y
            }
            if (diff > maxDiff) {
                maxDiff = diff; sorted[2] = x; sorted[3] = y
            }
            if (sum > maxSum) {
                maxSum = sum; sorted[4] = x; sorted[5] = y
            }
            if (diff < minDiff) {
                minDiff = diff; sorted[6] = x; sorted[7] = y
            }
        }
        sorted.copyInto(points)
    }

    /**
     * Прямоугольник внутри четырёхугольника телевизора для замеров авто-сна. Сжимается на
     * [inset] от каждой стороны, чтобы слегка неточная калибровка углов не заставляла
     * смотреть на стену вместо панели. Пишет в [out] четыре числа: left, top, right, bottom.
     */
    fun computeIdleBounds(
        mappedCorners: FloatArray,
        out: IntArray,
        width: Int,
        height: Int,
        inset: Float,
    ) {
        var minX = mappedCorners[0]
        var maxX = mappedCorners[0]
        var minY = mappedCorners[1]
        var maxY = mappedCorners[1]
        for (i in 1 until 4) {
            val x = mappedCorners[i * 2]
            val y = mappedCorners[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val insetX = (maxX - minX) * inset
        val insetY = (maxY - minY) * inset
        out[0] = (minX + insetX).toInt().coerceIn(0, width - 1)
        out[1] = (minY + insetY).toInt().coerceIn(0, height - 1)
        out[2] = (maxX - insetX).toInt().coerceIn(out[0], width - 1)
        out[3] = (maxY - insetY).toInt().coerceIn(out[1], height - 1)
    }

    /** Средняя по модулю разница яркости между двумя сетками замеров. */
    fun meanDeviation(samples: IntArray, reference: IntArray): Int {
        var sum = 0
        for (i in samples.indices) {
            val d = samples[i] - reference[i]
            sum += if (d < 0) -d else d
        }
        return sum / samples.size
    }
}
