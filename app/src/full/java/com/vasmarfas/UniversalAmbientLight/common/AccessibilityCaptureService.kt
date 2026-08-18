package com.vasmarfas.UniversalAmbientLight.common

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Захват содержимого экрана через Accessibility API (Android 11+).
 * Обходной путь для устройств, где MediaProjection заблокирован.
 *
 * Он же автоматически считывает шестизначный код сопряжения беспроводного ADB с системного
 * экрана «Подключение с помощью кода», чтобы пользователю не пришлось вводить код вручную
 * и уходить с этого экрана — уход отменил бы сопряжение.
 *
 * Аннотации @RequiresApi(30) на классе намеренно нет: служба работает с API 26, а
 * единственный вызов из API 30 (takeScreenshot) закрыт проверкой в рантайме ниже. Пометь
 * мы весь класс, и API 30 потребовался бы каждому обращению к companion-объекту по всему
 * приложению, хотя сами эти вызовы безопасны.
 */
class AccessibilityCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service connected")
        instance.set(this)
        returnToAppIfRequested()
    }

    /**
     * Если пользователя только что отправили сюда включить службу для автосопряжения,
     * возвращаем приложение на передний план, чтобы ему не пришлось выбираться из настроек
     * кнопкой «Назад». Best-effort: часть версий ОС запрещает запуск активити из фона —
     * тогда «Назад» по-прежнему работает.
     */
    private fun returnToAppIfRequested() {
        val ts = returnRequestedAt
        if (ts == 0L || System.currentTimeMillis() - ts > 10 * 60 * 1000L) return
        returnRequestedAt = 0L
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (intent != null) startActivity(intent)
        } catch (_: Exception) {
            // Возврат в приложение — удобство, а не обязательный шаг: прошивка может
            // запретить запуск активити из службы, работать это не мешает.
        }
    }

    private var lastClickLabel = ""
    private var lastClickTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pairingWatch) return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        scanActiveWindowInternal()
    }

    /**
     * Читает код сопряжения, если диалог уже открыт, иначе проходит по меню настроек вперёд,
     * нажимая строки «Подключение с помощью кода» и «Отладка по Wi-Fi» (переключатели не трогает).
     * Работает и по событиям, и по опросу от координатора, потому что статичные экраны
     * (например, «Для разработчиков») после запуска наблюдения событий не присылают.
     */
    private fun scanActiveWindowInternal() {
        if (!pairingWatch) return
        try {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString() ?: ""
            // Работаем только внутри системного приложения «Настройки».
            if (!pkg.contains("settings", ignoreCase = true)) return

            val code = findSixDigitCode(root)
            if (code != null) {
                detectedCode.set(code)
                return
            }
            if (clickByKeywords(root, PAIR_KEYWORDS, "pair")) return
            clickByKeywords(root, WIRELESS_DEBUG_KEYWORDS, "wd")
        } catch (_: Exception) {
            // Обход дерева чужого окна настроек: узлы исчезают прямо во время перебора,
            // и это штатная ситуация — просто попробуем на следующем событии.
        }
    }

    private fun findSixDigitCode(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString()?.trim()
        if (text != null && SIX_DIGITS.matches(text)) return text
        for (i in 0 until node.childCount) {
            findSixDigitCode(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** Находит узел, в тексте или описании которого есть одно из ключевых слов, и жмёт его строку. */
    private fun clickByKeywords(root: AccessibilityNodeInfo?, keywords: List<String>, label: String): Boolean {
        val now = System.currentTimeMillis()
        if (label == lastClickLabel && now - lastClickTime < 3000) return false
        val node = findByKeywords(root, keywords) ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            // Нажимаем контейнер строки, но никогда не переключатель — иначе перевернём саму настройку.
            if (n.isClickable && !isToggle(n)) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastClickLabel = label
                    lastClickTime = now
                    return true
                }
                return false
            }
            n = n.parent
        }
        return false
    }

    private fun findByKeywords(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val hay =
            ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: ""))
                .lowercase()
        if (hay.isNotBlank() && keywords.any { hay.contains(it) }) return node
        for (i in 0 until node.childCount) {
            findByKeywords(node.getChild(i), keywords)?.let { return it }
        }
        return null
    }

    private fun isToggle(n: AccessibilityNodeInfo): Boolean {
        val cn = n.className?.toString() ?: ""
        return cn.contains("Switch") || cn.contains("Toggle") || cn.contains("CompoundButton")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service interrupted")
        instance.set(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance.set(null)
        return super.onUnbind(intent)
    }

    fun takeScreenshot(
        executor: Executor = mainExecutor,
        callback: (Bitmap?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Конвертация HardwareBuffer в читаемый bitmap — до десятков мегабайт копии на
            // кадр; вызывающий передаёт executor своего потока, чтобы не грузить главный
            takeScreenshot(0, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var copy: Bitmap? = null
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        // Копируем в программно читаемый bitmap. Bitmap поверх wrapHardwareBuffer
                        // нельзя recycle() — достаточно закрыть сам буфер.
                        copy = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot conversion failed", e)
                    } finally {
                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                            // Буфер обязателен к освобождению, но мог закрыться при ошибке
                            // конвертации выше — она уже залогирована.
                        }
                    }
                    callback(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            })
        } else {
            callback(null)
        }
    }

    companion object {
        private const val TAG = "AccessibilityCapture"
        private val instance = AtomicReference<AccessibilityCaptureService?>(null)
        private val SIX_DIGITS = Regex("^\\d{6}$")

        // Подстроки в нижнем регистре, по которым узнаём нужные строки настроек в разных локалях.
        private val PAIR_KEYWORDS = listOf(
            "pair device with pairing code", "pairing code", "pair using",
            "кода подключения", "помощью кода", "код сопряж"
        )
        private val WIRELESS_DEBUG_KEYWORDS = listOf(
            "wireless debugging", "отладка по wi", "беспроводн"
        )

        @Volatile
        private var pairingWatch = false
        private val detectedCode = AtomicReference<String?>(null)

        @Volatile
        private var returnRequestedAt = 0L

        /** Вызывать прямо перед отправкой пользователя включать службу — тогда вернём его сами. */
        fun requestReturnToAppOnConnect() {
            returnRequestedAt = System.currentTimeMillis()
        }

        @Volatile
        private var autoPairPending = false

        /** Отмечает, что после возвращения в приложение автосопряжение нужно продолжить (со спросом). */
        fun markAutoPairPending() {
            autoPairPending = true
        }

        /** Возвращает true один раз, если автосопряжение было отложено, и снимает флаг. */
        fun consumeAutoPairPending(): Boolean {
            val v = autoPairPending
            autoPairPending = false
            return v
        }

        fun getInstance(): AccessibilityCaptureService? = instance.get()

        /** True, если служба доступности сейчас подключена и может читать экран. */
        fun isAvailable(): Boolean = instance.get() != null

        /** Начать следить за системным экраном сопряжения в ожидании шестизначного кода. */
        fun startPairingWatch() {
            detectedCode.set(null)
            pairingWatch = true
        }

        fun stopPairingWatch() {
            pairingWatch = false
            detectedCode.set(null)
        }

        /** Последний прочитанный с экрана сопряжения шестизначный код или null. */
        fun detectedPairingCode(): String? = detectedCode.get()

        /** Триггер опроса: на статичных экранах настроек события не приходят. */
        fun scanActiveWindow() {
            instance.get()?.scanActiveWindowInternal()
        }
    }
}
