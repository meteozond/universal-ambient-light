package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Помогает довести пользователя до настроек разработчика, чтобы он включил отладку по USB
 * или по Wi-Fi, не выходя из приложения надолго.
 */
object DevOptionsHelper {
    private const val TAG = "DevOptionsHelper"

    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    /** Открывает экран «Для разработчиков». Возвращает false, если открыть не удалось. */
    fun openDeveloperOptions(context: Context): Boolean {
        return tryOpen(
            context,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.system.development.DevelopmentActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.system.DevelopmentFragment"
                )
            )
        )
    }

    /**
     * Пробует открыть экран «Отладка по Wi-Fi» (Android 11+). Если прямого пути нет,
     * открывает «Для разработчиков» с прокруткой к нужному переключателю (через аргументы
     * фрагмента у SettingsActivity). В самом крайнем случае — просто «Для разработчиков».
     */
    fun openWirelessDebugging(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val direct = tryOpen(
                context,
                // Публичная константа action с API 31, её же понимает AOSP Android 11.
                Intent("android.settings.ADB_WIRELESS_SETTINGS"),
                // Явная активити Pixel и AOSP.
                Intent().setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$AdbWirelessSettingsActivity"
                    )
                ),
                // Варианты у отдельных производителей.
                Intent().setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.development.WirelessDebuggingActivity"
                    )
                ),
                // Android TV (com.android.tv.settings).
                Intent().setComponent(
                    ComponentName(
                        "com.android.tv.settings",
                        "com.android.tv.settings.system.development.WirelessDebuggingActivity"
                    )
                )
            )
            if (direct) return true
        }

        // Запасной путь через аргументы фрагмента: открывает «Для разработчиков» с подсветкой и
        // прокруткой к переключателю отладки по Wi-Fi. Работает на большинстве настроек из AOSP.
        // Ключ совпадает с id настройки в development_settings.xml во всех версиях.
        val highlightIntents = listOf(
            "toggle_adb_wireless",
            "adb_wireless",
            "wireless_debugging"
        ).map { key ->
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", key)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", key)
                })
            }
        }
        if (tryOpen(context, *highlightIntents.toTypedArray())) return true

        return openDeveloperOptions(context)
    }

    /**
     * Открывает экран «О телефоне» или «Об устройстве», чтобы пользователь семь раз нажал
     * «Номер сборки» и разблокировал режим разработчика.
     */
    fun openAboutDeviceForBuildNumber(context: Context): Boolean {
        return tryOpen(
            context,
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.DeviceInfoSettings")
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.about.AboutFragment"
                )
            )
        )
    }

    private fun tryOpen(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (context.packageManager.resolveActivity(intent, 0) == null) continue
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (e: Exception) {
                Log.d(TAG, "Intent ${intent.component ?: intent.action} failed: ${e.message}")
            }
        }
        return false
    }
}
