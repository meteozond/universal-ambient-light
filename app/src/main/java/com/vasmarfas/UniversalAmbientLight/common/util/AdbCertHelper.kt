package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * Создаёт и хранит пару ключей RSA и самоподписанный сертификат X.509 для беспроводного
 * ADB на Android 11+ (TLS). Использует перепакованные классы `android.sun.security.x509`
 * (sun-security-android), а НЕ BouncyCastle: его классы `org.bouncycastle.*` конфликтуют
 * с частичной копией в boot classpath Android и падают с NoClassDefFoundError
 * (EdECObjectIdentifiers). Файлы лежат в приватном хранилище приложения.
 */
object AdbCertHelper {
    private const val KEY_FILE = "adb_tls_private.key"
    private const val CERT_FILE = "adb_tls_cert.der"
    private const val ALGORITHM = "SHA512withRSA"

    data class Material(val privateKey: PrivateKey, val certificate: Certificate)

    @Synchronized
    fun getOrCreate(context: Context): Material {
        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)

        tryRead(keyFile, certFile)?.let { return it }

        val material = generate()
        keyFile.writeBytes(material.privateKey.encoded)
        certFile.writeBytes(material.certificate.encoded)
        return material
    }

    private fun tryRead(keyFile: File, certFile: File): Material? {
        if (!keyFile.exists() || !certFile.exists()) return null
        return try {
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val certificate = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            Material(privateKey, certificate)
        } catch (e: Exception) {
            null
        }
    }

    private fun generate(): Material {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        }.generateKeyPair()
        val publicKey = keyPair.public
        val privateKey = keyPair.private

        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000)
        val x500Name = X500Name("CN=UniversalAmbientLight")

        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
        )
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

        val certInfo = X509CertInfo()
        certInfo.set("version", CertificateVersion(2))
        certInfo.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        certInfo.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(ALGORITHM)))
        certInfo.set("subject", CertificateSubjectName(x500Name))
        certInfo.set("key", CertificateX509Key(publicKey))
        certInfo.set("validity", CertificateValidity(notBefore, notAfter))
        certInfo.set("issuer", CertificateIssuerName(x500Name))
        certInfo.set("extensions", extensions)

        val cert = X509CertImpl(certInfo)
        cert.sign(privateKey, ALGORITHM)

        return Material(privateKey, cert)
    }
}
