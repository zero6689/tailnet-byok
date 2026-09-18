package io.github.zero6689.tailnetbyok.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SessionCookieJar].
 *
 * The jar exists because a real service (the DSH auth proxy) answers the first
 * `GET /` with `303 Set-Cookie` and only serves the page on the second request.
 * These tests pin the two properties that matter for that handshake — the cookie
 * comes back on the next request to the same place, and it does not come back
 * anywhere else — plus the expiry and replacement rules that keep a stale or
 * duplicated credential from being sent.
 */
class SessionCookieJarTest {

    private val target = "http://100.101.102.103:3080/".toHttpUrl()

    private fun sessionCookie(header: String): Cookie =
        requireNotNull(Cookie.parse(target, header)) { "unparseable test cookie: $header" }

    @Test
    fun `replays the session cookie the proxy mints on the first 303`() {
        val jar = SessionCookieJar()

        jar.saveFromResponse(
            target,
            listOf(
                sessionCookie(
                    "dsh-auth-abc=v1.cGF5bG9hZA.c2ln; Max-Age=2592000; Path=/; " +
                        "Expires=Wed, 01 Jan 2031 00:00:00 GMT; HttpOnly; SameSite=Strict",
                ),
            ),
        )

        val sent = jar.loadForRequest(target)
        assertEquals(1, sent.size)
        assertEquals("dsh-auth-abc", sent.single().name)
        assertEquals("v1.cGF5bG9hZA.c2ln", sent.single().value)
    }

    @Test
    fun `does not offer one host's cookie to another host`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(target, listOf(sessionCookie("dsh-auth-abc=v1.a.b; Path=/")))

        assertTrue(jar.loadForRequest("http://100.101.102.104:3080/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `respects the cookie path`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(target, listOf(sessionCookie("scoped=v1.a.b; Path=/api")))

        assertTrue("must not leak a /api cookie to /", jar.loadForRequest(target).isEmpty())
        assertEquals(
            1,
            jar.loadForRequest("http://100.101.102.103:3080/api/state".toHttpUrl()).size,
        )
    }

    @Test
    fun `drops an already expired cookie instead of storing it`() {
        val jar = SessionCookieJar()

        jar.saveFromResponse(
            target,
            listOf(sessionCookie("stale=v1.a.b; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")),
        )

        assertTrue(jar.loadForRequest(target).isEmpty())
    }

    @Test
    fun `re-issued cookie replaces the previous one instead of duplicating it`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(target, listOf(sessionCookie("dsh-auth-abc=old; Path=/")))
        jar.saveFromResponse(target, listOf(sessionCookie("dsh-auth-abc=new; Path=/")))

        val sent = jar.loadForRequest(target)
        assertEquals("a re-issue must not leave two copies to send", 1, sent.size)
        assertEquals("new", sent.single().value)
    }

    @Test
    fun `clear removes every session`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(target, listOf(sessionCookie("dsh-auth-abc=v1.a.b; Path=/")))

        jar.clear()

        assertTrue(jar.loadForRequest(target).isEmpty())
    }
}
