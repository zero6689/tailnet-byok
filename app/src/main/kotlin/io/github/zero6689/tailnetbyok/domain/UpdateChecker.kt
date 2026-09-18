package io.github.zero6689.tailnetbyok.domain

import io.github.zero6689.tailnetbyok.net.FetchResult
import io.github.zero6689.tailnetbyok.net.HttpRequestSpec

/**
 * Asks an update source what it is offering, and — when it offers something newer
 * — downloads it and proves it is what the source said it was.
 *
 * # One dependency, as a function
 *
 * The only thing this class needs from the outside is "fetch this URL". It takes
 * that as a lambda rather than a `ConnectivityProvider`, which has two
 * consequences worth having: the checker cannot accidentally start a node, and
 * the entire decision table — up to date, newer, missing sidecar, mismatched
 * hash, truncated body, HTML instead of an APK — is testable with an in-memory
 * map instead of a network, a device, or a mock.
 *
 * The caller is responsible for the transport being up (the embedded node has to
 * be running before a fetch through it can succeed) and for what happens to the
 * bytes afterwards; this class only decides whether they are trustworthy.
 *
 * # Fail-closed, in one place
 *
 * Every branch below that is not "verified bytes" returns a [UpdateCheck.Failed]
 * naming the exact reason. There is no path here that returns bytes without
 * having compared them against the sidecar, and none that treats an unreadable
 * sidecar as permission to proceed.
 */
