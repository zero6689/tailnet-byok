package io.github.zero6689.tailnetbyok.net

import androidx.annotation.StringRes
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.domain.TargetAddress
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the app needs from "a way to reach the target", behind one
 * interface.
 *
 * Two implementations exist, and the split is the most important structural
 * decision in this codebase:
 *
 *  * [ProviderId.EMBEDDED_TSNET] — the app runs its own Tailscale node in
 *    process via the gomobile bridge in `tailnet/`. It uses the user's auth key,
 *    needs no other app, and asks for no VPN permission. This is the provider
 *    that makes an auth-key field meaningful.
 *  * [ProviderId.SYSTEM_NETWORK] — plain sockets on whatever network the device
 *    already has. This is the fallback for people who run the official Tailscale
 *    app (or who are already on the tailnet by some other means) and only want
 *    the configuration and testing half of this app.
 *
 * Neither implementation knows about the other. The UI talks to this interface,
 * reads [status], and never branches on which provider is active — that is what
 * keeps "add a third provider" a local change.
 */
interface ConnectivityProvider {

    val id: ProviderId

    /** Current state, observable so the UI can render without polling. */
    val status: StateFlow<ProviderStatus>

    /**
     * The version of the bundled node library, or null when there is none.
     *
     * This is the version of the *library in the APK*, not of the running node:
     * it is answerable before anything is started, and it is what a bug report
     * needs in order to say which build the report is about. The system-network
     * provider bundles nothing and answers null.
     */
    val libraryVersion: String?
        get() = null

    /**
     * Brings the provider up.
     *
     * [credentials] carries the auth key. Implementations must not retain it
     * beyond what the underlying library requires, must never write it to disk
     * in cleartext, and must never include it in [ProviderStatus] or diagnostics.
     *
     * [forceLogin] tells a provider that keeps node state to discard it and
     * re-authenticate. This exists because a stale node identity silently
     * overrides a freshly pasted key — the failure mode users blame on the app.
     */
    suspend fun start(
        config: TailnetConfig,
        credentials: TailnetCredentials,
        forceLogin: Boolean = false,
    ): ProviderStatus

    /** Takes the provider down. Idempotent. */
    suspend fun stop()

    /**
     * Removes persisted node state.
     *
     * Called when the auth key or control URL changes: the old node identity is
     * meaningless against a new control plane.
     */
    suspend fun clearState()

    /**
     * Opens a TCP connection to [target] and closes it.
     *
     * A refused or timed-out connection is a [ProbeResult] with `ok = false`,
     * never an exception: "the host is unreachable" is a normal answer for a
     * test button.
     */
    suspend fun probe(target: TargetAddress, timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS): ProbeResult

    /**
     * Performs one HTTP request to the target.
     *
     * HTTP lives here rather than in the caller because with the embedded
     * provider the socket must be created by the tailnet's own network stack,
     * and that stack lives on the other side of a JNI boundary. Handing raw
     * bytes back is the only way across it.
     */
    suspend fun fetch(request: HttpRequestSpec): FetchResult

    /** Recent, already-redacted diagnostic lines. In memory only. */
    suspend fun diagnostics(): List<String>

