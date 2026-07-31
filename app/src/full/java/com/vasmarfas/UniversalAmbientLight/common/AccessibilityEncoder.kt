package com.vasmarfas.UniversalAmbientLight.common

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import kotlin.math.max

class AccessibilityEncoder(
    private val mService: AccessibilityCaptureService,
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mScreenWidth: Int,
    private val mScreenHeight: Int,
    private val mOptions: AppOptions,
) : CaptureBackend {
    @Volatile
    private var mRunning = false
    @Volatile
    private var mCapturing = false

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null

    // Скриншоты через доступность тяжёлые, поэтому ограничиваем частоту кадров
    private val mFrameIntervalMs: Long = max(200L, 1000L / mOptions.frameRate)

    private var mRgbBuffer: ByteArray? = null
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()
    private var mPixelBuffer: IntArray? = null

    private val mCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            val start = System.currentTimeMillis()

            mService.takeScreenshot { bitmap ->
                if (bitmap != null) {
                    processBitmap(bitmap)
                    bitmap.recycle()
                }

                val elapsed = System.currentTimeMillis() - start
                if (mRunning) {
                    val delay = max(50L, mFrameIntervalMs - elapsed)
                    mHandler?.postDelayed(this, delay)
                }
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
        // No-op
    }

    private fun startCapture() {
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        mThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        mHandler = handler
        mRunning = true
        mCapturing = true
        handler.post(mCaptureRunnable)
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (mOptions.useAverageColor) {
            sendAvgColor(bitmap)
        } else {
            sendPixelData(bitmap)
        }
    }

    private fun sendPixelData(bitmap: Bitmap) {
        // При необходимости уменьшаем по captureQuality
        var bmp = bitmap
        val w = bmp.width
        val h = bmp.height

        // Скриншоты доступности приходят в полном разрешении, поэтому уменьшаем их простым
        // масштабированием, если картинка заметно крупнее выбранного качества.
        val targetDim = mOptions.captureQuality.coerceAtLeast(64)
        if (w > targetDim && h > targetDim) {
            val scale = targetDim.toFloat() / max(w, h).toFloat()
            val newW = (w * scale).toInt()
            val newH = (h * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
            // Входной bitmap здесь наш: служба отдаёт копию, поэтому освобождать его можно.
            bmp = scaled
        }

        val fw = bmp.width
        val fh = bmp.height
        val pixelCount = fw * fh

        var pixelBuffer = mPixelBuffer
        if (pixelBuffer == null || pixelBuffer.size < pixelCount) {
            pixelBuffer = IntArray(pixelCount)
            mPixelBuffer = pixelBuffer
        }
        bmp.getPixels(pixelBuffer, 0, fw, 0, 0, fw, fh)

        val rgbSize = pixelCount * 3
        var rgbBuffer = mRgbBuffer
        if (rgbBuffer == null || rgbBuffer.size < rgbSize) {
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
        val cropped = mBorderCropper.applyForEncoder(rgbBuffer, fw, fh, mOptions)
        mListener.sendFrame(cropped.rgb, cropped.width, cropped.height)

        if (bmp != bitmap) {
            bmp.recycle()
        }
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
        private const val TAG = "AccessibilityEncoder"
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
    }
}
