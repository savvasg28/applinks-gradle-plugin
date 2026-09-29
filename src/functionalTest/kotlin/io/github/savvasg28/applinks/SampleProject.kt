package io.github.savvasg28.applinks

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * A minimal Android app for TestKit runs. One directory per test class so AGP's outputs are reused
 * between tests; [write] refreshes the build files each test.
 */
class SampleProject(
    val dir: File,
) {
    val adbLog = File(dir, "adb.log")

    /** A throwaway signing key, so expectations never depend on the machine's debug keystore. */
    val keystore = File(dir, "test.jks")
    val fingerprint: String by lazy {
        keytool(
            "-genkeypair",
            "-keystore",
            keystore.path,
            "-storepass",
            "secret",
            "-keypass",
            "secret",
            "-alias",
            "test",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-dname",
            "CN=test",
            "-validity",
            "1",
            "-storetype",
            "PKCS12",
        )
        Fingerprints.fromKeystore(keystore, "secret", "test", "PKCS12")
    }

    fun write(
        appLinks: String = "",
        extraBuildTypes: String = "",
        extraFilters: String = "",
    ) {
        fingerprint // make sure the keystore exists before the build file references it
        adbLog.delete()
        File(dir, "settings.gradle").writeText("rootProject.name = 'sample'\n")
        File(dir, "local.properties").writeText("sdk.dir=${sdkDir()}\n")
        File(dir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")
        // AGP and the plugin under test must share one classloader, so both go on the buildscript classpath.
        val classpath =
            GradleRunner
                .create()
                .withPluginClasspath()
                .pluginClasspath
                .joinToString(", ") { "'${it.absolutePath}'" }
        File(dir, "build.gradle").writeText(
            """
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:8.7.3'
                    classpath files($classpath)
                }
            }
            apply plugin: 'com.android.application'
            apply plugin: 'io.github.savvasg28.applinks'
            repositories { google(); mavenCentral() }
            android {
                namespace 'uk.co.sample'
                compileSdk 35
                defaultConfig { applicationId 'uk.co.sample'; minSdk 24 }
                signingConfigs {
                    test {
                        storeFile file('${keystore.absolutePath}')
                        storePassword 'secret'; keyAlias 'test'; keyPassword 'secret'; storeType 'PKCS12'
                    }
                }
                buildTypes {
                    debug { applicationIdSuffix '.debug' }
                    // Non-debuggable but signed with a known key, so tests know the expected fingerprint.
                    staging { initWith release; signingConfig signingConfigs.test }
                    $extraBuildTypes
                }
            }
            appLinks {
                $appLinks
            }
            """.trimIndent(),
        )
        File(dir, "src/main").mkdirs()
        File(dir, "src/main/AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application android:label="Sample">
                <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter android:autoVerify="true">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                    <category android:name="android.intent.category.BROWSABLE"/>
                    <data android:scheme="https" android:host="sample.uk"/>
                    <data android:host="app.sample.uk"/>
                    <data android:host="excluded.sample.uk"/>
                  </intent-filter>
                  $extraFilters
                </activity>
              </application>
            </manifest>
            """.trimIndent(),
        )
    }

    fun appendBuild(text: String) = File(dir, "build.gradle").appendText("\n$text\n")

    fun run(vararg args: String): GradleRunner = GradleRunner.create().withProjectDir(dir).withArguments(*args, "--stacktrace")

    fun report(name: String): String = File(dir, "build/reports/app-links/$name.json").readText()

    /**
     * A shell script standing in for adb: records every call to [adbLog] and answers from [devices]
     * (serial to API level) and [getAppLinks], a bash snippet printing `pm get-app-links` output for `$pkg`.
     */
    fun fakeAdb(
        name: String,
        devices: Map<String, Int>,
        getAppLinks: String,
    ): File {
        val deviceList = devices.keys.joinToString("") { "$it\\tdevice\\n" }
        val apiCases = devices.entries.joinToString(" ") { (serial, api) -> "*$serial*) echo $api ;;" }
        val file = File(dir, name)
        file.writeText(
            """
            #!/usr/bin/env bash
            all="${'$'}*"
            echo "${'$'}all" >> "${adbLog.absolutePath}"
            case "${'$'}all" in
              devices) printf 'List of devices attached\n${deviceList}offline-1\toffline\n\n' ;;
              *"getprop ro.build.version.sdk"*) case "${'$'}all" in $apiCases esac ;;
              *"pm set-app-links"*|*"pm verify-app-links"*) : ;;
              *"pm get-app-links"*) pkg="${'$'}{all##* }"; $getAppLinks ;;
              *) echo "unexpected adb call: ${'$'}all" >&2; exit 1 ;;
            esac
            """.trimIndent(),
        )
        file.setExecutable(true)
        return file
    }

    private fun keytool(vararg args: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(keytool, *args).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    }

    companion object {
        fun sdkDir(): String {
            val dir =
                System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: "${System.getProperty("user.home")}/Library/Android/sdk"
            assumeTrue(File(dir, "platform-tools").isDirectory, "Android SDK not found; set ANDROID_HOME")
            return dir
        }

        /** Canned `pm get-app-links` output with the given host states. */
        fun appLinksOutput(
            signature: String,
            vararg states: Pair<String, String>,
        ): String =
            "printf '  %s:\\n    ID: 1\\n    Signatures: [$signature]\\n    Domain verification state:\\n" +
                states.joinToString("") { (h, s) -> "      $h: $s\\n" } +
                "  User 0:\\n    Verification link handling allowed: true\\n' \"\$pkg\""
    }
}