    /**
     * The route a WebView should take to render the target's own interface.
     *
     * # Why the default is a real answer, not a placeholder
     *
     * The default is the target's own URL, and that is correct for
     * [ProviderId.SYSTEM_NETWORK]: the tunnel in that case belongs to the phone,
     * so a WebView on this device already has a route to [target] and nothing
     * needs to be proxied.
     *
     * The embedded node cannot use it, and that is the whole reason this method
     * exists on the interface. Its socket is created inside the Go netstack, and
     * a Go `net.Conn` cannot cross the gomobile boundary — so Android's own HTTP
     * stack, and therefore a WebView, has no route to the target at all. That
     * implementation answers with a loopback reverse proxy instead. See
     * `TsnetConnectivityProvider` in the `tsnet` source set, and
     * `tailnet/proxy.go` for what the proxy guarantees.
     *
     * # What a caller must do with the answer
     *
     * [target] is an address the caller has already validated. Implementations
     * must build the URL from it with [TargetAddress.url], which is the same
     * construction the connection test uses — so the page the user is shown and
     * the health check that proved the target reachable can never disagree.
     *
     * [WebAccess.Ready.url] may carry a capability token. It is a [WebUrl], not
     * a `String`, for exactly that reason: it must not reach a log, a message or
     * a `toString()`. What the caller gets out of it is what it hands the
     * WebView, and the caller is responsible for releasing the route with
     * [closeWebAccess] when it is done — for the embedded node that release is
     * what closes the loopback listener.
     */
    suspend fun openWebAccess(
        target: TargetAddress,
        scheme: String,
        path: String,
    ): WebAccess = WebAccess.Ready(WebUrl(target.url(scheme, path)))

    /**
     * Releases whatever [openWebAccess] set up. Safe when nothing was set up,
     * and safe to call twice.
     *
     * Not `suspend`, deliberately. The two moments this runs are both
     * non-suspending — a screen being closed, and a view model being cleared —
     * and a teardown that can only be *started* from a coroutine scope is a
     * teardown that gets skipped when that scope dies first. Implementations
     * must therefore do their work off the calling thread and return promptly;
     * the embedded one hands it to a scope of its own.
     */
    fun closeWebAccess() = Unit

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS = 8_000
        const val DEFAULT_FETCH_TIMEOUT_MS = 15_000
    }
}

/**
 * The two ways to reach a target.
 *
 * [storageKey] is persisted, so it is an ABI: never rename or reorder it.
 * [labelRes] is the translated display name — an id, not text, so the UI can
 * render it in the active language (see `core.text.TextRef` for the rule).
 */
enum class ProviderId(val storageKey: String, @StringRes val labelRes: Int) {
    /** Own node, own auth key, no VPN slot. */
    EMBEDDED_TSNET("embedded_tsnet", R.string.provider_embedded_tsnet),

    /** Whatever network the device already has. */
    SYSTEM_NETWORK("system_network", R.string.provider_system_network),
    ;

    companion object {
        fun fromStorageKey(key: String?): ProviderId =
            entries.firstOrNull { it.storageKey == key } ?: EMBEDDED_TSNET
    }
}

/** Non-secret node settings. Safe to persist in cleartext. */
data class TailnetConfig(
    val hostname: String,
    val controlUrl: String,
    val stateDir: String,
    val ephemeral: Boolean,
)

/**
 * The one secret in the app.
 *
 * It is a [String] because the Android text field hands us one and because the
 * Go bridge takes one. That is a real limitation, stated rather than hidden:
 * Kotlin strings are immutable and cannot be zeroed, so the plaintext key exists
 * in the heap for as long as the object lives. The mitigations that *are*
 * available, and which this codebase applies, are:
 *
 *  * it is never persisted in cleartext — see `KeystoreSecretVault`;
 *  * it is never logged — see `SafeLog` and `Redact`;
 *  * it is never put in a `data class` `toString()` (this class overrides it);
 *  * it is dropped as soon as the node is up, and the node holds its own copy.
 */
class TailnetCredentials(private val authKey: String) {

    fun reveal(): String = authKey

    fun isEmpty(): Boolean = authKey.isBlank()

    /** Deliberately opaque: a `println(credentials)` must not leak the key. */
    override fun toString(): String = "TailnetCredentials(key=${if (authKey.isEmpty()) "absent" else "present"})"

    companion object {
        val EMPTY = TailnetCredentials("")
    }
}

/**
 * The route a WebView should take to render the target's own interface.
 *
 * Two outcomes, because "there is no route" is a normal answer rather than an
 * exception: the proxy can refuse a target it cannot parse or a loopback port it
 * cannot bind, and the user needs the reason in the same register as every other
 * failure in this app — a translated [TextRef], not a stack trace.
 */
