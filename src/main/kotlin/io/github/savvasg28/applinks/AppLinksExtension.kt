package io.github.savvasg28.applinks

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configured via the `appLinks { }` block in an application module.
 */
abstract class AppLinksExtension {
    /**
     * True by default. `assemble<Variant>` and `install<Variant>` of every debuggable variant are finalized by
     * `autoApproveAppLinks<Variant>`, so Android Studio's Run button and `./gradlew installDebug` keep App Links
     * approved without anyone remembering to. That task never fails the build: no connected device, or an app
     * that is not installed yet, only logs a line. Set to false to run `approveAppLinks<Variant>` by hand only.
     */
    abstract val autoApproveDebuggableVariants: Property<Boolean>

    /**
     * Names of non-debuggable variants (for example `staging`) that may still be force-approved.
     * Debuggable variants are always allowed. Empty by default.
     */
    abstract val allowedVariants: SetProperty<String>

    /** Hosts to leave untouched even though the manifest declares them with autoVerify. */
    abstract val excludedHosts: SetProperty<String>

    /** Restrict to these device serials. Empty means every connected device. */
    abstract val deviceSerials: SetProperty<String>

    /**
     * Certificate fingerprints (`AA:BB:...`) that assetlinks.json must list besides the one computed from the
     * variant's signing config. Typically the Play App Signing key, which never exists locally.
     */
    abstract val additionalCertificateFingerprints: SetProperty<String>

    /**
     * Fetch assetlinks.json for a host from a different URL, for example a staging deployment or a local
     * server in tests. Keys are hosts, values are full URLs.
     */
    abstract val assetLinksUrlOverrides: MapProperty<String, String>

    /** How long `verifyAppLinks<Variant>` waits for the device's verifier. Default 30. */
    abstract val verifyTimeoutSeconds: Property<Int>

    /** Path to the adb executable. Defaults to the one from the SDK the Android Gradle Plugin resolved. */
    abstract val adbExecutable: Property<String>
}
