package io.github.savvasg28.applinks

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/** SHA-256 certificate fingerprints in the `AA:BB:...` form assetlinks.json and `pm get-app-links` use. */
internal object Fingerprints {
    private val pattern = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")

    fun normalise(raw: String): String = raw.trim().uppercase()

    fun isWellFormed(fingerprint: String): Boolean = pattern.matches(fingerprint)

    fun fromKeystore(
        storeFile: File,
        storePassword: String?,
        keyAlias: String,
        storeType: String? = null,
    ): String {
        val type = storeType ?: if (storeFile.extension.equals("jks", ignoreCase = true)) "JKS" else KeyStore.getDefaultType()
        val keyStore = KeyStore.getInstance(type)
        storeFile.inputStream().use { keyStore.load(it, storePassword?.toCharArray()) }
        val certificate =
            keyStore.getCertificate(keyAlias)
                ?: throw IllegalArgumentException("Alias '$keyAlias' not found in ${storeFile.path}")
        return fromDer(certificate.encoded)
    }

    fun fromDer(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
}