class UpdateChecker(
    private val fetch: suspend (HttpRequestSpec) -> FetchResult,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /**
     * What this transport can stage, in bytes.
     *
     * Passed in rather than assumed because the two routes differ by a lot: the
     * embedded node carries bodies through a base64 JSON field and can only hold a
     * fraction of what the system network route can. See
     * [UpdateProtocol.MAX_EMBEDDED_APK_BYTES] for the arithmetic.
     */
    private val maxPackageBytes: Int = UpdateProtocol.MAX_APK_BYTES,
) {

    /** What the source said, and — when it said something newer — the bytes. */
    sealed interface UpdateCheck {

        /** The installed build is the newest the source has. */
        data class UpToDate(val current: String, val advertised: String) : UpdateCheck

        /**
         * A newer, hash-verified package.
         *
         * A plain class, not a data class: [bytes] is a large mutable array, and a
         * generated `equals`/`toString` over it would be both wrong (identity of an
         * array) and dangerous (a 60 MB `toString` in a log line).
         */
        class Downloaded(
            val current: String,
            val available: String,
            val bytes: ByteArray,
        ) : UpdateCheck {
            val sizeBytes: Int get() = bytes.size
        }

        data class Failed(val reason: UpdateFailure) : UpdateCheck
    }

    /**
     * Checks [baseUrl] and downloads from it when it advertises something newer
     * than [currentVersion].
     *
     * `currentVersion` is whatever the app believes it is running —
     * `BuildConfig.VERSION_NAME` in practice, including the `-debug` suffix; the
     * comparison ignores build suffixes (see [UpdateProtocol.compare]).
     */
    suspend fun check(baseUrl: String, currentVersion: String): UpdateCheck {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE)

        val versionUrl = UpdateProtocol.endpoint(base, UpdateProtocol.VERSION_PATH)
        val apkUrl = UpdateProtocol.endpoint(base, UpdateProtocol.APK_PATH)
        val sidecarUrl = UpdateProtocol.endpoint(base, UpdateProtocol.SIDECAR_PATH)

        val versionText = when (val fetched = fetchChecked(versionUrl, UpdateProtocol.MAX_TEXT_BYTES)) {
            is Fetched.Ok -> fetched.result.bodyText(UpdateProtocol.MAX_TEXT_BYTES)
            // A cut-off version file is not a shorter version, and an oversized one
            // is not a version at all; both are "nothing usable was served".
            Fetched.TooLarge, Fetched.Failed -> return UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE)
        }

        val advertised = UpdateProtocol.parseAdvertisedVersion(versionText)
            ?: return UpdateCheck.Failed(UpdateFailure.VERSION_UNPARSABLE)

        if (!UpdateProtocol.isNewer(advertised, currentVersion)) {
            return UpdateCheck.UpToDate(current = currentVersion, advertised = advertised)
        }

        // Only now is anything large fetched: a source that is not offering a
        // newer build costs one small text request, which is also what makes the
        // check cheap enough to run on demand rather than on a timer.
        val body = when (val fetched = fetchChecked(apkUrl, maxPackageBytes)) {
            is Fetched.Ok -> fetched.result.body
            // The transport hit its own ceiling. That is a specific, actionable
            // outcome — "this package is bigger than this route can stage" — and
            // reporting it as a generic download failure would send the user
            // looking for a network problem that is not there.
            Fetched.TooLarge -> return UpdateCheck.Failed(UpdateFailure.PACKAGE_TOO_LARGE)
            Fetched.Failed -> return UpdateCheck.Failed(UpdateFailure.DOWNLOAD_FAILED)
        }

        val apk = try {
            UpdateProtocol.gunzipIfNeeded(body)
        } catch (e: java.io.IOException) {
            // A body that claims to be gzip and is not. Reported as "not an APK":
            // from the user's point of view the source served junk, and the
            // distinction between two flavours of junk is not actionable.
            return UpdateCheck.Failed(UpdateFailure.NOT_AN_APK)
        }
        if (!UpdateProtocol.looksLikeZip(apk)) return UpdateCheck.Failed(UpdateFailure.NOT_AN_APK)

        val sidecarText = when (val fetched = fetchChecked(sidecarUrl, UpdateProtocol.MAX_TEXT_BYTES)) {
            is Fetched.Ok -> fetched.result.bodyText(UpdateProtocol.MAX_TEXT_BYTES)
            Fetched.TooLarge, Fetched.Failed -> return UpdateCheck.Failed(UpdateFailure.NO_SIDECAR)
        }
        val expected = UpdateProtocol.parseSidecar(sidecarText)
            ?: return UpdateCheck.Failed(UpdateFailure.NO_SIDECAR)

        // The hash is taken over the decompressed bytes: `dsh.apk.sha256` describes
        // the file the server is publishing, and what gets installed is the zip,
        // not the transfer encoding it arrived in.
        if (UpdateProtocol.sha256Hex(apk) != expected) {
            return UpdateCheck.Failed(UpdateFailure.SIDECAR_MISMATCH)
        }

        return UpdateCheck.Downloaded(current = currentVersion, available = advertised, bytes = apk)
    }

    /**
     * Why a fetch cannot be used, kept apart from "the body".
     *
     * The three cases lead to different sentences on screen, so they must not be
     * collapsed into one null on the way out.
     */
    private sealed interface Fetched {
        class Ok(val result: FetchResult) : Fetched

        /** The body hit the cap this call asked for. */
        data object TooLarge : Fetched

        /** The transport failed, the status was not a success, or it threw. */
        data object Failed : Fetched
    }

    /**
     * One GET, classified.
     *
     * Truncation counts as its own outcome rather than as data: a cut-off text body
     * would be parsed as a shorter version, and a cut-off APK would be hashed and
     * rejected — with a misleading reason, and only after the user waited for the
     * whole transfer.
     */
    private suspend fun fetchChecked(url: String, limit: Int): Fetched {
        val result = try {
            fetch(HttpRequestSpec(url = url, method = "GET", timeoutMs = timeoutMs, maxBodyBytes = limit))
        } catch (e: Exception) {
            // The providers report transport errors inside the result rather than
            // throwing, but a fetch that throws must not take the screen with it.
            return Fetched.Failed
        }
        if (result.truncated) return Fetched.TooLarge
        if (result.error != null || result.statusCode !in 200..299) return Fetched.Failed
        return Fetched.Ok(result)
    }

    companion object {
        /**
         * Generous, because the body can be tens of megabytes over a tailnet, and
         * the wait is visible to the user as a progress spinner rather than as a
         * frozen screen.
         */
        const val DEFAULT_TIMEOUT_MS: Int = 120_000
    }
}
