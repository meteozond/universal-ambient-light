package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.util.CameraIdleDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraIdleDetectorTest {

    @Test
    fun `a lit panel keeps the detector awake`() {
        val detector = CameraIdleDetector(config())
        detector.update(200, 40, 1_000)
        assertEquals(State.AWAKE, detector.update(200, 40, 60_000))
    }

    @Test
    fun `a dark panel puts the detector to sleep after the timeout`() {
        val detector = CameraIdleDetector(config())
        detector.update(4, 0, 1_000)
        assertEquals(State.SLEEP_DARK, detector.update(4, 0, 2_000))
    }

    @Test
    fun `the dark timer restarts when the panel lights up again`() {
        val detector = CameraIdleDetector(config())
        detector.update(4, 0, 1_000)
        detector.update(200, 90, 1_500)
        assertEquals(State.AWAKE, detector.update(4, 0, 2_100))
    }

    @Test
    fun `a frozen picture keeps the detector awake while static sleep is off`() {
        val detector = CameraIdleDetector(config(staticSleepEnabled = false))
        detector.update(200, 0, 1_000)
        assertEquals(State.AWAKE, detector.update(200, 0, 5_000))
    }

    @Test
    fun `a frozen picture puts the detector to sleep while static sleep is on`() {
        val detector = CameraIdleDetector(config(staticSleepEnabled = true))
        detector.update(200, 0, 1_000)
        assertEquals(State.SLEEP_STATIC, detector.update(200, 0, 2_000))
    }

    @Test
    fun `a single bright frame does not wake the detector`() {
        val detector = sleepingDark()
        assertEquals(State.SLEEP_DARK, detector.update(200, 0, 3_000))
    }

    @Test
    fun `two bright frames in a row wake the detector`() {
        val detector = sleepingDark()
        detector.update(200, 0, 3_000)
        assertEquals(State.AWAKE, detector.update(200, 0, 3_100))
    }

    @Test
    fun `noise just below the wake thresholds keeps the detector asleep`() {
        // Порог засыпания 12, порог пробуждения 18: кадр между ними будить не должен
        val detector = sleepingDark()
        detector.update(17, 7, 3_000)
        detector.update(17, 7, 3_100)
        assertEquals(State.SLEEP_DARK, detector.update(17, 7, 3_200))
    }

    @Test
    fun `static sleep waits for motion and ignores brightness`() {
        val detector = sleepingStatic()
        detector.update(255, 0, 3_000)
        assertEquals(State.SLEEP_STATIC, detector.update(255, 0, 4_000))
    }

    @Test
    fun `motion wakes the detector from static sleep`() {
        val detector = sleepingStatic()
        detector.update(200, 20, 3_000)
        assertEquals(State.AWAKE, detector.update(200, 20, 3_100))
    }

    @Test
    fun `reset returns a sleeping detector to awake`() {
        val detector = sleepingDark()
        detector.reset()
        assertFalse(detector.isAsleep)
    }

    private fun config(
        timeoutMs: Long = 1_000,
        darkLevel: Int = 12,
        motionLevel: Int = 4,
        staticSleepEnabled: Boolean = false,
    ) = CameraIdleDetector.Config(timeoutMs, darkLevel, motionLevel, staticSleepEnabled)

    private fun sleepingDark(): CameraIdleDetector {
        val detector = CameraIdleDetector(config())
        detector.update(4, 0, 1_000)
        detector.update(4, 0, 2_000)
        return detector
    }

    private fun sleepingStatic(): CameraIdleDetector {
        val detector = CameraIdleDetector(config(staticSleepEnabled = true))
        detector.update(200, 0, 1_000)
        detector.update(200, 0, 2_000)
        return detector
    }
}
