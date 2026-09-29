package io.github.savvasg28.applinks

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Runs `pm set-app-links --package <id> 2 <hosts>` on every connected Android 12+ device, then reads the
 * state back. State 2 (STATE_APPROVED) tells the OS to treat the domains as verified and stops the
 * verification agent from overriding that. The approval survives in-place reinstalls and is lost on
 * uninstall.
 *
 * Registered twice per debuggable variant: `approveAppLinks<Variant>` for people, which fails on problems,
 * and `autoApproveAppLinks<Variant>` as a finalizer of assemble/install, which only logs them.
 */
@DisableCachingByDefault(because = "Talks to devices or servers whose state is not a build input")
abstract class ApproveAppLinksTask : DeviceAppLinksTask() {
    @get:Input abstract val debuggable: Property<Boolean>

    @get:Input abstract val allowedVariants: SetProperty<String>

    @get:Input abstract val failOnError: Property<Boolean>

    @TaskAction
    fun approve() {
        val variant = variantName.get()
        val packageName = applicationId.get()
        val report = newReport("approveAppLinks")

        if (!debuggable.get() && variant !in allowedVariants.get()) {
            report.error(
                "Refusing to force-approve App Links for non-debuggable variant '$variant' ($packageName). " +
                    "A release build failing verification is a real bug. If this variant is meant for testing, add " +
                    "appLinks { allowedVariants.add(\"$variant\") }.",
            )
            return finish(report)
        }

        val hosts =
            try {
                hostsToHandle()
            } catch (e: IllegalArgumentException) {
                report.error(e.message.orEmpty())
                return finish(report)
            }
        if (hosts.isEmpty) {
            report.note("No autoVerify hosts to approve.")
            return finish(report)
        }
        if (hosts.hasWildcard && excludedHosts.get().isNotEmpty()) {
            report.warning("wildcard host present, approving 'all'; excludedHosts cannot be honoured for this run")
        }

        val devices =
            try {
                connectedDevices()
            } catch (e: AdbException) {
                report.error("App Links not approved: ${e.message}")
                return finish(report)
            }
        if (devices.isEmpty()) report.error("App Links not approved: ${noDevicesMessage()}")

        for (device in devices) {
            try {
                approveOn(device, packageName, hosts, report)
            } catch (e: AdbException) {
                report.error("${device.serial}: ${e.message}")
            }
        }
        finish(report)
    }

    private fun approveOn(
        device: AppLinksDevice,
        packageName: String,
        hosts: AutoVerifyHosts,
        report: Report,
    ) {
        if (!device.supportsDomainVerification) {
            report.note(device.unsupportedReason)
            return
        }
        val links = if (device.setAppLinks(packageName, 2, hosts.pmDomainsArgument)) device.appLinks(packageName) else null
        if (links == null) {
            report.note("${device.serial}: $packageName is not installed yet, nothing approved.")
            return
        }
        logger.lifecycle("${device.serial} (API ${device.apiLevel}), $packageName:")
        for (host in hosts.concrete + (links.states.keys - hosts.concrete)) {
            val state = links.states[host] ?: "missing"
            logHost(host, state, AppLinksStateParser.describe(state))
            report.host("${device.serial}/$host", "device" to device.serial, "host" to host, "state" to state)
            if (host in hosts.concrete && state != "approved") {
                report.error("${device.serial}: $host is '$state', expected 'approved'")
            }
        }
    }

    private fun finish(report: Report) = report.finish(logger, reportFile.get().asFile, "App Links approval", failOnError.get())
}
