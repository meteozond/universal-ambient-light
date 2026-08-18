package com.vasmarfas.UniversalAmbientLight.common

import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import java.util.concurrent.atomic.AtomicBoolean

abstract class ScreenEncoderBase(
    protected val mListener: HyperionThread.HyperionThreadListener,
    protected val mMediaProjection: MediaProjection,
    width: Int,
    height: Int,
    protected val mDensity: Int,
    options: AppOptions,
) : CaptureBackend {

    // Настройки, неизменные после создания
    protected val mFrameRate: Int = options.frameRate
    protected val mAvgColor: Boolean = options.useAverageColor
    private val mInitOrientation: Int
    private val mWidthScaled: Int
    private val mHeightScaled: Int

    // Настоящие размеры экрана
    private val mScreenWidth: Int = width
    private val mScreenHeight: Int = height

    // Компоненты
    protected val mHandler: Handler
    private val mHandlerThread: HandlerThread

    // Изменяемое состояние
    @Volatile
    protected var mCurrentOrientation: Int = 0

    @Volatile
    private var mIsCapturing: Boolean = false

    private val mClearing = AtomicBoolean(false)

    init {
        mInitOrientation =
            if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        mCurrentOrientation = mInitOrientation

        val divisor = options.findDivisor(width, height)
        mWidthScaled = width / divisor
        mHeightScaled = height / divisor

        // Отдельный поток с Handler под обратные вызовы
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        mHandlerThread = thread
        mHandler = Handler(thread.looper)

        if (DEBUG) {
            Log.d(
                TAG,
                "Init: " + width + "x" + height + " -> " + mWidthScaled + "x" + mHeightScaled
            )
        }
    }

    override fun clearLights() {
        // Пока предыдущая серия чёрных кадров не доиграла, повторный вызов ничего не
        // добавляет: получится несколько потоков, шлющих одно и то же одному клиенту.
        if (!mClearing.compareAndSet(false, true)) return
        startClearThread(disconnect = false, releaseFlag = true)
    }

    protected fun clearAndDisconnect() {
        // Отключение пропускать нельзя, даже если гашение уже идёт.
        startClearThread(disconnect = true, releaseFlag = false)
    }

    private fun startClearThread(disconnect: Boolean, releaseFlag: Boolean) {
        Thread({
            try {
                repeat(CLEAR_FRAMES) {
                    sleep(CLEAR_DELAY_MS.toLong())
                    mListener.clear()
                }
                if (disconnect) mListener.disconnect()
            } finally {
                if (releaseFlag) mClearing.set(false)
            }
        }, "clear-lights").start()
    }

    override fun isCapturing(): Boolean {
        return mIsCapturing
    }

    protected fun setCapturing(capturing: Boolean) {
        mIsCapturing = capturing
    }

    override fun sendStatus() {
        mListener.sendStatus(mIsCapturing)
    }

    protected fun getGrabberWidth(): Int {
        return if (mInitOrientation != mCurrentOrientation) mHeightScaled else mWidthScaled
    }

    protected fun getGrabberHeight(): Int {
        return if (mInitOrientation != mCurrentOrientation) mWidthScaled else mHeightScaled
    }

    // Настоящие размеры экрана, без уменьшения делителем
    protected fun getScreenWidth(): Int {
        return if (mInitOrientation != mCurrentOrientation) mScreenHeight else mScreenWidth
    }

    protected fun getScreenHeight(): Int {
        return if (mInitOrientation != mCurrentOrientation) mScreenWidth else mScreenHeight
    }

    protected fun stopHandlerThread() {
        mHandlerThread.quitSafely()
        // Колбэк MediaProjection.onStop приходит на этом же HandlerThread — join самого
        // себя всегда выждал бы полный таймаут, удерживая монитор stopInternal.
        if (Thread.currentThread() === mHandlerThread) return
        try {
            mHandlerThread.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "ScreenEncoderBase"
        const val DEBUG = false
        private const val CLEAR_DELAY_MS = 100
        private const val CLEAR_FRAMES = 5

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
