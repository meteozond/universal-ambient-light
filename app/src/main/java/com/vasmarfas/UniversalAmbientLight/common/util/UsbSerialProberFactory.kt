package com.vasmarfas.UniversalAmbientLight.common.util

import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Единый пробер USB-serial устройств для всего приложения.
 *
 * Берёт стандартную таблицу mik3y (FTDI / CP210x / CH34x / PL2303 / generic-CDC по классу
 * интерфейса) и дополняет её несколькими VID/PID новых китайских мостов, которых нет в
 * дефолтном списке. Так «китайские коробки» Adalight определяются так же широко, как в
 * стороннем приложении на felHR.
 *
 * Все места, которые ищут serial-устройство (клиент, запрос разрешения, root-grant),
 * должны ходить через [getProber], чтобы охват распознавания был одинаковым.
 */
object UsbSerialProberFactory {

    private val cachedProber: UsbSerialProber by lazy { buildProber() }

    fun getProber(): UsbSerialProber = cachedProber

    private fun buildProber(): UsbSerialProber {
        val table: ProbeTable = UsbSerialProber.getDefaultProbeTable()

        // Новые чипы WCH, которых нет в дефолтной таблице mik3y.
        table.addProduct(0x1a86, 0x55d3, Ch34xSerialDriver::class.java) // CH343
        table.addProduct(0x1a86, 0x55d4, Ch34xSerialDriver::class.java) // CH9102F
        table.addProduct(0x1a86, 0x55d5, Ch34xSerialDriver::class.java) // CH9102X
        // Альтернативный VID QinHeng (некоторые клоны CH340/CH341).
        table.addProduct(0x4348, 0x5523, Ch34xSerialDriver::class.java)

        return UsbSerialProber(table)
    }
}
