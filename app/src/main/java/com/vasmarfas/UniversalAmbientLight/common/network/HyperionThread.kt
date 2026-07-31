package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HyperionThread(
    private val mCallback: ScreenGrabberService.HyperionThreadBroadcaster,
    private val mContext: Context,
    config: ConnectionConfig,
) : Thread(TAG) {

    private val mHost: String = config.host
    private val mPort: Int = config.port
    private val mPriority: Int = config.priority
    private val mBaudRate: Int = config.baudRate
    private val mWledProtocol: String = config.wledProtocol
    private val mWledRgbw: Boolean = config.wledRgbw
    private val mWledBrightness: Int = config.wledBrightness
    private val mAdalightProtocol: String = config.adalightProtocol
    private val mSmoothingEnabled: Boolean = config.smoothingEnabled
    private val mSmoothingPreset: String = config.smoothingPreset
    private val mSettlingTime: Int = config.settlingTime
    private val mOutputDelayMs: Long = config.outputDelayMs
    private val mUpdateFrequency: Int = config.updateFrequency

    private val mReconnectDelayMs: Long = (config.reconnectDelaySeconds * 1000).toLong()
    private val mConnectionType: String = config.connectionType
    private val mWledColorOrder: String = config.wledColorOrder
    private val mReconnectEnabled = AtomicBoolean(config.reconnect)
    private val mConnected = AtomicBoolean(false)
    private val mStandbyPaused = AtomicBoolean(false)
    private val mClient = AtomicReference<HyperionClient?>()
    private val mExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var mPendingTask: Future<*>? = null

    @Volatile
    private var mPendingFrame: FrameData? = null

    @Volatile
    private var mLastSentFrame: FrameData? = null
    private val mSendLock = Any()
    private val mKeepAliveExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private class FrameData(val data: ByteArray, val width: Int, val height: Int)

    private val mListener = object : HyperionThreadListener {
        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            if (mStandbyPaused.get()) return
            val client = mClient.get()
            if (client == null || !client.isConnected()) return
            if (mExecutor.isShutdown) return

            mPendingFrame = FrameData(data, width, height)
            val pending = mPendingTask
            if (pending != null && !pending.isDone) {
                pending.cancel(false)
            }
            try {
                mPendingTask = mExecutor.submit { sendPendingFrame() }
            } catch (_: RejectedExecutionException) {
                // Executor успели остановить между проверкой isShutdown и submit (гонка при отключении).
            }
        }

        private fun sendPendingFrame() {
            if (mStandbyPaused.get()) return
            val frame = mPendingFrame
            val client = mClient.get()

            if (frame == null || client == null || !client.isConnected()) return

            try {
                synchronized(mSendLock) {
                    client.setImage(
                        frame.data,
                        frame.width,
                        frame.height,
                        mPriority,
                        FRAME_DURATION
                    )
                }
                // Держим стабильную копию для повторов keepalive.
                mLastSentFrame = FrameData(frame.data.copyOf(), frame.width, frame.height)

                if (client is HyperionFlatBuffers) {
                    client.cleanReplies()
                }
            } catch (e: IOException) {
                handleError(e)
            }
        }

        override fun clear() {
            val client = mClient.get()
            if (client != null && client.isConnected()) {
                try {
                    client.clear(mPriority)
                } catch (e: IOException) {
                    mCallback.onConnectionError(e.hashCode(), e.message)
                }
            }
        }

        override fun disconnect() {
            val pending = mPendingTask
            if (pending != null) {
                pending.cancel(true)
                mPendingTask = null
            }
            mPendingFrame = null
            mLastSentFrame = null

            if (!mKeepAliveExecutor.isShutdown) {
                mKeepAliveExecutor.shutdownNow()
            }

            if (!mExecutor.isShutdown) {
                mExecutor.shutdownNow()
                try {
                    mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }

            val client = mClient.getAndSet(null)
            if (client != null) {
                try {
                    client.disconnect()
                } catch (ignored: IOException) {
                }
            }

            mConnected.set(false)
        }

        override fun sendStatus(isGrabbing: Boolean) {
            mCallback.onReceiveStatus(isGrabbing)
        }
    }

    val receiver: HyperionThreadListener
        get() = mListener

    /**
     * Сбрасывает блокировку отправки данных для WLED клиента.
     * Вызывается при включении экрана, чтобы возобновить отправку после ошибки EPERM.
     */
    fun resetBlockedIfWLED() {
        val client = mClient.get()
        if (client is WLEDClient) {
            client.resetBlocked()
        }
    }

    /**
     * Полностью останавливает отправку данных на время сна ТВ (экран выключен).
     * Соединение с устройством сохраняется, чтобы возобновление было мгновенным.
     */
    fun pauseSending() {
        mStandbyPaused.set(true)
        when (val client = mClient.get()) {
            is WLEDClient -> client.pauseSending()
            is AdalightClient -> client.pauseSending()
            else -> {}
        }
    }

    /**
     * Возобновляет отправку данных после включения экрана.
     */
    fun resumeSending() {
        mStandbyPaused.set(false)
        when (val client = mClient.get()) {
            is WLEDClient -> client.resumeSending()
            is AdalightClient -> client.resumeSending()
            else -> {}
        }
    }

    override fun run() {
        startKeepAlive()
        connect()
    }

    private fun startKeepAlive() {
        mKeepAliveExecutor.scheduleWithFixedDelay({
            if (mStandbyPaused.get()) return@scheduleWithFixedDelay
            val client = mClient.get() ?: return@scheduleWithFixedDelay
            if (!client.isConnected()) return@scheduleWithFixedDelay
            // У WLED и Adalight своя логика keepalive.
            if (client !is HyperionFlatBuffers) return@scheduleWithFixedDelay

            val last = mLastSentFrame ?: return@scheduleWithFixedDelay
            try {
                synchronized(mSendLock) {
                    client.setImage(last.data, last.width, last.height, mPriority, FRAME_DURATION)
                }
                client.cleanReplies()
            } catch (e: IOException) {
                handleError(e)
            }
        }, KEEPALIVE_PERIOD_MS, KEEPALIVE_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun connect() {
        while (!isInterrupted) {
            try {
                val client = createClient()
                if (client != null && client.isConnected()) {
                    mClient.set(client)
                    mConnected.set(true)
                    AnalyticsHelper.logProtocolStarted(mContext, mConnectionType)
                    mCallback.onConnected()
                    Log.i(TAG, "Connected to $mConnectionType at $mHost:$mPort")
                    return
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: " + e.message)
                mCallback.onConnectionError(e.hashCode(), e.message)
            }
            if (!mReconnectEnabled.get()) return
            sleepSafe(mReconnectDelayMs)
        }
    }

    @Throws(IOException::class)
    private fun createClient(): HyperionClient? {
        // Порт должен попадать в диапазон 1-65535
        if (mPort < 1 || mPort > 65535) {
            throw IOException("Port out of range: $mPort (must be between 1 and 65535)")
        }

        val host = mHost
        return if ("wled".equals(mConnectionType, ignoreCase = true)) {
            WLEDClient(
                mContext,
                host,
                mPort,
                mPriority,
                mWledColorOrder,
                mWledProtocol,
                mSmoothingEnabled,
                mSmoothingPreset,
                mSettlingTime,
                mOutputDelayMs,
                mUpdateFrequency,
                mWledRgbw,
                mWledBrightness
            )
        } else if ("adalight".equals(mConnectionType, ignoreCase = true)) {
            AdalightClient(
                mContext, mPriority, mBaudRate, mAdalightProtocol,
                mSmoothingEnabled, mSmoothingPreset, mSettlingTime, mOutputDelayMs, mUpdateFrequency
            )
        } else {
            // По умолчанию — Hyperion
            HyperionFlatBuffers(host, mPort, mPriority)
        }
    }

    private fun handleError(e: IOException) {
        mCallback.onConnectionError(e.hashCode(), e.message)

        // Не пересоздавать клиент во сне: для Adalight новое открытие порта сбрасывает Arduino.
        if (mReconnectEnabled.get() && mConnected.get() && !mStandbyPaused.get()) {
            sleepSafe(mReconnectDelayMs)
            try {
                val newClient = createClient()

                if (newClient != null && newClient.isConnected()) {
                    mClient.set(newClient)
                }
            } catch (ignored: IOException) {
            }
        }
    }

    private fun sleepSafe(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            mReconnectEnabled.set(false)
            mConnected.set(false)
            currentThread().interrupt()
        }
    }

    interface HyperionThreadListener {
        fun sendFrame(data: ByteArray, width: Int, height: Int)
        fun clear()
        fun disconnect()
        fun sendStatus(isGrabbing: Boolean)
    }

    companion object {
        private const val TAG = "HyperionThread"
        private const val FRAME_DURATION = -1
        private const val SHUTDOWN_TIMEOUT_MS = 100
        private const val KEEPALIVE_PERIOD_MS = 1000L
    }
}

