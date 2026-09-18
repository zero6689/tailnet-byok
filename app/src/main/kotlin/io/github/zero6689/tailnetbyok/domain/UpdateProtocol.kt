package io.github.zero6689.tailnetbyok.domain

/**
 * The wire contract an update source has to satisfy, and the pure functions that
 * decide whether it did.
 *
 * # The contract
 *
 * Three files, served from one base URL, next to the app's own UI:
 *
 *  * `dsh.apk.version` — plain text; the version the server is offering.
 *  * `dsh.apk`         — the package itself.
 *  * `dsh.apk.sha256`  — the SHA-256 of those exact bytes.
 *
 * The names are fixed, and the base URL is the only thing that moves. That is
 * deliberate: it means one setting covers "my DSH host" (the default — the target
 * origin, where the header's `dsh.apk` already lives) and any mirror that speaks
 * the same three names.
 *
 * # Why every decision here is fail-closed
 *
 * An APK this app downloads is code it will hand to the package installer. The
 * sidecar check is therefore not a nicety to be skipped when the network is
 * flaky: a missing sidecar, a short one, or one that disagrees with the bytes we
 * received is a **failure**, never a "well, probably fine". The same applies to
 * the shape checks — gzip or not, the payload must still be a zip archive — and
 * to a version string that cannot be read at all. The rule is simple and worth
 * stating once: *we install what we can prove, and nothing else.*
 *
 * Everything in this file is pure Kotlin (no `Context`, no provider, no I/O), so
 * the whole decision table is unit-testable — see `UpdateProtocolTest`.
 */
object UpdateProtocol {

    /** Version sidecar, relative to the update base URL. */
    const val VERSION_PATH = "dsh.apk.version"

    /** The package, relative to the update base URL. */
    const val APK_PATH = "dsh.apk"

    /** Hash sidecar, relative to the update base URL. */
    const val SIDECAR_PATH = "dsh.apk.sha256"

    /**
     * Ceiling for the download on the system-network route.
     *
     * The bundled-node builds are large — a four-ABI debug APK is ~180 MB, and the
     * one-ABI build ~60 MB — so this is generous on purpose. It is not unbounded:
     * a provider that answered with a gigantic body would otherwise be allowed to
     * exhaust the process before we ever get to look at it.
     */
    const val MAX_APK_BYTES: Int = 200 * 1024 * 1024

    /**
     * Ceiling for the embedded-node route, and the reason it is lower.
     *
     * That route crosses the gomobile boundary as a **base64 body inside a JSON
     * string** (`Mobile.fetch` → `bodyB64`, decoded in Kotlin), so a body of N
     * bytes costs roughly N on the Go side, ~1.34N of base64 text, another copy of
     * that as a Java string, and N again once it is decoded — several hundred
     * megabytes of transient heap for a package this app would itself produce.
     * Android would not fail politely; it would kill the process.
     *
     * So the embedded route gets a ceiling it can actually hold, and a package
     * above it is refused with a message that names the limit instead of an
     * out-of-memory crash. The real fix is to stream the body to a file across the
     * bridge instead of carrying it through JSON (see `docs/TSNET.md`), which needs
     * a new binding and therefore a re-bound AAR; until then this is the honest
     * bound. The system-network route has no such doubling and uses
     * [MAX_APK_BYTES].
     */
    const val MAX_EMBEDDED_APK_BYTES: Int = 32 * 1024 * 1024

    /** Ceiling for the two text sidecars. */
    const val MAX_TEXT_BYTES: Int = 64 * 1024

    /** `base` + one of the three names, with no double slash at the seam. */
    fun endpoint(baseUrl: String, name: String): String =
        baseUrl.trim().trimEnd('/') + "/" + name

    // -- Versions ------------------------------------------------------------

    /**
     * A version shape we are willing to compare: dotted numbers, with an
     * optional `-suffix`/`+build` tail (which is ignored for ordering).
     *
     * Rejecting everything else is the point. A directory listing or an HTML
     * error page served at `dsh.apk.version` must not be silently read as
     * "some version, probably older, carry on".
     */
    private val VERSION_SHAPE = Regex("^[0-9]+(?:\\.[0-9]+)*(?:[-+][0-9A-Za-z.+-]*)?$")

