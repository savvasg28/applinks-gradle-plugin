package io.github.savvasg28.applinks

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/** Collects findings for one task run, prints them uniformly and writes them as JSON for CI to archive. */
class Report(private val task: String, private val variant: String, private val applicationId: String) {
    private val hosts = linkedMapOf<String, MutableMap<String, Any?>>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val info = mutableListOf<String>()

    fun host(name: String, vararg fields: Pair<String, Any?>) {
        hosts.getOrPut(name) { linkedMapOf() }.putAll(fields)
    }

    fun error(message: String) { errors += message }
    fun warning(message: String) { warnings += message }
    fun note(message: String) { info += message }

    val passed: Boolean get() = errors.isEmpty()

    /**
     * Logs everything collected, writes the JSON file, then either throws (so the build fails) or, when
     * [failOnError] is false, logs the errors and returns. [subject] names the outcome, e.g. "App Links check".
     */
    fun finish(logger: Logger, file: File, subject: String, failOnError: Boolean = true) {
        info.forEach { logger.lifecycle("info: $it") }
        warnings.forEach { logger.warn("warning: $it") }
        write(file)
        if (passed) {
            logger.lifecycle("$subject passed for $applicationId. Report: $file")
            return
        }
        val summary = "$subject failed:\n  " + errors.joinToString("\n  ") + "\nReport: $file"
        if (failOnError) throw GradleException(summary) else logger.lifecycle(summary)
    }

    private fun write(file: File) {
        file.parentFile.mkdirs()
        val json = linkedMapOf(
            "task" to task,
            "variant" to variant,
            "applicationId" to applicationId,
            "passed" to passed,
            "hosts" to hosts,
            "errors" to errors,
            "warnings" to warnings,
            "info" to info,
        )
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)) + "\n")
    }
}
