package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorSmoothing - класс для устранения стробоскопического эффекта при обновлении LED.
 */
class ColorSmoothing(private val mDataSender: LedDataSender?) {

    // Значения по умолчанию
    companion object {
        private const val TAG = "ColorSmoothing"
        private const val DEBUG = false
        private const val DEFAULT_UPDATE_FREQUENCY_HZ = 60
        private const val DEFAULT_SETTLING_TIME_MS = 50
        private const val DEFAULT_OUTPUT_DELAY_MS = 0L
        private const val MIN_UPDATE_INTERVAL_MS = 1L
    }

    // Конфигурация
    private var mUpdateFrequencyHz = DEFAULT_UPDATE_FREQUENCY_HZ
    private var mSettlingTimeMs = DEFAULT_SETTLING_TIME_MS
    private var mOutputDelayMs: Long = DEFAULT_OUTPUT_DELAY_MS
    private var mEnabled = true

    // Состояние
    private var mPreviousValues: Array<ColorRgb>? = null
    private var mTargetValues: Array<ColorRgb>? = null
    private var mTargetTime: Long = 0

    // Очередь вывода (Output Delay) - хранит пары (время добавления, кадр)
    private data class TimedFrame(val timestamp: Long, val colors: Array<ColorRgb>)

    private val mOutputQueue = ArrayDeque<TimedFrame>()

    // Таймер
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: Handler? = null

    @Volatile
    private var mRunning = false

    // Отслеживание времени последнего обновления (дебаунсинг)
    private var mLastUpdateTime: Long = 0

    // Интерфейс для отправки данных
    fun interface LedDataSender {
        fun sendLedData(colors: Array<ColorRgb>)
    }

    private val mUpdateRunnable = object : Runnable {
        override fun run() {
            if (!mRunning || !mEnabled) return

            updateLeds()

            if (mRunning && mHandler != null) {
                val intervalMs = 1000L / mUpdateFrequencyHz
                mHandler?.postDelayed(this, intervalMs)
            }
        }
    }

