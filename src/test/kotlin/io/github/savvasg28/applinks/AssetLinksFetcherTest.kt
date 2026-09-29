package io.github.savvasg28.applinks

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class AssetLinksFetcherTest {

    private lateinit var server: HttpServer
    private val base get() = "http://localhost:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun serve(path: String, status: Int, contentType: String, body: String, location: String? = null) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            location?.let { exchange.responseHeaders.add("Location", it) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun fetcher(vararg overrides: Pair<String, String>) = AssetLinksFetcher(overrides.toMap())

    @Test
    fun `parses statements, normalises fingerprints and warns on lower case`() {
        serve("/ok", 200, "application/json; charset=utf-8", """[
          {"relation": ["delegate_permission/common.handle_all_urls"],
           "target": {"namespace": "android_app", "package_name": "com.example.app", "sha256_cert_fingerprints": ["c3:62:47:db:64:64:64:8c:82:a2:1d:18:8b:d3:f2:0d:9c:73:f5:86:4b:a8:ff:78:e5:86:a2:d9:f4:36:08:9b"]}},
          {"relation": ["delegate_permission/common.get_login_creds"],
           "target": {"namespace": "web", "site": "https://example.com"}}
        ]""")
        val result = fetcher("example.com" to "$base/ok").fetch("example.com")
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(AndroidStatement("com.example.app", listOf("C3:62:47:DB:64:64:64:8C:82:A2:1D:18:8B:D3:F2:0D:9C:73:F5:86:4B:A8:FF:78:E5:86:A2:D9:F4:36:08:9B"), listOf("delegate_permission/common.handle_all_urls"))), result.statements)
        assertTrue(result.warnings.single().contains("not upper case"))
    }

    @Test
    fun `redirects, wrong content type and bad json are errors`() {
        serve("/redirect", 301, "text/html", "", location = "$base/ok")
        serve("/html", 200, "text/html", "[]")
        serve("/bad", 200, "application/json", "{not json")
        serve("/missing", 404, "application/json", "")
        listOf("redirect" to "redirect to", "html" to "Content-Type", "bad" to "Invalid JSON", "missing" to "HTTP 404").forEach { (path, expected) ->
            val result = fetcher("h" to "$base/$path").fetch("h")
            assertTrue(result.errors.single().contains(expected), "$path: ${result.errors}")
        }
    }

    @Test
    fun `follows one level of include`() {
        serve("/root", 200, "application/json", """[{"include": "$base/child"}]""")
        serve("/child", 200, "application/json", """[{"relation": ["delegate_permission/common.handle_all_urls"],
           "target": {"namespace": "android_app", "package_name": "com.example.app", "sha256_cert_fingerprints": ["C3:62:47:DB:64:64:64:8C:82:A2:1D:18:8B:D3:F2:0D:9C:73:F5:86:4B:A8:FF:78:E5:86:A2:D9:F4:36:08:9B"]}}]""")
        val result = fetcher("h" to "$base/root").fetch("h")
        assertEquals(listOf("com.example.app"), result.statements.map { it.packageName })
    }

    @Test
    fun `unreachable host is an error not an exception`() {
        val result = fetcher("h" to "http://localhost:1/nothing").fetch("h")
        assertEquals(1, result.errors.size)
    }
}
