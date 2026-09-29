package io.github.savvasg28.applinks

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Asks each device's own Domain Verification Agent to verify the installed app for real, then fails
 * unless every autoVerify host is `verified`. Meant for nightly and pre-release pipelines; never wired
 * automatically. On failure it says whether the installed certificate is the one the server lists.
 */
@DisableCachingByDefault(because = "Talks to devices or servers whose state is not a build input")
abstract class VerifyAppLinksTask : DeviceAppLinksTask() {
    @get:Input abstract val debuggable: Property<Boolean>

    @get:Input abstract val assetLinksUrlOverrides: MapProperty<String, String>

    @get:Input abstract val timeoutSeconds: Property<Int>

    @get:Internal internal var fetcherFactory: (Map<String, String>) -> AssetLinksFetcher = { AssetLinksFetcher(it) }

    @TaskAction
    fun verify() {
        val packageName = applicationId.get()
        val report = newReport("verifyAppLinks")
        if (debuggable.get()) {
            report.warning("${variantName.get()} is debuggable; its certificate is unlikely to be the one production lists")
        }
        val hosts = hostsToHandle()
        if (hosts.isEmpty) {
            report.note("No autoVerify hosts declared; nothing to verify.")
            return report.finish(logger, reportFile.get().asFile, "App Links verification")
        }

        val devices = connectedDevices()
        if (devices.isEmpty()) {
            report.error("App Links not verified: ${noDevicesMessage()}")
            return report.finish(logger, reportFile.get().asFile, "App Links verification")
        }

        // Kick every device off first; the verifiers run independently, so the wait overlaps.
        val pending =
            devices.filter { device ->
                when {
                    !device.supportsDomainVerification -> {
                        report.warning(device.unsupportedReason)
                        false
                    }
                    !device.setAppLinks(packageName, 0, hosts.pmDomainsArgument) -> {
                        report.error("${device.serial}: $packageName is not installed; install the variant first")
                        false
                    }
                    else -> {
                        device.requestVerification(packageName)
                        true
                    }
                }
            }
        val results = awaitVerification(pending, packageName, hosts.concrete)

        val server = ServerFingerprints(fetcherFactory(assetLinksUrlOverrides.get()), packageName)
        for (device in pending) {
            val links = results[device.serial] ?: PackageAppLinks(emptyMap(), emptyList())
            logger.lifecycle("${device.serial} (API ${device.apiLevel}), $packageName, installed cert ${links.signatures.joinToString()}:")
            for (host in hosts.concrete) {
                val state = links.states[host] ?: "missing"
                val explanation =
                    if (state == "verified") {
                        AppLinksStateParser.describe(state)
                    } else {
                        explainFailure(state, links.signatures, server.forHost(host)).also {
                            report.error("${device.serial}: $host is '$state': $it")
                        }
                    }
                report.host("${device.serial}/$host", "device" to device.serial, "host" to host, "state" to state)
                logHost(host, state, explanation)
            }
        }
        report.finish(logger, reportFile.get().asFile, "App Links verification")
    }

    private fun explainFailure(
        state: String,
        installed: List<String>,
        server: Set<String>?,
    ): String =
        when {
            server == null ->
                "${AppLinksStateParser.describe(state)}; assetlinks.json could not be fetched from the build machine either"
            installed.any { it in server } ->
                "${AppLinksStateParser.describe(
                    state,
                )}; the server lists the installed cert, so the failure is on the device side (network, cached state, OEM verifier)"
            else ->
                "installed cert ${installed.joinToString()} is not in assetlinks.json (lists ${server.joinToString().ifEmpty {
                    "nothing for this package"
                }})"
        }

    /**
     * Polls every device until no host is still `none`, or the timeout passes. One extra re-verify request
     * is sent to devices that never answered, then the last observed state is returned regardless.
     */
    private fun awaitVerification(
        devices: List<AppLinksDevice>,
        packageName: String,
        hosts: Set<String>,
    ): Map<String, PackageAppLinks> {
        val results = mutableMapOf<String, PackageAppLinks>()
        var waiting = devices
        repeat(2) { attempt ->
            if (attempt == 1) {
                waiting.forEach {
                    logger.lifecycle("${it.serial}: verifier has not answered, sending one more re-verify request")
                    it.requestVerification(packageName)
                }
            }
            val deadline = System.currentTimeMillis() + timeoutSeconds.get() * 1000L
            while (waiting.isNotEmpty() && System.currentTimeMillis() <= deadline) {
                waiting =
                    waiting.filter { device ->
                        val links = device.appLinks(packageName) ?: PackageAppLinks(emptyMap(), emptyList())
                        results[device.serial] = links
                        hosts.any { (links.states[it] ?: "none") == "none" }
                    }
                if (waiting.isNotEmpty()) Thread.sleep(1000)
            }
            if (waiting.isEmpty()) return results
        }
        return results
    }

    /** Fetches each host's assetlinks.json at most once, remembering failures too. */
    private class ServerFingerprints(
        private val fetcher: AssetLinksFetcher,
        private val packageName: String,
    ) {
        private val cache = mutableMapOf<String, Set<String>?>()

        fun forHost(host: String): Set<String>? {
            if (host !in cache) {
                cache[host] =
                    fetcher
                        .fetch(host)
                        .takeIf { it.errors.isEmpty() }
                        ?.statementsFor(packageName)
                        ?.flatMap { it.fingerprints }
                        ?.toSet()
            }
            return cache[host]
        }
    }
}
