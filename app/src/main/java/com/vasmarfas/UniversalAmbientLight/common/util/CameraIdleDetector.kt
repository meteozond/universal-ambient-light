package com.vasmarfas.UniversalAmbientLight.common.util

/**
 * Решает, когда сессия захвата с камеры может перестать слать кадры: панель погасла
 * (телевизор выключен или в ждущем режиме) либо картинка застыла (пауза, статичное меню).
 *
 * В отличие от экранных источников, режим камеры не получает ACTION_SCREEN_OFF — сама
 * картинка остаётся единственным признаком простоя. Вызывающий код передаёт два дешёвых
 * числа на кадр — среднюю яркость области телевизора и её отклонение от эталонного
 * замера — и реагирует на смену состояния. Время тоже приходит снаружи, поэтому у класса
 * нет зависимости от Android.
 *
 * Во сне вызывающий код обязан держать эталонный замер замороженным на моменте засыпания.
 * Сравнение с предыдущим кадром вместо этого проспало бы медленное затухание, где каждый
 * отдельный шаг лежит ниже любого разумного порога.
 */
class CameraIdleDetector(val config: Config) {

    /**
     * @param timeoutMs сколько картинка должна оставаться чёрной или застывшей до засыпания
     * @param darkLevel средняя яркость (0-255), при которой и ниже панель считается погасшей
     * @param motionLevel среднее отклонение яркости (0-255), при котором и ниже картинка
     *   считается неизменной
     * @param staticSleepEnabled разрешено ли засыпать на застывшей, но не чёрной картинке
     */
    data class Config(
        val timeoutMs: Long,
        val darkLevel: Int,
        val motionLevel: Int,
        val staticSleepEnabled: Boolean,
    )

    enum class State {
        /** Полный конвейер захвата, кадры уходят на ленту. */
        AWAKE,

        /** Панель погасла: лента выключена, захват идёт на пониженной частоте. */
        SLEEP_DARK,

        /** Картинка застыла: лента держит последние цвета, захват простаивает. */
        SLEEP_STATIC,
    }

    var state: State = State.AWAKE
        private set

    private var darkSinceMs = 0L
    private var stillSinceMs = 0L
    private var wakeTicks = 0

    // Для пробуждения нужен заметно более яркий или более отличающийся кадр, чем тот, что
    // усыпил, иначе шум сенсора, сидящий ровно на пороге, будет дёргать состояние на
    // каждом замере.
    private val wakeLumaLevel =
        config.darkLevel + (config.darkLevel / 2).coerceAtLeast(MIN_LUMA_MARGIN)
    private val wakeMotionLevel =
        (config.motionLevel * 2).coerceAtLeast(config.motionLevel + MIN_MOTION_MARGIN)

    /** True, пока вызывающий код может пропускать дорогую часть своего конвейера. */
    val isAsleep: Boolean get() = state != State.AWAKE

    /**
     * Принимает один замер и возвращает состояние, действующее с этого момента.
     *
     * @param luma средняя яркость (0-255) снятой области телевизора
     * @param deviation среднее по модулю отличие яркости (0-255) от эталонного замера:
     *   предыдущего кадра в бодрствовании и кадра засыпания во сне
     */
    fun update(luma: Int, deviation: Int, nowMs: Long): State {
        state = if (state == State.AWAKE) {
            updateAwake(luma, deviation, nowMs)
        } else {
            updateAsleep(luma, deviation)
        }
        return state
    }

    /** Сбрасывает таймеры и возвращает [State.AWAKE] — для перезапущенной сессии захвата. */
    fun reset() {
        state = State.AWAKE
        darkSinceMs = 0L
        stillSinceMs = 0L
        wakeTicks = 0
    }

    private fun updateAwake(luma: Int, deviation: Int, nowMs: Long): State {
        val blank = luma <= config.darkLevel
        darkSinceMs = if (!blank) 0L else if (darkSinceMs == 0L) nowMs else darkSinceMs

        val still = deviation <= config.motionLevel
        stillSinceMs = if (!still) 0L else if (stillSinceMs == 0L) nowMs else stillSinceMs

        // Чёрный экран — тоже застывший; выключить ленту лучше, чем заморозить её на
        // последних цветах, поэтому проверка на черноту идёт первой.
        if (blank && nowMs - darkSinceMs >= config.timeoutMs) return fallAsleep(State.SLEEP_DARK)
        if (config.staticSleepEnabled && still && nowMs - stillSinceMs >= config.timeoutMs) {
            return fallAsleep(State.SLEEP_STATIC)
        }
        return State.AWAKE
    }

    private fun updateAsleep(luma: Int, deviation: Int): State {
        val woken = when (state) {
            // Загоревшаяся панель — основной признак, но достаточно яркое изменение в
            // остальном тёмном кадре (меню на чёрном фоне) тоже считается.
            State.SLEEP_DARK -> luma >= wakeLumaLevel || deviation >= wakeMotionLevel
            else -> deviation >= wakeMotionLevel
        }
        if (!woken) {
            wakeTicks = 0
            return state
        }
        // Два замера подряд, чтобы один шумный кадр не будил всю ленту.
        if (++wakeTicks < WAKE_CONFIRM_TICKS) return state

        wakeTicks = 0
        darkSinceMs = 0L
        stillSinceMs = 0L
        return State.AWAKE
    }

    private fun fallAsleep(target: State): State {
        darkSinceMs = 0L
        stillSinceMs = 0L
        wakeTicks = 0
        return target
    }

    companion object {
        /** Сколько подряд «пробуждающих» замеров нужно, чтобы вернуться к полному захвату. */
        const val WAKE_CONFIRM_TICKS = 2

        private const val MIN_LUMA_MARGIN = 6
        private const val MIN_MOTION_MARGIN = 4
    }
}
