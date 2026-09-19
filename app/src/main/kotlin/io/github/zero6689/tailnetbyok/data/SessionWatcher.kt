package io.github.zero6689.tailnetbyok.data

import io.github.zero6689.tailnetbyok.domain.SessionListProtocol
import io.github.zero6689.tailnetbyok.domain.SessionRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Asks the target which sessions are running, through the app's own loopback route.
 *
 * It goes through the same proxy the WebView is using, carrying the same cookie
 * the WebView holds, which is what makes it work on both routes without either
 * route knowing about it: on the embedded route the socket into the tailnet lives
 * in Go, and this is just another request over loopback.
 *
 * The cookie is read per request rather than captured: the WebView's jar is the
 * authority on it, it is cleared when the screen closes, and a copy kept here
 * would be a credential with a longer life than the session it belongs to.
 */
class SessionWatcher(
    private val baseUrl: String,
    private val cookie: () -> String?,
    private val client: OkHttpClient = defaultClient(),
) {

    private var rpcSeq = 0

    /** The sessions, or null when the answer could not be used (see [SessionListProtocol]). */
    suspend fun sessions(): List<SessionRun>? = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/api/" + SessionListProtocol.METHOD
        val body = SessionListProtocol.request("byok-watch-${++rpcSeq}")
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("content-type", "application/json")
        // No cookie, no session: the request will come back refused, which the
        // parser reports as "no answer" rather than as "nothing is running".
        cookie()?.takeIf { it.isNotBlank() }?.let { builder.header("cookie", it) }

        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    SessionListProtocol.parseSessions(response.body?.string().orEmpty())
                }
            }
        }.getOrNull()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Short timeouts on purpose.
         *
         * This call is a poll: when the target is slow or unreachable, waiting
         * longer produces a staler answer, not a better one, and the next poll is
         * already coming. Nothing is waiting on the result — unlike the updater,
         * where the user is watching a progress row.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
