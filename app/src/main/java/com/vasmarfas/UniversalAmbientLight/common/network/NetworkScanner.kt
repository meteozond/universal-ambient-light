package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import androidx.annotation.WorkerThread
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Arrays
import java.util.Collections
import kotlin.math.abs

/** Ищет в локальной сети запущенные серверы Hyperion.
 * Автор: nino, 27-5-18.
 */
class NetworkScanner {
    private val ipsToTry: Array<String>
    private var lastTriedIndex = -1

    init {
        ipsToTry = getIPsToTry()
    }

    /** Проверяет следующий адрес из списка.
     *
     * @return имя хоста (или IP строкой), если сервер Hyperion найден
     */
    @WorkerThread
    fun tryNext(): String? {
        if (!hasNextAttempt()) {
            throw IllegalStateException("No more ip addresses to try")
        }

        val socket = Socket()
        val ip = ipsToTry[++lastTriedIndex]
        try {
            socket.connect(InetSocketAddress(ip, PORT), ATTEMPT_TIMEOUT_MS)

            if (socket.isConnected) {
                socket.close()
                return ip
            }
        } catch (e: Exception) {
            return null
        }

        return null
    }

    /** Показывает, какая доля адресов уже проверена.
     *
     * @return прогресс в диапазоне [0.0 .. 1.0]
     */
    val progress: Float
        get() {
            if (ipsToTry.isEmpty()) {
                return 1f
            }

            // lastTriedIndex начинается с -1 и на последнем адресе равен size-1:
            // без +1 прогресс стартовал бы с отрицательного и не доходил до 1.0
            return (lastTriedIndex + 1) / ipsToTry.size.toFloat()
        }

    /** True, пока проверены не все адреса
     *
     */
    fun hasNextAttempt(): Boolean {
        return ipsToTry.isNotEmpty() && lastTriedIndex + 1 < ipsToTry.size
    }

    companion object {
        const val PORT = 19400

        /** Сколько миллисекунд ждём подключения к адресу, прежде чем сдаться  */
        private const val ATTEMPT_TIMEOUT_MS = 50

        /**
         * Возвращает адреса всех интерфейсов, кроме localhost.
         * @param useIPv4 true — вернуть IPv4, false — IPv6
         * @return список найденных адресов (может быть пустым)
         *
         * https://stackoverflow.com/a/13007325
         */
        private fun getIPAddresses(useIPv4: Boolean): List<String> {
            val foundAddresses = ArrayList<String>()
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress
                            val isIPv4 = sAddr.indexOf(':') < 0

                            if (useIPv4) {
                                if (isIPv4)
                                    foundAddresses.add(sAddr)
                            } else {
                                if (!isIPv4) {
                                    val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                    val v6Addr =
                                        if (delim < 0) sAddr.uppercase() else sAddr.substring(
                                            0,
                                            delim
                                        ).uppercase()
                                    foundAddresses.add(v6Addr)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ошибки перечисления интерфейсов игнорируем: адрес просто не попадёт в список
                Log.e("HYPERION SCANNER", "Could not get ip address", e)
            }
            return foundAddresses
        }

        private fun getIPsToTry(): Array<String> {
            try {
                val localIpV4Addresses = getIPAddresses(true)
                val allIpsToTry = arrayOfNulls<String>(localIpV4Addresses.size * 254)

                for (localIpIdx in localIpV4Addresses.indices) {
                    val localIpV4Address = localIpV4Addresses[localIpIdx]
                    val ipsToTry = arrayOfNulls<String>(254)

                    val ipParts =
                        localIpV4Address.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray()

                    // Один нестандартный адрес не должен ронять скан всех интерфейсов:
                    // внешний catch вернул бы пустой список целиком
                    if (ipParts.size != 4 || ipParts[3].toIntOrNull() == null) continue

                    val ipPrefix = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + "."
                    for (i in 1..254) {
                        ipsToTry[i - 1] = ipPrefix + i
                    }

                    val localNumberInSubnet = Integer.parseInt(ipParts[3])

                    // Сортируем так, чтобы адреса рядом с локальным проверялись первыми
                    Arrays.sort(ipsToTry) { lhs, rhs ->
                        // Массив заполнен целиком циклом выше, null здесь означал бы поломку инварианта
                        val lhsIp = checkNotNull(lhs) { "ipsToTry contains null" }
                        val rhsIp = checkNotNull(rhs) { "ipsToTry contains null" }
                        val lhsNumberInSubnet = Integer.parseInt(
                            lhsIp.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[3]
                        )
                        val rhsNumberInSubnet = Integer.parseInt(
                            rhsIp.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[3]
                        )
                        val lhsDistance = abs(lhsNumberInSubnet - localNumberInSubnet)
                        val rhsDistance = abs(rhsNumberInSubnet - localNumberInSubnet)
                        lhsDistance - rhsDistance
                    }

                    for (i in ipsToTry.indices) {
                        // Чередуем с адресами, найденными ранее
                        val allIndex = localIpV4Addresses.size * i + localIpIdx
                        allIpsToTry[allIndex] = ipsToTry[i]
                    }
                }

                // Часть интерфейсов могла дать меньше 254 записей (ошибки разбора, интерфейсы
                // только с IPv6 и так далее) — убираем пустые ячейки, чтобы непроверяемое
                // приведение типа не выстрелило NPE во время сканирования.
                return allIpsToTry.filterNotNull().toTypedArray()
            } catch (e: Exception) {
                Log.e("HYPERION SCANNER", "Error while building list of subnet ip's", e)
                return emptyArray()
            }
        }
    }
}
