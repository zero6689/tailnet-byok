package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The decision table behind the updater.
 *
 * These are the tests that matter most in this feature: everything here is a
 * judgement about whether bytes are trustworthy, and each case is a way a server
 * (or something between the app and the server) could hand over something that
 * must *not* reach the package installer.
 */
class UpdateProtocolTest {

    // -- Versions ------------------------------------------------------------

    @Test
    fun `reads a bare version`() {
        assertEquals("1.59", UpdateProtocol.parseAdvertisedVersion("1.59\n"))
    }

    @Test
    fun `reads a version with a leading v and surrounding whitespace`() {
        assertEquals("0.2.6", UpdateProtocol.parseAdvertisedVersion("  v0.2.6  \n\n"))
    }

    @Test
    fun `ignores everything after the first non-blank line`() {
        assertEquals("1.59", UpdateProtocol.parseAdvertisedVersion("\n\n1.59\nbuilt 2026-09-19\n"))
    }

    @Test
    fun `rejects bodies with no version in them`() {
        assertNull(UpdateProtocol.parseAdvertisedVersion(""))
        assertNull(UpdateProtocol.parseAdvertisedVersion("   \n \n"))
        assertNull(UpdateProtocol.parseAdvertisedVersion("<html><body>404</body></html>"))
        assertNull(UpdateProtocol.parseAdvertisedVersion("dsh.apk"))
        // A directory listing is the realistic version of this: the source is a
        // web server that answered with a page rather than a version.
        assertNull(UpdateProtocol.parseAdvertisedVersion("dsh.apk\ndsh.apk.sha256\ndsh.apk.version\n"))
    }

    @Test
    fun `ordering is numeric, not lexicographic`() {
        // The bug this prevents: "0.9" > "0.10" as strings.
        assertTrue(UpdateProtocol.isNewer("0.10", "0.9"))
        assertFalse(UpdateProtocol.isNewer("0.9", "0.10"))
        assertTrue(UpdateProtocol.isNewer("1.0", "0.99.99"))
        assertTrue(UpdateProtocol.isNewer("1.59", "1.58"))
        assertFalse(UpdateProtocol.isNewer("1.59", "1.59"))
        assertFalse(UpdateProtocol.isNewer("1.58", "1.59"))
    }

    @Test
    fun `missing components count as zero`() {
        assertEquals(0, UpdateProtocol.compare("1.59", "1.59.0"))
        assertEquals(0, UpdateProtocol.compare("2", "2.0.0"))
        assertTrue(UpdateProtocol.isNewer("2.0.1", "2"))
    }

    @Test
    fun `a build suffix does not change the ordering`() {
        // What a debug build actually reports: "0.2.6-debug".
        assertEquals(0, UpdateProtocol.compare("0.2.6", "0.2.6-debug"))
        assertFalse(UpdateProtocol.isNewer("0.2.6", "0.2.6-debug"))
        assertTrue(UpdateProtocol.isNewer("0.2.7", "0.2.6-debug"))
    }

    // -- The hash sidecar ----------------------------------------------------

    /** 64 hex characters, which is all a sha256 sidecar may be. */
    private val hash = "c63bbff479b76939eae9dfa67bbe9469f54fe35e6c4ce3919a91cece6c60c124"

    @Test
    fun `reads a bare hash, case-insensitively`() {
        assertEquals(hash, UpdateProtocol.parseSidecar(hash))
        assertEquals(hash, UpdateProtocol.parseSidecar(hash.uppercase()))
        assertEquals(hash, UpdateProtocol.parseSidecar("  $hash\n"))
    }

    @Test
    fun `reads sha256sum output, with the filename after the hash`() {
        assertEquals(hash, UpdateProtocol.parseSidecar("$hash  dsh.apk\n"))
        assertEquals(hash, UpdateProtocol.parseSidecar("$hash *dsh.apk"))
    }

