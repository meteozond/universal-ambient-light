package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.max

/**
 * Решает, какие зоны и когда отправлять в Home Assistant. Умные лампы — не адресная лента:
 * Zigbee-сеть захлёбывается уже на десятке команд в секунду, поэтому обновления идут редко
 * (интервал), только по делу (порог изменения цвета) и с гашением на тёмных сценах.
 *
 * Времени и Android внутри нет — момент вызова передаётся снаружи, класс целиком
 * проверяется юнит-тестами.
 */
class HomeAssistantUpdatePolicy(
    private val mUpdateIntervalMs: Long,
    private val mChangeThreshold: Int,
    private val mDarkOffEnabled: Boolean,
    private val mDarkThreshold: Int,
) {

    /** Команда одной зоне: включить с цветом либо погасить. */
    class Update(
        val zone: HomeAssistantZone,
        val red: Int,
        val green: Int,
        val blue: Int,
        val turnOff: Boolean,
    )

    private val mLastSent = arrayOfNulls<IntArray>(HomeAssistantZone.entries.size)
    private val mDark = BooleanArray(HomeAssistantZone.entries.size)
    private var mLastBurstMs = Long.MIN_VALUE

    /**
     * @param colors триплеты RGB по ordinal зоны, как их отдаёт ZoneColorExtractor
     * @return команды для зон, которым пора обновиться; пустой список — ничего не делать
     */
    fun plan(
        nowMs: Long,
        colors: IntArray,
        zones: Collection<HomeAssistantZone>,
    ): List<Update> {
        if (mLastBurstMs != Long.MIN_VALUE && nowMs - mLastBurstMs < mUpdateIntervalMs) {
            return emptyList()
        }

        val updates = ArrayList<Update>(zones.size)
        for (zone in zones) {
            val base = zone.ordinal * 3
            val r = colors[base]
            val g = colors[base + 1]
            val b = colors[base + 2]
            val luma = max(r, max(g, b))

            if (mDarkOffEnabled) {
                if (mDark[zone.ordinal]) {
                    // Гистерезис: чтобы лампа не мигала на сцене, дрожащей вокруг порога
                    if (luma < mDarkThreshold + DARK_HYSTERESIS) continue
                    mDark[zone.ordinal] = false
                    // Проснулись — цвет уходит безусловно, лампа-то выключена
                    mLastSent[zone.ordinal] = null
                } else if (luma < mDarkThreshold) {
                    mDark[zone.ordinal] = true
                    mLastSent[zone.ordinal] = intArrayOf(r, g, b)
                    updates.add(Update(zone, r, g, b, turnOff = true))
                    continue
                }
            }

            val last = mLastSent[zone.ordinal]
            if (last != null && colorDistance(last, r, g, b) < mChangeThreshold) continue

            mLastSent[zone.ordinal] = intArrayOf(r, g, b)
            updates.add(Update(zone, r, g, b, turnOff = false))
        }

        if (updates.isNotEmpty()) mLastBurstMs = nowMs
        return updates
    }

    /** Забыть отправленное: после паузы или переподключения зоны уходят заново. */
    fun reset() {
        mLastSent.fill(null)
        mDark.fill(false)
        mLastBurstMs = Long.MIN_VALUE
    }

    private fun colorDistance(last: IntArray, r: Int, g: Int, b: Int): Int {
        return max(
            abs(last[0] - r),
            max(abs(last[1] - g), abs(last[2] - b))
        )
    }

    companion object {
        /** Насколько выше порога темноты должна подняться яркость, чтобы лампа включилась. */
        private const val DARK_HYSTERESIS = 10
    }
}
