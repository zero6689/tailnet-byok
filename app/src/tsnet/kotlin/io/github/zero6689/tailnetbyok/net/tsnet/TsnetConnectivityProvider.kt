package io.github.zero6689.tailnetbyok.net.tsnet

import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.Redact
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.domain.TargetAddress
import io.github.zero6689.tailnetbyok.mobile.Mobile
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.FetchResult
import io.github.zero6689.tailnetbyok.net.HttpRequestSpec
import io.github.zero6689.tailnetbyok.net.ProbeResult
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderStatus
import io.github.zero6689.tailnetbyok.net.TailnetConfig
import io.github.zero6689.tailnetbyok.net.TailnetCredentials
import io.github.zero6689.tailnetbyok.net.WebAccess
import io.github.zero6689.tailnetbyok.net.WebUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/**
 * The provider that makes the auth-key field meaningful.
 *
 * It runs a complete Tailscale node inside this app's process, on a userspace
 * network stack, and dials the target through it. No other app is involved, no
 * VPN permission is requested, and the device's other traffic is untouched —
 * this app opens one socket to one destination, rather than taking over the
 * phone's network.
 *
 * # Where the code actually runs
 *
 * Everything interesting happens in Go, behind the gomobile-generated
 * [Mobile] facade. This class is a translator: it turns Kotlin intent into
 * calls on that facade and turns the JSON documents that come back into the
 * types the UI already understands. That is why there is no socket code here —
 * a Go `net.Conn` cannot be represented in Java, so the HTTP and TCP work has to
 * happen on the Go side of the boundary and come back as bytes.
 *
 * # Threading
 *
 * Every native call blocks, and `start` blocks for as long as it takes the
 * control plane to admit the node — seconds, or up to its timeout. All of them
 * are therefore confined to [Dispatchers.IO]. Calling them from the main thread
 * would freeze the UI in exactly the moment the user is watching it.
 *
 * # Failure surface
 *
 * gomobile converts a Go `error` return into a thrown Java exception, so every
 * call site here is wrapped. Messages from the Go side are already redacted
 * before they cross — see `redact()` in `tailnet/tailnet.go` — and this class
 * never adds the auth key to a message of its own.
 */
internal class TsnetConnectivityProvider : ConnectivityProvider {