sealed interface WebAccess {

    /** Load [url]. */
    data class Ready(val url: WebUrl) : WebAccess

    /** Show [message] instead, and do not open the screen. */
    data class Refused(val message: TextRef) : WebAccess
}

/**
 * A URL a WebView must load to reach the target's own interface.
 *
 * A class rather than a bare [String], and for the same reason
 * [TailnetCredentials] is one: on the embedded-node route this URL carries the
 * loopback proxy's session token in its query string, and a token that travels
 * through a `toString()` — of a data class, of a log line, of a crash report —
 * is a token that has leaked. [reveal] is the only way to the text, and its one
 * caller hands it straight to the WebView.
 *
 * This is a capability, not a credential to the tailnet: it is worth exactly one
 * loopback port, and only until [ConnectivityProvider.closeWebAccess] runs.
 */
class WebUrl(private val value: String) {

    fun reveal(): String = value

    /** Deliberately opaque: `println(url)` must not print the token. */
    override fun toString(): String = "WebUrl(<redacted>)"

    // Value semantics, so a state comparison in the UI behaves like the String
    // it wraps without the String ever being printed.
    override fun equals(other: Any?): Boolean =
        this === other || (other is WebUrl && other.value == value)

    override fun hashCode(): Int = value.hashCode()
}

sealed interface ProviderStatus {
    val provider: ProviderId

    /** Compiled out, or the native bridge failed to load. */
    data class Unavailable(
        override val provider: ProviderId,
        val reason: TextRef,
    ) : ProviderStatus

    data class Stopped(override val provider: ProviderId) : ProviderStatus

    data class Starting(override val provider: ProviderId) : ProviderStatus

    data class Running(
        override val provider: ProviderId,
        val ipv4: String,
        val ipv6: String?,
        val hostname: String,
        val controlUrl: String,
        val tailnetName: String?,
    ) : ProviderStatus

    /**
     * [loginUrl] is set when the node needs interactive authentication — the
     * auth key was rejected, expired, or was never supplied. Surfacing the URL
     * turns a dead end into a next step.
     *
     * [message] is whatever the underlying library said; it is passed through
     * as-is because it is diagnostic text, not app copy. [messageRes] lets the
     * caller render a translated line instead when it knows more.
     */
    data class Error(
        override val provider: ProviderId,
        val message: String,
        val loginUrl: String? = null,
        val messageRes: TextRef? = null,
    ) : ProviderStatus

    val isReachable: Boolean get() = this is Running
}

data class ProbeResult(
    val ok: Boolean,
    val elapsedMs: Long,
    val resolvedAddress: String? = null,
    val error: TextRef? = null,
)

data class HttpRequestSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = ConnectivityProvider.DEFAULT_FETCH_TIMEOUT_MS,
    val maxBodyBytes: Int = 256 * 1024,
) {
    // ByteArray in a data class breaks equals/hashCode; providing them here
    // keeps the class honest instead of leaving a latent bug in test assertions.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequestSpec) return false
        return url == other.url &&
            method == other.method &&
            headers == other.headers &&
            timeoutMs == other.timeoutMs &&
            maxBodyBytes == other.maxBodyBytes &&
            (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeoutMs
        result = 31 * result + maxBodyBytes
        return result
    }
}

data class FetchResult(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val truncated: Boolean,
    val elapsedMs: Long,
    val error: TextRef? = null,
) {
    /** A lossy view for showing a health-check response in the UI. */
    fun bodyText(limit: Int = 2048): String =
        String(body, Charsets.UTF_8).take(limit)

    val ok: Boolean get() = error == null && statusCode in 200..399

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FetchResult) return false
        return statusCode == other.statusCode &&
            headers == other.headers &&
            truncated == other.truncated &&
            elapsedMs == other.elapsedMs &&
            error == other.error &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + truncated.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}
