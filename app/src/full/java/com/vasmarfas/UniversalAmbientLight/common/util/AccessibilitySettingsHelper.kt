package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService

/**
 * Открывает настройки специальных возможностей как можно ближе к нашей службе:
 *  - Android 13+  → сразу страница нашей службы (недокументированный intent, работает на AOSP)
 *  - Android 9–12 → общий список с прокруткой и подсветкой нашего компонента через аргументы фрагмента
 *  - запасной путь → обычный ACTION_ACCESSIBILITY_SETTINGS
 */
fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(
        context.packageName,
        AccessibilityCaptureService::class.java.name
    )
    val componentKey = component.flattenToString()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+: ведём прямо на страницу ИМЕННО этой службы. Action и extra помечены @hide
        // (констант в SDK нет), поэтому пишем строками. В прежнем коде ключ extra был неверный
        // ("accessibility_service"), и пользователь попадал в общий список; правильный — ниже.
        try {
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            intent.putExtra("android.provider.extra.ACCESSIBILITY_DETAILS_SETTINGS", componentKey)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Прямой переход к экрану конкретной службы поддерживают не все прошивки —
            // ниже открываем общий список специальных возможностей.
        }
    }

    // Android 9–12: приём с аргументами фрагмента — список прокручивается к нашей службе и подсвечивает её
    try {
        val fragmentArgs = Bundle()
        fragmentArgs.putString(":settings:fragment_args_key", componentKey)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.putExtra(":settings:fragment_args_key", componentKey)
        intent.putExtra(":settings:show_fragment_args", fragmentArgs)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    // Запасной путь: обычные настройки специальных возможностей
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // Последняя попытка открыть настройки: если и её прошивка не поддерживает,
        // пользователю остаётся дойти до них вручную.
    }
}
