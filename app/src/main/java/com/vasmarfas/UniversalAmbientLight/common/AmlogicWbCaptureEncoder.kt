package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Захват экрана на Amlogic через обратную запись дисплейного контроллера (write back).
 *
 * Контроллер отдаёт в память уже собранный кадр — тот самый, что уходит на телевизор.
 * Точка съёма стоит после смешивания слоёв, поэтому в кадре сразу и видео, и экранное
 * меню. На этих приставках иначе никак: видеослой идёт мимо композитора, и в обычном
 * снимке экрана на его месте пустота — во время фильма подсветке нечего показывать.
 *
 * Уменьшает картинку сам VDIN, аппаратно, так что процессор почти не нагружается.
 *
 * Требуется:
 *   - root-доступ
 *   - /dev/video12 — узел amlvideo2, к которому ведёт цепочка кадров от VDIN
 *
 * Бинарник (aml_wbcap_server) запускается от root через su и пишет сырые RGB-кадры в
 * stdout. Формат: на кадр приходится 4 байта ширины LE + 4 байта высоты LE + (w*h*3)
 * байт RGB.
 */
class AmlogicWbCaptureEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val onFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile
    private var mRunning = false

    @Volatile
    private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    @Volatile
    private var mProcess: java.lang.Process? = null

    private var mCaptureWidth = 0
    private var mCaptureHeight = 0

    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    init {
        calculateCaptureDimensions()
        // Медленную работу (распаковку APK, запуск su) уводим в рабочий поток, чтобы
        // конструктор, вызываемый из ScreenGrabberService на главном потоке, возвращался сразу.
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        mThread = thread
        val handler = Handler(thread.looper)
        mHandler = handler
        mRunning = true
        mCapturing = true
        handler.post { startCaptureOnWorker() }
    }

    private fun calculateCaptureDimensions() {
        // Для выборки цветов хватает мелкой картинки, а уменьшает её сам VDIN.
        // Нулевые метрики (часть прошивок на старте) заменяем на 16:9.
        val aspectRatio = if (mScreenWidth > 0 && mScreenHeight > 0) {
            mScreenWidth.toFloat() / mScreenHeight.toFloat()
        } else {
            16f / 9f
        }
        mCaptureWidth = CAPTURE_WIDTH
        mCaptureHeight = (mCaptureWidth / aspectRatio).roundToInt()
        // Приводим к чётному: цветовая плоскость NV21 идёт вдвое реже
        mCaptureWidth = (mCaptureWidth + 1) and 0x7FFFFFFE.toInt()
        mCaptureHeight = (mCaptureHeight + 1) and 0x7FFFFFFE.toInt()
    }

    private fun extractBinary(): File? {
        val destFile = File(mContext.filesDir, BINARY_NAME)

        // Распаковываем заново, если APK новее сохранённого бинарника (то есть после обновления)
        val apkLastModified = File(mContext.applicationInfo.sourceDir).lastModified()
        if (destFile.exists() && destFile.canExecute() && destFile.lastModified() >= apkLastModified) {
            return destFile
        }

        // Сначала пробуем nativeLibraryDir на диске
        val nativeLibDir = mContext.applicationInfo.nativeLibraryDir
        val diskFile = File(nativeLibDir, "lib${BINARY_NAME}.so")
        if (diskFile.exists()) {
            try {
                diskFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
                Log.i(TAG, "Extracted binary from disk: ${diskFile.absolutePath}")
                return destFile
            } catch (e: IOException) {
                Log.w(TAG, "Failed to copy from disk, trying APK", e)
            }
        }

        // Достаём из zip-архива APK
        try {
            val apkPath = mContext.applicationInfo.sourceDir
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
            val entryName = "lib/$abi/lib${BINARY_NAME}.so"

            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    Log.e(TAG, "Entry $entryName not found in APK")
                    return null
                }
                zip.getInputStream(entry).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            destFile.setExecutable(true, false)
            Log.i(TAG, "Extracted binary from APK to ${destFile.absolutePath}")
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary from APK", e)
            return null
        }
    }

    private fun startCaptureOnWorker() {
        val binary = extractBinary()
        if (binary == null) {
            mCapturing = false
            mRunning = false
            onFatalError?.invoke("Amlogic Write-back: binary not found")
            return
        }

        // Кадр приходится брать полным (см. сервер), а читать его дорого,
        // поэтому частоту здесь придерживаем.
        val fps = max(1, minOf(mOptions.frameRate, MAX_FPS))
        val cmd = arrayOf(
            "su", "-c",
            "${binary.absolutePath} $mCaptureWidth $mCaptureHeight $fps $DEVICE_PATH $VDIN_INDEX"
        )

        try {
            mProcess = Runtime.getRuntime().exec(cmd)
            Log.i(TAG, "Started capture: ${mCaptureWidth}x${mCaptureHeight} @ ${fps}fps")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start capture process", e)
            mCapturing = false
            mRunning = false
            onFatalError?.invoke("Amlogic Write-back: failed to start process")
            return
        }

        if (!mRunning) {
            // stopRecording сработал раньше, чем запустился процесс.
            try {
                mProcess?.destroy()
            } catch (_: Exception) {
                // Гонка со stopRecording: процесс мог быть уже убит там.
            }
            mProcess = null
            return
        }
        readFrameLoop()
    }

    private fun restartCaptureOnWorker() {
        if (mProcess != null) return
        startCaptureOnWorker()
    }

    private fun readFrameLoop() {
        val process = mProcess ?: return
        val input = DataInputStream(process.inputStream)
        val headerBuf = ByteArray(8)

        // stderr вычитываем параллельно: разговорчивый бинарник иначе забил бы буфер
        // трубы (64 КБ) и завис на записи — захват встал бы без единой строки в логе
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) Log.w(TAG, "[capture] $line")
                }
            } catch (_: Exception) {
                // Труба закрывается вместе с процессом — это штатный конец чтения.
            }
        }, "aml-wbcap-stderr").apply { isDaemon = true }.start()

        try {
            while (mRunning) {
                input.readFully(headerBuf)
                val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val w = bb.getInt()
                val h = bb.getInt()

                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
                    Log.e(TAG, "Invalid frame dimensions: ${w}x${h}")
                    break
                }

                val rgb = ByteArray(w * h * 3)
                input.readFully(rgb)

                if (!mRunning) break

                ColorProcessor.processRgbData(rgb, mOptions)
                val cropped = mBorderCropper.applyForEncoder(rgb, w, h, mOptions)
                mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
            }
        } catch (e: IOException) {
            // Причины сбоя процесс печатает в stderr — их уже вывел поток aml-wbcap-stderr
            if (mRunning) Log.e(TAG, "Read error", e)
        } catch (e: Exception) {
            if (mRunning) Log.e(TAG, "Unexpected error", e)
        }

        // Цикл закончился: процесс либо умер сам, либо его убил stopInternal. Останки
        // подчищаем здесь, иначе mProcess != null навсегда блокировал бы
        // restartCaptureOnWorker, а mRunning=true — resumeRecording, и после сбоя
        // бинарника захват не оживал бы до перезапуска сервиса.
        try {
            process.destroy()
        } catch (_: Exception) {
            // Процесс мог закончиться сам, раз чтение оборвалось.
        }
        if (mProcess === process) mProcess = null
        mCapturing = false
        mRunning = false
    }

    override fun isCapturing(): Boolean = mCapturing

    override fun sendStatus() {
        mListener.sendStatus(mCapturing)
    }

    override fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                Thread.sleep(CLEAR_DELAY_MS)
                mListener.clear()
            }
        }.start()
    }

    override fun stopRecording() {
        stopInternal(disconnect = true)
    }

    override fun resumeRecording() {
        if (mRunning) return
        var handler = mHandler
        if (handler == null) {
            val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            mThread = thread
            handler = Handler(thread.looper)
            mHandler = handler
        }
        mRunning = true
        mCapturing = true
        handler.post { restartCaptureOnWorker() }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun setOrientation(orientation: Int) {
        // Кадр снимается с выхода контроллера, ориентация там уже учтена
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false

        mProcess?.let { proc ->
            // Остановка: root-процесс мог уже умереть сам, закрываем и убиваем best-effort.
            try {
                proc.outputStream.close()
            } catch (_: Exception) {
                // Труба уже закрыта — процесс на той стороне закончился.
            }
            try {
                proc.destroy()
            } catch (_: Exception) {
                // Процесс мог быть убит раньше, здесь это уже неважно.
            }
        }
        mProcess = null

        mHandler?.removeCallbacksAndMessages(null)
        mThread?.quitSafely()
        mThread = null
        mHandler = null

        if (disconnect) {
            Thread {
                repeat(CLEAR_FRAMES) {
                    Thread.sleep(CLEAR_DELAY_MS)
                    mListener.clear()
                }
                mListener.disconnect()
            }.start()
        } else {
            clearLights()
        }
    }

    companion object {
        private const val TAG = "AmlogicWbCaptureEncoder"
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L
        private const val BINARY_NAME = "aml_wbcap_server"

        /** Узел amlvideo2, к которому ведёт цепочка кадров от VDIN. */
        private const val DEVICE_PATH = "/dev/video12"
        private const val VDIN_INDEX = 1

        /** Для выборки цветов хватает и этого, а уменьшает картинку сам VDIN. */
        private const val CAPTURE_WIDTH = 96

        /** Выше этого захват съедает слишком много: кадр читается целиком. */
        private const val MAX_FPS = 20
        private const val MAX_SIDE = 1920
        private const val AVAILABILITY_CHECK_TIMEOUT_SEC = 3L

        @Volatile
        private var sCachedAvailable: Boolean? = null
        private val sCheckInProgress = AtomicBoolean(false)

        /**
         * Никогда не блокирует вызывающий поток. Первый вызов запускает проверку `su` в
         * фоновом потоке и возвращает false; последующие отдают уже готовый результат.
         */
        fun isAvailable(): Boolean {
            sCachedAvailable?.let { return it }
            // Первый вызывающий запускает фоновую проверку, остальные до её конца видят false.
            if (sCheckInProgress.compareAndSet(false, true)) {
                Thread {
                    try {
                        sCachedAvailable = checkAvailableBlocking()
                    } finally {
                        sCheckInProgress.set(false)
                    }
                }.apply {
                    isDaemon = true
                    name = "AmlWbAvailCheck"
                }.start()
            }
            return false
        }

        private fun checkAvailableBlocking(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "test -c $DEVICE_PATH && test -d /sys/class/vdin/vdin$VDIN_INDEX && echo OK"
                    )
                )
                val completed = process.waitFor(AVAILABILITY_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) {
                    try {
                        process.destroy()
                    } catch (_: Exception) {
                        // Проверка доступности не удалась по таймауту; процесс мог
                        // завершиться сам, пока мы его убивали.
                    }
                    Log.w(
                        TAG,
                        "Availability probe timed out after ${AVAILABILITY_CHECK_TIMEOUT_SEC}s"
                    )
                    return false
                }
                val result = process.inputStream.bufferedReader().readText().trim()
                result == "OK"
            } catch (e: Exception) {
                false
            }
        }
    }
}
