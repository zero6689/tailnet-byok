package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the address policy.
 *
 * This is the app's main input-validation surface, and the place where a wrong
 * answer is either a security hole (letting the app dial an arbitrary public
 * host) or a bad first-run experience (rejecting something that works). Both
 * directions are covered.
 */
class TailnetAddressPolicyTest {

    private val defaultPort = 3080

    // https by default: these tests are about the address shape, and the
    // cleartext rule has its own section at the bottom of this file.
    private fun classify(input: String, scheme: String = "https") =
        TailnetAddressPolicy.classify(input, defaultPort, scheme)

    // -- Accepted ------------------------------------------------------------

    @Test
    fun `accepts a tailnet IPv4 literal with a port`() {
        val verdict = classify("100.101.102.103:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Allowed)
        assertEquals("100.101.102.103", verdict.address.host)
        assertEquals(3080, verdict.address.port)
    }

    @Test
    fun `accepts a tailnet IPv4 literal and defaults the port, with a warning`() {
        val verdict = classify("100.101.102.103")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.AllowedWithWarning)
        assertEquals(
            TailnetAddressPolicy.Warning.DEFAULTED_PORT,
            (verdict as TailnetAddressPolicy.Verdict.AllowedWithWarning).warning,
        )
        assertEquals(defaultPort, verdict.address.port)
    }

    @Test
    fun `accepts the lowest and highest address in 100_64_0_0 slash 10`() {
        assertTrue(classify("100.64.0.0:80") is TailnetAddressPolicy.Verdict.Allowed)
        assertTrue(classify("100.127.255.255:80") is TailnetAddressPolicy.Verdict.Allowed)
    }

