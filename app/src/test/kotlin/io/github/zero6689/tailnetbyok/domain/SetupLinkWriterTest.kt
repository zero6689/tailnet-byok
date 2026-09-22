package io.github.zero6689.tailnetbyok.domain

import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.net.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer side of the configuration link.
 *
 * The property that matters is not "it produces a string" but "the string comes
 * back as what it was made from". A code that scans into a *different* target is
 * the one failure this feature must not have, and it is the failure a
 * hand-written encoder gets: a field renamed on one side, a port lost in
 * translation, a path that gains a slash every round trip.
 */
class SetupLinkWriterTest {

    private fun config(
        host: String = "100.101.102.103",
        port: Int = 3080,
        scheme: String = "http",
        path: String = "/",
        provider: ProviderId = ProviderId.EMBEDDED_TSNET,
        updateUrl: String = "",
        controlUrl: String = "",
        nodeHostname: String = AppConfig.DEFAULT_NODE_HOSTNAME,
    ) = AppConfig(
        provider = provider,
        scheme = scheme,
        hostInput = host,
        port = port,
        path = path,
        updateUrl = updateUrl,
        controlUrl = controlUrl,
        nodeHostname = nodeHostname,
    )

    /** The link, parsed back, applied to a device that has nothing configured. */
    private fun roundTrip(link: String): AppConfig {
        val result = SetupLinkParser.parse(link)
        assertTrue("expected $link to parse, got $result", result is SetupLinkParse.Parsed)
        return (result as SetupLinkParse.Parsed).link.applyTo(AppConfig())
    }

    @Test
    fun `the link reproduces the target exactly`() {
        val original = config(host = "100.101.102.103", port = 3080, path = "/dsh")
        val link = SetupLinkParser.format(original)
        assertNotNull(link)

        val applied = roundTrip(link!!)
        assertEquals("100.101.102.103", applied.hostInput)
        assertEquals(3080, applied.port)
        assertEquals("http", applied.scheme)
        assertEquals("/dsh", applied.path)
    }

    @Test
    fun `the link reproduces the transport, the update source and a self-hosted control plane`() {
        val original = config(
            provider = ProviderId.SYSTEM_NETWORK,
            updateUrl = "http://100.101.102.103:8089/byok",
            controlUrl = "https://headscale.example.org",
        )
        val applied = roundTrip(SetupLinkParser.format(original)!!)

        assertEquals(ProviderId.SYSTEM_NETWORK, applied.provider)
        assertEquals("http://100.101.102.103:8089/byok", applied.updateUrl)
        assertEquals("https://headscale.example.org", applied.controlUrl)
    }

    @Test
    fun `a non-default port and an https target survive`() {
        val original = config(scheme = "https", port = 8443, path = "/ui")
        val applied = roundTrip(SetupLinkParser.format(original)!!)

        assertEquals("https", applied.scheme)
        assertEquals(8443, applied.port)
        assertEquals("/ui", applied.path)
    }

    @Test
    fun `the hosted control plane is left out rather than written down`() {
        // "Not self-hosted" is the absence of a value, and writing Tailscale's
        // own URL into a link would make the receiver treat it as a custom
        // server — the opposite of what the sender meant.
        val original = config(controlUrl = AppConfig.HOSTED_CONTROL_URL)
        val link = SetupLinkParser.format(original)!!

        assertTrue("no control field expected in $link", !link.contains("control="))
        assertEquals("", roundTrip(link).controlUrl)
    }

    @Test
    fun `the node hostname is never shared`() {
        // Two devices that adopt one node name fight over it inside the tailnet.
        // The name identifies this phone; the link describes the server.
        val original = config(nodeHostname = "my-phone")
        val link = SetupLinkParser.format(original)!!

        assertTrue("no node field expected in $link", !link.contains("node="))
        assertEquals(AppConfig.DEFAULT_NODE_HOSTNAME, roundTrip(link).nodeHostname)
    }

    @Test
    fun `no credential-shaped field name is ever emitted`() {
        val original = config(updateUrl = "http://100.101.102.103:8089/byok")
        val link = SetupLinkParser.format(original)!!

        // The parser refuses a whole link for one of these names, so a link the
        // writer produced that tripped this would be a link the reader refuses —
        // the round trip above would fail. Asserted directly as well, because
        // this is the boundary a future field would break.
        val names = link.substringAfter('?').split('&').map { it.substringBefore('=').lowercase() }
        val forbidden = listOf("auth", "key", "pass", "pwd", "secret", "token", "credential", "bearer", "otp", "pin")
        assertTrue(
            "credential-shaped field in $names",
            names.none { name -> forbidden.any { name.contains(it) } },
        )
    }

    @Test
    fun `no target means no link`() {
        assertNull(SetupLinkParser.format(config(host = "")))
        assertNull(SetupLinkParser.format(config(host = "   ")))
    }

    @Test
    fun `a host typed as a whole URL is refused, not silently rewritten`() {
        // The receiver would end up with a different `hostInput` than the sender
        // had, and the settings field on their phone would show something the
        // sender never typed. Refusing is the honest answer; the writer's round
        // trip is what notices.
        assertNull(SetupLinkParser.format(config(host = "http://100.101.102.103:3080/")))
    }

    @Test
    fun `the link has the shape the parser documents`() {
        val link = SetupLinkParser.format(config(host = "host.example.org"))!!
        assertTrue(link.startsWith("dshbyok://setup?"))
        assertTrue(link.contains("target=host.example.org"))
        assertTrue(link.contains("mode=embedded_tsnet"))
    }
}
