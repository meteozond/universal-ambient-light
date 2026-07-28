package com.vasmarfas.UniversalAmbientLight.common.util

/**
 * Decides when a camera capture session can stop streaming: the panel went blank
 * (TV off / standby) or the picture froze (paused video, static menu).
 *
 * Unlike the screen-capture sources, camera mode gets no ACTION_SCREEN_OFF — the
 * picture itself is the only available standby signal. The caller feeds two cheap
 * per-frame numbers, mean luminance of the TV area and how far it deviates from a
 * reference sample, and reacts to the reported state changes. Time comes from the
 * caller, so this class has no Android dependency.
 *
 * While asleep the caller must keep the reference sample frozen at the moment of
 * falling asleep. Comparing against the previous frame instead would sleep through a
 * slow fade, where every individual step sits below any usable threshold.
 */
class CameraIdleDetector(val config: Config) {

    /**
     * @param timeoutMs how long the picture must stay blank/frozen before sleeping
     * @param darkLevel mean luminance (0-255) at or below which the panel counts as blank
     * @param motionLevel mean luminance deviation (0-255) at or below which the picture
     *   counts as unchanged
     * @param staticSleepEnabled whether a frozen — but not blank — picture may sleep too
     */
    data class Config(
        val timeoutMs: Long,
        val darkLevel: Int,
        val motionLevel: Int,
        val staticSleepEnabled: Boolean,
    )

    enum class State {
        /** Full capture pipeline, frames streaming to the LEDs. */
        AWAKE,

        /** Blank panel: LEDs off, capture idling at a low sample rate. */
        SLEEP_DARK,

        /** Frozen picture: LEDs hold their last colors, capture idling. */
        SLEEP_STATIC,
    }

    var state: State = State.AWAKE
        private set

    private var darkSinceMs = 0L
    private var stillSinceMs = 0L
    private var wakeTicks = 0

    // Waking needs a clearly brighter / more different frame than the one that put us to
    // sleep, otherwise sensor noise sitting right on the threshold flips the state on
    // every sample.
    private val wakeLumaLevel =
        config.darkLevel + (config.darkLevel / 2).coerceAtLeast(MIN_LUMA_MARGIN)
    private val wakeMotionLevel =
        (config.motionLevel * 2).coerceAtLeast(config.motionLevel + MIN_MOTION_MARGIN)

    /** True while the caller may skip the expensive part of its capture pipeline. */
    val isAsleep: Boolean get() = state != State.AWAKE

    /**
     * Feeds one sample and returns the state that applies from now on.
     *
     * @param luma mean luminance (0-255) of the captured TV area
     * @param deviation mean absolute luminance difference (0-255) against the reference
     *   sample — the previous frame while awake, the frame we fell asleep on while asleep
     */
    fun update(luma: Int, deviation: Int, nowMs: Long): State {
        state = if (state == State.AWAKE) {
            updateAwake(luma, deviation, nowMs)
        } else {
            updateAsleep(luma, deviation)
        }
        return state
    }

    /** Drops all timers and returns to [State.AWAKE] — for a restarted capture session. */
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

        // A blank screen is also a frozen one; turning the LEDs off beats freezing them on
        // the last colors, so the blank check goes first.
        if (blank && nowMs - darkSinceMs >= config.timeoutMs) return fallAsleep(State.SLEEP_DARK)
        if (config.staticSleepEnabled && still && nowMs - stillSinceMs >= config.timeoutMs) {
            return fallAsleep(State.SLEEP_STATIC)
        }
        return State.AWAKE
    }

    private fun updateAsleep(luma: Int, deviation: Int): State {
        val woken = when (state) {
            // The panel lighting up is the primary signal, but a bright-enough change in an
            // otherwise dark frame (a menu on a black background) counts too.
            State.SLEEP_DARK -> luma >= wakeLumaLevel || deviation >= wakeMotionLevel
            else -> deviation >= wakeMotionLevel
        }
        if (!woken) {
            wakeTicks = 0
            return state
        }
        // Two samples in a row, so a single noisy frame can't wake the whole strip.
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
        /** Consecutive wake samples required before resuming full capture. */
        const val WAKE_CONFIRM_TICKS = 2

        private const val MIN_LUMA_MARGIN = 6
        private const val MIN_MOTION_MARGIN = 4
    }
}
