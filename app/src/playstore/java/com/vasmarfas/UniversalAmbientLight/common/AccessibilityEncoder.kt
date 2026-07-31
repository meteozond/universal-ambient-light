package com.vasmarfas.UniversalAmbientLight.common

import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions

/**
 * Заглушка для Google Play. Повторяет публичный интерфейс энкодера из флейвора full, чтобы
 * общий код компилировался, но здесь он не создаётся: AccessibilityCaptureService.getInstance()
 * в этом флейворе возвращает null, поэтому путь захвата с этим энкодером недостижим.
 */
@Suppress("UNUSED_PARAMETER")
class AccessibilityEncoder(
    service: AccessibilityCaptureService,
    listener: HyperionThread.HyperionThreadListener,
    screenWidth: Int,
    screenHeight: Int,
    options: AppOptions,
) : CaptureBackend {
    override fun isCapturing(): Boolean = false
    override fun sendStatus() {}
    override fun clearLights() {}
    override fun stopRecording() {}
    override fun resumeRecording() {}
    override fun setOrientation(orientation: Int) {}
}
