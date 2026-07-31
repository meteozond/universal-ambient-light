package com.vasmarfas.UniversalAmbientLight.common

/**
 * Заглушка для Google Play. Во флейворе full здесь настоящая служба доступности, а сборка
 * для Google Play не должна содержать никаких AccessibilityService, поэтому здесь обычный
 * инертный класс, чтобы общий код компилировался. Все точки входа отвечают «недоступно», и
 * зависящие от доступности пути в этой сборке не работают.
 */
class AccessibilityCaptureService private constructor() {

    companion object {
        fun getInstance(): AccessibilityCaptureService? = null
        fun isAvailable(): Boolean = false
        fun consumeAutoPairPending(): Boolean = false
        fun requestReturnToAppOnConnect() {}
        fun markAutoPairPending() {}
    }
}