    override val id: ProviderId = ProviderId.EMBEDDED_TSNET

    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.Stopped(id))
    override val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    /**
     * The `x/mobile`-stamped version of the bridge compiled into this APK.
     *
     * Read from the native library rather than from `BuildConfig`, because the two
     * answer different questions: the app's version says which release this is,
     * and this says which `tailscale.com` revision the embedded node is. A bug
     * report that carries both can be reproduced; one that carries neither cannot.
     * Null only if the bridge is missing, which the `R.string.*` fallback handles.
     */
    override val libraryVersion: String?
        get() = runCatching { Mobile.version() }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun start(
        config: TailnetConfig,
        credentials: TailnetCredentials,
        forceLogin: Boolean,
    ): ProviderStatus = withContext(Dispatchers.IO) {
        _status.value = ProviderStatus.Starting(id)
        lastStateDir = config.stateDir

        val authKey = credentials.reveal()
        if (authKey.isEmpty()) {
            // Not a thrown error: an absent key means interactive login, and the
            // login URL is the useful thing to show.
            val loginUrl = runCatching { statusDocument().optString("loginURL") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val error = ProviderStatus.Error(
                provider = id,
                message = "no auth key supplied",
                loginUrl = loginUrl,
                messageRes = TextRef.of(R.string.net_no_auth_key),
            )
            _status.value = error
            return@withContext error
        }

        try {
            Mobile.start(
                config.stateDir,
                authKey,
                config.controlUrl,
                config.hostname,
                config.ephemeral,
                forceLogin,
                START_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            // The Go side already redacted this message; passing the key as a
            // second scrub term is belt-and-braces for the case where the
            // control plane echoed it back inside an unexpected wrapper.
            SafeLog.eScrubbed(TAG, "embedded node failed to start", e, authKey)
            val loginUrl = runCatching { statusDocument().optString("loginURL") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val error = ProviderStatus.Error(
                provider = id,
                message = e.message ?: "the embedded node failed to start",
                loginUrl = loginUrl,
            )
            _status.value = error
            return@withContext error
        }

        val running = readStatus()
        _status.value = running
        running
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching { Mobile.stop() }
                .onFailure { SafeLog.e(TAG, "failed to stop embedded node", it) }
            lastStateDir = null
            _status.value = ProviderStatus.Stopped(id)
        }
    }

    override suspend fun clearState() {
        withContext(Dispatchers.IO) {
            val dir = lastStateDir ?: return@withContext
            runCatching { Mobile.clearState(dir) }
                .onFailure { SafeLog.e(TAG, "failed to clear node state", it) }
            lastStateDir = null
            _status.value = ProviderStatus.Stopped(id)
        }
    }

    override suspend fun probe(target: TargetAddress, timeoutMs: Int): ProbeResult =
        withContext(Dispatchers.IO) {
            val doc = try {
                // `.toLong()` because gomobile binds Go `int` as Java `long`.
                JSONObject(Mobile.probe(target.dialString(), timeoutMs.toLong()))
            } catch (e: Exception) {
                return@withContext ProbeResult(
                    ok = false,
                    elapsedMs = 0,
                    // The native layer's own wording, kept verbatim inside a
                    // translated frame: it is diagnostic text from Go, not app copy.
                    error = TextRef.of(R.string.net_failed_with_detail, e.message ?: "probe failed"),
                )
            }
            ProbeResult(
                ok = doc.optBoolean("ok", false),
                elapsedMs = doc.optLong("ms", 0),
                resolvedAddress = doc.optString("resolved").takeIf { it.isNotEmpty() },
                error = doc.optString("error").takeIf { it.isNotEmpty() }
                    ?.let { TextRef.of(R.string.net_failed_with_detail, it) },
            )
        }

    override suspend fun fetch(request: HttpRequestSpec): FetchResult =
        withContext(Dispatchers.IO) {
            val headersJson = JSONObject().apply {
                request.headers.forEach { (name, value) -> put(name, value) }
            }.toString()
            val bodyBase64 = request.body
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                ?: ""

            val doc = try {
                // The two trailing values widen from Int to Long: gomobile binds
                // a Go `int` as a Java `long` across the whole boundary.
                JSONObject(
                    Mobile.fetch(
                        request.url,
                        request.method,
                        headersJson,
                        bodyBase64,
                        request.timeoutMs.toLong(),
                        request.maxBodyBytes.toLong(),
                    ),
                )
            } catch (e: Exception) {
                return@withContext FetchResult(
                    statusCode = 0,
                    headers = emptyMap(),
                    body = ByteArray(0),
                    truncated = false,
                    elapsedMs = 0,
                    error = TextRef.of(R.string.net_failed_with_detail, e.message ?: "request failed"),
                )
            }

            val error = doc.optString("error").takeIf { it.isNotEmpty() }
                ?.let { TextRef.of(R.string.net_failed_with_detail, it) }
            val body = doc.optString("bodyB64")
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?: ByteArray(0)

            FetchResult(
                statusCode = doc.optInt("status", 0),
                headers = parseHeaders(doc.optJSONObject("headers")),
                body = body,
                truncated = doc.optBoolean("truncated", false),
                elapsedMs = doc.optLong("ms", 0),
                error = error,
            )
        }

    override suspend fun diagnostics(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = Mobile.logs()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrElse { listOf("diagnostics unavailable: ${it.message ?: "native bridge error"}") }
    }

    // -----------------------------------------------------------------------
    // The route to the target's own web UI
    // -----------------------------------------------------------------------

    /**
     * Asks the Go side for a loopback proxy in front of the target.
     *
     * This override is why the method exists at all. A WebView is Android's HTTP
     * stack, and the socket to the target is created inside the tsnet netstack on
     * the other side of a JNI boundary, so there is no route for the WebView to
     * take. The proxy is that route: it listens on `127.0.0.1`, and it makes the
     * outbound connection through this package, where the tailnet dialer lives.
     * It dials the platform's own network when no node is up, which is the right
     * answer for the system-network connection method — but with the embedded
     * node selected, the caller is expected to have brought the node up first
     * (see `ConnectionTester.Scope.BRING_UP`), because until it is, the proxy
     * answers every request with a 502.
     *
     * # One thing this method must never do
     *
     * The document that comes back contains `url`, and that URL carries the
     * proxy's session token in its query string. It is a [WebUrl] from the moment
     * it is parsed, it is never logged, and it is never put into a message: the
     * only thing that receives it is the WebView.
     */
    override suspend fun openWebAccess(
        target: TargetAddress,
        scheme: String,
        path: String,
    ): WebAccess = withContext(Dispatchers.IO) {
        // A release from the screen the user just left may still be in flight.
        // Without this, a proxy started now could be shut down by it a moment
        // later — the Go side serialises both calls, it just cannot know which
        // of them the app means to be last.
        webAccessTeardown?.join()

        val targetUrl = target.url(scheme, path)

        // The proxy fails closed without an allowlist, so grant exactly this
        // target's origin before asking it to start. This is the fixed-target
        // rule (anti-SSRF): the Go side refuses any URL not on this list.
        Mobile.setSecurityConfig(securityConfigFor(scheme, target))

        val doc = try {
            JSONObject(Mobile.startProxy(targetUrl))
        } catch (e: Exception) {
            // No URL is passed to the log line, and the scrubber would mask the
            // token if one ever reached it: see SafeLog and Redact.
            SafeLog.w(TAG, "local proxy failed to start for ${Redact.hostLabel(target.host)}", e)
            return@withContext WebAccess.Refused(
                // The native layer's own wording, inside a translated frame: it
                // is diagnostic text from Go, not app copy.
                TextRef.of(R.string.net_failed_with_detail, e.message ?: "the local proxy failed"),
            )
        }

        val url = doc.optString("url")
        if (!doc.optBoolean("running", false) || url.isEmpty()) {
            val detail = doc.optString("error").ifEmpty { "the local proxy refused the target" }
            SafeLog.w(TAG, "local proxy refused ${Redact.hostLabel(target.host)}: $detail")
            return@withContext WebAccess.Refused(TextRef.of(R.string.web_proxy_failed, detail))
        }

        SafeLog.d(TAG, "local proxy is up for ${Redact.hostLabel(target.host)}")
        WebAccess.Ready(WebUrl(url))
    }

    /**
     * The security policy handed to the Go proxy before it starts. The only
     * rule it carries is the allowlist: this target's origin, and nothing else,
     * so the proxy can never be pointed at a URL the app did not validate.
     */
    private fun securityConfigFor(scheme: String, target: TargetAddress): String =
        JSONObject()
            .put("allowedTargets", JSONArray().put("$scheme://${target.authority()}"))
            .toString()

    /**
     * Closes the loopback listener.
     *
     * `webAccessTeardown` is why this can run from a main-thread callback: the
     * port stops listening shortly after, not during, the call.
     */
    override fun closeWebAccess() {
        webAccessTeardown = teardownScope.launch {
            runCatching { Mobile.stopProxy() }
                .onFailure { SafeLog.w(TAG, "failed to stop the local proxy", it) }
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * Remembers the state directory so [clearState] can work without the caller
     * re-supplying it. Cleared on stop, like the node itself.
     */
    private var lastStateDir: String? = null

    /**
     * Where [closeWebAccess] does its work.
     *
     * Deliberately process-scoped rather than tied to a screen or a view model:
     * the whole point of a teardown is that it still runs when the thing that
     * asked for it is gone. Closing the listener blocks for as long as the Go
     * side gives in-flight responses to finish, which is not something a caller
     * on the main thread may wait for.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The teardown in flight, if any, so [openWebAccess] can wait it out. */
    @Volatile
    private var webAccessTeardown: Job? = null

    private fun readStatus(): ProviderStatus {
        val doc = statusDocument()
        return when (doc.optString("state")) {
            "running" -> ProviderStatus.Running(
                provider = id,
                ipv4 = doc.optString("ip4"),
                ipv6 = doc.optString("ip6").takeIf { it.isNotEmpty() },
                hostname = doc.optString("hostname"),
                controlUrl = doc.optString("controlURL"),
                tailnetName = doc.optString("tailnet").takeIf { it.isNotEmpty() },
            )
            "error" -> ProviderStatus.Error(
                provider = id,
                message = doc.optString("error").ifEmpty { "the node reported an error" },
                loginUrl = doc.optString("loginURL").takeIf { it.isNotEmpty() },
            )
            "starting" -> ProviderStatus.Starting(id)
            else -> ProviderStatus.Stopped(id)
        }
    }

    private fun statusDocument(): JSONObject =
        runCatching { JSONObject(Mobile.status()) }.getOrElse { JSONObject() }

    private fun parseHeaders(obj: JSONObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (name in obj.keys()) {
            val value = obj.opt(name)
            out[name] = when (value) {
                is JSONArray -> (0 until value.length()).map { value.optString(it) }
                is String -> listOf(value)
                null -> emptyList()
                else -> listOf(value.toString())
            }
        }
        return out
    }

    private companion object {
        const val TAG = "Tsnet"

        /**
         * How long to wait for the control plane to admit the node.
         *
         * A `Long`, not an `Int`, because gomobile binds a Go `int` as a Java
         * `long` — every integer parameter crossing this boundary is 64-bit.
         * That is not a detail you can guess from the Go source, and getting it
         * wrong produces "actual type is Int, but Long was expected" on a line
         * whose Go counterpart looks perfectly correct.
         */
        const val START_TIMEOUT_MS = 45_000L
    }
}
