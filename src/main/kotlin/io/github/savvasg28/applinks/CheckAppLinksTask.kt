package io.github.savvasg28.applinks

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.util.concurrent.Executors

/**
 * Validates App Links without a device: the manifest side (filters that Android can verify) and the
 * server side (assetlinks.json reachable, well formed, naming this applicationId and this signing key).
 * Fast enough for every PR. It reimplements the verifier's rules; `verifyAppLinks<Variant>` asks the
 * real verifier on a device.
 */
abstract class CheckAppLinksTask : AppLinksTask() {

    /** Fingerprints configured explicitly, on top of the signing config's. */
    @get:Input abstract val extraFingerprints: SetProperty<String>
    @get:Input abstract val assetLinksUrlOverrides: MapProperty<String, String>

    // Signing details are secrets and machine paths: never task inputs.
    @get:Internal abstract val signingStoreFile: RegularFileProperty
    @get:Internal abstract val signingStorePassword: Property<String>
    @get:Internal abstract val signingKeyAlias: Property<String>
    @get:Internal abstract val signingStoreType: Property<String>

    @get:Internal
    var fetcherFactory: (Map<String, String>) -> AssetLinksFetcher = { AssetLinksFetcher(it) }

    @TaskAction
    fun check() {
        val packageName = applicationId.get()
        val report = newReport("checkAppLinks")
        val expected = expectedFingerprints(report)

        val (verifiable, unverified) = ManifestParser.linkFilters(mergedManifest.get().asFile)
            .filter { it.isWeb }.partition { it.autoVerify }
        unverified.forEach {
            report.note("${it.activity}: unverified web link for ${it.hosts.joinToString()} (no autoVerify), by design")
        }
        verifiable.forEach { filter ->
            filter.verificationProblems().forEach {
                report.error("${filter.activity}: $it, Android will not verify ${filter.hosts.joinToString()}")
            }
        }

        val hosts = ManifestParser.autoVerifyHosts(verifiable) - excludedHosts.get()
        if (hosts.isEmpty) report.note("No autoVerify hosts to check.")
        hosts.wildcards.forEach {
            report.host(it, "status" to "skipped")
            report.warning("$it: wildcard hosts are verified per concrete host; check each one explicitly")
        }

        for ((host, result) in fetchAll(hosts.concrete)) {
            result.errors.forEach(report::error)
            result.warnings.forEach(report::warning)
            if (result.errors.isNotEmpty()) {
                report.host(host, "status" to "unreachable", "url" to result.url)
                continue
            }
            val statements = result.statementsFor(packageName)
            val listed = statements.filter { AssetLinksFetcher.HANDLE_ALL_URLS in it.relations }.flatMap { it.fingerprints }.toSet()
            val missing = expected - listed
            val problem = when {
                statements.isEmpty() ->
                    "${result.url} has no statement for package $packageName (has: ${result.statements.map { it.packageName }.distinct()})"
                listed.isEmpty() ->
                    "statements for $packageName grant no App Links (relation ${AssetLinksFetcher.HANDLE_ALL_URLS} missing)"
                missing.isNotEmpty() ->
                    "${result.url} does not list ${missing.joinToString()}; it lists ${listed.joinToString()}"
                else -> null
            }
            problem?.let { report.error("$host: $it") }
            report.host(
                host,
                "url" to result.url, "listed" to listed.toList(), "expected" to expected.toList(),
                "status" to if (problem == null) "ok" else "failed",
            )
        }

        report.finish(logger, reportFile.get().asFile, "App Links check")
    }

    /** Hosts are independent, so fetch them concurrently; results come back in host order. */
    private fun fetchAll(hosts: Set<String>): List<Pair<String, AssetLinksResult>> {
        if (hosts.isEmpty()) return emptyList()
        val fetcher = fetcherFactory(assetLinksUrlOverrides.get())
        val executor = Executors.newFixedThreadPool(minOf(hosts.size, 4))
        try {
            return hosts.map { host -> host to executor.submit<AssetLinksResult> { fetcher.fetch(host) } }
                .map { (host, future) -> host to future.get() }
        } finally {
            executor.shutdown()
        }
    }

    private fun expectedFingerprints(report: Report): Set<String> {
        val (wellFormed, malformed) = extraFingerprints.get().map(Fingerprints::normalise).partition(Fingerprints::isWellFormed)
        malformed.forEach { report.error("configured fingerprint is malformed: $it") }
        val fingerprints = wellFormed.toMutableSet()
        val store = signingStoreFile.orNull?.asFile
        if (store != null && signingKeyAlias.isPresent) {
            try {
                fingerprints += Fingerprints.fromKeystore(store, signingStorePassword.orNull, signingKeyAlias.get(), signingStoreType.orNull)
            } catch (e: Exception) {
                report.error("could not read signing certificate from ${store.path}: ${e.message}")
            }
        } else if (fingerprints.isEmpty()) {
            report.error("variant ${variantName.get()} has no signing config and appLinks.additionalCertificateFingerprints is empty; nothing to compare against")
        }
        return fingerprints
    }
}
