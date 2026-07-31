package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyHelper {

    // Синхронизировано, чтобы два энкодера не начали одновременно генерировать RSA-2048 при
    // первом запуске: файлы ключей повредились бы, и авторизация ADB отвалилась бы до перезапуска.
    @Synchronized
    fun getKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")

        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }

        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
