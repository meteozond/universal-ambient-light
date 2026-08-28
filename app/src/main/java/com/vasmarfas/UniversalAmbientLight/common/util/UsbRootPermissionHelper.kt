package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.hardware.usb.UsbManager
import com.vasmarfas.UniversalAmbientLight.common.network.LightpackClient
import android.util.Log
import androidx.annotation.WorkerThread
import com.vasmarfas.UniversalAmbientLight.common.util.UsbRootPermissionHelper.grantPermissionViaRootAsync
import java.util.concurrent.TimeUnit

/**
 * Выдаёт разрешение на USB-устройство через root (su и app_process), чтобы приложение
 * могло работать с USB-последовательными устройствами без диалога.
 *
 * Использует скрытый API IUsbManager.grantDevicePermission(), вызываемый из root-процесса
 * через app_process. Разрешение выдаётся прямо в памяти UsbService — действует сразу,
 * перезагрузка не нужна.
 *
 * Требуется Magisk или другой способ получения root.
 */
object UsbRootPermissionHelper {
    private const val TAG = "UsbRootPermission"
    private const val ROOT_CHECK_TIMEOUT_SEC = 2L

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * Проверяет, доступен ли root (su) на этом устройстве.
     * Результат кэшируется на время жизни процесса.
     *
     * Вызывается в том числе с главного потока (BootActivity, ToggleActivity), поэтому
     * дорогой запуск `su` делается только там, где бинарник вообще есть: на обычной
     * прошивке проверка сводится к нескольким stat(). Кэш дополнительно прогревается
     * из фонового потока приложения — см. [warmUpAsync].
     */
    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val result = hasSuBinary() && checkRootAvailable()
        cachedRootAvailable = result
        return result
    }

    /** Прогревает кэш проверки root вне главного потока — до 2 секунд на запуск `su`. */
    fun warmUpAsync() {
        if (cachedRootAvailable != null) return
        Thread { isRootAvailable() }.apply { isDaemon = true; name = "UsbRootCheck" }.start()
    }

    /** Кэшированный результат без запуска `su`: null, пока проверка ещё не выполнялась. */
    fun isRootAvailableCached(): Boolean? = cachedRootAvailable

    private fun hasSuBinary(): Boolean = SU_PATHS.any {
        try {
            java.io.File(it).exists()
        } catch (_: Exception) {
            // SecurityException от кастомного SELinux-профиля читаем как «бинарника нет».
            false
        }
    }

    private fun checkRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val completed = process.waitFor(ROOT_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                Log.w(TAG, "Root check timed out after ${ROOT_CHECK_TIMEOUT_SEC}s")
                return false
            }
            val result = process.inputStream.bufferedReader().readText()
            result.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Выдаёт разрешение на USB для подключённых последовательных устройств через root.
     * Блокирующий вызов — из рабочего потока либо через [grantPermissionViaRootAsync].
     * @return true, если разрешение выдано хотя бы для одного устройства
     */
    @WorkerThread
    fun grantPermissionViaRoot(context: Context): Boolean {
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        // Разрешение нужно и последовательным устройствам, и Lightpack: он подключается
        // как устройство ввода, и общий перебор портов его не находит.
        val devices = UsbSerialProberFactory.getProber().findAllDrivers(usbManager)
            .map { it.device }
            .toMutableList()
        LightpackClient.findDevice(context)?.let { lightpack ->
            if (devices.none { it.deviceId == lightpack.deviceId }) devices.add(lightpack)
        }

        if (devices.isEmpty()) {
            Log.w(TAG, "No USB devices found")
            return false
        }

        val uid = android.os.Process.myUid()
        var anyGranted = false

        for (device in devices) {
            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "Already have permission for ${device.deviceName}")
                anyGranted = true
                continue
            }

            if (grantSingleDevice(context, device.deviceName, uid)) {
                anyGranted = true
                Log.i(TAG, "Root-granted USB permission for ${device.deviceName} (uid=$uid)")
            }
        }

        return anyGranted
    }

    @WorkerThread
    private fun grantSingleDevice(context: Context, deviceName: String, uid: Int): Boolean {
        val apkPath = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                "com.vasmarfas.UniversalAmbientLight.common.util.UsbPermissionGranterCli " +
                "$deviceName $uid"

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            if (!process.waitFor(GRANT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                try {
                    process.destroy()
                } catch (_: Exception) {
                    // Процесс мог завершиться сам между таймаутом и попыткой убить.
                }
                Log.w(
                    TAG,
                    "app_process grant timed out after ${GRANT_TIMEOUT_SEC}s for $deviceName"
                )
                return false
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                Log.i(TAG, "app_process grant OK: $stdout")
                true
            } else {
                Log.e(TAG, "app_process grant failed (exit=$exitCode): $stderr")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run app_process: ${e.message}", e)
            false
        }
    }

    /** Выдача через root без ожидания результата; ответ приходит на главный поток. */
    fun grantPermissionViaRootAsync(context: Context, onComplete: (granted: Boolean) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val result = try {
                grantPermissionViaRoot(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "grantPermissionViaRootAsync failed", e)
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(result) }
        }.apply { isDaemon = true; name = "UsbRootGrantAsync" }.start()
    }

    private const val GRANT_TIMEOUT_SEC = 4L

    // Типовые места su: Magisk (бинд-маунт в /system/bin и /sbin), классические root-прошивки.
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/odm/bin/su",
        "/product/bin/su",
    )
}
