package io.github.savvasg28.applinks

import java.io.File
import java.util.concurrent.TimeUnit

/** Minimal adb surface the tasks need. Kept as an interface so tests can fake it. */
internal interface Adb {
    /** Serials of devices in the `device` state (not offline or unauthorized). */
    fun serials(): List<String>

    fun shell(
        serial: String,
        command: String,
    ): String
}

internal class AdbException(
    message: String,
) : RuntimeException(message)

internal class ProcessAdb(
    private val executable: String,
) : Adb {
    init {
        if (!File(executable).canExecute()) throw AdbException("adb is not executable: $executable")
    }

    override fun serials(): List<String> =
        run(listOf("devices")).lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE)
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }

    override fun shell(
        serial: String,
        command: String,
    ): String = run(listOf("-s", serial, "shell", command))

    private fun run(args: List<String>): String {
        val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        // Drain output on another thread so the timeout below can fire even if adb never closes stdout.
        val output =
            java.util.concurrent.CompletableFuture
                .supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AdbException("adb timed out after 60s: ${args.joinToString(" ")}")
        }
        val text = output.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            throw AdbException("adb failed (exit ${process.exitValue()}): ${args.joinToString(" ")}\n$text")
        }
        return text
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** What `pm get-app-links` knows about one installed package on one device. */
internal data class PackageAppLinks(
    val states: Map<String, String>,
    val signatures: List<String>,
)

/**
 * The domain-verification operations of one device. Every `pm` command the plugin uses lives here,
 * so the tasks only express policy.
 */
internal class AppLinksDevice(
    private val adb: Adb,
    val serial: String,
) {
    val apiLevel: Int? by lazy { adb.shell(serial, "getprop ro.build.version.sdk").trim().toIntOrNull() }

    /** Android 12 introduced the verification model and the `pm *-app-links` commands. */
    val supportsDomainVerification: Boolean get() = (apiLevel ?: 0) >= 31

    val unsupportedReason: String get() = "$serial: API ${apiLevel ?: "?"} predates the Android 12 verification model, skipped"

    /** Returns false when the package is not installed on this device. */
    fun setAppLinks(
        packageName: String,
        state: Int,
        domainsArgument: String,
    ): Boolean = installedOnly { adb.shell(serial, "pm set-app-links --package $packageName $state $domainsArgument") }

    fun requestVerification(packageName: String) {
        adb.shell(serial, "pm verify-app-links --re-verify $packageName")
    }

    /**
     * Leaves a shell loop running on the device that approves [domainsArgument] for [packageName] as soon as
     * the package is installed, then exits. Needed because Android Studio installs the APK after Gradle has
     * finished, so a finalizer of `assemble` runs too early on a fresh device. Any watcher already waiting for
     * the same package is killed first, so repeated builds do not stack loops.
     */
    fun approveWhenInstalled(
        packageName: String,
        domainsArgument: String,
        timeoutSeconds: Int = WATCHER_TIMEOUT_SECONDS,
    ) {
        adb.shell(serial, watcherCommand(packageName, domainsArgument, timeoutSeconds))
    }

    /** Null when the package is not installed. */
    fun appLinks(packageName: String): PackageAppLinks? {
        val output = adb.shell(serial, "pm get-app-links --user cur $packageName")
        return AppLinksStateParser.parse(output, packageName)
    }

    companion object {
        const val WATCHER_TIMEOUT_SECONDS = 120
        private val SAFE_PACKAGE = Regex("[A-Za-z0-9._]+")

        /**
         * The marker `applinks-watch:<package>` becomes `$0` of the loop's shell, so `pkill -f` can find an
         * earlier watcher. It is assembled from a variable so the pattern never matches the launching shell's
         * own command line, which would kill the launcher before it starts the loop.
         */
        fun watcherCommand(
            packageName: String,
            domainsArgument: String,
            timeoutSeconds: Int,
        ): String {
            require(SAFE_PACKAGE.matches(packageName)) { "Refusing package name '$packageName'" }
            val loop =
                "i=0; while [ \$i -lt $timeoutSeconds ]; do " +
                    "if pm path $packageName >/dev/null 2>&1; then sleep 2; " +
                    "pm set-app-links --package $packageName 2 $domainsArgument; exit 0; fi; " +
                    "i=\$((i+1)); sleep 1; done"
            return "P=$packageName; pkill -f \"applinks-watch:\$P\" 2>/dev/null; " +
                "nohup sh -c '$loop' \"applinks-watch:\$P\" </dev/null >/dev/null 2>&1 &"
        }
    }

    private fun installedOnly(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: AdbException) {
            // pm exits 1 with this text for an unknown package; it is the only failure the tasks treat specially.
            if ("Package not found" in e.message.orEmpty()) false else throw e
        }
}
