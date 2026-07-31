package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService

/**
 * Сопряжение беспроводного ADB «в одну кнопку» для Android 11+.
 *
 * Считывает шестизначный код с системного экрана «Подключение с помощью кода» через службу
 * доступности, находит порт сопряжения через mDNS и сопрягается в фоне — пользователю не
 * приходится ни уходить с этого экрана (уход отменил бы сопряжение), ни вводить код руками.
 *
 *
 * Блокирующий вызов, из главного потока не запускать.
 */
object AdbAutoPair {
    private const val TAG = "AdbAutoPair"

    sealed class Result {
        object Paired : Result()
        object NeedsAccessibility : Result()
        object Timeout : Result()
        data class Failed(val message: String) : Result()
    }

    fun run(context: Context, timeoutMs: Long = 90_000L): Result {
        if (!AccessibilityCaptureService.isAvailable()) return Result.NeedsAccessibility

        AccessibilityCaptureService.startPairingWatch()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var host: String? = null
            var port = -1

            while (System.currentTimeMillis() < deadline) {
                // Опрашиваем текущий экран настроек: читаем код либо шагаем вперёд по
                // «Отладка по Wi-Fi» → «Подключение с помощью кода» (статичные экраны событий не шлют).
                AccessibilityCaptureService.scanActiveWindow()

                val code = AccessibilityCaptureService.detectedPairingCode()

                if (port <= 0) {
                    val svc = AdbMdns.discover(context, AdbMdns.TLS_PAIRING, 2500)
                    if (svc != null) {
                        host = svc.host.hostAddress
                        port = svc.port
                    }
                }

                if (code != null && port > 0) {
                    val mgr = AppAdbConnectionManager.getInstance(context)
                    val pairHost = host ?: "127.0.0.1"
                    val ok = try {
                        mgr.pair(pairHost, port, code)
                    } catch (e: Throwable) {
                        Log.w(TAG, "pair failed: ${e.message}")
                        return Result.Failed(e.message ?: "pair error")
                    }
                    if (!ok) return Result.Failed("pairing rejected")
                    // Сразу поднимаем рабочее соединение (best-effort).
                    try {
                        mgr.autoConnect(context, 8000)
                    } catch (_: Throwable) {
                        // Сопряжение уже удалось — это лишь попытка сразу поднять
                        // рабочее соединение, её провал не отменяет успех.
                    }
                    return Result.Paired
                }

                Thread.sleep(400)
            }
            return Result.Timeout
        } finally {
            AccessibilityCaptureService.stopPairingWatch()
        }
    }
}
