package io.github.zero6689.tailnetbyok.domain

/** One component whose licence text ships inside the app. */
data class LicenseEntry(
    val key: String,
    val licence: String,
    val modules: String,
    val asset: String,
)

/** What the in-app licence screen reads: a short scope note, then the components. */
data class LicenseIndex(
    val notes: List<String>,
    val entries: List<LicenseEntry>,
)

/**
 * Parses `assets/licenses/notices.tsv`, the file `scripts/generate-license-screen.mjs`
 * writes.
 *
 * # Why this is a parser and not a dependency
 *
 * The app has no serialisation library and no Android runtime in its unit tests, so
 * a JSON reader would be a new dependency and a `JSONObject` would not run on the
 * JVM at all. The format is one generated file with one writer, so a tab-separated
 * line is enough — and it is testable where it matters.
 *
 * # Why a bad line throws instead of being skipped
 *
 * A licence list that quietly loses entries still looks complete: that is the one
 * failure mode this screen exists to prevent. The asset is generated and committed,
 * so a malformed line is a build error, not a state a user can reach. The caller
 * renders "missing from this build" only when the asset cannot be read at all.
 */
object LicenseIndexParser {

    /** Path inside `assets/`. Kept here so screen and generator name the same file. */
    const val ASSET_PATH = "licenses/notices.tsv"

    fun parse(text: String): LicenseIndex {
        val notes = mutableListOf<String>()
        val entries = mutableListOf<LicenseEntry>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val note = line.removePrefix("#").trim()
                if (note.isNotEmpty()) notes += note
                continue
            }
            val fields = line.split('\t')
            require(fields.size == 4) { "expected 4 tab-separated fields, got ${fields.size}" }
            val (key, licence, modules, asset) = fields
            require(key.isNotBlank() && licence.isNotBlank() && modules.isNotBlank() && asset.isNotBlank()) {
                "a licence entry is missing a field: $line"
            }
            entries += LicenseEntry(key = key, licence = licence, modules = modules, asset = asset)
        }
        require(entries.isNotEmpty()) { "the licence index lists no components" }
        return LicenseIndex(notes = notes, entries = entries)
    }
}
