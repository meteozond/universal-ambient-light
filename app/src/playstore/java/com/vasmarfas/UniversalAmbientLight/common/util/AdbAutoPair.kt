package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context

/**
 * Заглушка для Google Play. Автосопряжение опирается на службу доступности, которой в этом
 * флейворе нет. Интерфейс автосопряжения скрыт за BuildConfig.HAS_ACCESSIBILITY, поэтому
 * run() здесь никогда не вызывается; на всякий случай он возвращает NeedsAccessibility.
 */
@Suppress("UNUSED_PARAMETER")
object AdbAutoPair {

    sealed class Result {
        object Paired : Result()
        object NeedsAccessibility : Result()
        object Timeout : Result()
        data class Failed(val message: String) : Result()
    }

    fun run(context: Context, timeoutMs: Long = 90_000L): Result = Result.NeedsAccessibility
}
