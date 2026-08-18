package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Захват экрана системной командой `screencap` через shell.
 *
 * Запасной путь для устройств, где MediaProjection заблокирован на уровне прошивки
 * (например, телевизоры Яндекса на YaOS). Бинарник `screencap` есть на всех Android и
 * разрешения MediaProjection не требует.
 *
 * Ограничения по сравнению со ScreenEncoder:
 * - ниже частота кадров (обычно 5–7 в секунду)
 * - выше нагрузка на процессор (на каждый кадр — декодирование PNG через BitmapFactory)
 * - нет VirtualDisplay, поэтому качество ограничено шагами inSampleSize
 */
class ScreencapEncoder(
    private val mContext: Context,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
    private val mUseRoot: Boolean = false,
    private val onFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    // screencap медленный: быстрее 10 кадров в секунду не пытаемся, что бы ни стояло в настройках
    private val mFrameIntervalMs: Long = max(100L, 1000L / mOptions.frameRate)

    private var mRgbBuffer: ByteArray? = null
    private var mPixelBuffer: IntArray? = null
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()

    private var mUseRawScreencap = false
    private var mUseFileMode = false
    private var mFailCount = 0

    // Процесс текущего кадра: чтение его stdout не ограничено таймаутом, и зависший
    // screencap держал бы поток захвата вечно — stopInternal убивает процесс напрямую.
    @Volatile
    private var mCurrentProcess: java.lang.Process? = null

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            val start = System.currentTimeMillis()
            captureFrame()
            val elapsed = System.currentTimeMillis() - start
            if (mRunning) {
                val delay = max(50L, mFrameIntervalMs - elapsed)
                mHandler?.postDelayed(this, delay)
            }
        }
    }

    init {
        startCapture()
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

    fun stopRecordingKeepConnection() {
        stopInternal(disconnect = false)
    }

    override fun resumeRecording() {
        if (!mRunning) {
            var handler = mHandler
            if (handler == null) {
                val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
                mThread = thread
                thread.start()
                handler = Handler(thread.looper)
                mHandler = handler
            }
            mRunning = true
            mCapturing = true
            handler.post(mCaptureRunnable)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun setOrientation(orientation: Int) {
        // screencap снимает то, что сейчас на экране, вместе с поворотом — делать нечего
    }

    private fun startCapture() {
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        mThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        mHandler = handler
        mRunning = true
        mCapturing = true
        // Уборка сотен PNG после упавшей сессии — файловый I/O, конструктор зовут с
        // главного потока сервиса
        handler.post { cleanupStaleCaptureFiles() }
        handler.post(mCaptureRunnable)
    }

    private fun cleanupStaleCaptureFiles() {
        // В файловом режиме кадры пишутся в externalCacheDir как cap_*.png. Если прошлая
        // сессия упала посреди захвата, они копятся и рано или поздно забьют раздел.
        try {
            val dir = mContext.externalCacheDir ?: mContext.cacheDir ?: return
            dir.listFiles { f -> f.name.startsWith("cap_") && f.name.endsWith(".png") }
                ?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
            // Уборка мусора от прошлых сессий: недоступный кеш не повод не начинать захват.
        }
    }

    private fun captureFrame() {
        var process: java.lang.Process? = null
        try {
            if (!mRunning) return
            var bitmap: Bitmap? = null

            if (mUseFileMode) {
                // Захват через файл (запасной путь, когда SELinux блокирует stdout)
                val cacheDir = mContext.externalCacheDir ?: mContext.cacheDir
                val file = File(cacheDir, "cap_${System.currentTimeMillis()}.png")

                val cmd = if (mUseRoot) {
                    arrayOf("su", "-c", "screencap -p ${file.absolutePath}")
                } else {
                    arrayOf("screencap", "-p", file.absolutePath)
                }

                process = Runtime.getRuntime().exec(cmd)
                mCurrentProcess = process
                if (!process.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "screencap (file mode) timed out after ${CAPTURE_TIMEOUT_MS}ms")
                    try {
                        process.destroyForcibly()
                    } catch (_: Exception) {
                        // Таймаут уже залогирован; процесс мог завершиться сам между
                        // проверкой и попыткой его убить.
                    }
                    runCatching { file.delete() }
                    mFailCount++
                    return
                }

                if (file.exists() && file.length() > 0) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize() }
                    bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                    file.delete()
                    // Сбрасывать счётчик можно только по декодированному кадру: файл,
                    // который не разобрался, — такой же провал, и без инкремента здесь
                    // эскалация на onFatalError никогда бы не наступила
                    if (bitmap != null) mFailCount = 0 else mFailCount++
                } else {
                    val err = process.errorStream.bufferedReader().use { it.readText() }
                    Log.w(TAG, "File capture failed (root=$mUseRoot). Stderr: $err")
                    mFailCount++
                }
            } else {
                // Захват через stdout
                val baseCmd = if (mUseRoot) "su -c screencap" else "screencap"
                val cmd = if (mUseRawScreencap) baseCmd else "$baseCmd -p"

                process = Runtime.getRuntime().exec(cmd)
                mCurrentProcess = process

                val inputStream = process.inputStream
                val buffer = ByteArrayOutputStream()
                val temp = ByteArray(8192)
                var read: Int
                while (inputStream.read(temp).also { read = it } != -1) {
                    buffer.write(temp, 0, read)
                }
                val data = buffer.toByteArray()

                val err = process.errorStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "screencap (stdout mode) timed out after ${CAPTURE_TIMEOUT_MS}ms")
                    try {
                        process.destroyForcibly()
                    } catch (_: Exception) {
                        // Таймаут уже залогирован; процесс мог завершиться сам между
                        // проверкой и попыткой его убить.
                    }
                    mFailCount++
                    return
                }

                if (data.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize() }
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts)

                    if (bitmap == null) {
                        // Проверяем, не пришли ли сырые данные вместо PNG
                        if (data.size > 12) {
                            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            val w = bb.int
                            val h = bb.int
                            val f = bb.int
                            val pixelSize = 4
                            val expectedDataSize = w * h * pixelSize

                            if (w in 100..4096 && h in 100..4096 && (data.size - 12) >= expectedDataSize) {
                                if (mUseRawScreencap) Log.d(TAG, "Raw frame: ${w}x${h}, format=$f")
                                else Log.w(
                                    TAG,
                                    "Detected raw frame despite -p flag: ${w}x${h}, format=$f"
                                )

                                try {
                                    val rawBitmap =
                                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val pixelBuf = ByteBuffer.wrap(data, 12, expectedDataSize)
                                    rawBitmap.copyPixelsFromBuffer(pixelBuf)

                                    if (mOptions.captureQuality < h) {
                                        val scale = mOptions.captureQuality.toFloat() / h.toFloat()
                                        val newW = (w * scale).toInt()
                                        val newH = (h * scale).toInt()
                                        val scaled =
                                            Bitmap.createScaledBitmap(rawBitmap, newW, newH, true)
                                        rawBitmap.recycle()
                                        bitmap = scaled
                                    } else {
                                        bitmap = rawBitmap
                                    }
                                    mFailCount = 0
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse raw bitmap: ${e.message}")
                                }
                            }
                        }

                        if (bitmap == null) {
                            mFailCount++
                            // Неожиданный формат — пишем в лог
                            val headerHex = data.take(16).joinToString(" ") { "%02X".format(it) }
                            Log.e(
                                TAG,
                                "decodeByteArray failed (root=$mUseRoot). Header: $headerHex"
                            )
                        }
                    } else {
                        mFailCount = 0
                    }
                } else {
                    Log.w(TAG, "screencap stdout empty (root=$mUseRoot). Stderr: $err")
                    mFailCount++
                }
            }

            if (bitmap != null) {
                processBitmap(bitmap)
                bitmap.recycle()
            } else {
                // Стратегия последовательного перебора запасных режимов
                if (mFailCount > 3) {
                    if (!mUseRawScreencap && !mUseFileMode) {
                        mUseRawScreencap = true
                        Log.w(TAG, "Switching to RAW stdout mode")
                        mFailCount = 0
                    } else if (mUseRawScreencap && !mUseFileMode) {
                        mUseFileMode = true
                        Log.w(TAG, "Switching to FILE mode")
                        mFailCount = 0
                    } else if (mUseFileMode && mFailCount > 8) {
                        // Все три режима исчерпаны — screencap на этом устройстве полностью закрыт
                        Log.e(
                            TAG,
                            "All screencap modes failed (root=$mUseRoot). Device likely blocks screencap via SELinux."
                        )
                        mRunning = false
                        mCapturing = false
                        val msg = if (mUseRoot)
                            "Screencap (Root) is blocked by the device. Try ADB Localhost method."
                        else
                            "Screencap (Shell) is blocked by the device. Try ADB Localhost or Accessibility method."
                        onFatalError?.invoke(msg)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "screencap error: ${e.message}")
            mFailCount++
        } finally {
            mCurrentProcess = null
            process?.destroy()
        }
    }

    /**
     * Подбирает наибольшую степень двойки для inSampleSize, при которой ширина после
     * декодирования всё ещё не меньше [AppOptions.captureQuality] пикселей.
     */
    private fun computeSampleSize(): Int {
        val targetWidth = mOptions.captureQuality.coerceIn(64, 512)
        var sampleSize = 1
        var width = mScreenWidth
        while (width / 2 >= targetWidth) {
            sampleSize = sampleSize shl 1
            width = width shr 1
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (mOptions.useAverageColor) {
            sendAvgColor(bitmap)
        } else {
            sendPixelData(bitmap)
        }
    }

    private fun sendPixelData(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixelCount = w * h

        var pixelBuffer = mPixelBuffer
        if (pixelBuffer == null || pixelBuffer.size < pixelCount) {
            pixelBuffer = IntArray(pixelCount)
            mPixelBuffer = pixelBuffer
        }
        bitmap.getPixels(pixelBuffer, 0, w, 0, 0, w, h)

        // Размер сверяем точно: Hyperion сериализует весь массив, и буфер длиннее кадра
        // ушёл бы на сервер с хвостом от прежнего разрешения
        val rgbSize = pixelCount * 3
        var rgbBuffer = mRgbBuffer
        if (rgbBuffer == null || rgbBuffer.size != rgbSize) {
            rgbBuffer = ByteArray(rgbSize)
            mRgbBuffer = rgbBuffer
        }

        var dst = 0
        for (i in 0 until pixelCount) {
            val pixel = pixelBuffer[i]
            rgbBuffer[dst++] = ((pixel shr 16) and 0xFF).toByte()
            rgbBuffer[dst++] = ((pixel shr 8) and 0xFF).toByte()
            rgbBuffer[dst++] = (pixel and 0xFF).toByte()
        }

        ColorProcessor.processRgbData(rgbBuffer, mOptions)
        val cropped = mBorderCropper.applyForEncoder(rgbBuffer, w, h, mOptions)
        mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)
    }

    private fun sendAvgColor(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
                x += 4
            }
            y += 4
        }
        if (count > 0) {
            val (rOut, gOut, bOut) = ColorProcessor.processColor(
                (r / count).toInt(), (g / count).toInt(), (b / count).toInt(),
                mOptions.brightness, mOptions.contrast,
                mOptions.blackLevel, mOptions.whiteLevel, mOptions.saturation,
                mOptions.brightnessR, mOptions.brightnessG, mOptions.brightnessB,
                mOptions.gammaR, mOptions.gammaG, mOptions.gammaB
            )
            mListener.sendFrame(byteArrayOf(rOut.toByte(), gOut.toByte(), bOut.toByte()), 1, 1)
        }
    }

    private fun stopInternal(disconnect: Boolean) {
        mRunning = false
        mCapturing = false
        // Зависший screencap держит поток захвата в чтении stdout — убиваем процесс,
        // чтобы quitSafely ниже был не пустым пожеланием
        try {
            mCurrentProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        mHandler?.removeCallbacksAndMessages(null)
        mThread?.quitSafely()
        mThread = null
        mHandler = null
        mRgbBuffer = null
        mPixelBuffer = null
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
        private const val TAG = "ScreencapEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
        private const val CAPTURE_TIMEOUT_MS = 2000L

        /**
         * Быстрая проверка, доступен ли бинарник screencap из нашего процесса.
         * Запускать в фоновом потоке до того, как показывать пользователю этот вариант.
         */
        fun isAvailable(): Boolean {
            return try {
                val process: java.lang.Process = Runtime.getRuntime().exec("screencap -p")
                val firstByte = process.inputStream.read()
                process.destroy()
                firstByte != -1
            } catch (e: Exception) {
                false
            }
        }
    }
}
