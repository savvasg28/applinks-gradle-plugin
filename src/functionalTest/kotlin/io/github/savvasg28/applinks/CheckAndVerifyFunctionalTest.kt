package io.github.savvasg28.applinks

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress

/** checkAppLinks against a local server standing in for each host, verifyAppLinks against a fake adb. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckAndVerifyFunctionalTest {

    private lateinit var project: SampleProject
    private lateinit var server: HttpServer
    private val base get() = "http://localhost:${server.address.port}"
    private val other = "11:" + "22:".repeat(30) + "33"

    @BeforeAll
    fun start(@TempDir dir: File) {
        project = SampleProject(dir)
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0).also { it.start() }
    }

    @AfterAll
    fun stop() = server.stop(0)

    @BeforeEach
    fun writeProject() {
        server.removeContextQuietly("/sample.uk"); server.removeContextQuietly("/app.sample.uk")
        project.write(
            appLinks = """
                assetLinksUrlOverrides.put('sample.uk', '$base/sample.uk')
                assetLinksUrlOverrides.put('app.sample.uk', '$base/app.sample.uk')
                excludedHosts.add('excluded.sample.uk')
            """.trimIndent(),
            extraFilters = """
                <intent-filter>
                  <action android:name="android.intent.action.VIEW"/>
                  <category android:name="android.intent.category.DEFAULT"/>
                  <category android:name="android.intent.category.BROWSABLE"/>
                  <data android:scheme="https" android:host="link.payment-provider.com"/>
                </intent-filter>
            """.trimIndent(),
        )
    }

    private fun HttpServer.removeContextQuietly(path: String) = runCatching { removeContext(path) }

    private fun serveAssetLinks(host: String, vararg fingerprints: String, packageName: String = "uk.co.sample") {
        val body = """[{"relation": ["delegate_permission/common.handle_all_urls"],
            "target": {"namespace": "android_app", "package_name": "$packageName",
                       "sha256_cert_fingerprints": [${fingerprints.joinToString { "\"$it\"" }}]}}]"""
        server.createContext("/$host") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `check passes when every host lists the signing certificate`() {
        serveAssetLinks("sample.uk", project.fingerprint)
        serveAssetLinks("app.sample.uk", project.fingerprint, other)

        val result = project.run("checkAppLinksStaging").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkAppLinksStaging")?.outcome)
        assertTrue(result.output.contains("unverified web link for link.payment-provider.com"), result.output)
        assertTrue(project.report("checkAppLinksStaging").contains("\"passed\": true"))
    }

    @Test
    fun `check fails naming the missing fingerprint and the wrong package`() {
        serveAssetLinks("sample.uk", other)
        serveAssetLinks("app.sample.uk", project.fingerprint, packageName = "uk.co.someone.else")

        val result = project.run("checkAppLinksStaging").buildAndFail()

        assertTrue(result.output.contains("sample.uk: $base/sample.uk does not list ${project.fingerprint}"), result.output)
        assertTrue(result.output.contains("app.sample.uk: $base/app.sample.uk has no statement for package uk.co.sample"), result.output)
        assertTrue(project.report("checkAppLinksStaging").contains("\"status\": \"failed\""))
    }

    @Test
    fun `check uses configured fingerprints when the variant has no signing config`() {
        val playKey = "AB:" + "CD:".repeat(30) + "EF"
        serveAssetLinks("sample.uk", playKey)
        serveAssetLinks("app.sample.uk", playKey)
        project.appendBuild("appLinks { additionalCertificateFingerprints.add('$playKey') }")

        assertEquals(TaskOutcome.SUCCESS, project.run("checkAppLinksRelease").build().task(":checkAppLinksRelease")?.outcome)
    }

    @Test
    fun `verify waits for the verifier and explains failures using the installed certificate`() {
        serveAssetLinks("sample.uk", project.fingerprint)
        serveAssetLinks("app.sample.uk", other)
        // First poll answers "none" (verifier still running), later polls answer for real.
        val counter = File(project.dir, "poll-count")
        val fakeAdb = project.fakeAdb(
            "fake-adb-verify", devices = mapOf("emulator-5554" to 35),
            getAppLinks = """n=${'$'}(cat "${counter.absolutePath}" 2>/dev/null || echo 0); echo ${'$'}((n+1)) > "${counter.absolutePath}"
                if [ "${'$'}n" -lt 1 ]; then ${SampleProject.appLinksOutput(project.fingerprint, "sample.uk" to "none", "app.sample.uk" to "none")}
                else ${SampleProject.appLinksOutput(project.fingerprint, "sample.uk" to "verified", "app.sample.uk" to "1024")}; fi""",
        )
        project.appendBuild("appLinks { adbExecutable = '${fakeAdb.absolutePath}'; verifyTimeoutSeconds = 2 }")

        val result = project.run("verifyAppLinksStaging").buildAndFail()

        val commands = project.adbLog.readLines()
        assertTrue(commands.any { "pm set-app-links --package uk.co.sample 0 sample.uk app.sample.uk" in it }, commands.toString())
        assertTrue(commands.any { "pm verify-app-links --re-verify uk.co.sample" in it }, commands.toString())
        assertTrue(commands.count { "pm get-app-links" in it } >= 2, "must poll more than once")
        assertTrue(result.output.contains("sample.uk") && result.output.contains("verified"), result.output)
        assertTrue(result.output.contains("app.sample.uk is '1024': installed cert ${project.fingerprint} is not in assetlinks.json"), result.output)
        assertTrue(project.report("verifyAppLinksStaging").contains("\"passed\": false"))
    }

    @Test
    fun `check and verify are never wired to assemble`() {
        val result = project.run("assembleStaging", "--dry-run").build()
        assertNull(result.task(":verifyAppLinksStaging"))
        assertNull(result.task(":checkAppLinksStaging"))
    }
}
