package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log

/**
 * Поведение захвата, когда экран устройства погас.
 *
 * Держит два блокиратора: PARTIAL_WAKE_LOCK не даёт процессору уснуть настолько, чтобы
 * замолчали keepalive-потоки (иначе WLED через несколько секунд возвращается к своему
 * эффекту), а WIFI_MODE_FULL_HIGH_PERF снимает троттлинг UDP в простое — на части
 * прошивок Android TV без него пакеты перестают уходить.
 *
 * Второе поведение — отложенное молчание: если keepalive в настройках выключен, вывод
 * глушится не сразу, а через паузу, чтобы чёрные кадры успели дойти до ленты.
 */
internal class StandbyController(
    private val context: Context,
    private val handler: Handler,
    private val onPause: () -> Unit,
) {

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    private val mPauseRunnable = Runnable { onPause() }

    fun acquireWakeLock() {
        // Re-acquire if the previous lock already timed out on its own (standby longer than the timeout)
        if (mWakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScreenGrabberService::ScreenCapture"
            )
            mWakeLock = lock
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    fun releaseWakeLock() {
        val lock = mWakeLock ?: return
        try {
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
        mWakeLock = null
    }

    fun acquireWifiLock() {
        if (mWifiLock?.isHeld == true) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                // HighPerf to prevent UDP throttling during idle (helps on some Android TV firmwares)
                val lock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "ScreenGrabberService::Wifi"
                )
                mWifiLock = lock
                lock.setReferenceCounted(false)
                lock.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock", e)
        }
    }

    fun releaseWifiLock() {
        val lock = mWifiLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifi lock", e)
        }
        mWifiLock = null
    }

    /** Экран уже погашен? Нужно, чтобы решить, глушить ли вывод сразу после подключения. */
    fun isScreenOff(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive.not()
        } catch (e: Exception) {
            // Без ответа от PowerManager считаем экран включённым: лучше продолжить
            // отправку, чем погасить ленту при работающем телевизоре.
            false
        }
    }

    fun schedulePause() {
        handler.removeCallbacks(mPauseRunnable)
        handler.postDelayed(mPauseRunnable, PAUSE_DELAY_MS)
    }

    fun cancelPause() {
        handler.removeCallbacks(mPauseRunnable)
    }

    fun releaseAll() {
        cancelPause()
        releaseWakeLock()
        releaseWifiLock()
    }

    companion object {
        private const val TAG = "StandbyController"

        // Delay before silencing output on SCREEN_OFF: covers the 5 clear frames (~500ms)
        // plus smoothing settling/output delay so the strip is reliably black first.
        private const val PAUSE_DELAY_MS = 1500L

        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
