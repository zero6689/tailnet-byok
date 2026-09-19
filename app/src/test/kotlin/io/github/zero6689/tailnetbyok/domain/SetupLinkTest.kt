package io.github.zero6689.tailnetbyok.domain

import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.net.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration-link decision table.
 *
 * Two kinds of case matter here and they are not the same kind of test:
 *
 *  * the **parsing** cases, which decide whether a link is understood; and
 *  * the **boundary** cases, which decide what a link is allowed to do at all.
 *    Those are the ones worth reading first: a link is written by somebody else,
 *    and the failures that matter are the ones where it is *obeyed too much*
 *    (carrying a credential), or obeyed silently (re-pointing the app without the
 *    user seeing it — which is why nothing in this file applies a link).
 */
class SetupLinkTest {

    private fun parse(raw: String): SetupLinkParse = SetupLinkParser.parse(raw)

    private fun parsed(raw: String): SetupLink {
        val result = parse(raw)
        assertTrue("expected $raw to parse, got $result", result is SetupLinkParse.Parsed)
        return (result as SetupLinkParse.Parsed).link
    }

    private fun rejection(raw: String): SetupLinkRejection {
        val result = parse(raw)
        assertTrue("expected $raw to be refused, got $result", result is SetupLinkParse.Rejected)
        return (result as SetupLinkParse.Rejected).reason
    }

    // -- Parsing -------------------------------------------------------------

    @Test
    fun `a bare host`() {
        val link = parsed("dshbyok://setup?target=100.101.102.103")
        assertEquals("100.101.102.103", link.host)
        assertNull(link.port)
    }

    @Test
    fun `host and port`() {
        val link = parsed("dshbyok://setup?target=100.101.102.103:3080")
        assertEquals("100.101.102.103", link.host)
        assertEquals(3080, link.port)
    }

    @Test
    fun `a full url brings its scheme, port and path`() {
        val link = parsed("dshbyok://setup?target=https%3A%2F%2Fphone.tailnet-name.ts.net%3A8443%2Fhealth")
        assertEquals("phone.tailnet-name.ts.net", link.host)
        assertEquals(8443, link.port)
        assertEquals("https", link.scheme)
        assertEquals("/health", link.path)
    }

    @Test
    fun `a magicdns name is a host too`() {
        assertEquals("phone.tailnet-name.ts.net", parsed("dshbyok://setup?target=phone.tailnet-name.ts.net").host)
    }

    @Test
    fun `mode synonyms map to the two connection methods`() {
        assertEquals(ProviderId.SYSTEM_NETWORK, parsed("dshbyok://setup?mode=system").provider)
        assertEquals(ProviderId.SYSTEM_NETWORK, parsed("dshbyok://setup?mode=SYSTEM_NETWORK").provider)
        assertEquals(ProviderId.EMBEDDED_TSNET, parsed("dshbyok://setup?mode=embedded").provider)
        assertEquals(ProviderId.EMBEDDED_TSNET, parsed("dshbyok://setup?mode=tsnet").provider)
    }

    @Test
    fun `an explicit update source and a path are carried`() {
        val link = parsed(
            "dshbyok://setup?target=100.101.102.103&update=http%3A%2F%2F192.0.2.10%3A8089%2Fmirror&path=health",
        )
        assertEquals("http://192.0.2.10:8089/mirror", link.updateUrl)
        // A path without a leading slash is normalised rather than refused.
        assertEquals("/health", link.path)
    }

    @Test
    fun `unknown non-credential fields are ignored, so a newer link still works`() {
        val link = parsed("dshbyok://setup?target=100.101.102.103&somethingNew=1&colour=blue")
        assertEquals("100.101.102.103", link.host)
    }

    @Test
    fun `the scheme and host are matched case-insensitively, the fields are not`() {
        assertEquals("100.101.102.103", parsed("DSHBYOK://SETUP?TARGET=100.101.102.103").host)
    }

    // -- The boundary --------------------------------------------------------

    @Test
    fun `a link carrying a credential is refused whole`() {
        // Every one of these is a name somebody could plausibly write into a link,
        // and every one of them means "a secret is travelling through something
        // that can be photographed off a screen".
        for (name in listOf("authkey", "auth_key", "key", "apikey", "token", "password", "pass", "secret", "credential")) {
            assertEquals(
                "expected $name to be refused",
                SetupLinkRejection.CREDENTIAL_FIELD,
                rejection("dshbyok://setup?target=100.101.102.103&$name=whatever"),
            )
        }
    }

    @Test
    fun `a credential-shaped name is refused even when it is unknown to us`() {
        // The role of the check is to be broader than this app's own field list: a
        // field invented later must not become a smuggling route.
        assertEquals(
            SetupLinkRejection.CREDENTIAL_FIELD,
            rejection("dshbyok://setup?target=100.101.102.103&nodeSecretFuture=1"),
        )
    }

    @Test
    fun `a target with credentials in it is refused`() {
        // `http://user:pass@host` is a credential in the authority, which is the
        // other place one could hide.
        assertEquals(
            SetupLinkRejection.MALFORMED_VALUE,
            rejection("dshbyok://setup?target=http%3A%2F%2Fuser%3Apart%40host.tailnet-name.ts.net"),
        )
    }

    @Test
    fun `not a setup link is refused`() {
        assertEquals(SetupLinkRejection.NOT_A_SETUP_LINK, rejection("https://example.com/setup?target=x"))
        assertEquals(SetupLinkRejection.NOT_A_SETUP_LINK, rejection("dshbyok://other?target=x"))
        assertEquals(SetupLinkRejection.NOT_A_SETUP_LINK, rejection("dshbyok"))
        assertEquals(SetupLinkRejection.NOT_A_SETUP_LINK, rejection(""))
    }

