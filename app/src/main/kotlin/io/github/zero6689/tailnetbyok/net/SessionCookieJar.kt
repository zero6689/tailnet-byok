package io.github.zero6689.tailnetbyok.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory cookie jar: just enough browser to survive a login handshake.
 *
 * Why this exists. A DSH-style remote endpoint authenticates a first-time
 * visitor by answering `GET /` with
 *
 *     303 See Other, Location: /, Set-Cookie: dsh-auth-…=<token>
 *
 * and then serving the page on the *second* request, when the cookie comes
 * back. That is a browser-shaped handshake, and OkHttp is not a browser: its
 * default is [CookieJar.NO_COOKIES], so the cookie is thrown away, the follow-up
 * request to `/` looks identical to the first one, and OkHttp walks the same
 * redirect until its 20-follow-up cap trips:
 *
 *     ProtocolException: Too many follow-up requests: 21
 *
 * Measured against the real proxy on 2026-09-13: no cookie → 303 to `/` every
 * time; the cookie kept → 200. So the missing piece was this jar, not the
 * service, the path, or the scheme.
 *
 * Scope and safety:
 *  * Memory only. Nothing is written to disk, and nothing survives a provider
 *    restart — [clear] is called when the target configuration changes.
 *  * The cookie is a bearer credential for the target, so it must never be
 *    logged or shown. Nothing here does; `SafeLog`/`Redact` cover the callers.
 *  * Matching is delegated to [Cookie.matches], which enforces domain, path,
 *    expiry and the secure flag, so a cookie minted for one host cannot leak to
 *    another or travel over a connection the server did not intend.
 *  * [CookieJar.NO_COOKIES] remains the right choice for every other client in
 *    the app; this one is only for talking to a user's own service.
 */
class SessionCookieJar : CookieJar {

    private val byHost = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val stored = byHost.computeIfAbsent(url.host) { mutableListOf() }
        synchronized(stored) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                // A re-issued cookie with the same name and path replaces the old
                // one; keeping both would send a duplicate on the next request.
                stored.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > now) stored.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val stored = byHost[url.host] ?: return emptyList()
        synchronized(stored) {
            val now = System.currentTimeMillis()
            stored.removeAll { it.expiresAt <= now }
            return stored.filter { it.matches(url) }
        }
    }

    /** Drops every session. Called when the target configuration changes. */
    fun clear() {
        byHost.clear()
    }
}
