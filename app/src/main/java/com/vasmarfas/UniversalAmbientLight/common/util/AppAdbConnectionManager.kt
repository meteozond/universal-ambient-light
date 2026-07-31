package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Беспроводное подключение по ADB для Android 11+ (TLS и сопряжение по коду) на базе
 * libadb-android. Подключается к собственному демону ADB устройства через loopback.
 *
 * На старых устройствах (API < 30) энкодеры продолжают ходить путём dadb/RSA.
 */
class AppAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val material = AdbCertHelper.getOrCreate(context)

    init {
        api = Build.VERSION.SDK_INT
        hostAddress = "127.0.0.1"
    }

    override fun getPrivateKey(): PrivateKey = material.privateKey

    override fun getCertificate(): Certificate = material.certificate

    override fun getDeviceName(): String = "UniversalAmbientLight"

    companion object {
        @Volatile
        private var instance: AppAdbConnectionManager? = null

        @Synchronized
        fun getInstance(context: Context): AppAdbConnectionManager {
            return instance ?: AppAdbConnectionManager(context.applicationContext).also {
                instance = it
            }
        }
    }
}
