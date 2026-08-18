package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantZone

/**
 * Считает усреднённые цвета зон кадра для ламп Home Assistant: весь экран целиком, четыре
 * краевые полосы и четыре угла. Лампа слева от телевизора берёт цвет левого края картинки,
 * торшер за диваном — средний цвет экрана, лампа в углу комнаты — цвет своего угла экрана.
 *
 * Кадр — тот же RGB-массив, что уходит остальным клиентам. Один проход по пикселям,
 * без выделений памяти; результат складывается в переданный массив по ordinal зоны.
 */
object ZoneColorExtractor {

    val ZONE_COUNT = HomeAssistantZone.entries.size

    /** Доля ширины (высоты) кадра, которую занимает краевая полоса. */
    private const val EDGE_FRACTION = 0.3f

    /**
     * Угол — не голое пятно пикселей своего квадрата, а этот же цвет, слегка стянутый к
     * среднему двух прилегающих краёв: иначе на резкой границе угловая лампа заметно
     * дёргается относительно соседних боковых.
     */
    private const val CORNER_BLEND = 0.3f

    /**
     * @param out массив длиной [ZONE_COUNT]*3, триплеты RGB по ordinal зоны
     * @return false, если кадр не совпал с заявленными размерами и разбирать нечего
     */
    fun extract(data: ByteArray, width: Int, height: Int, out: IntArray): Boolean {
        if (width <= 0 || height <= 0) return false
        if (data.size < width * height * 3 || out.size < ZONE_COUNT * 3) return false

        val bandW = (width * EDGE_FRACTION).toInt().coerceAtLeast(1)
        val bandH = (height * EDGE_FRACTION).toInt().coerceAtLeast(1)
        val rightFrom = width - bandW
        val bottomFrom = height - bandH

        val sums = LongArray(ZONE_COUNT * 3)
        val counts = LongArray(ZONE_COUNT)

        var idx = 0
        for (y in 0 until height) {
            val inTop = y < bandH
            val inBottom = y >= bottomFrom
            for (x in 0 until width) {
                val r = (data[idx].toInt() and 0xFF).toLong()
                val g = (data[idx + 1].toInt() and 0xFF).toLong()
                val b = (data[idx + 2].toInt() and 0xFF).toLong()
                idx += 3

                val inLeft = x < bandW
                val inRight = x >= rightFrom

                accumulate(sums, counts, HomeAssistantZone.AVERAGE.ordinal, r, g, b)
                if (inLeft) accumulate(sums, counts, HomeAssistantZone.LEFT.ordinal, r, g, b)
                if (inRight) accumulate(sums, counts, HomeAssistantZone.RIGHT.ordinal, r, g, b)
                if (inTop) accumulate(sums, counts, HomeAssistantZone.TOP.ordinal, r, g, b)
                if (inBottom) accumulate(sums, counts, HomeAssistantZone.BOTTOM.ordinal, r, g, b)
                if (inTop && inLeft) {
                    accumulate(sums, counts, HomeAssistantZone.TOP_LEFT.ordinal, r, g, b)
                }
                if (inTop && inRight) {
                    accumulate(sums, counts, HomeAssistantZone.TOP_RIGHT.ordinal, r, g, b)
                }
                if (inBottom && inLeft) {
                    accumulate(sums, counts, HomeAssistantZone.BOTTOM_LEFT.ordinal, r, g, b)
                }
                if (inBottom && inRight) {
                    accumulate(sums, counts, HomeAssistantZone.BOTTOM_RIGHT.ordinal, r, g, b)
                }
            }
        }

        for (zone in 0 until ZONE_COUNT) {
            val count = counts[zone]
            val base = zone * 3
            if (count == 0L) {
                out[base] = 0
                out[base + 1] = 0
                out[base + 2] = 0
            } else {
                out[base] = (sums[base] / count).toInt()
                out[base + 1] = (sums[base + 1] / count).toInt()
                out[base + 2] = (sums[base + 2] / count).toInt()
            }
        }

        blendCorner(out, HomeAssistantZone.TOP_LEFT, HomeAssistantZone.LEFT, HomeAssistantZone.TOP)
        blendCorner(out, HomeAssistantZone.TOP_RIGHT, HomeAssistantZone.RIGHT, HomeAssistantZone.TOP)
        blendCorner(out, HomeAssistantZone.BOTTOM_LEFT, HomeAssistantZone.LEFT, HomeAssistantZone.BOTTOM)
        blendCorner(
            out,
            HomeAssistantZone.BOTTOM_RIGHT,
            HomeAssistantZone.RIGHT,
            HomeAssistantZone.BOTTOM
        )
        return true
    }

    private fun blendCorner(
        out: IntArray,
        corner: HomeAssistantZone,
        edgeA: HomeAssistantZone,
        edgeB: HomeAssistantZone,
    ) {
        val cornerBase = corner.ordinal * 3
        val aBase = edgeA.ordinal * 3
        val bBase = edgeB.ordinal * 3
        for (i in 0 until 3) {
            val sideAverage = (out[aBase + i] + out[bBase + i]) / 2
            out[cornerBase + i] =
                (out[cornerBase + i] * (1f - CORNER_BLEND) + sideAverage * CORNER_BLEND).toInt()
        }
    }

    private fun accumulate(
        sums: LongArray,
        counts: LongArray,
        zone: Int,
        r: Long,
        g: Long,
        b: Long,
    ) {
        val base = zone * 3
        sums[base] += r
        sums[base + 1] += g
        sums[base + 2] += b
        counts[zone]++
    }
}
