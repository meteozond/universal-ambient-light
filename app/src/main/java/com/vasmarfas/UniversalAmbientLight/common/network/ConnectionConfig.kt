package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Параметры подключения к контроллеру, собранные из настроек одним объектом.
 * Раньше те же значения уходили в [HyperionThread] семнадцатью позиционными
 * аргументами подряд — перепутать соседние Int или String было слишком легко.
 *
 * Значения по умолчанию повторяют прежние дефолты конструктора [HyperionThread].
 */
data class ConnectionConfig(
    val host: String,
    val port: Int,
    val priority: Int,
    val reconnect: Boolean,
    val reconnectDelaySeconds: Int,
    val connectionType: String,
    val baudRate: Int,
    val wledColorOrder: String,
    val wledProtocol: String = "ddp",
    val wledRgbw: Boolean = false,
    /** 0..255; 255 — отправлять цвета как есть. */
    val wledBrightness: Int = 255,
    val adalightProtocol: String = "ada",
    val smoothingEnabled: Boolean = true,
    val smoothingPreset: String = "balanced",
    val settlingTime: Int = 200,
    val outputDelayMs: Long = 80L,
    val updateFrequency: Int = 25,
)