    fun setTargetColors(targetColors: Array<ColorRgb>?) {
        if (targetColors == null || targetColors.isEmpty()) {
            return
        }

        // Дебаунсинг
        val now = System.currentTimeMillis()
        if (now - mLastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        mLastUpdateTime = now

        synchronized(this) {
            mTargetTime = now + mSettlingTimeMs

            // Инициализация при первом вызове или изменении размера
            val current = mTargetValues
            if (current == null || current.size != targetColors.size) {
                val targets = Array(targetColors.size) { ColorRgb(0, 0, 0) }
                val previous = Array(targetColors.size) { ColorRgb(0, 0, 0) }
                mTargetValues = targets
                mPreviousValues = previous

                // Копируем начальное состояние
                for (i in targetColors.indices) {
                    targets[i].set(targetColors[i])
                    previous[i].set(targetColors[i])
                }

                // Запускаем таймер только если сглаживание включено
                if (mEnabled) {
                    start()
                }
            } else {
                // Обновление без мусора: копируем значения на месте
                for (i in targetColors.indices) {
                    current[i].set(targetColors[i])
                }
            }
        }

        // Если сглаживание выключено, отправляем данные напрямую (клонируем для безопасности)
        if (!mEnabled) {
            val colorsCopy = Array(targetColors.size) { i -> targetColors[i].clone() }
            sendToDevice(colorsCopy)
        }
    }

    private fun updateLeds() {
        val colorsToSend: Array<ColorRgb>

        synchronized(this) {
            colorsToSend = interpolateFrameLinear() ?: return
        }

        queueColors(colorsToSend)
    }

    private fun interpolateFrameLinear(): Array<ColorRgb>? {
        val targets = mTargetValues ?: return null
        val previous = mPreviousValues ?: return null
        val now = System.currentTimeMillis()
        val deltaTime = mTargetTime - now

        if (deltaTime <= 0) {
            // Время истекло, использовать целевые значения
            // Обновляем mPreviousValues на месте
            for (i in targets.indices) previous[i].set(targets[i])

            if (mOutputDelayMs == 0L) return previous

            // Клонируем только если кадр уходит в очередь
            return Array(previous.size) { i -> previous[i].clone() }
        }

        // Линейная интерполяция
        var k = 1.0f - deltaTime.toFloat() / mSettlingTimeMs
        k = max(0.0f, min(1.0f, k))

        val length = min(previous.size, targets.size)
        for (i in 0 until length) {
            val rDiff = targets[i].red - previous[i].red
            val gDiff = targets[i].green - previous[i].green
            val bDiff = targets[i].blue - previous[i].blue

            val r = max(0, min(255, previous[i].red + (k * rDiff).roundToInt()))
            val g = max(0, min(255, previous[i].green + (k * gDiff).roundToInt()))
            val b = max(0, min(255, previous[i].blue + (k * bDiff).roundToInt()))

            previous[i].set(r, g, b)
        }

        if (mOutputDelayMs == 0L) return previous

        return Array(previous.size) { i -> previous[i].clone() }
    }

    private fun queueColors(ledColors: Array<ColorRgb>) {
        if (mOutputDelayMs == 0L) {
            sendToDevice(ledColors)
        } else {
            val now = System.currentTimeMillis()
            synchronized(mOutputQueue) {
                // Добавляем кадр с текущим временем
                mOutputQueue.addLast(TimedFrame(now, ledColors))

                // Проверяем, прошло ли достаточно времени с момента добавления самого старого кадра
                while (mOutputQueue.isNotEmpty()) {
                    val oldestFrame = mOutputQueue.first
                    val elapsed = now - oldestFrame.timestamp
                    if (elapsed >= mOutputDelayMs) {
                        val frameToSend = mOutputQueue.removeFirst()
                        sendToDevice(frameToSend.colors)
                    } else {
                        break // Самый старый кадр еще не готов к отправке
                    }
                }
            }
        }
    }

    private fun sendToDevice(colors: Array<ColorRgb>) {
        mDataSender?.sendLedData(colors)
    }

    fun start() {
        if (mRunning) return

        val thread = HandlerThread("ColorSmoothing", Process.THREAD_PRIORITY_BACKGROUND)
        mHandlerThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        mHandler = handler

        mRunning = true
        val intervalMs = 1000L / mUpdateFrequencyHz
        handler.postDelayed(mUpdateRunnable, intervalMs)
    }

    fun stop() {
        mRunning = false
        mHandler?.removeCallbacksAndMessages(null)
        mHandler = null
        mHandlerThread?.quitSafely()
        mHandlerThread = null
        synchronized(mOutputQueue) {
            mOutputQueue.clear()
        }
        synchronized(this) {
            mPreviousValues = null
            mTargetValues = null
        }
    }

    fun setSettlingTime(ms: Int) {
        mSettlingTimeMs = max(0, min(1000, ms))
    }

    fun setOutputDelay(ms: Long) {
        mOutputDelayMs = max(0L, min(1000L, ms)) // Задержка в миллисекундах (0-1000 мс)
    }

    fun setUpdateFrequency(hz: Int) {
        mUpdateFrequencyHz = max(1, min(60, hz))
        // Обновить интервал, если уже запущено
        val handler = mHandler
        if (mRunning && handler != null) {
            handler.removeCallbacksAndMessages(null)
            val intervalMs = 1000L / mUpdateFrequencyHz
            handler.postDelayed(mUpdateRunnable, intervalMs)
        }
    }

    fun setEnabled(enabled: Boolean) {
        val wasEnabled = mEnabled
        mEnabled = enabled

        // Если сглаживание выключено, останавливаем таймер
        if (!enabled && wasEnabled && mRunning) {
            stop()
        }
        // Если сглаживание включено и таймер не запущен, запускаем его
        else if (enabled && !wasEnabled && mTargetValues != null && !mRunning) {
            start()
        }
    }

    fun isEnabled(): Boolean {
        return mEnabled
    }

    /**
     * Применяет пресет сглаживания
     * @param preset «off», «responsive», «balanced» или «smooth»
     */
    fun applyPreset(preset: String) {
        when (preset.lowercase()) {
            "off" -> {
                mEnabled = false
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "responsive" -> {
                mEnabled = true
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "balanced" -> {
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }

            "smooth" -> {
                mEnabled = true
                mSettlingTimeMs = 500
                mOutputDelayMs = 200L // ~5 кадров при 25 FPS
                mUpdateFrequencyHz = 20
            }

            else -> {
                // По умолчанию "balanced"
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }
        }
        // Обновить интервал, если уже запущено
        val handler = mHandler
        if (mRunning && handler != null) {
            handler.removeCallbacksAndMessages(null)
            val intervalMs = 1000L / mUpdateFrequencyHz
            handler.postDelayed(mUpdateRunnable, intervalMs)
        }
    }
}