    @Test
    fun `accepts a MagicDNS FQDN`() {
        val verdict = classify("phone.tailnet-name.ts.net:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Allowed)
        assertEquals("phone.tailnet-name.ts.net", verdict.address.host)
    }

    @Test
    fun `accepts a tailnet IPv6 literal in brackets`() {
        val verdict = classify("[fd7a:115c:a1e0::1]:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Allowed)
        assertEquals("fd7a:115c:a1e0::1", verdict.address.host)
    }

    @Test
    fun `allows a bare short name but warns, because MagicDNS short names are not guaranteed`() {
        val verdict = classify("phone")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.AllowedWithWarning)
        assertEquals(
            TailnetAddressPolicy.Warning.BARE_SHORT_NAME,
            (verdict as TailnetAddressPolicy.Verdict.AllowedWithWarning).warning,
        )
    }

    @Test
    fun `warns on an FQDN outside the ts_net zone, which is the headscale case`() {
        val verdict = classify("server.internal.example.com:8080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.AllowedWithWarning)
        assertEquals(
            TailnetAddressPolicy.Warning.NON_MAGIC_DNS_NAME,
            (verdict as TailnetAddressPolicy.Verdict.AllowedWithWarning).warning,
        )
    }

    @Test
    fun `trims surrounding whitespace from a pasted value`() {
        val verdict = classify("  100.101.102.103:3080  ")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Allowed)
        assertEquals("100.101.102.103", verdict.address.host)
    }

    // -- Rejected ------------------------------------------------------------

    @Test
    fun `rejects a public IPv4 address`() {
        val verdict = classify("93.184.216.34:80")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.PUBLIC_ADDRESS,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects addresses just outside the tailnet range`() {
        // 100.63.x and 100.128.x are the neighbours of 100.64.0.0/10 and are the
        // off-by-one a hand-written range check gets wrong.
        assertTrue(classify("100.63.255.255:80") is TailnetAddressPolicy.Verdict.Rejected)
        assertTrue(classify("100.128.0.0:80") is TailnetAddressPolicy.Verdict.Rejected)
    }

    @Test
    fun `rejects loopback, because 127_0_0_1 is the phone`() {
        val verdict = classify("127.0.0.1:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.LOOPBACK,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects a pasted URL rather than silently accepting its host`() {
        val verdict = classify("http://100.101.102.103:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.SCHEME_PRESENT,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects empty input`() {
        assertEquals(
            TailnetAddressPolicy.Reason.EMPTY,
            (classify("   ") as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects embedded whitespace, which is what a double paste looks like`() {
        assertEquals(
            TailnetAddressPolicy.Reason.MALFORMED,
            (classify("100.101.102.103 3080") as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects an out-of-range port`() {
        assertTrue(classify("100.101.102.103:0") is TailnetAddressPolicy.Verdict.Rejected)
        assertTrue(classify("100.101.102.103:70000") is TailnetAddressPolicy.Verdict.Rejected)
        assertTrue(classify("100.101.102.103:not-a-port") is TailnetAddressPolicy.Verdict.Rejected)
    }

    @Test
    fun `rejects an unbracketed IPv6 literal, because it is ambiguous with host-colon-port`() {
        // `fd7a:115c:a1e0::1:3080` is a *valid IPv6 address* — the trailing 3080
        // is a hex group, not a port. So it cannot be disambiguated by parsing,
        // and resolving it as an address would be right about half the time.
        // Brackets are required instead.
        val verdict = classify("fd7a:115c:a1e0::1:3080")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.IPV6_NEEDS_BRACKETS,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `rejects an unbracketed IPv6 literal even without a port`() {
        // Same rule, same reason: consistency beats a special case that works
        // only until someone adds a port to it.
        assertEquals(
            TailnetAddressPolicy.Reason.IPV6_NEEDS_BRACKETS,
            (classify("fd7a:115c:a1e0::1") as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    // -- Formatting ----------------------------------------------------------

    @Test
    fun `renders authority with brackets for IPv6`() {
        assertEquals("100.101.102.103:3080", TargetAddress("100.101.102.103", 3080).authority())
        assertEquals("[fd7a::1]:3080", TargetAddress("fd7a::1", 3080).authority())
    }

    @Test
    fun `does not resolve a hostname during classification`() {
        // A validator that performs DNS is a validator that blocks on the network
        // and can be made to leak a lookup. This must return promptly.
        val started = System.nanoTime()
        classify("this-name-does-not-exist-anywhere.invalid:3080")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("classification took ${elapsedMs}ms, so it touched the network", elapsedMs < 100)
    }

    // -- Cleartext ------------------------------------------------------------
    //
    // Plain http is legal for the tailnet and nowhere else. This rule used to
    // live in res/xml/network_security_config.xml, which could not express the
    // 100.64.0.0/10 range and so blocked the app's own primary use case. It is
    // enforced here now; these tests are that rule's contract.

    @Test
    fun `allows cleartext to a tailnet IPv4 literal`() {
        // The regression that started all of this: 100.101.102.103:3080 over http
        // used to be refused by the platform with a CLEARTEXT error.
        assertTrue(classify("100.101.102.103:3080", "http") is TailnetAddressPolicy.Verdict.Allowed)
    }

    @Test
    fun `allows cleartext to a tailnet IPv6 literal`() {
        assertTrue(
            classify("[fd7a:115c:a1e0::1]:3080", "http") is TailnetAddressPolicy.Verdict.Allowed,
        )
    }

    @Test
    fun `allows cleartext to a MagicDNS name, with or without the trailing dot`() {
        assertTrue(
            classify("desktop.tailnet-name.ts.net:3080", "http") is
                TailnetAddressPolicy.Verdict.Allowed,
        )
        // MagicDNS reports fully-qualified names with a trailing dot; the same
        // destination must not be treated differently because of it.
        val fqdn = classify("desktop.tailnet-name.ts.net.:3080", "http")
        assertTrue(fqdn is TailnetAddressPolicy.Verdict.Allowed)
        // ... and the canonical form is what every later layer builds a URL
        // from, so the root label must not survive into it.
        assertEquals("desktop.tailnet-name.ts.net", fqdn.address.host)
    }

    @Test
    fun `allows cleartext to private-network name suffixes`() {
        for (host in listOf("nas.local", "box.internal", "printer.lan", "thing.home.arpa")) {
            assertTrue(
                "$host should accept cleartext",
                classify("$host:80", "http") is TailnetAddressPolicy.Verdict.AllowedWithWarning,
            )
        }
        // `localhost` is a bare name to the address parser, so it carries the
        // bare-name warning — but it is explicitly exempt from the cleartext
        // rule, which is what this asserts.
        assertTrue(
            classify("localhost:8080", "http") is TailnetAddressPolicy.Verdict.AllowedWithWarning,
        )
    }

    @Test
    fun `refuses cleartext to a public hostname`() {
        val verdict = classify("example.com:80", "http")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.CLEARTEXT_REQUIRES_HTTPS,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `refuses cleartext to a bare short name, which cannot be shown to be tailnet-only`() {
        val verdict = classify("phone", "http")

        assertTrue(verdict is TailnetAddressPolicy.Verdict.Rejected)
        assertEquals(
            TailnetAddressPolicy.Reason.CLEARTEXT_REQUIRES_HTTPS,
            (verdict as TailnetAddressPolicy.Verdict.Rejected).reason,
        )
    }

    @Test
    fun `the same public hostname is accepted over https`() {
        assertTrue(
            classify("example.com:443", "https") is TailnetAddressPolicy.Verdict.AllowedWithWarning,
        )
    }

    @Test
    fun `a rejected address stays rejected whatever the scheme`() {
        // The cleartext rule is an additional gate, never a replacement: a public
        // IP literal must not become diallable by picking https.
        for (scheme in listOf("http", "https")) {
            assertEquals(
                TailnetAddressPolicy.Reason.PUBLIC_ADDRESS,
                (classify("93.184.216.34:80", scheme) as TailnetAddressPolicy.Verdict.Rejected).reason,
            )
        }
    }
}
