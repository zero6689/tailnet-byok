package io.github.zero6689.tailnetbyok.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the redactor.
 *
 * These are the tests that matter most in this repository, because `Redact` is
 * the last line between a credential and a log file, and it is the kind of code
 * that is written once and then trusted for years. Each case below is a real
 * shape a secret has taken in this project's problem space.
 */
class RedactTest {

    @Test
    fun `scrubs a tailscale auth key by shape`() {
        val line = "connecting with tskey-auth-kQ9wZpLm4vRt7xYb2nH8sD3fG6jK1cV5 as credential"

        val scrubbed = Redact.scrub(line)

        assertFalse("the key must not survive", scrubbed.contains("kQ9wZpLm4vRt7xYb2nH8sD3fG6jK1cV5"))
        assertTrue(scrubbed.contains("tskey-"))
        assertTrue(scrubbed.contains(Redact.MASK))
        // The surrounding sentence must remain readable, or the log is useless.
        assertTrue(scrubbed.contains("connecting with"))
    }

    @Test
    fun `scrubs a headscale pre-auth key`() {
        val scrubbed = Redact.scrub("registering node with hskey-auth-9f8e7d6c5b4a3210")

        assertFalse(scrubbed.contains("9f8e7d6c5b4a3210"))
        assertTrue(scrubbed.contains("hskey-"))
    }

    @Test
    fun `scrubs a bearer header`() {
        val scrubbed = Redact.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")

        assertFalse(scrubbed.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `keeps the parameter name when scrubbing a query string`() {
        val scrubbed = Redact.scrub("GET /?token=abc123def456&page=2")

        assertTrue("the parameter name is diagnostic, keep it", scrubbed.contains("token="))
        assertFalse(scrubbed.contains("abc123def456"))
        // Unrelated parameters are untouched.
        assertTrue(scrubbed.contains("page=2"))
    }

    @Test
    fun `scrubs a bare 43-character base64url blob`() {
        // The shape of a DSH web session token: 32 random bytes, base64url, no padding.
        val token = "Zk3mQ7pXr1vT9wYb2nH8sD4fG6jK1cV5aB0eR2uI3oP"
        assertEquals(43, token.length)

        val scrubbed = Redact.scrub("open http://100.101.102.103:3080/?token=$token")

        assertFalse(scrubbed.contains(token))
    }

    @Test
    fun `does not mangle ordinary text`() {
        val line = "node came up at 100.101.102.103 in phone.tailnet-name.ts.net after 1840 ms"

        assertEquals(line, Redact.scrub(line))
    }

    @Test
    fun `scrubKnown removes a value the caller knows`() {
        val key = "tskey-auth-notshape-checked-here"
        val scrubbed = Redact.scrubKnown("control plane rejected $key", key)

        assertFalse(scrubbed.contains(key))
    }

    @Test
    fun `scrubKnown tolerates null and empty terms`() {
        assertEquals("plain text", Redact.scrubKnown("plain text", null, ""))
    }

    @Test
    fun `fingerprint is stable and does not reveal the input`() {
        val key = "tskey-auth-kQ9wZpLm4vRt7xYb2nH8sD3fG6jK1cV5"

        val first = Redact.fingerprint(key)
        val second = Redact.fingerprint(key)

        assertEquals("must be stable, or logs cannot be correlated", first, second)
        assertFalse("must not contain the value", first.contains("kQ9wZpLm"))
        assertTrue(first.startsWith("fp:"))
        assertNotEquals(first, Redact.fingerprint("$key-different"))
    }

    @Test
    fun `hostLabel shortens long names but keeps short ones intact`() {
        assertEquals("100.101.102.103", Redact.hostLabel("100.101.102.103"))
        assertEquals("(empty)", Redact.hostLabel(""))

        val long = "a-very-long-machine-name.tailnet-name.ts.net"
        val label = Redact.hostLabel(long)
        assertTrue("must be shorter than the input", label.length < long.length)
        assertTrue("must keep the tail, which carries the tailnet name", label.endsWith(".ts.net"))
    }
}
