package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppLinksStateParserTest {
    private val output = javaClass.getResource("/get-app-links-api35.txt")!!.readText()

    @Test
    fun `parses host states and signatures in one pass and stops at the user section`() {
        val links = AppLinksStateParser.parse(output, "dev.spike.applinks")!!
        assertEquals(mapOf("spike.example.com" to "1024", "app.example.com" to "approved"), links.states)
        assertEquals(
            listOf("C3:62:47:DB:64:64:64:8C:82:A2:1D:18:8B:D3:F2:0D:9C:73:F5:86:4B:A8:FF:78:E5:86:A2:D9:F4:36:08:9B"),
            links.signatures,
        )
    }

    @Test
    fun `returns null when the package is not installed`() {
        assertNull(AppLinksStateParser.parse(output, "dev.other.app"))
    }

    @Test
    fun `returns empty states when the package has no verification block`() {
        val noDomains = "  dev.spike.applinks:\n    ID: x\n    Signatures: []\n"
        assertEquals(PackageAppLinks(emptyMap(), emptyList()), AppLinksStateParser.parse(noDomains, "dev.spike.applinks"))
    }

    @Test
    fun `describes numeric verifier errors`() {
        assertEquals(true, AppLinksStateParser.describe("1024").contains("fingerprint"))
        assertEquals("unknown state", AppLinksStateParser.describe("banana"))
    }
}
