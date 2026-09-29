package io.github.savvasg28.applinks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/** Inputs every App Links task shares. */
abstract class AppLinksTask : DefaultTask() {

    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val applicationId: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input abstract val excludedHosts: SetProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init {
        // Every task here talks to a device or a server whose state is not a Gradle input.
        outputs.upToDateWhen { false }
    }

    protected fun newReport(taskKind: String) = Report(taskKind, variantName.get(), applicationId.get())

    protected fun hostsToHandle(): AutoVerifyHosts =
        ManifestParser.autoVerifyHosts(mergedManifest.get().asFile) - excludedHosts.get()

    protected fun logHost(host: String, state: String, explanation: String) =
        logger.lifecycle("  %-32s %-12s %s".format(host, state, explanation))
}

/** Adds the device plumbing used by approve and verify. */
abstract class DeviceAppLinksTask : AppLinksTask() {

    @get:Input abstract val deviceSerials: SetProperty<String>
    @get:Internal abstract val adbExecutable: Property<String>

    @get:Internal var adbFactory: (String) -> Adb = { ProcessAdb(it) }

    /** Connected devices, filtered by [deviceSerials]; API level is queried lazily per device. */
    protected fun connectedDevices(): List<AppLinksDevice> {
        val adb = adbFactory(adbExecutable.get())
        val wanted = deviceSerials.get()
        return adb.serials().filter { wanted.isEmpty() || it in wanted }.map { AppLinksDevice(adb, it) }
    }

    protected fun noDevicesMessage(): String =
        "no connected devices" + if (deviceSerials.get().isEmpty()) "." else " matching ${deviceSerials.get()}."
}
