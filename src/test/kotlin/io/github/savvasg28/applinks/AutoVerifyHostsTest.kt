package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoVerifyHostsTest {
    @Test
    fun `every host, wildcard included, goes into the pm argument literally`() {
        val hosts = AutoVerifyHosts.of(listOf("example.com", "app.example.com", "example.com"))
        assertEquals(setOf("example.com", "app.example.com"), hosts.concrete)
        assertEquals("example.com app.example.com", hosts.pmDomainsArgument)

        val wild = AutoVerifyHosts.of(listOf("example.com", "*.example.com"))
        assertEquals(setOf("*.example.com"), wild.wildcards)
        assertEquals("example.com *.example.com", wild.pmDomainsArgument)
        assertEquals("example.com", AutoVerifyHosts.baseDomain("*.example.com"))
        assertEquals("app.example.com", AutoVerifyHosts.baseDomain("app.example.com"))
    }

    @Test
    fun `exclusion and emptiness`() {
        val hosts = AutoVerifyHosts.of(listOf("example.com", "staging.example.com")) - setOf("staging.example.com")
        assertEquals(setOf("example.com"), hosts.all)
        assertTrue((hosts - setOf("example.com")).isEmpty)
    }
}