    @Test
    fun `a link that names nothing is refused`() {
        assertEquals(SetupLinkRejection.EMPTY_LINK, rejection("dshbyok://setup"))
        assertEquals(SetupLinkRejection.EMPTY_LINK, rejection("dshbyok://setup?"))
        assertEquals(SetupLinkRejection.EMPTY_LINK, rejection("dshbyok://setup?target="))
        assertEquals(SetupLinkRejection.EMPTY_LINK, rejection("dshbyok://setup?unknownField=1"))
    }

    @Test
    fun `malformed values are refused rather than guessed at`() {
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103&port=0"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103&port=65536"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103&port=http"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103&mode=sideways"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103&scheme=ftp"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=ftp%3A%2F%2Fhost"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=100.101.102.103%3Aabc"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=has%20space"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=a%40b"))
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?node=has%2Fslash"))
    }

    @Test
    fun `an over-long target is refused`() {
        val long = "a".repeat(256) + ".ts.net"
        assertEquals(SetupLinkRejection.MALFORMED_VALUE, rejection("dshbyok://setup?target=$long"))
    }

    // -- Applying ------------------------------------------------------------

    @Test
    fun `applying a link changes only what it names`() {
        val before = AppConfig(
            provider = ProviderId.EMBEDDED_TSNET,
            hostInput = "old.tailnet-name.ts.net",
            port = 3080,
            path = "/",
            updateUrl = "http://192.0.2.10:8089",
            controlUrl = "https://headscale.example.com",
            nodeHostname = "my-phone",
            hasStoredKey = true,
            acknowledgedSecurityModel = true,
        )

        val after = parsed("dshbyok://setup?target=100.101.102.103&mode=system&port=8080").applyTo(before)

        assertEquals("100.101.102.103", after.hostInput)
        assertEquals(8080, after.port)
        assertEquals(ProviderId.SYSTEM_NETWORK, after.provider)
        // Untouched: the link said nothing about them, so they keep their values.
        assertEquals("/", after.path)
        assertEquals("http://192.0.2.10:8089", after.updateUrl)
        assertEquals("https://headscale.example.com", after.controlUrl)
        assertEquals("my-phone", after.nodeHostname)
        // And a credential is not something a link can set or clear.
        assertTrue(after.hasStoredKey)
        assertTrue(after.acknowledgedSecurityModel)
    }

    @Test
    fun `a mode-only link leaves the target alone`() {
        val before = AppConfig(hostInput = "100.101.102.103", port = 3080)
        val after = parsed("dshbyok://setup?mode=system").applyTo(before)
        assertEquals("100.101.102.103", after.hostInput)
        assertEquals(3080, after.port)
        assertEquals(ProviderId.SYSTEM_NETWORK, after.provider)
    }

    @Test
    fun `the format helper produces a link that parses back`() {
        val link = SetupLinkParser.format(
            target = "100.101.102.103:3080",
            mode = "system",
            updateUrl = "http://192.0.2.10:8089/mirror",
        )
        assertTrue(link.startsWith("dshbyok://setup?target="))
        val back = parsed(link)
        assertEquals("100.101.102.103", back.host)
        assertEquals(3080, back.port)
        assertEquals(ProviderId.SYSTEM_NETWORK, back.provider)
        assertEquals("http://192.0.2.10:8089/mirror", back.updateUrl)
    }

    // -- Build-time defaults (-PdefaultTarget and friends) --------------------

    @Test
    fun `no build properties means no defaults, which is what the public build ships`() {
        val defaults = SetupLinkParser.parseDefaults("", "", "")
        assertEquals("", defaults.hostInput)
        assertEquals("", defaults.updateUrl)
        assertEquals("", defaults.controlUrl)
        // The app's own defaults, untouched.
        assertEquals(AppConfig.DEFAULT_PORT, defaults.port)
        assertEquals(AppConfig.DEFAULT_NODE_HOSTNAME, defaults.nodeHostname)
    }

    @Test
    fun `a default target and mode pre-fill the configuration`() {
        val defaults = SetupLinkParser.parseDefaults(
            target = "100.101.102.103:8080",
            mode = "system",
            updateUrl = "http://192.0.2.10:8089/mirror",
        )
        assertEquals("100.101.102.103", defaults.hostInput)
        assertEquals(8080, defaults.port)
        assertEquals(ProviderId.SYSTEM_NETWORK, defaults.provider)
        assertEquals("http://192.0.2.10:8089/mirror", defaults.updateUrl)
    }

    @Test
    fun `a malformed build property degrades to no default rather than a broken app`() {
        val defaults = SetupLinkParser.parseDefaults(
            target = "100.101.102.103:not-a-port",
            mode = "sideways",
            updateUrl = "",
        )
        assertEquals("", defaults.hostInput)
        assertEquals(ProviderId.EMBEDDED_TSNET, defaults.provider)
    }

    @Test
    fun `a build property cannot smuggle a second field in`() {
        // The value is percent-encoded by the builder, so the ampersand survives as
        // data and lands in the host-shape check instead of becoming a field.
        val defaults = SetupLinkParser.parseDefaults(
            target = "100.101.102.103&authkey=tskey-auth-not-a-secret-0000",
            mode = "",
            updateUrl = "",
        )
        assertEquals("", defaults.hostInput)
    }
}
