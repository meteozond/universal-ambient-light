package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.CameraGeometry
import com.vasmarfas.UniversalAmbientLight.common.util.CameraIdleDetector
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Захватывает кадры с камеры, применяет перспективную коррекцию (по 4 углам)
 * и отправляет RGB данные на контроллер подсветки — аналог ScreenEncoder для режима камеры.
 *
 * Предназначен для работы внутри foreground-сервиса без Activity.
 */
class CameraEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray, // 8 floats: tl_x, tl_y, tr_x, tr_y, br_x, br_y, bl_x, bl_y (normalized 0..1)
) : LifecycleOwner, CaptureBackend {

    // --- Lifecycle для CameraX ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var mRunning = false

    @Volatile
    private var mCapturing = false

    // Держим ссылку на свой use case, чтобы отвязывать только его и не трогать чужие
    private var imageAnalysisUseCase: ImageAnalysis? = null

    // Углы копируем, чтобы снаружи их нельзя было поменять
    private val mCorners = corners.copyOf()

    // Тайминги
    private val frameIntervalMs = 1000L / options.frameRate

    // Во сне достаточно замеров, чтобы заметить, что телевизор снова включился.
    private val idleFrameIntervalMs = max(frameIntervalMs, IDLE_FRAME_INTERVAL_MS)

    // Размеры выходного изображения
    private val outputWidth: Int
    private val outputHeight: Int

    // Переиспользуемые буферы
    private var srcBitmap: Bitmap? = null
    private var correctedBitmap: Bitmap? = null
    private var rgbBuffer: ByteArray? = null
    private val mBorderCropper = com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor()
    private var rgbaBytes: ByteArray? = null
    private var pixelInts: IntArray? = null
    private var outPixels: IntArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val perspectiveMatrix = Matrix()

    /** Приёмник перспективной коррекции — весь выходной bitmap. */
    private val outputRect: FloatArray

    // Углы, спроецированные в координаты сырого буфера; кэшируются под геометрию кадра.
    private val mappedCorners = FloatArray(8)
    private var mappedWidth = -1
    private var mappedHeight = -1
    private var mappedRotation = -1

    // --- Автоматический сон (issue #38) ---
    // Пересоздаётся при правке порогов пользователем; null, пока функция выключена.
    private var idleDetector: CameraIdleDetector? = null

    // Сетка яркости последнего обработанного кадра (в бодрствовании) или того кадра, на
    // котором уснули, плюс запасной буфер, которым эти два меняются местами.
    private var idleReference: IntArray? = null
    private var idleSamples: IntArray? = null

    // Область замеров внутри четырёхугольника ТВ: left, top, right, bottom в сырых координатах.
    private val idleBounds = IntArray(4)

    @Volatile
    private var idleState = CameraIdleDetector.State.AWAKE

    private var blackFrame: ByteArray? = null
    private var lastSentWidth = 0
    private var lastSentHeight = 0

    init {
        val q = if (options.captureQuality > 0) options.captureQuality else 128
        outputWidth = max(32, min(q, 512))
        outputHeight = max(32, (outputWidth * 9f / 16f).toInt())

        outputRect = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )

        if (DEBUG) Log.d(
            TAG,
            "CameraEncoder init: output=${outputWidth}x${outputHeight}, fps=${options.frameRate}"
        )
    }

    // ======================== Публичный API ========================

    fun start() {
        mainHandler.post {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindCamera()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get CameraProvider", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    override fun stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording")
        mRunning = false
        mCapturing = false

        mainHandler.post {
            try {
                // Отвязываем только свой ImageAnalysis: Preview активити может быть ещё привязан
                imageAnalysisUseCase?.let { cameraProvider?.unbind(it) }
                imageAnalysisUseCase = null
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
            cameraProvider = null

            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (e: Exception) {
                Log.w(TAG, "Lifecycle transition failed", e)
            }
        }

        cameraExecutor.shutdownNow()
        clearAndDisconnect()
    }

    fun stopRecordingNoDisconnect() {
        if (DEBUG) Log.i(TAG, "stopRecordingNoDisconnect")
        mRunning = false
        mCapturing = false

        mainHandler.post {
            // Отвязка CameraX и перевод жизненного цикла выполняются на пути остановки:
            // провайдер мог быть уже отвязан активити, и падать из-за этого нельзя.
            try {
                imageAnalysisUseCase?.let { cameraProvider?.unbind(it) }
                imageAnalysisUseCase = null
            } catch (_: Exception) {
            }
            cameraProvider = null
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (_: Exception) {
            }
        }

        clearLights()
    }

    override fun resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording")
        if (!mCapturing) {
            start()
        }
    }

    override fun isCapturing(): Boolean = mCapturing

    override fun sendStatus() {
        listener.sendStatus(mCapturing)
    }

    override fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
        }.start()
    }

    override fun setOrientation(orientation: Int) {
        // Поворот камеры учитывается автоматически через rotationDegrees в processFrame
    }

    // ======================== Привязка камеры ========================

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        // Отвязываем только свой предыдущий ImageAnalysis, а не всё подряд:
        // так у активити сохраняется её Preview.
        imageAnalysisUseCase?.let {
            try {
                provider.unbind(it)
            } catch (_: Exception) {
                // Use case мог быть отвязан раньше — важно лишь, что он не привязан сейчас.
            }
        }

        // Замена устаревшего setTargetResolution. Правило CLOSEST_HIGHER_THEN_LOWER —
        // то же, что подразумевал старый вызов; соотношение сторон CameraX по умолчанию
        // предпочитает 4:3, а 640x480 как раз 4:3, так что выбор камеры не меняется.
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(CAMERA_WIDTH, CAMERA_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysisUseCase = imageAnalysis

        var lastFrameTime = 0L
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!mRunning) {
                imageProxy.close()
                return@setAnalyzer
            }
            val now = System.currentTimeMillis()
            val interval = if (idleState == CameraIdleDetector.State.AWAKE) {
                frameIntervalMs
            } else {
                idleFrameIntervalMs
            }
            if (now - lastFrameTime >= interval) {
                lastFrameTime = now
                try {
                    processFrame(imageProxy)
                } catch (e: Exception) {
                    if (DEBUG) Log.w(TAG, "processFrame error", e)
                }
            }
            imageProxy.close()
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Перепривязка камеры начинает новую сессию: судить о ней по старым кадрам нельзя.
        idleDetector?.reset()
        idleReference = null
        idleState = CameraIdleDetector.State.AWAKE

        try {
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            mRunning = true
            mCapturing = true
            listener.sendStatus(true)
            Log.i(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    // ======================== Обработка кадра ========================

    private fun processFrame(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride

        // Проецируем заданные углы на сырой буфер (результат кэшируется по геометрии)
        val srcPts = mapCornersToRaw(width, height, rotation)

        // Автосон: пока телевизору нечего показать, всё, что ниже, пропускается
        if (!updateIdleState(buffer, rowStride)) return

        val totalBytes = rowStride * height
        var rgba = rgbaBytes
        if (rgba == null || rgba.size < totalBytes) {
            rgba = ByteArray(totalBytes)
            rgbaBytes = rgba
        }
        buffer.rewind()
        buffer.get(rgba, 0, min(totalBytes, buffer.remaining()))

        var src = srcBitmap
        if (src == null || src.width != width || src.height != height) {
            src?.recycle()
            src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            srcBitmap = src
        }

        val totalPixels = width * height
        var ints = pixelInts
        if (ints == null || ints.size < totalPixels) {
            ints = IntArray(totalPixels)
            pixelInts = ints
        }

        // CameraX отдаёт RGBA_8888: байты идут R, G, B, A
        // Android ждёт ARGB_8888 в int: 0xAARRGGBB
        for (y in 0 until height) {
            val rowOff = y * rowStride
            for (x in 0 until width) {
                val i = rowOff + x * 4
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                ints[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        src.setPixels(ints, 0, width, 0, 0, width, height)

        // Перспективная коррекция: srcPts → выходной прямоугольник
        perspectiveMatrix.setPolyToPoly(srcPts, 0, outputRect, 0, 4)

        var corrected = correctedBitmap
        if (corrected == null || corrected.width != outputWidth || corrected.height != outputHeight) {
            corrected?.recycle()
            corrected = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            correctedBitmap = corrected
        }

        val canvas = Canvas(corrected)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(src, perspectiveMatrix, paint)

        val pixels = outPixels ?: IntArray(outputWidth * outputHeight).also { outPixels = it }
        corrected.getPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val rgbSize = outputWidth * outputHeight * 3
        var rgb = rgbBuffer
        if (rgb == null || rgb.size < rgbSize) {
            rgb = ByteArray(rgbSize)
            rgbBuffer = rgb
        }

        var idx = 0
        for (pixel in pixels) {
            rgb[idx++] = ((pixel shr 16) and 0xFF).toByte() // R
            rgb[idx++] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgb[idx++] = (pixel and 0xFF).toByte()           // B
        }

        ColorProcessor.processRgbData(rgb, options)

        // Отправка кадра с необязательной обрезкой чёрных полос
        val cropped =
            mBorderCropper.applyForEncoder(rgb, outputWidth, outputHeight, options)
        lastSentWidth = cropped.width
        lastSentHeight = cropped.height
        listener.sendFrame(cropped.rgb, cropped.width, cropped.height)
    }

    // ======================== Геометрия ========================

    /**
     * Проецирует заданные углы (нормализованные, в координатах экрана) в координаты
     * сырого буфера. Возвращает переиспользуемый массив — его можно только читать.
     * Пересчитывается при смене геометрии кадра или поворота сенсора, заодно обновляя [idleBounds].
     */
    private fun mapCornersToRaw(width: Int, height: Int, rotation: Int): FloatArray {
        if (width == mappedWidth && height == mappedHeight && rotation == mappedRotation) {
            return mappedCorners
        }

        CameraGeometry.mapCornersToRaw(mCorners, mappedCorners, width, height, rotation)
        CameraGeometry.computeIdleBounds(
            mappedCorners,
            idleBounds,
            width,
            height,
            IDLE_ROI_INSET
        )

        mappedWidth = width
        mappedHeight = height
        mappedRotation = rotation
        return mappedCorners
    }

    // ======================== Автоматический сон ========================

    /**
     * Обновляет состояние автосна по текущему кадру.
     *
     * @return true, если кадр нужно обработать и отправить на ленту
     */
    private fun updateIdleState(buffer: ByteBuffer, rowStride: Int): Boolean {
        val detector = syncIdleDetector()
        if (detector == null) {
            idleState = CameraIdleDetector.State.AWAKE
            return true
        }

        val samples = idleSamples ?: IntArray(IDLE_SAMPLE_COUNT).also { idleSamples = it }
        val luma = sampleLuma(buffer, rowStride, samples)

        // Первый замер сессии сравнивать не с чем: сообщаем максимальное отклонение,
        // чтобы уснуть раньше двух увиденных кадров было нельзя.
        val reference = idleReference
        val deviation =
            if (reference == null) MAX_DEVIATION else CameraGeometry.meanDeviation(samples, reference)

        val previous = detector.state
        val current = detector.update(luma, deviation, System.currentTimeMillis())
        idleState = current

        if (current == CameraIdleDetector.State.AWAKE) {
            // Бодрствуем: сравниваем с предыдущим кадром. Массивы меняются местами, чтобы
            // ни один не пришлось выделять заново.
            idleReference = samples
            idleSamples = reference
        }
        // Во сне эталон заморожен на кадре засыпания: медленное затухание накапливается,
        // а не теряется в шуме между соседними кадрами. См. CameraIdleDetector.

        if (current != previous) onIdleStateChanged(previous, current)
        return current == CameraIdleDetector.State.AWAKE
    }

    /**
     * Возвращает детектор, соответствующий текущим настройкам: пересоздаёт его, если
     * пороги поменяли по ходу сессии, и отдаёт null, пока автосон выключен.
     */
    private fun syncIdleDetector(): CameraIdleDetector? {
        if (!options.cameraIdleEnabled) {
            val existing = idleDetector ?: return null
            if (existing.isAsleep) Log.i(TAG, "Auto-sleep turned off while asleep — resuming")
            idleDetector = null
            idleReference = null
            return null
        }

        // Сравниваем поле за полем, а не со свежим Config: в обычном случае (настройки
        // не менялись) это не выделяет памяти в потоке захвата.
        val timeoutMs = options.cameraIdleTimeoutSec * 1000L
        val existing = idleDetector
        if (existing != null &&
            existing.config.timeoutMs == timeoutMs &&
            existing.config.darkLevel == options.cameraIdleDarkLevel &&
            existing.config.motionLevel == options.cameraIdleMotionLevel &&
            existing.config.staticSleepEnabled == options.cameraIdleStaticSleep
        ) {
            return existing
        }

        // Пороги изменились (или это первый кадр): начинаем бодрствовать с новыми настройками.
        val config = CameraIdleDetector.Config(
            timeoutMs = timeoutMs,
            darkLevel = options.cameraIdleDarkLevel,
            motionLevel = options.cameraIdleMotionLevel,
            staticSleepEnabled = options.cameraIdleStaticSleep,
        )
        return CameraIdleDetector(config).also { idleDetector = it }
    }

    private fun onIdleStateChanged(
        from: CameraIdleDetector.State,
        to: CameraIdleDetector.State,
    ) {
        when (to) {
            CameraIdleDetector.State.SLEEP_DARK -> {
                Log.i(TAG, "Auto-sleep: screen blank, turning LEDs off")
                sendBlackFrame()
            }

            CameraIdleDetector.State.SLEEP_STATIC ->
                Log.i(TAG, "Auto-sleep: picture frozen, holding last colors")

            CameraIdleDetector.State.AWAKE ->
                Log.i(TAG, "Auto-sleep: woke up from $from")
        }
    }

    /**
     * Гасит ленту на время сна по чёрному экрану.
     *
     * Отправляем именно кадром, а не через [HyperionThread.HyperionThreadListener.clear]:
     * keepalive Hyperion повторяет последний *кадр* ([HyperionThread] держит его и после
     * очистки), поэтому погашенная лента через секунду загорелась бы снова. Чёрный кадр
     * остаётся чёрным, сколько бы keepalive любого протокола его ни повторял.
     */
    private fun sendBlackFrame() {
        val w = if (lastSentWidth > 0) lastSentWidth else outputWidth
        val h = if (lastSentHeight > 0) lastSentHeight else outputHeight
        val size = w * h * 3
        val black = blackFrame?.takeIf { it.size == size }
            ?: ByteArray(size).also { blackFrame = it }
        listener.sendFrame(black, w, h)
    }

    /**
     * Заполняет [out] разрежённой сеткой яркости по области [idleBounds] и возвращает среднее.
     * Читает буфер камеры напрямую: несколько сотен замеров на кадр вместо полной
     * конвертации, которая нужна пути потоковой отправки.
     */
    private fun sampleLuma(buffer: ByteBuffer, rowStride: Int, out: IntArray): Int {
        val left = idleBounds[0]
        val top = idleBounds[1]
        val spanX = (idleBounds[2] - left).toFloat()
        val spanY = (idleBounds[3] - top).toFloat()
        val limit = buffer.limit()

        var sum = 0
        var i = 0
        for (row in 0 until IDLE_SAMPLE_ROWS) {
            val y = top + (spanY * (row + 0.5f) / IDLE_SAMPLE_ROWS).toInt()
            val rowOffset = y * rowStride
            for (col in 0 until IDLE_SAMPLE_COLS) {
                val x = left + (spanX * (col + 0.5f) / IDLE_SAMPLE_COLS).toInt()
                val index = rowOffset + x * 4
                // На части устройств строки дополнены и выходят за конец буфера.
                val luma = if (index >= 0 && index + 2 < limit) {
                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF
                    (r * 77 + g * 150 + b * 29) shr 8
                } else {
                    0
                }
                out[i++] = luma
                sum += luma
            }
        }
        return sum / IDLE_SAMPLE_COUNT
    }

    // ======================== Вспомогательное ========================

    private fun clearAndDisconnect() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
            listener.disconnect()
        }.start()
    }

    companion object {
        private const val TAG = "CameraEncoder"
        private const val DEBUG = false
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L

        // Замеры для автосна. Сетки 24x18 по площади телевизора хватает, чтобы отличить
        // светящуюся панель от тёмной и заметить смену картинки, а работы это требует
        // примерно в тысячу раз меньше, чем полный проход по пикселям.
        private const val IDLE_SAMPLE_COLS = 24
        private const val IDLE_SAMPLE_ROWS = 18
        private const val IDLE_SAMPLE_COUNT = IDLE_SAMPLE_COLS * IDLE_SAMPLE_ROWS

        /** Доля, отрезаемая с каждой стороны четырёхугольника ТВ перед замерами. */
        private const val IDLE_ROI_INSET = 0.12f

        /** Частота замеров во сне: 5 Гц, так что просыпание укладывается в доли секунды. */
        private const val IDLE_FRAME_INTERVAL_MS = 200L

        private const val MAX_DEVIATION = 255

        /** Разбор строки углов "x1,y1,x2,y2,x3,y3,x4,y4" → FloatArray(8) */
        fun parseCornersString(str: String?): FloatArray {
            if (str != null) {
                val parts = str.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size == 8) return parts.toFloatArray()
            }
            // По умолчанию отступ 10% от краёв
            return floatArrayOf(
                0.1f, 0.1f,   // top-left
                0.9f, 0.1f,   // top-right
                0.9f, 0.9f,   // bottom-right
                0.1f, 0.9f    // bottom-left
            )
        }

        fun cornersToString(corners: FloatArray): String {
            return corners.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
        }

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
