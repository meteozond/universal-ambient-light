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
