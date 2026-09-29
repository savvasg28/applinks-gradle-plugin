package io.github.savvasg28.applinks

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project

/** The only class that touches AGP types. See [AppLinksPlugin] for why. */
internal object AndroidTaskRegistrar {

    fun register(project: Project, extension: AppLinksExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val android = project.extensions.getByType(ApplicationExtension::class.java)
        val reportDir = project.layout.buildDirectory.dir("reports/app-links")
        // AGP has already resolved the SDK for this project; an explicit path still wins.
        val adb = extension.adbExecutable.orElse(components.sdkComponents.adb.map { it.asFile.absolutePath })

        components.onVariants(components.selector().all()) { variant ->
            val capitalised = variant.name.replaceFirstChar { it.uppercase() }
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)

            fun <T : AppLinksTask> register(name: String, type: Class<T>, description: String, configure: (T) -> Unit) =
                project.tasks.register(name, type) { task ->
                    task.group = AppLinksPlugin.TASK_GROUP
                    task.description = description
                    task.variantName.set(variant.name)
                    task.applicationId.set(variant.applicationId)
                    task.mergedManifest.set(manifest)
                    task.excludedHosts.set(extension.excludedHosts)
                    task.reportFile.set(reportDir.map { it.file("$name.json") })
                    if (task is DeviceAppLinksTask) {
                        task.deviceSerials.set(extension.deviceSerials)
                        task.adbExecutable.set(adb)
                    }
                    configure(task)
                }

            fun approve(name: String, failOnError: Boolean, description: String) =
                register(name, ApproveAppLinksTask::class.java, description) { task ->
                    task.debuggable.set(variant.debuggable)
                    task.allowedVariants.set(extension.allowedVariants)
                    task.failOnError.set(failOnError)
                }

            approve(
                "approveAppLinks$capitalised", failOnError = true,
                "Force-approves autoVerify App Link hosts of the ${variant.name} variant on connected devices.",
            )
            if (variant.debuggable) {
                val auto = approve(
                    "autoApproveAppLinks$capitalised", failOnError = false,
                    "Runs after assemble/install of ${variant.name}; like approveAppLinks but never fails the build.",
                )
                auto.configure { it.group = null }
                val hooks = setOf("assemble$capitalised", "install$capitalised")
                // configureEach is lazy per task and, unlike named(Spec), exists on every Gradle 8.x.
                project.tasks.configureEach { hook ->
                    if (hook.name in hooks && extension.autoApproveDebuggableVariants.get()) hook.finalizedBy(auto)
                }
            }

            register(
                "checkAppLinks$capitalised", CheckAppLinksTask::class.java,
                "Checks the ${variant.name} manifest and each host's assetlinks.json without a device.",
            ) { task ->
                task.extraFingerprints.set(extension.additionalCertificateFingerprints)
                task.assetLinksUrlOverrides.set(extension.assetLinksUrlOverrides)
                signingConfigFor(android, variant)?.let { signing ->
                    signing.storeFile?.let { task.signingStoreFile.set(it) }
                    signing.storePassword?.let { task.signingStorePassword.set(it) }
                    signing.keyAlias?.let { task.signingKeyAlias.set(it) }
                    signing.storeType?.let { task.signingStoreType.set(it) }
                }
            }

            register(
                "verifyAppLinks$capitalised", VerifyAppLinksTask::class.java,
                "Runs real App Links verification for the installed ${variant.name} variant on connected devices.",
            ) { task ->
                task.debuggable.set(variant.debuggable)
                task.assetLinksUrlOverrides.set(extension.assetLinksUrlOverrides)
                task.timeoutSeconds.set(extension.verifyTimeoutSeconds)
            }
        }
    }

    /** Build type wins, then the first flavor that sets one, mirroring AGP. */
    private fun signingConfigFor(android: ApplicationExtension, variant: ApplicationVariant): ApkSigningConfig? {
        val fromBuildType = variant.buildType?.let { android.buildTypes.findByName(it)?.signingConfig }
        if (fromBuildType != null) return fromBuildType
        return variant.productFlavors.asSequence()
            .mapNotNull { (_, flavor) -> android.productFlavors.findByName(flavor)?.signingConfig }
            .firstOrNull()
    }
}
