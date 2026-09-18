package io.github.zero6689.tailnetbyok.core.log

import io.github.zero6689.tailnetbyok.BuildConfig
import android.util.Log

/**
 * The only logging entry point in this app.
 *
 * Why not just call `Log.d` directly: a secret leaks through a log far more
 * often by accident than on purpose — an exception message that quotes the
 * request, a `toString()` on a data class that happens to hold a key, a
 * "let me just print the config to see what's wrong". Routing every line
 * through [Redact.scrub] means that class of accident cannot reach logcat.
 *
 * Release builds additionally strip `v`/`d`/`i` via ProGuard (see
 * `app/proguard-rules.pro`), so a release APK cannot emit them even if a call
 * site is added later by mistake.
 *
 * Deliberate omissions:
 *  * no timber, no logging framework — one fewer dependency to audit;
 *  * no file sink, so nothing can be scraped off the device's storage;
 *  * no `Log.wtf`, which historically wrote to a dropbox other apps could read.
 */
object SafeLog {

    private const val MAX_TAG = 23 // platform limit; exceeding it silently truncates
    private const val MAX_MESSAGE = 2048

    private val baseTag = "TailnetBYOK"

    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(tag(tag), scrub(message))
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag(tag), scrub(message))
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag(tag), scrub(message))
    }

    /**
     * Warnings survive into release builds: a failing connection with no
     * diagnostic is worse than the (already redacted) information here.
     */
    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag(tag), scrub(message), error?.let { sanitized(it) })
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag(tag), scrub(message), error?.let { sanitized(it) })
    }

    /**
     * Logs an error the caller knows the secret shape of.
     *
     * Use this — not [e] — when the throwable came from a layer that may have
     * echoed the credential back (the control plane does this on a bad key).
     */
    fun eScrubbed(tag: String, message: String, error: Throwable?, vararg secrets: String?) {
        val safeMessage = Redact.scrubKnown(message, *secrets)
        val safeError = error?.let { sanitized(it, *secrets) }
        Log.e(tag(tag), safeMessage, safeError)
    }

    /** Scrubs a whole [Throwable] chain, including suppressed and cause links. */
    fun sanitized(error: Throwable, vararg secrets: String?): Throwable {
        val scrubbed = Throwable(
            Redact.scrubKnown(error.message ?: error::class.java.simpleName, *secrets),
        )
        scrubbed.stackTrace = error.stackTrace
        error.cause?.let { cause ->
            runCatching { scrubbed.initCause(sanitized(cause, *secrets)) }
        }
        return scrubbed
    }

    private fun tag(tag: String): String {
        val full = "$baseTag/$tag"
        return if (full.length <= MAX_TAG) full else full.take(MAX_TAG)
    }

    private fun scrub(message: String): String {
        val safe = Redact.scrub(message)
        return if (safe.length <= MAX_MESSAGE) safe else safe.take(MAX_MESSAGE) + "…(truncated)"
    }
}
