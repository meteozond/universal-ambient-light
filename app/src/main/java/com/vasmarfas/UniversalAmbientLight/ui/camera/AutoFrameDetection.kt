package com.vasmarfas.UniversalAmbientLight.ui.camera

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.CameraEncoder
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.CameraFrameDetectionRun
import com.vasmarfas.UniversalAmbientLight.common.util.CameraFrameDetector
import com.vasmarfas.UniversalAmbientLight.common.util.CameraGeometry
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private const val TAG = "AutoFrameDetection"

/**
 * Запас сверх окна замеров, после которого кнопка перестаёт ждать результат. Покрывает
 * открытие камеры до первого кадра (до полутора секунд на старых устройствах) и ответ
 * сервиса.
 */
private const val DETECT_GRACE_MS = 3000L

/**
 * Состояние кнопки автоподстройки углов. [analyzer] непустой, только пока идёт локальный
 * поиск, — вызывающий код отдаёт его своему превью камеры.
 */
internal class AutoFrameDetectionHandle(
    val analyzer: ImageAnalysis.Analyzer?,
    private val detectingState: MutableState<Boolean>,
    private val onStart: () -> Unit,
) {
    val detecting: Boolean get() = detectingState.value

    fun start() {
        if (!detectingState.value) onStart()
    }
}

/**
 * Общая механика кнопки «Найти телевизор» для экрана настройки углов и главного экрана.
 *
 * Камера в один момент времени принадлежит кому-то одному, поэтому путей два. Пока захват
 * идёт, поиск запускается внутри сервиса ([ScreenGrabberService.ACTION_DETECT_FRAME]), и
 * результат возвращается через настройку углов — здесь она слушается, чтобы погасить
 * индикатор и отдать углы. Пока камера свободна, кадры разбираются собственным
 * [ImageAnalysis] рядом с превью.
 *
 * [onResult] вызывается на главном потоке ровно один раз на запуск: с углами (TL, TR, BR,
 * BL, нормализованные 0..1) либо с null и кодом причины, когда экран не нашёлся.
 */
@Composable
internal fun rememberAutoFrameDetection(
    onResult: (FloatArray?, CameraFrameDetector.Code) -> Unit,
): AutoFrameDetectionHandle {
    val context = LocalContext.current
    val detecting = remember { mutableStateOf(false) }
    var useService by remember { mutableStateOf(false) }
    val detectionRun = remember { CameraFrameDetectionRun() }
    // Окно замеров открывает первый кадр анализа, а не нажатие кнопки: между ними лежит
    // открытие камеры, и открытое заранее окно потеряло бы на нём свою первую половину.
    val pendingStart = remember { AtomicBoolean(false) }
    val grid = remember { IntArray(detectionRun.cellCount) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentOnResult by rememberUpdatedState(onResult)

    fun finish(found: FloatArray?, code: CameraFrameDetector.Code) {
        if (!detecting.value) return
        detecting.value = false
        currentOnResult(found, code)
    }

    val analyzer = remember(detecting.value, useService) {
        if (!detecting.value || useService) {
            null
        } else {
            ImageAnalysis.Analyzer { proxy ->
                try {
                    if (pendingStart.compareAndSet(true, false)) {
                        detectionRun.start(System.currentTimeMillis())
                    }
                    val plane = proxy.planes[0]
                    CameraFrameDetectionRun.sampleGrid(
                        plane.buffer, plane.rowStride, proxy.width, proxy.height,
                        detectionRun.cols, detectionRun.rows, grid
                    )
                    val rotation = proxy.imageInfo.rotationDegrees
                    val detection = detectionRun.addFrame(grid, System.currentTimeMillis())
                    if (detection != null) {
                        val display = detectedCorners(detection, rotation)
                        mainHandler.post { finish(display, detection.code) }
                    }
                } catch (e: Exception) {
                    // Кадр мог прийти в неожиданном виде — поиск не должен ронять экран
                    Log.w(TAG, "Frame detection failed", e)
                    mainHandler.post { finish(null, CameraFrameDetector.Code.NO_FRAMES) }
                } finally {
                    proxy.close()
                }
            }
        }
    }

    // Путь через сервис: найденные углы он пишет в настройку углов, причину отказа — в
    // отдельный ключ; отсюда слушаем оба
    val cornersKey = stringResource(R.string.pref_key_camera_corners)
    val resultKey = stringResource(R.string.pref_key_camera_detect_result)
    DisposableEffect(cornersKey, resultKey) {
        val store = Preferences.defaultSharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (!detecting.value || !useService) return@OnSharedPreferenceChangeListener
            when (key) {
                cornersKey -> finish(
                    CameraEncoder.parseCornersString(changed.getString(key, null)),
                    CameraFrameDetector.Code.FOUND
                )

                resultKey -> finish(null, parseDetectCode(changed.getString(key, null)))
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Страховка: если кадры перестали приходить или сервис не ответил (например, захват
    // идёт не с камеры), кнопка не должна остаться в состоянии поиска навсегда.
    LaunchedEffect(detecting.value) {
        if (!detecting.value) return@LaunchedEffect
        delay(CameraFrameDetectionRun.DEFAULT_WINDOW_MS + DETECT_GRACE_MS)
        pendingStart.set(false)
        detectionRun.cancel()
        Log.i(TAG, "Auto frame detection timed out waiting for frames")
        finish(null, CameraFrameDetector.Code.NO_FRAMES)
    }

    return remember(analyzer) {
        AutoFrameDetectionHandle(analyzer, detecting) {
            useService = ScreenGrabberService.sInstanceRunning
            detecting.value = true
            if (useService) {
                val intent = Intent(context, ScreenGrabberService::class.java)
                intent.action = ScreenGrabberService.ACTION_DETECT_FRAME
                context.startService(intent)
            } else {
                pendingStart.set(true)
            }
        }
    }
}

/** Код из строки вида «NO_SCREEN:1723987654321», которую пишет сервис. */
private fun parseDetectCode(stored: String?): CameraFrameDetector.Code {
    val name = stored?.substringBefore(':') ?: return CameraFrameDetector.Code.NO_SCREEN
    return CameraFrameDetector.Code.entries.firstOrNull { it.name == name }
        ?: CameraFrameDetector.Code.NO_SCREEN
}

/** Что показать пользователю: у каждой причины отказа своя подсказка, что делать. */
internal fun CameraFrameDetector.Code.messageRes(): Int = when (this) {
    CameraFrameDetector.Code.TOO_DARK -> R.string.camera_auto_frame_too_dark
    CameraFrameDetector.Code.NO_SCREEN -> R.string.camera_auto_frame_no_screen
    CameraFrameDetector.Code.NO_FRAMES -> R.string.camera_auto_frame_no_frames
    CameraFrameDetector.Code.FOUND -> R.string.camera_auto_frame_not_found
}

/**
 * Углы найденного экрана в координатах интерфейса либо null, если детектор отказался.
 * Причина отказа уходит в лог — по ней видно, какая именно проверка не прошла.
 */
private fun detectedCorners(
    detection: CameraFrameDetector.Detection,
    rotation: Int,
): FloatArray? {
    val found = detection.corners
    if (found == null) {
        Log.i(TAG, "Auto frame detection gave up: ${detection.reason}")
        return null
    }
    val display = FloatArray(8)
    CameraGeometry.rawToDisplayCorners(found, display, rotation)
    Log.i(TAG, "Auto frame detection: corners=" + CameraEncoder.cornersToString(display))
    return display
}