    @Test
    fun `rejects anything that is not a sha256`() {
        assertNull(UpdateProtocol.parseSidecar(""))
        assertNull(UpdateProtocol.parseSidecar("   \n"))
        assertNull(UpdateProtocol.parseSidecar("not a hash"))
        // One character short, and one too long: both are the "close enough"
        // cases that a length check exists to catch.
        assertNull(UpdateProtocol.parseSidecar(hash.dropLast(1)))
        assertNull(UpdateProtocol.parseSidecar(hash + "a"))
        assertNull(UpdateProtocol.parseSidecar("z".repeat(64)))
        assertNull(UpdateProtocol.parseSidecar("404 not found"))
    }

    @Test
    fun `hashing matches the known vector`() {
        // SHA-256("abc"), the standard FIPS 180-4 example.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateProtocol.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateProtocol.sha256Hex(ByteArray(0)),
        )
    }

    // -- Transport shapes ----------------------------------------------------

    @Test
    fun `a gzipped body is unwrapped, and a plain one is left alone`() {
        val payload = "dsh.apk".toByteArray(Charsets.UTF_8)
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload) }
        }.toByteArray()

        assertTrue(UpdateProtocol.looksGzipped(gzipped))
        assertFalse(UpdateProtocol.looksGzipped(payload))
        assertArrayEquals(payload, UpdateProtocol.gunzipIfNeeded(gzipped))
        assertArrayEquals(payload, UpdateProtocol.gunzipIfNeeded(payload))
    }

    @Test
    fun `zip magic is recognised, and HTML is not`() {
        assertTrue(UpdateProtocol.looksLikeZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
        assertTrue(UpdateProtocol.looksLikeZip(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0x00)))
        assertFalse(UpdateProtocol.looksLikeZip("<html>".toByteArray(Charsets.UTF_8)))
        assertFalse(UpdateProtocol.looksLikeZip("PK".toByteArray(Charsets.UTF_8)))
        assertFalse(UpdateProtocol.looksLikeZip(ByteArray(0)))
    }

    @Test
    fun `the endpoint never doubles a slash`() {
        assertEquals(
            "http://100.101.102.103:3080/dsh.apk.version",
            UpdateProtocol.endpoint("http://100.101.102.103:3080/", UpdateProtocol.VERSION_PATH),
        )
        assertEquals(
            "http://host:8089/mirror/dsh.apk",
            UpdateProtocol.endpoint(" http://host:8089/mirror ", UpdateProtocol.APK_PATH),
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}

/**
 * The persisted record.
 *
 * It exists so that an outcome survives the package installer stopping the app,
 * which makes two properties worth testing: a round trip is faithful, and a
 * record this build does not understand yields "unknown" instead of throwing.
 */
class UpdateRecordTest {

    @Test
    fun `round-trips every outcome`() {
        val outcomes = listOf(
            UpdateOutcome.Unknown,
            UpdateOutcome.UpToDate(current = "0.2.6-debug", advertised = "0.2.6"),
            UpdateOutcome.Ready(current = "0.2.6-debug", available = "0.2.7", sizeBytes = 61_651_328),
            UpdateOutcome.Failed(UpdateFailure.SIDECAR_MISMATCH),
        )
        for (outcome in outcomes) {
            assertEquals(outcome, UpdateRecord.decode(UpdateRecord.encode(outcome)))
        }
    }

    @Test
    fun `an absent or unreadable record is unknown, not a crash`() {
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode(null))
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode(""))
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode("something-else|1|2"))
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode("failed|NOT_A_REAL_REASON"))
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode("ready|0.2.6|0.2.7"))
        assertEquals(UpdateOutcome.Unknown, UpdateRecord.decode("uptodate|0.2.6"))
    }

    @Test
    fun `a persisted failure keeps its exact reason`() {
        for (reason in UpdateFailure.values()) {
            assertEquals(
                UpdateOutcome.Failed(reason),
                UpdateRecord.decode(UpdateRecord.encode(UpdateOutcome.Failed(reason))),
            )
        }
    }
}
