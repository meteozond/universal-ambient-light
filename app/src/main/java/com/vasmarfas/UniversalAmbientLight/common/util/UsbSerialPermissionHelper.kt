package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Запрашивает разрешение на первое (или конкретное) USB-последовательное устройство.
 *
 * Задачи: сделать запуск «в одно касание» из MainActivity, BootActivity, ToggleActivity и
 * плитки быстрых настроек, а также при желании запрашивать разрешение автоматически, когда
 * устройство подключают при открытом приложении.
 */
object UsbSerialPermissionHelper {
    private const val TAG = "UsbSerialPermission"

    // Значение action менять нельзя — оно уже используется в MainActivity
    const val ACTION_USB_PERMISSION = "com.vasmarfas.UniversalAmbientLight.USB_PERMISSION"

    @Volatile
    private var lastRequestedDeviceId: Int? = null

    fun hasAnySerialDevice(context: Context): Boolean {
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProberFactory.getProber().findAllDrivers(usbManager).isNotEmpty()
    }

    fun findFirstSerialDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val drivers = UsbSerialProberFactory.getProber().findAllDrivers(usbManager)
        return drivers.firstOrNull()?.device
    }

    fun isSerialDevice(context: Context, device: UsbDevice): Boolean {
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProberFactory.getProber()
            .findAllDrivers(usbManager)
            .any { it.device.deviceId == device.deviceId }
    }

    /**
     * Обеспечивает разрешение на USB-последовательное устройство Adalight.
     *
     * Если разрешение уже выдано, [onReady] вызывается сразу; иначе разрешение запрашивается,
     * и [onReady] вызывается после согласия пользователя.
     *
     * @return true, если всё уже готово, и false, если запрос только начат.
     */
    fun ensurePermissionForSerialDevice(
        context: Context,
        device: UsbDevice?,
        onReady: () -> Unit,
        onDenied: (() -> Unit)? = null,
        showToast: Boolean = true,
        force: Boolean = false,
    ): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            if (showToast) Toast.makeText(
                context,
                "USB service is not available on this device",
                Toast.LENGTH_LONG
            ).show()
            onDenied?.invoke()
            return false
        }

        val target = device ?: findFirstSerialDevice(context)
        if (target == null) {
            if (showToast) Toast.makeText(
                context,
                "No USB serial devices found. Connect your device via USB OTG",
                Toast.LENGTH_LONG
            ).show()
            onDenied?.invoke()
            return false
        }

        // Не спрашиваем про устройства, не являющиеся последовательными (случайная USB-периферия)
        if (!isSerialDevice(context, target)) {
            onDenied?.invoke()
            return false
        }

        if (usbManager.hasPermission(target)) {
            onReady()
            return true
        }

        // Сначала пробуем root, только потом системный диалог; `su` блокирует, поэтому асинхронно.
        if (UsbRootPermissionHelper.isRootAvailable()) {
            if (!force && lastRequestedDeviceId == target.deviceId) return false
            lastRequestedDeviceId = target.deviceId

            UsbRootPermissionHelper.grantPermissionViaRootAsync(context) { rootGranted ->
                if (rootGranted && usbManager.hasPermission(target)) {
                    onReady()
                } else {
                    // Снимаем метку ограничения частоты, чтобы следующий запуск по кнопке смог спросить.
                    lastRequestedDeviceId = null
                    onDenied?.invoke()
                }
            }
            return false
        }

        // Не показываем один и тот же запрос по кругу
        if (!force && lastRequestedDeviceId == target.deviceId) {
            return false
        }
        lastRequestedDeviceId = target.deviceId

        AnalyticsHelper.logUsbPermissionRequested(context)

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            target.deviceId,
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                    // Одноразовый приёмник: система могла снять его вместе с контекстом.
                }

                val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val targetDevice = deviceFromIntent ?: target
                val grantedByBroadcast =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                // Часть прошивок присылает неверный или пустой EXTRA_PERMISSION_GRANTED — перепроверяем сами.
                val grantedByManager = usbManager.hasPermission(targetDevice)
                val granted = grantedByBroadcast || grantedByManager

                if (granted) {
                    AnalyticsHelper.logUsbPermissionGranted(ctx)
                    onReady()
                } else {
                    AnalyticsHelper.logUsbPermissionDenied(ctx)
                    if (showToast) {
                        Toast.makeText(
                            ctx,
                            "USB device permission denied. Please allow USB access.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onDenied?.invoke()
                }
            }
        }

        // Приёмник регистрируем как неэкспортируемый
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            usbManager.requestPermission(target, permissionIntent)
            if (showToast) Toast.makeText(
                context,
                "Подтвердите доступ к USB устройству",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}", e)
            onDenied?.invoke()
        }

        return false
    }
}

