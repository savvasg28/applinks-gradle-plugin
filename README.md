# App Links Gradle plugin

This document explains how to install, configure, and run the App Links Gradle plugin. The plugin makes [Android App Links](https://developer.android.com/training/app-links) work on debug builds and catches App Link regressions before users do.

**Audience:** Android developers who maintain an app that handles App Links and who build the app with Gradle.

**Prerequisites:** You know what App Links are and how [Android verifies them](https://developer.android.com/training/app-links/verify-android-applinks) against the [assetlinks.json](https://developer.android.com/training/app-links/configure-assetlinks) file on your website.

**Not covered:** How to add App Link intent filters to your manifest or how to host assetlinks.json. The Android documentation linked above covers both.

## Why you need this plugin

Since Android 12, an `https://` link opens your app only after the device has verified the domain. The device fetches `https://<host>/.well-known/assetlinks.json` and looks for the SHA-256 fingerprint of the certificate that signed the installed APK. When the fingerprint is missing, the link opens in the browser instead. This rule causes the following two problems:

- **Debug builds never pass verification.** Each developer signs debug builds with a local debug key, and assetlinks.json does not list those keys. Every App Link opens in the browser on the build you are working on.
- **Server regressions go unnoticed.** Someone can [misconfigure](https://developer.android.com/training/app-links/troubleshoot) assetlinks.json, or list the upload key while [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) signs with a different key. The app build does not notice. Users notice.

The Gradle build already knows the applicationId, the hosts in the merged manifest, and the signing key. The plugin uses that information to solve both problems.

## How the plugin works

The plugin adds the following three tasks to every variant of an application module:

| Task | Needs | Runs | Fails when |
|---|---|---|---|
| `autoApproveAppLinks<Variant>` | A connected device | Automatically after `assemble` and `install` of debuggable variants | Never. Problems become warnings. |
| `checkAppLinks<Variant>` | Network access | When you run it, typically on every pull request | assetlinks.json is missing, malformed, names another package, or does not list the signing key |
| `verifyAppLinks<Variant>` | An installed build on a device with network access | When you run it, typically nightly and before a release | The device's verifier does not report `verified` for every host |

The plugin reads hosts only from intent filters that declare `android:autoVerify="true"`. The plugin reports other web filters, such as a payment provider's redirect host that you do not own, as unverified by design and never fails on them.

### Approve App Links on debug builds

`autoApproveAppLinks<Variant>` tells each connected device to treat the app's hosts as verified. The task runs the following command for the variant's applicationId and hosts:

```
pm set-app-links --package <applicationId> 2 <host> [<host>...]
```

State 2 means `STATE_APPROVED`, and the device's verifier cannot overwrite that state. The approval survives the in-place reinstalls that Android Studio performs. The approval is lost only when you uninstall the app or wipe the device.

Android Studio installs the APK after Gradle has finished. On a fresh device the task therefore leaves a small watcher running on the device. The watcher approves the hosts as soon as the package appears, then exits. The watcher gives up after two minutes.

The task never fails a build. The task never touches a non-debuggable variant unless you list that variant in `allowedVariants`.

### Check assetlinks.json without a device

`checkAppLinks<Variant>` validates both sides of App Link verification in about a second. The task computes the expected fingerprint from the variant's signing config and fetches each host's assetlinks.json the way Android does: over HTTPS, without following redirects, and requiring the `application/json` content type. The task fails when a file is missing, malformed, names another package, or does not list the key. The task also fails on manifest filters that Android would never verify, such as an autoVerify filter without the `BROWSABLE` category.

### Verify App Links on a device

`verifyAppLinks<Variant>` asks the device's own verifier for the ground truth. The task resets the cached result for the app's hosts, requests a new verification, waits for the result, and fails unless every host is `verified`. When a host fails, the task reads the installed certificate from the device and reports whether the server or the key is wrong.

## Install the plugin

Add the plugin to the application module's build script:

```kotlin
plugins {
    id("com.android.application")
    id("io.github.savvasg28.applinks") version "0.1.3"
}
```

Debug builds need no further configuration. The next time you run the app from Android Studio, the build prints output like the following:

```
> Task :app:autoApproveAppLinksDebug
emulator-5554 (API 35), com.example.app.debug:
  example.com                      approved     force-approved via adb, opens in app
  app.example.com                  approved     force-approved via adb, opens in app
App Links approval passed for com.example.app.debug. Report: app/build/reports/app-links/autoApproveAppLinksDebug.json
```

To run the other two tasks, use the following commands:

```bash
./gradlew checkAppLinksRelease
./gradlew installRelease verifyAppLinksRelease
```

Every task writes a JSON report to `build/reports/app-links/<task name>.json`.

## Configure the plugin

All settings are optional. The following example shows every setting with its default or a typical value:

```kotlin
appLinks {
    autoApproveDebuggableVariants = true   // Default. Set to false to run approveAppLinks<Variant> by hand only.
    allowedVariants.add("staging")         // Non-debuggable variants that approveAppLinks<Variant> may touch.
    excludedHosts.add("prod.example.com")  // Hosts that every task leaves alone.
    additionalCertificateFingerprints.add("AB:CD:...")  // Certificates that assetlinks.json must list besides the variant's own.
    assetLinksUrlOverrides.put("example.com", "https://staging.example.com/.well-known/assetlinks.json")
    deviceSerials.add("emulator-5554")     // Default: every connected device.
    verifyTimeoutSeconds = 30              // How long verifyAppLinks<Variant> waits for the device's verifier.
    adbExecutable.set("/custom/adb")       // Default: the adb of the SDK that the Android Gradle Plugin resolved.
}
```

`checkAppLinks<Variant>` expects the certificate that the variant is signed with. The plugin resolves that certificate in the same order as the Android Gradle Plugin: `android.injected.signing.*` properties, then the build type, then the first product flavor, then `defaultConfig`. With Play App Signing, users' devices see Google's key instead of yours. Copy that key's fingerprint from Play Console under **App integrity** and add it to `additionalCertificateFingerprints`.

## Run verification in CI

The following GitHub Actions steps boot an emulator, install the release build, verify the App Links, and keep the report even when the job fails:

```yaml
- uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 34
    target: google_apis
    script: ./gradlew installRelease verifyAppLinksRelease
- uses: actions/upload-artifact@v4
  if: always()
  with: { name: app-links-report, path: app/build/reports/app-links/ }
```

The release variant needs a signing config, and assetlinks.json must list that key's fingerprint next to the Play App Signing key.

## Limitations

- The plugin requires Gradle 8.0 or later and Android Gradle Plugin 8.0 or later. The plugin is built against AGP 8.7.3 and tested on Gradle 8.13.
- The plugin skips devices below API 31, because those devices use the older verification model.
- For a wildcard host such as `*.example.com`, `checkAppLinks<Variant>` fetches assetlinks.json from `example.com`, which is where Android's verifier looks. The device tasks treat a wildcard host like any other host.
- To undo an approval by hand, run `adb shell pm set-app-links --package <id> 0 all` and then `adb shell pm verify-app-links --re-verify <id>`.

## Develop the plugin

Use the following commands while working on the plugin:

```bash
./gradlew check            # Runs the tests, ktlint, detekt, plugin validation, and the API compatibility check.
./gradlew spotlessApply    # Fixes formatting.
./gradlew apiDump          # Records an intended change to the public API.
```

## License

The plugin is licensed under [Apache 2.0](LICENSE).
