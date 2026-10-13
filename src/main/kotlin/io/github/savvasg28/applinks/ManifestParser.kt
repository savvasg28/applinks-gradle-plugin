package io.github.savvasg28.applinks

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One `<intent-filter>` that handles VIEW intents with a scheme, i.e. a deep link or App Link. */
internal data class LinkFilter(
    val activity: String,
    val autoVerify: Boolean,
    val schemes: Set<String>,
    val hosts: Set<String>,
    val categories: Set<String>,
) {
    val isWeb: Boolean get() = schemes.any { it == "http" || it == "https" }

    /** Android only attempts verification for web filters marked autoVerify. */
    val isVerifiable: Boolean get() = autoVerify && isWeb

    /** Problems that stop Android from ever verifying this filter, per the App Links docs. */
    fun verificationProblems(): List<String> =
        buildList {
            if ("android.intent.category.DEFAULT" !in categories) add("missing category DEFAULT")
            if ("android.intent.category.BROWSABLE" !in categories) add("missing category BROWSABLE")
            if ("https" !in schemes) add("no https scheme, only ${schemes.joinToString()}")
            if (hosts.isEmpty()) add("no android:host")
        }
}

/**
 * The hosts Android will try to verify for a package. `pm get-app-links` lists a wildcard such as
 * `*.example.com` literally and `pm set-app-links` accepts it, so wildcards are ordinary hosts to the
 * device. Only the server check treats them differently, because the verifier fetches the base domain.
 */
internal data class AutoVerifyHosts(
    val concrete: Set<String>,
    val wildcards: Set<String>,
) {
    val isEmpty: Boolean get() = concrete.isEmpty() && wildcards.isEmpty()
    val all: Set<String> get() = concrete + wildcards

    /** The `<DOMAINS>` argument for `pm set-app-links`. */
    val pmDomainsArgument: String get() = all.joinToString(" ")

    operator fun minus(excluded: Set<String>): AutoVerifyHosts = AutoVerifyHosts(concrete - excluded, wildcards - excluded)

    companion object {
        private val SAFE_HOST = Regex("[A-Za-z0-9.*-]+")

        /** `*.example.com` is verified against `https://example.com/.well-known/assetlinks.json` (AOSP verifier behaviour). */
        fun baseDomain(host: String): String = host.removePrefix("*.")

        /** Hosts end up inside an `adb shell` command line, so anything outside a hostname alphabet is refused. */
        fun of(hosts: Iterable<String>): AutoVerifyHosts {
            hosts.firstOrNull { !SAFE_HOST.matches(it) }?.let {
                throw IllegalArgumentException("Refusing host '$it' from the manifest: only letters, digits, '.', '-' and '*' are allowed")
            }
            val (wildcards, concrete) = hosts.toCollection(linkedSetOf()).partition { it.startsWith("*") }
            return AutoVerifyHosts(concrete.toCollection(linkedSetOf()), wildcards.toCollection(linkedSetOf()))
        }
    }
}

internal object ManifestParser {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }

    fun autoVerifyHosts(manifest: File): AutoVerifyHosts = autoVerifyHosts(linkFilters(manifest))

    fun autoVerifyHosts(filters: List<LinkFilter>): AutoVerifyHosts =
        AutoVerifyHosts.of(filters.filter { it.isVerifiable }.flatMap { it.hosts })

    fun linkFilters(manifest: File): List<LinkFilter> = manifest.inputStream().use { linkFilters(it) }

    fun linkFilters(manifestXml: ByteArray): List<LinkFilter> = linkFilters(manifestXml.inputStream())

    private fun linkFilters(stream: java.io.InputStream): List<LinkFilter> {
        val document = factory.newDocumentBuilder().parse(stream)
        val filters = document.getElementsByTagName("intent-filter")
        return (0 until filters.length).mapNotNull { (filters.item(it) as Element).toLinkFilter() }
    }

    /** Null for filters that are not VIEW intents with a scheme, i.e. not links at all. */
    private fun Element.toLinkFilter(): LinkFilter? {
        if (!hasViewAction()) return null
        val data = childElements("data")
        val schemes = data.map { it.attr("scheme") }.filter { it.isNotBlank() }.toSet()
        if (schemes.isEmpty()) return null
        return LinkFilter(
            activity = (parentNode as? Element)?.attr("name") ?: "?",
            autoVerify = attr("autoVerify") == "true",
            schemes = schemes,
            hosts = data.map { it.attr("host") }.filter { it.isNotBlank() }.toCollection(linkedSetOf()),
            categories = childElements("category").map { it.attr("name") }.toSet(),
        )
    }

    private fun Element.attr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Element.hasViewAction(): Boolean = childElements("action").any { it.attr("name") == "android.intent.action.VIEW" }

    private fun Element.childElements(tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) result += node
        }
        return result
    }
}
