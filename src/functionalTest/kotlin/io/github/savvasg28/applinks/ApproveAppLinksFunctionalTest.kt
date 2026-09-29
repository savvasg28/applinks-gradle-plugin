package io.github.savvasg28.applinks

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApproveAppLinksFunctionalTest {
    private lateinit var project: SampleProject
    private lateinit var fakeAdb: File

    @BeforeAll
    fun createProject(
        @TempDir dir: File,
    ) {
        project = SampleProject(dir)
        fakeAdb =
            project.fakeAdb(
                "fake-adb",
                devices = mapOf("emulator-5554" to 35, "R5CX20" to 30),
                getAppLinks =
                    SampleProject.appLinksOutput(
                        "AA",
                        "sample.uk" to "approved",
                        "app.sample.uk" to "approved",
                        "excluded.sample.uk" to "1024",
                    ),
            )
    }

    @BeforeEach
    fun writeProject() {
        project.write(appLinks = "adbExecutable = '${fakeAdb.absolutePath}'\nexcludedHosts.add('excluded.sample.uk')")
    }

    @Test
    fun `approves hosts on every eligible device for the debug variant`() {
        val result = project.run("approveAppLinksDebug").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":approveAppLinksDebug")?.outcome)
        val commands = project.adbLog.readLines()
        assertTrue(
            "-s emulator-5554 shell pm set-app-links --package uk.co.sample.debug 2 sample.uk app.sample.uk" in commands,
            commands.toString(),
        )
        assertTrue(commands.none { "R5CX20" in it && "set-app-links" in it }, "API 30 device must be skipped")
        assertTrue(result.output.contains("R5CX20: API 30"), result.output)
        assertTrue(project.report("approveAppLinksDebug").contains("\"passed\": true"))
    }

    @Test
    fun `refuses a non-debuggable variant unless allowed`() {
        val result = project.run("approveAppLinksRelease").buildAndFail()

        assertTrue(result.output.contains("Refusing to force-approve App Links for non-debuggable variant 'release'"), result.output)
        assertTrue(!project.adbLog.exists() || project.adbLog.readText().isBlank(), "adb must not be called")
    }

    @Test
    fun `allows an opted-in non-debuggable variant`() {
        project.appendBuild("appLinks { allowedVariants.add('staging') }")

        val result = project.run("approveAppLinksStaging").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":approveAppLinksStaging")?.outcome)
        assertTrue(project.adbLog.readLines().any { "--package uk.co.sample 2 sample.uk app.sample.uk" in it })
    }

    @Test
    fun `assembleDebug runs the lenient auto task by default`() {
        val result = project.run("assembleDebug").build()

        val executed = result.tasks.map { it.path }
        assertEquals(TaskOutcome.SUCCESS, result.task(":autoApproveAppLinksDebug")?.outcome, executed.toString())
        assertTrue(executed.indexOf(":autoApproveAppLinksDebug") > executed.indexOf(":assembleDebug"), executed.toString())
        assertNull(result.task(":approveAppLinksDebug"))
        assertTrue(project.adbLog.readLines().any { "--package uk.co.sample.debug 2 sample.uk app.sample.uk" in it })
    }

    @Test
    fun `release never gets an auto task and automatic approval can be switched off`() {
        assertNull(project.run("assembleRelease", "--dry-run").build().task(":autoApproveAppLinksRelease"))

        project.appendBuild("appLinks { autoApproveDebuggableVariants = false }")
        assertNull(project.run("assembleDebug", "--dry-run").build().task(":autoApproveAppLinksDebug"))
    }

    @Test
    fun `no device is a log line for the auto task and a failure when explicit`() {
        val none = project.fakeAdb("fake-adb-none", devices = emptyMap(), getAppLinks = ":")
        project.appendBuild("appLinks { adbExecutable = '${none.absolutePath}' }")

        val automatic = project.run("assembleDebug").build()
        assertEquals(TaskOutcome.SUCCESS, automatic.task(":autoApproveAppLinksDebug")?.outcome)
        assertTrue(automatic.output.contains("no connected devices"), automatic.output)

        val explicit = project.run("approveAppLinksDebug").buildAndFail()
        assertTrue(explicit.output.contains("no connected devices"), explicit.output)
    }

    @Test
    fun `a wrong explicit adb path fails instead of falling back`() {
        project.appendBuild("appLinks { adbExecutable = '/definitely/missing/adb' }")

        val result = project.run("approveAppLinksDebug").buildAndFail()

        assertTrue(result.output.contains("/definitely/missing/adb"), result.output)
    }
}