    /**
     * The version advertised by a `dsh.apk.version` body, or null when the body
     * says nothing usable.
     *
     * Tolerant about shape, strict about content: blank lines and a leading `v`
     * are handled (both are common in hand-written version files), anything after
     * the first non-blank line is ignored.
     */
    fun parseAdvertisedVersion(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val candidate = line.removePrefix("v").removePrefix("V").trim()
        return candidate.takeIf { VERSION_SHAPE.matches(it) }
    }

    /**
     * Numeric ordering of two versions: > 0 when [a] is newer than [b].
     *
     * Missing components count as zero, so `1.59` and `1.59.0` are equal, and a
     * build suffix (`0.2.6-debug`) only affects ordering through its numeric
     * prefix. This deliberately does not implement semver's pre-release rules:
     * what is being ordered here is an Android `versionName`, and the only
     * question that matters is "is the server offering something newer than what
     * is installed".
     */
    fun compare(a: String, b: String): Int {
        val left = numericParts(a)
        val right = numericParts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l - r
        }
        return 0
    }

    /** Whether [advertised] is strictly newer than [current]. */
    fun isNewer(advertised: String, current: String): Boolean = compare(advertised, current) > 0

    private fun numericParts(version: String): List<Int> =
        version.substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    // -- The hash sidecar ----------------------------------------------------

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * The SHA-256 from a `dsh.apk.sha256` body, lowercased, or null when there is
     * no hash in it.
     *
     * Written as "the first 64-hex token" rather than "the whole file, trimmed"
     * because both layouts are in the wild: `sha256sum` output is
     * `hash  filename`, while a file written by hand is usually the bare hash.
     * Accepting either is strictly safer than trimming the whole body — the
     * comparison below is still exact, so nothing is loosened by parsing more
     * generously. A body with no 64-hex token at all is a failure, not a pass.
     */
    fun parseSidecar(text: String): String? =
        text.split(Regex("\\s+"))
            .firstOrNull { HEX64.matches(it) }
            ?.lowercase()

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val b = digest[i].toInt()
            hex[i * 2] = digits[(b shr 4) and 0x0F]
            hex[i * 2 + 1] = digits[b and 0x0F]
        }
        return String(hex)
    }

    // -- Transport shapes ----------------------------------------------------

    /**
     * DSH's own static routes gzip a body even when the client did not ask for
     * it, and not every HTTP stack on Android undoes that transparently. A
     * gzip-wrapped APK reaches the installer as garbage, so the magic bytes are
     * sniffed and the body unwrapped before anything else looks at it.
     */
    fun looksGzipped(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** [bytes] with a gzip wrapper removed, or [bytes] unchanged. */
    fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        if (!looksGzipped(bytes)) return bytes
        return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readBytes() }
    }

    /**
     * Whether the payload is a zip archive.
     *
     * An APK is a zip, so this is the cheapest possible "is this even a package"
     * check, and it runs before the hash comparison would otherwise be the only
     * thing standing between a captive-portal HTML page and the installer. All
     * three zip magics are accepted: an empty archive (`PK\x05\x06`) is not a
     * valid APK, but it is a zip, and the hash check decides the rest.
     */
    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 3.toByte() || bytes[2] == 5.toByte() || bytes[2] == 7.toByte())
}

/**
 * Why an update attempt did not end in a downloaded, verified package.
 *
 * An enum rather than a message: it is persisted between runs (the installer
 * kills the process, so the outcome has to survive it) and rendered from
 * resources, which keeps the report translatable.
 */
enum class UpdateFailure {
    /** The connection to the update source could not be brought up at all. */
    TRANSPORT_UNAVAILABLE,

    /** The version sidecar could not be fetched at all. */
    VERSION_UNREADABLE,

    /** It answered, but there was no version in it. */
    VERSION_UNPARSABLE,

    /** The package could not be fetched, or arrived truncated. */
    DOWNLOAD_FAILED,

