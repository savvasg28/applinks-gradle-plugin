package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FingerprintsTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `fingerprint from a keystore matches keytool`() {
        val store = File(dir, "test.jks")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", store.path, "-storepass", "secret", "-keypass", "secret",
            "-alias", "upload", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test", "-validity", "1", "-storetype", "PKCS12"
        ).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())

        val expected = ProcessBuilder(keytool, "-list", "-v", "-keystore", store.path, "-storepass", "secret", "-alias", "upload")
            .start().inputStream.bufferedReader().readText()
            .lines().first { "SHA256:" in it }.substringAfter("SHA256:").trim()

        val actual = Fingerprints.fromKeystore(store, "secret", "upload", "PKCS12")
        assertEquals(expected, actual)
        assertTrue(Fingerprints.isWellFormed(actual))
    }

    @Test
    fun `well formed check`() {
        assertTrue(Fingerprints.isWellFormed("C3:62:47:DB:64:64:64:8C:82:A2:1D:18:8B:D3:F2:0D:9C:73:F5:86:4B:A8:FF:78:E5:86:A2:D9:F4:36:08:9B"))
        assertEquals(false, Fingerprints.isWellFormed("C3:62"))
        assertEquals("AA:BB", Fingerprints.normalise(" aa:bb "))
    }
}
