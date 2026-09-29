package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `parses assetlinks shaped documents`() {
        val parsed = Json.parse(
            """[{"relation": ["delegate_permission/common.handle_all_urls"],
                 "target": {"namespace": "android_app", "package_name": "com.example.app",
                            "sha256_cert_fingerprints": ["AA:BB", "cc:dd"]}, "n": 1.5, "t": true, "z": null}]"""
        )
        val statement = (parsed as List<*>)[0] as Map<*, *>
        val target = statement["target"] as Map<*, *>
        assertEquals("com.example.app", target["package_name"])
        assertEquals(listOf("AA:BB", "cc:dd"), target["sha256_cert_fingerprints"])
        assertEquals(1.5, statement["n"])
        assertEquals(true, statement["t"])
        assertEquals(null, statement["z"])
    }

    @Test
    fun `handles escapes and rejects garbage`() {
        assertEquals("a\"b\\c\né", Json.parse(""""a\"b\\c\né""""))
        assertThrows(Json.JsonException::class.java) { Json.parse("[1,]") }
        assertThrows(Json.JsonException::class.java) { Json.parse("{\"a\":1} x") }
    }

    @Test
    fun `writes and reads back`() {
        val value = linkedMapOf("passed" to false, "hosts" to listOf("a", "b"), "nested" to mapOf("k" to "v\"q"))
        val text = Json.write(value)
        assertEquals(value, Json.parse(text))
    }
}