    /**
     * The package is larger than the route carrying it can stage.
     *
     * Not a network failure: the transport has a size it can hold safely (see
     * [UpdateProtocol.MAX_EMBEDDED_APK_BYTES]), and this package is over it.
     */
    PACKAGE_TOO_LARGE,

    /** The hash sidecar is missing, empty, or contains no SHA-256. */
    NO_SIDECAR,

    /** The bytes we received do not hash to the advertised value. */
    SIDECAR_MISMATCH,

    /** The payload is not a zip archive (a captive portal, an HTML page, junk). */
    NOT_AN_APK,

    /** Verified bytes, but the archive is a different application. */
    WRONG_PACKAGE,

    /** The staged file is no longer on disk (the cache was cleared). */
    APK_GONE,

    /** Nothing on the device will handle an install intent. */
    NO_INSTALLER,

    /** Android requires the user to allow installs from this app first. */
    INSTALL_BLOCKED,
}

/**
 * The outcome of the most recent update attempt.
 *
 * Persisted, because the interesting cases are exactly the ones that do not
 * survive in memory: a successful download is followed by the package installer,
 * which stops this process, and a failure is what the user wants to read on the
 * next launch. See [UpdateRecord] for the on-disk form.
 */
sealed interface UpdateOutcome {

    /** Nothing has been attempted yet on this install. */
    data object Unknown : UpdateOutcome

    /** The source is not offering anything newer. */
    data class UpToDate(val current: String, val advertised: String) : UpdateOutcome

    /**
     * A newer package was downloaded and its hash verified; it is waiting for the
     * user to hand it to the installer.
     */
    data class Ready(val current: String, val available: String, val sizeBytes: Int) : UpdateOutcome

    data class Failed(val reason: UpdateFailure) : UpdateOutcome
}

/**
 * The persisted form of [UpdateOutcome].
 *
 * A flat `kind|field|field` line in the preferences store, written after every
 * attempt. It is deliberately *not* a rendered sentence: the app is translated,
 * so a stored sentence would freeze the language it was written in. Only stable
 * tokens are persisted — the reason enum's name, and version/size fields — and
 * the sentence is built from resources on the way out.
 *
 * [decode] is total: an unreadable or unrecognised record yields
 * [UpdateOutcome.Unknown] rather than throwing. A preferences file written by a
 * newer build must not take this one down.
 */
object UpdateRecord {

    private const val SEPARATOR = "|"
    private const val NONE = "none"
    private const val UP_TO_DATE = "uptodate"
    private const val READY = "ready"
    private const val FAILED = "failed"

    fun encode(outcome: UpdateOutcome): String = when (outcome) {
        UpdateOutcome.Unknown -> NONE
        is UpdateOutcome.UpToDate -> listOf(UP_TO_DATE, outcome.current, outcome.advertised).joinToString(SEPARATOR)
        is UpdateOutcome.Ready ->
            listOf(READY, outcome.current, outcome.available, outcome.sizeBytes.toString()).joinToString(SEPARATOR)
        is UpdateOutcome.Failed -> listOf(FAILED, outcome.reason.name).joinToString(SEPARATOR)
    }

    fun decode(record: String?): UpdateOutcome {
        val parts = record?.split(SEPARATOR) ?: return UpdateOutcome.Unknown
        return when (parts.firstOrNull()) {
            UP_TO_DATE -> {
                val current = parts.getOrNull(1) ?: return UpdateOutcome.Unknown
                val advertised = parts.getOrNull(2) ?: return UpdateOutcome.Unknown
                UpdateOutcome.UpToDate(current, advertised)
            }
            READY -> {
                val current = parts.getOrNull(1) ?: return UpdateOutcome.Unknown
                val available = parts.getOrNull(2) ?: return UpdateOutcome.Unknown
                val size = parts.getOrNull(3)?.toIntOrNull() ?: return UpdateOutcome.Unknown
                UpdateOutcome.Ready(current, available, size)
            }
            FAILED -> {
                val reason = parts.getOrNull(1)?.let { name ->
                    UpdateFailure.values().firstOrNull { it.name == name }
                } ?: return UpdateOutcome.Unknown
                UpdateOutcome.Failed(reason)
            }
            else -> UpdateOutcome.Unknown
        }
    }
}
