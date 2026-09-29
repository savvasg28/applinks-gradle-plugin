# App Links Gradle plugin

Makes [Android App Links](https://developer.android.com/training/app-links) testable on debug builds, and catches App Link regressions before they reach users.

## The problem

Since Android 12 an `https://` link only opens your app once the device has [verified the domain](https://developer.android.com/training/app-links/verify-android-applinks) against the signing certificate listed in the site's [assetlinks.json](https://developer.android.com/training/app-links/configure-assetlinks). That breaks two things.

**Testing.** Debug builds are signed with each developer's own key, so verification fails and every App Link opens in the browser on the build you are working on.

**Regressions.** The server file can be [misconfigured](https://developer.android.com/training/app-links/troubleshoot) in a dozen ways, or list the upload key when [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) signs with another. The app build does not notice. Users do.

The build already knows the applicationId, the hosts in the merged manifest and the signing key. This plugin uses that.

## What it does

Three tasks per variant.

`autoApproveAppLinks<Variant>` runs after every `assemble` and `install` of a debuggable variant and force-approves the app's hosts on each connected device with [`pm set-app-links`](https://developer.android.com/training/app-links/verify-android-applinks#manual-verification). The approval survives the in-place reinstalls Android Studio does, so it effectively runs once per device. It never fails a build and never touches non-debuggable variants unless you opt them in.

`checkAppLinks<Variant>` is the PR gate. No device, about a second. It computes the fingerprint from the variant's signing config, fetches each host's assetlinks.json under [Android's rules](https://developer.android.com/training/app-links/configure-assetlinks), and fails if the file is missing, malformed, names another package or does not list the key. Manifest filters Android would never verify fail too.

`verifyAppLinks<Variant>` is the ground truth for nightly and release pipelines. It makes the device's own verifier run again and fails unless every host is `verified`. On failure it reads the installed certificate from the device and says whether the server or the key is wrong.

Filters without `android:autoVerify="true"` are reported as unverified by design and never fail anything.

## Usage

```kotlin
plugins {
    id("com.android.application")
    id("io.github.savvasg28.applinks") version "0.1.0"
}
```

Debug builds need nothing else. The other two run by hand or in CI:

```bash
./gradlew checkAppLinksRelease
./gradlew installRelease verifyAppLinksRelease
```

Every task writes `build/reports/app-links/<task name>.json`.

## Configuration

All optional.

```kotlin
appLinks {
    autoApproveDebuggableVariants = true        // the default
    allowedVariants.add("staging")              // non-debuggable variants approveAppLinks may touch
    excludedHosts.add("prod.example.com")       // hosts every task leaves alone
    additionalCertificateFingerprints.add("AB:CD:...")   // certificates assetlinks.json must list besides the variant's own
    assetLinksUrlOverrides.put("example.com", "https://staging.example.com/.well-known/assetlinks.json")
    deviceSerials.add("emulator-5554")          // default: every connected device
    verifyTimeoutSeconds = 30
    adbExecutable.set("/custom/adb")            // default: the SDK's adb, as resolved by AGP
}
```

`checkAppLinks` always expects the certificate of the variant's signing config. With Play App Signing users' devices see Google's key instead, so add its fingerprint from Play Console under App integrity.

## Verify in CI

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

The release variant needs a signing config, and that key's fingerprint must be in assetlinks.json next to the Play key.

## Notes

- Gradle 8.0+ and Android Gradle Plugin 8.0+. Built against AGP 8.7.3, tested on Gradle 8.13.
- Devices below API 31 are skipped.
- A wildcard host makes approve pass `all`, so excluded hosts cannot be honoured on that run.
- Undo on a device: `adb shell pm set-app-links --package <id> 0 all`, then `adb shell pm verify-app-links --re-verify <id>`.

## Development

```bash
./gradlew check            # tests, ktlint, detekt, plugin validation, API compatibility
./gradlew spotlessApply    # fix formatting
./gradlew apiDump          # after an intended change to the public API
```

## License

[Apache 2.0](LICENSE)
