package io.github.savvasg28.applinks

import groovy.json.JsonException
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI

/** One Android statement from an assetlinks.json file. */
internal data class AndroidStatement(
    val packageName: String,
    val fingerprints: List<String>,
    val relations: List<String>,
)

internal data class AssetLinksResult(
    val url: String,
    val statements: List<AndroidStatement>,
    /** Problems with the transport or the document that make the file unusable for verification. */
    val errors: List<String>,
    /** Things Android tolerates but that are worth fixing. */
    val warnings: List<String>,
) {
    fun statementsFor(packageName: String) = statements.filter { it.packageName == packageName }
}

/**
 * Fetches and validates `https://<host>/.well-known/assetlinks.json` the way Android's verifier does:
 * HTTPS, no redirects, `application/json`, a JSON array of statements, optionally following `include`.
 */
internal class AssetLinksFetcher(
    private val urlOverrides: Map<String, String> = emptyMap(),
    private val timeoutMillis: Int = 10_000,
    private val userAgent: String = "applinks-gradle-plugin",
) {
    companion object {
        const val HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls"
        const val WELL_KNOWN = "/.well-known/assetlinks.json"
    }

    fun fetch(host: String): AssetLinksResult {
        val url = urlOverrides[host] ?: "https://$host$WELL_KNOWN"
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val body = download(url, errors) ?: return AssetLinksResult(url, emptyList(), errors, warnings)
        val statements = parseStatements(body, url, errors, warnings, depth = 0)
        return AssetLinksResult(url, statements, errors, warnings)
    }

    /**
     * Returns the body, or null after adding the reason to [errors]. URL parsing, connecting and reading throw
     * unrelated exception families, and every one of them means "unreachable", hence the generic catch.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun download(
        url: String,
        errors: MutableList<String>,
    ): String? {
        val body =
            try {
                val connection =
                    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = timeoutMillis
                        readTimeout = timeoutMillis
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Accept", "application/json")
                    }
                val status = connection.responseCode
                val contentType = connection.contentType.orEmpty()
                when {
                    status in 300..399 ->
                        Failure(
                            "HTTP $status redirect to ${connection.getHeaderField("Location")}. " +
                                "Android does not follow redirects for assetlinks.json.",
                        )
                    status != 200 -> Failure("HTTP $status")
                    !contentType.startsWith("application/json") ->
                        Failure("Content-Type is '$contentType', must be application/json")
                    // Closing the stream (not disconnect()) keeps the socket alive for an `include` on the same origin.
                    else -> connection.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                Failure("${e.javaClass.simpleName}: ${e.message}")
            }
        if (body is Failure) errors += "$url: ${body.reason}"
        return body as? String
    }

    private class Failure(
        val reason: String,
    )

    private fun parseStatements(
        body: String,
        url: String,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        depth: Int,
    ): List<AndroidStatement> {
        // Gradle bundles Groovy's JSON support, so this adds nothing to consumers' build classpath.
        val root =
            try {
                JsonSlurper().parseText(body)
            } catch (e: JsonException) {
                errors += "$url: invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}"
                return emptyList()
            }
        if (root !is List<*>) {
            errors += "$url: top level must be a JSON array of statements"
            return emptyList()
        }
        val statements = mutableListOf<AndroidStatement>()
        root.forEachIndexed { index, entry ->
            if (entry !is Map<*, *>) {
                errors += "$url: statement $index is not an object"
                return@forEachIndexed
            }
            val include = entry["include"] as? String
            if (include != null) {
                if (depth >= 1) {
                    warnings += "$url: nested include '$include' ignored (Android follows one level)"
                } else {
                    val included = download(include, errors)
                    if (included != null) statements += parseStatements(included, include, errors, warnings, depth + 1)
                }
                return@forEachIndexed
            }
            val relations = (entry["relation"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            val target = entry["target"] as? Map<*, *>
            if (target == null || target["namespace"] != "android_app") return@forEachIndexed
            val packageName = target["package_name"] as? String
            if (packageName == null) {
                errors += "$url: statement $index has no package_name"
                return@forEachIndexed
            }
            val rawFingerprints = (target["sha256_cert_fingerprints"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            rawFingerprints.forEach { raw ->
                if (raw != raw.uppercase()) warnings += "$url: fingerprint for $packageName is not upper case: $raw"
                if (!Fingerprints.isWellFormed(Fingerprints.normalise(raw))) {
                    errors += "$url: malformed fingerprint for $packageName: $raw"
                }
            }
            if (HANDLE_ALL_URLS !in relations) {
                warnings += "$url: statement for $packageName lacks relation '$HANDLE_ALL_URLS', so it does not grant App Links"
            }
            statements += AndroidStatement(packageName, rawFingerprints.map(Fingerprints::normalise), relations)
        }
        return statements
    }
}
