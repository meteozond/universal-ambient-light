package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context

/**
 * Заглушка для Google Play. Во флейворе full здесь открывается страница настроек нашей
 * службы доступности; в сборке для Google Play такой службы нет, поэтому здесь пусто.
 * Вызовы закрыты проверкой BuildConfig.HAS_ACCESSIBILITY и недостижимыми путями захвата.
 */
@Suppress("UNUSED_PARAMETER")
fun openAccessibilitySettings(context: Context) {
}
