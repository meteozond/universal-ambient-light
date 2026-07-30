package com.vasmarfas.UniversalAmbientLight.common

/**
 * Источник кадров для [ScreenGrabberService]: захватывает экран или камеру и отдаёт
 * кадры слушателю HyperionThread. Сервис держит ровно один активный бэкенд и работает
 * только через этот интерфейс, не зная, каким способом снимается картинка.
 *
 * Способы захвата различаются доступностью на конкретной прошивке, поэтому реализации
 * подбираются с фолбэками; всё, что выходит за рамки общего контракта (например,
 * остановка без разрыва соединения), остаётся собственным методом реализации.
 */
interface CaptureBackend {
    fun isCapturing(): Boolean

    fun sendStatus()

    /** Гасит ленту, не разрывая соединение с контроллером. */
    fun clearLights()

    fun stopRecording()

    fun resumeRecording()

    fun setOrientation(orientation: Int)
}
