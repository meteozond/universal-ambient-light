package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Подбирает порт ADB для энкодеров, работающих через dadb (scrcpy и adb).
 *
 * dadb понимает только устаревший протокол ADB с RSA. «Отладка по Wi-Fi» в Android 11+
 * выставляет порт только с TLS (и он ещё меняется при каждом переключении и перезагрузке),
 * поэтому dadb им пользоваться не может — рукопожатие не проходит. Чтобы обойти это, берём
 * умеющий TLS [AppAdbConnectionManager] (тот же путь, что и у кнопки «Проверить соединение»)
 * и переводим adbd в обычный режим `tcpip` на фиксированном порту, который dadb уже осилит.
 *
 * Блокирующий вызов (TLS-подключение, tcpip и проверки TCP). Вызывать из рабочего потока, не из главного.
 */
object AdbPortResolver {
    private const val TAG = "AdbPortResolver"
    private const val PROBE_TIMEOUT_MS = 600
    private const val TCPIP_PORT = 5555
    private const val TCPIP_WAIT_TRIES = 20
    private const val TCPIP_WAIT_STEP_MS = 250L

    /**
     * @param savedPort порт из настроек
     * @return порт, к которому dadb (устаревший RSA) действительно сможет подключиться
     */
    fun resolveForDadb(context: Context, savedPort: Int): Int {
        // Android 10 и ниже: настроенный RSA-порт (обычно 5555) работает с dadb напрямую.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return savedPort
        }

        // Android 11+: dadb нужен обычный порт, без TLS.
        if (isPortAlive(TCPIP_PORT)) {
            // adbd уже в режиме tcpip (после прошлого запуска или команды `adb tcpip 5555`).
            return TCPIP_PORT
        }
        if (switchAdbdToTcpip(context, TCPIP_PORT)) {
            // adbd перезапускается и переоткрывает порт; ждём, пока обычный порт поднимется.
            repeat(TCPIP_WAIT_TRIES) {
                if (isPortAlive(TCPIP_PORT)) {
                    Log.i(TAG, "adbd now in tcpip mode on $TCPIP_PORT")
                    return TCPIP_PORT
                }
                try {
                    Thread.sleep(TCPIP_WAIT_STEP_MS)
                } catch (_: InterruptedException) {
                    return savedPort
                }
            }
            Log.w(TAG, "tcpip:$TCPIP_PORT requested but the port did not come up")
        }
        // Последняя надежда: пусть dadb попробует настроенный порт (на TLS-порту, скорее всего, не выйдет).
        return savedPort
    }

    /**
     * Открывает TLS-соединение (порт беспроводной отладки находит сам через mDNS) и вызывает
     * сервис `tcpip:<порт>`, после чего adbd перезапускается и слушает обычный порт с RSA.
     *
     * Требует, чтобы устройство было уже сопряжено, иначе autoConnect бросит исключение.
     */
    private fun switchAdbdToTcpip(context: Context, port: Int): Boolean {
        return try {
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (!mgr.isConnected) {
                val auto = try {
                    mgr.autoConnect(context, 8000)
                } catch (e: Throwable) {
                    Log.w(TAG, "TLS autoConnect failed (paired?): ${e.message}")
                    false
                }
                if (!auto) return false
            }
            Log.i(TAG, "Requesting adbd 'tcpip:$port' over TLS…")
            val stream = mgr.openStream("tcpip:$port")
            // Вычитываем короткое подтверждение, чтобы adbd обработал запрос; после этого он перезапускается и поток закрывается.
            // adbd перезапускается прямо во время этих вызовов, поэтому обрыв чтения и
            // закрытия здесь — ожидаемый исход, а не ошибка.
            try {
                stream.openInputStream().readBytes()
            } catch (_: Exception) {
            }
            try {
                stream.close()
            } catch (_: Exception) {
            }
            try {
                mgr.disconnect()
            } catch (_: Exception) {
            } // the TLS link drops on adbd restart anyway
            true
        } catch (e: Throwable) {
            Log.w(TAG, "tcpip switch failed: ${e.message}")
            false
        }
    }

    private fun isPortAlive(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }
}
