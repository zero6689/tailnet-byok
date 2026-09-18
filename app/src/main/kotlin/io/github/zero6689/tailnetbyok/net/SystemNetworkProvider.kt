package io.github.zero6689.tailnetbyok.net

import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.domain.TargetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Reaches the target over whatever network the device already has.
 *
 * This provider exists for the case where the tunnel is somebody else's problem:
 * the user runs the official Tailscale app (or is on a corporate VPN, or on the
 * same LAN) and wants this app only for the configuration, validation and
 * testing half of the job.
 *
 * What it cannot do, stated plainly because the UI says so too: it cannot use an
 * auth key. There is no supported way for one Android app to hand an auth key to
 * `com.tailscale.ipn`, so this provider ignores [TailnetCredentials] entirely.
 * A user who pastes a key and picks this provider has not connected anything —
 * the settings screen warns about exactly that.
 *
 * It also inherits the real-world DNS caveats of that arrangement: an app
 * excluded from the official client's split tunnel loses DNS *and* traffic, and
 * an app doing its own DNS-over-HTTPS bypasses MagicDNS. That is why [probe] is
 * documented to work best with a literal `100.x` address.
 */
class SystemNetworkProvider : ConnectivityProvider {

    override val id: ProviderId = ProviderId.SYSTEM_NETWORK

    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.Stopped(id))
    override val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    /**
     * The session cookie jar is not an optimisation: without it the probe cannot
     * get past a login handshake. See [SessionCookieJar] for the measurement.
     */
    private val cookieJar = SessionCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        // No proxy: an HTTP proxy would see the plaintext of a request the
        // tunnel was supposed to protect.
        .proxy(java.net.Proxy.NO_PROXY)
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun start(
        config: TailnetConfig,
        credentials: TailnetCredentials,
        forceLogin: Boolean,
    ): ProviderStatus {
        // Nothing to bring up. Report Running so the UI enables the test button,
        // and let the probe be the thing that actually discovers the truth.
        val running = ProviderStatus.Running(
            provider = id,
            ipv4 = "",
            ipv6 = null,
            hostname = config.hostname,
            controlUrl = config.controlUrl,
            tailnetName = null,
        )
        _status.value = running
        SafeLog.i(TAG, "system-network provider active; auth key is not used by this provider")
        return running
    }

    override suspend fun stop() {
        _status.value = ProviderStatus.Stopped(id)
    }

    override suspend fun clearState() {
        // The minted session cookie belongs to the target that issued it. Keeping
        // it across a configuration change would offer this service's credential
        // to the next one.
        cookieJar.clear()
    }

    override suspend fun probe(target: TargetAddress, timeoutMs: Int): ProbeResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val remote = (socket.remoteSocketAddress as? InetSocketAddress)
                        ?.address?.hostAddress
                    ProbeResult(ok = true, elapsedMs = elapsed, resolvedAddress = remote)
                }
            } catch (e: IOException) {
                ProbeResult(
                    ok = false,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    error = describe(e),
                )
            } catch (e: SecurityException) {
                ProbeResult(
                    ok = false,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    error = TextRef.of(R.string.net_blocked, e.message ?: "security exception"),
                )
            }
        }

    override suspend fun fetch(request: HttpRequestSpec): FetchResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val spec = Request.Builder()
                .url(request.url)
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            when (request.method.uppercase()) {
                "GET" -> spec.get()
                "HEAD" -> spec.head()
                "POST" -> spec.post((request.body ?: ByteArray(0)).toRequestBody())
                "PUT" -> spec.put((request.body ?: ByteArray(0)).toRequestBody())
                "DELETE" -> spec.delete(request.body?.toRequestBody())
                else -> spec.method(request.method.uppercase(), request.body?.toRequestBody())
            }
            client.newBuilder()
                .connectTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(spec.build())
                .execute()
                .use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val truncated = bytes.size > request.maxBodyBytes
                    FetchResult(
                        statusCode = response.code,
                        headers = response.headers.toMultimap(),
                        body = if (truncated) bytes.copyOf(request.maxBodyBytes) else bytes,
                        truncated = truncated,
                        elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    )
                }
        } catch (e: IOException) {
            FetchResult(
                statusCode = 0,
                headers = emptyMap(),
                body = ByteArray(0),
                truncated = false,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                error = describe(e),
            )
        }
    }

    /**
     * Diagnostic lines, deliberately left in English and outside `strings.xml`.
     *
     * Unlike screen copy, these are pasted into bug reports and compared against
     * upstream tool output, so a stable, greppable identifier is worth more than
     * a translated sentence. The labels the app itself adds around them (in
     * `SetupViewModel`) *are* translated.
     */
    override suspend fun diagnostics(): List<String> = listOf(
        "provider: system network (no embedded node)",
        "auth key: not used by this provider",
    )

    /**
     * Turns a socket exception into something a user can act on. "No route to
     * host" and "connection refused" mean very different things, and the
     * difference is the whole value of a test button.
     */
    private fun describe(e: IOException): TextRef = when {
        e is java.net.SocketTimeoutException -> TextRef.of(R.string.net_timeout)
        e is java.net.UnknownHostException -> TextRef.of(R.string.net_name_not_resolved)
        e is java.net.ConnectException -> TextRef.of(R.string.net_connection_refused)
        e is java.net.NoRouteToHostException -> TextRef.of(R.string.net_no_route)
        e is java.net.SocketException ->
            TextRef.of(R.string.net_unreachable, e.message ?: "socket error")
        else -> TextRef.of(R.string.net_blocked, e.message ?: e::class.java.simpleName)
    }

    private companion object {
        const val TAG = "SystemNet"
    }
}
