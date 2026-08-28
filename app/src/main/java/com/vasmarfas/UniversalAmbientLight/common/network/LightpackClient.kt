package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import java.io.IOException

/**
 * Вывод на Lightpack — коробочку с десятью группами светодиодов, которая подключается по USB
 * и опознаётся системой как устройство ввода (HID).
 *
 * Родная программа для неё, Prismatik, давно заброшена и на Android не работает вовсе, так что
 * подключить ленту иначе как через посредника было нельзя.
 *
 * Устройство принимает кадр одним пакетом: байт команды, затем по шесть байт на канал.
 * Цвет у него двенадцатибитный: сначала идут старшие восемь бит трёх составляющих, потом
 * их младшие четыре — так же, как это делает родная программа. Плавность переходов и
 * яркость железо держит само, их достаточно задать один раз при подключении.
 *
 * Обратной точки обмена у устройства нет вовсе — в его описании объявлена одна, и та на
 * чтение, — поэтому кадры уходят управляющим запросом. Это не мешает: измерения показывают
 * запас до двухсот семидесяти кадров в секунду.
 *
 * Порядок каналов задаётся раскладкой светодиодов в настройках приложения — тем же способом,
 * что и для любой другой ленты.
 */
class LightpackClient(
    private val mContext: Context,
    private val mPriority: Int,
    smoothingEnabled: Boolean = true,
    smoothingPreset: String = "balanced",
    settlingTime: Int = 200,
    outputDelayMs: Long = 80L,
    updateFrequency: Int = 30,
    private val mBrightness: Int = 100,
    private val mHardwareSmoothing: Int = DEFAULT_SMOOTHING,
) : HyperionClient {

    private var mConnection: UsbDeviceConnection? = null
    private var mInterface: UsbInterface? = null
    private var mEndpoint: UsbEndpoint? = null

    @Volatile
    private var mConnected = false

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    /** Пакет переиспользуем: он уходит десятки раз в секунду. */
    private val mPacket = ByteArray(PACKET_SIZE)

    /** Прошлый кадр: одинаковые подряд отправлять незачем. */
    private val mLastFrame = ByteArray(PACKET_SIZE)
    private var mHasLastFrame = false

    init {
        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        mSmoothing.applyPreset(smoothingPreset)
        mSmoothing.setSettlingTime(settlingTime)
        mSmoothing.setOutputDelay(outputDelayMs)
        mSmoothing.setUpdateFrequency(updateFrequency)
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        val usbManager = mContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB service not available on this device")

        val device = findDevice(mContext)
            ?: throw IOException(
                "No Lightpack found. Please connect the device via USB OTG cable"
            )

        // Диалог доступа показывает активити, из сервиса только проверяем флаг.
        if (!usbManager.hasPermission(device)) {
            throw IOException(
                "USB device permission denied. Please allow USB access when prompted, " +
                        "or grant permission manually in Android Settings > Apps"
            )
        }

        val iface = findInterface(device)
            ?: throw IOException("Lightpack has no usable interface")

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open Lightpack. Please check USB connection")

        try {
            // Устройство опознано системой как HID, поэтому ядро уже держит его драйвером;
            // без принудительного захвата интерфейса писать в него нельзя.
            if (!connection.claimInterface(iface, true)) {
                throw IOException("Lightpack is busy: failed to claim interface")
            }

            mConnection = connection
            mInterface = iface
            mEndpoint = findOutEndpoint(iface)
            mConnected = true

            // Железо умеет сглаживать переходы само; заводское значение слишком велико и
            // подсветка заметно отстаёт от экрана.
            writeCommand(CMD_SET_SMOOTH_SLOWDOWN, mHardwareSmoothing.coerceIn(0, 255))
            writeCommand(CMD_SET_BRIGHTNESS, mBrightness.coerceIn(0, 100))

            mSmoothing.start()
            Log.i(
                TAG,
                "Successfully connected to Lightpack (VID=${device.vendorId} " +
                        "PID=${device.productId}, ${if (mEndpoint != null) "endpoint" else "control"} transfer)"
            )
        } catch (e: Exception) {
            mConnected = false
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
                // Интерфейс мог и не захватиться — освобождать тогда нечего.
            }
            try {
                connection.close()
            } catch (_: Exception) {
                // Иначе устройство останется занятым до перезапуска приложения.
            }
            mConnection = null
            mInterface = null
            mEndpoint = null
            throw IOException("Failed to open Lightpack: ${e.message}", e)
        }
    }

    private fun findInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) return iface
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_OUT) return ep
        }
        // Обратной точки может не быть — тогда кадры уходят управляющим запросом.
        return null
    }

    /**
     * Отправляет готовый пакет. Предпочитаем обратную точку: она быстрее и не мешает
     * управляющему каналу, но часть прошивок её не объявляет.
     */
    private fun write(packet: ByteArray): Boolean {
        val connection = mConnection ?: return false
        val endpoint = mEndpoint

        val sent = if (endpoint != null) {
            connection.bulkTransfer(endpoint, packet, packet.size, TRANSFER_TIMEOUT_MS)
        } else {
            connection.controlTransfer(
                REQUEST_TYPE_SET_REPORT,
                REQUEST_SET_REPORT,
                REPORT_TYPE_OUTPUT,
                mInterface?.id ?: 0,
                packet,
                packet.size,
                TRANSFER_TIMEOUT_MS
            )
        }

        if (sent < 0) {
            Log.w(TAG, "Write failed, connection lost")
            mConnected = false
            return false
        }
        return true
    }

    /** Короткая команда настройки: сама команда и одно значение. */
    private fun writeCommand(command: Int, value: Int): Boolean {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = command.toByte()
        packet[1] = value.toByte()
        return write(packet)
    }

    // Обратный вызов от ColorSmoothing
    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected()) return

        java.util.Arrays.fill(mPacket, 0)
        mPacket[0] = CMD_UPDATE_LEDS.toByte()

        val count = minOf(leds.size, LED_COUNT)
        for (i in 0 until count) {
            putColor(i, leds[i].red, leds[i].green, leds[i].blue)
        }

        // Картинка нередко стоит на месте — на паузе, в меню, на тёмной сцене.
        if (mHasLastFrame && mPacket.contentEquals(mLastFrame)) return
        if (write(mPacket)) {
            System.arraycopy(mPacket, 0, mLastFrame, 0, PACKET_SIZE)
            mHasLastFrame = true
        }
    }

    /**
     * Раскладывает цвет канала по пакету. Составляющие приходят восьмибитными, а устройство
     * ждёт двенадцать бит: старшие восемь идут подряд для всех трёх, следом младшие четыре.
     * Растягиваем повтором старших бит — тогда белый выходит ровно во всю шкалу.
     */
    private fun putColor(channel: Int, red: Int, green: Int, blue: Int) {
        val offset = 1 + channel * BYTES_PER_LED
        val r = (red shl 4) or (red shr 4)
        val g = (green shl 4) or (green shr 4)
        val b = (blue shl 4) or (blue shr 4)

        mPacket[offset] = ((r shr 4) and 0xFF).toByte()
        mPacket[offset + 1] = ((g shr 4) and 0xFF).toByte()
        mPacket[offset + 2] = ((b shr 4) and 0xFF).toByte()
        mPacket[offset + 3] = (r and 0x0F).toByte()
        mPacket[offset + 4] = (g and 0x0F).toByte()
        mPacket[offset + 5] = (b and 0x0F).toByte()
    }

    override fun isConnected(): Boolean = mConnected

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()
        mConnected = false

        val connection = mConnection
        val iface = mInterface
        if (connection != null) {
            // Гасим ленту: иначе она останется светить последним кадром.
            java.util.Arrays.fill(mPacket, 0)
            mPacket[0] = CMD_UPDATE_LEDS.toByte()
            try {
                write(mPacket)
            } catch (_: Exception) {
                // Устройство могли уже выдернуть — гасить больше нечего.
            }
            if (iface != null) {
                try {
                    connection.releaseInterface(iface)
                } catch (_: Exception) {
                    // Освобождение best-effort: соединение всё равно закрывается ниже.
                }
            }
            try {
                connection.close()
            } catch (_: Exception) {
                // Закрыть не вышло — дальше уже некуда.
            }
        }
        mConnection = null
        mInterface = null
        mEndpoint = null
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) = clearAll()

    @Throws(IOException::class)
    override fun clearAll() {
        if (!isConnected()) return
        mHasLastFrame = false
        java.util.Arrays.fill(mPacket, 0)
        mPacket[0] = CMD_UPDATE_LEDS.toByte()
        write(mPacket)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) = setColor(color, priority, -1)

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (!isConnected()) return

        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF

        java.util.Arrays.fill(mPacket, 0)
        mPacket[0] = CMD_UPDATE_LEDS.toByte()
        for (i in 0 until LED_COUNT) {
            putColor(i, red, green, blue)
        }
        mHasLastFrame = false
        write(mPacket)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!isConnected()) {
            throw IOException("Not connected to Lightpack")
        }

        val leds = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        mLedDataBuffer = leds
        if (leds.isEmpty()) return

        mSmoothing.setTargetColors(leds)
    }

    companion object {
        private const val TAG = "LightpackClient"

        /** Lightpack и его версия PixelKit: обе на одном контроллере Atmel. */
        private val KNOWN_DEVICES = listOf(
            0x03EB to 0x204F,
            0x1D50 to 0x6022,
        )

        /** Групп светодиодов у устройства ровно десять. */
        private const val LED_COUNT = 10
        private const val BYTES_PER_LED = 6
        private const val PACKET_SIZE = 1 + LED_COUNT * BYTES_PER_LED

        private const val CMD_UPDATE_LEDS = 1
        private const val CMD_SET_SMOOTH_SLOWDOWN = 5
        private const val CMD_SET_BRIGHTNESS = 6

        /**
         * Заводское сглаживание — 100, с ним подсветка заметно отстаёт от экрана; на этом
         * значении переходы ещё мягкие, но задержки уже не видно.
         */
        private const val DEFAULT_SMOOTHING = 15

        private const val TRANSFER_TIMEOUT_MS = 100

        /** Управляющий запрос HID: SET_REPORT для устройства, интерфейсу. */
        private const val REQUEST_TYPE_SET_REPORT = 0x21
        private const val REQUEST_SET_REPORT = 0x09

        /** Старший байт — тип отчёта (2 = выходной), младший — его номер. */
        private const val REPORT_TYPE_OUTPUT = 0x0200

        /** Находит подключённый Lightpack; null, если его нет. */
        fun findDevice(context: Context): UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return null
            return usbManager.deviceList.values.firstOrNull { device ->
                KNOWN_DEVICES.any { (vid, pid) ->
                    device.vendorId == vid && device.productId == pid
                }
            }
        }

        /** Есть ли вообще такое устройство: спрашивают до попытки подключения. */
        fun isAvailable(context: Context): Boolean = findDevice(context) != null
    }
}
