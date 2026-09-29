package io.github.savvasg28.applinks

import org.gradle.api.Plugin
import org.gradle.api.Project

class AppLinksPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("appLinks", AppLinksExtension::class.java).apply {
            // Approving debuggable builds automatically is the point of the plugin, so it is on unless switched off.
            autoApproveDebuggableVariants.convention(true)
            verifyTimeoutSeconds.convention(30)
        }

        // AGP classes are referenced only inside AndroidTaskRegistrar, which the JVM loads lazily here,
        // so applying this plugin without AGP on the classpath degrades to the warning below instead of
        // a NoClassDefFoundError.
        project.pluginManager.withPlugin("com.android.application") {
            AndroidTaskRegistrar.register(project, extension)
        }
        project.afterEvaluate {
            if (!project.pluginManager.hasPlugin("com.android.application")) {
                project.logger.warn(
                    "io.github.savvasg28.applinks: 'com.android.application' is not applied to " +
                        "${project.path}; no App Links tasks were registered."
                )
            }
        }
    }

    companion object {
        const val TASK_GROUP = "app links"
    }
}
