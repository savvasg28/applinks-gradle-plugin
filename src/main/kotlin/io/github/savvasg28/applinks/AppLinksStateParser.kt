package io.github.savvasg28.applinks

/**
 * Parses the output of `pm get-app-links <package>` (Android 12+).
 *
 * Example:
 * ```
 *   dev.spike.applinks:
 *     ID: 989b3109-66fc-4dea-afc9-23d7596e6ebd
 *     Signatures: [C3:62:...]
 *     Domain verification state:
 *       spike.example.com: 1024
 * ```
 */
internal object AppLinksStateParser {
    private val hostLine = Regex("""^\s+([^\s:]+):\s+(\S+)\s*$""")

    /** Null when the package section is absent, meaning the app is not installed. */
    fun parse(
        output: String,
        packageName: String,
    ): PackageAppLinks? {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.trim() == "$packageName:" }
        if (start < 0) return null
        val section = lines.drop(start + 1)

        val signatures =
            section
                .firstOrNull { it.trim().startsWith("Signatures:") }
                ?.substringAfter("[")
                ?.substringBefore("]")
                ?.split(",")
                ?.map(Fingerprints::normalise)
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        val states = linkedMapOf<String, String>()
        val stateIndex = section.indexOfFirst { it.trim() == "Domain verification state:" }
        if (stateIndex >= 0) {
            for (line in section.drop(stateIndex + 1)) {
                val match = hostLine.matchEntire(line) ?: break
                states[match.groupValues[1]] = match.groupValues[2]
            }
        }
        return PackageAppLinks(states, signatures)
    }

    fun describe(state: String): String =
        when (state) {
            "verified" -> "verified by the OS against assetlinks.json"
            "approved" -> "force-approved via adb, opens in app"
            "none" -> "no verification result recorded, opens in browser"
            "denied" -> "force-denied via adb"
            "migrated" -> "carried over from the legacy verifier"
            "restored" -> "restored from backup"
            "legacy_failure" -> "rejected by the legacy verifier"
            "system_configured" -> "approved by device configuration"
            else ->
                if (state.toIntOrNull()?.let { it >= 1024 } == true) {
                    "verifier error $state, usually a fingerprint mismatch or unreachable assetlinks.json"
                } else {
                    "unknown state"
                }
        }
}
