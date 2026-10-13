package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppLinksDeviceTest {
    private class FakeAdb(
        private val api: String,
        private val installed: Boolean,
    ) : Adb {
        val commands = mutableListOf<String>()

        override fun serials() = listOf("emulator-5554")

        override fun shell(
            serial: String,
            command: String,
        ): String {
            commands += command
            return when {
                command.startsWith("getprop") -> "$api\n"
                command.startsWith("pm set-app-links") && !installed -> throw AdbException("adb failed (exit 1)\nPackage not found: x")
                command.startsWith("pm get-app-links") ->
                    if (installed) "  x:\n    ID: 1\n    Signatures: [AA]\n    Domain verification state:\n      h: approved\n" else ""
                else -> ""
            }
        }
    }

    @Test
    fun `api level is queried once and gates verification support`() {
        val adb = FakeAdb(api = "30", installed = true)
        val device = AppLinksDevice(adb, "emulator-5554")
        assertFalse(device.supportsDomainVerification)
        assertFalse(device.supportsDomainVerification)
        assertEquals(1, adb.commands.count { it.startsWith("getprop") })
        assertTrue(AppLinksDevice(FakeAdb("31", true), "s").supportsDomainVerification)
    }

    @Test
    fun `package not found becomes a false return, other adb failures propagate`() {
        val device = AppLinksDevice(FakeAdb("35", installed = false), "emulator-5554")
        assertFalse(device.setAppLinks("x", 2, "h"))
        assertNull(device.appLinks("x"))

        val broken =
            object : Adb {
                override fun serials() = emptyList<String>()

                override fun shell(
                    serial: String,
                    command: String,
                ): String = throw AdbException("device offline")
            }
        assertThrows(AdbException::class.java) { AppLinksDevice(broken, "s").setAppLinks("x", 2, "h") }
    }

    @Test
    fun `set then read back`() {
        val adb = FakeAdb("35", installed = true)
        val device = AppLinksDevice(adb, "emulator-5554")
        assertTrue(device.setAppLinks("x", 2, "h other"))
        assertEquals(PackageAppLinks(mapOf("h" to "approved"), listOf("AA")), device.appLinks("x"))
        assertEquals("pm set-app-links --package x 2 h other", adb.commands.first { "set-app-links" in it })
    }

    @Test
    fun `watcher command waits for the package, approves once, and cannot kill its own launcher`() {
        val command = AppLinksDevice.watcherCommand("com.example.app", "a.example.com b.example.com", 120)
        assertTrue(command.startsWith("P=com.example.app; pkill -f \"applinks-watch:\$P\" 2>/dev/null; nohup sh -c '"), command)
        assertTrue("while [ \$i -lt 120 ]" in command, command)
        assertTrue("pm path com.example.app >/dev/null 2>&1" in command, command)
        assertTrue("pm set-app-links --package com.example.app 2 a.example.com b.example.com; exit 0" in command, command)
        assertTrue(command.endsWith("' \"applinks-watch:\$P\" </dev/null >/dev/null 2>&1 &"), command)
        // The expanded marker must not appear literally, or pkill would match the shell that runs this command.
        assertFalse("applinks-watch:com.example.app" in command, command)
        assertThrows(IllegalArgumentException::class.java) { AppLinksDevice.watcherCommand("x; rm -rf /", "h", 1) }
    }

    @Test
    fun `not installed starts the watcher through adb`() {
        val adb = FakeAdb("35", installed = false)
        AppLinksDevice(adb, "emulator-5554").approveWhenInstalled("x", "h")
        assertTrue(adb.commands.last().contains("nohup sh -c"), adb.commands.toString())
    }

    @Test
    fun `process adb refuses a non-executable path up front`() {
        val error = assertThrows(AdbException::class.java) { ProcessAdb(File("/definitely/missing/adb").path) }
        assertTrue(error.message!!.contains("/definitely/missing/adb"))
    }
}
