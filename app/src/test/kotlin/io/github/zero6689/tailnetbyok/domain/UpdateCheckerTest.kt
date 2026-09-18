package io.github.zero6689.tailnetbyok.domain

import io.github.zero6689.tailnetbyok.net.FetchResult
import io.github.zero6689.tailnetbyok.net.HttpRequestSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The updater's behaviour at the wire level, driven by an in-memory "server".
 *
 * The checker takes its transport as a lambda, so every branch below is exercised
 * without a device, a network, or a mock: the fake records the URLs it was asked
 * for and answers from a map. Two things are being tested, and the second is the
 * one that matters:
 *
 *  1. that the three files are requested in the right order, from the right base,
 *     and that an up-to-date source costs exactly one small request;
 *  2. that **no** answer other than a hash-verified zip produces bytes. There is a
 *     test here for each way a source can be wrong, and every one of them asserts
 *     a refusal.
 */
class UpdateCheckerTest {

    /** A minimal but genuine zip: enough for the shape check to accept it. */
    private val apkBytes: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }.toByteArray()

    private val apkHash: String = UpdateProtocol.sha256Hex(apkBytes)

    private val base = "http://100.101.102.103:3080"

    /** A fake source: path → body, with the request log kept for assertions. */
    private class FakeSource(
        private val routes: Map<String, ByteArray>,
        /** URLs to answer with a body that the provider flags as truncated. */
        private val truncatedUrls: Set<String> = emptySet(),
    ) {
        val requested = mutableListOf<String>()

        suspend fun fetch(spec: HttpRequestSpec): FetchResult {
            requested += spec.url
            val body = routes[spec.url]
                ?: return FetchResult(
                    statusCode = 404,
                    headers = emptyMap(),
                    body = ByteArray(0),
                    truncated = false,
                    elapsedMs = 1,
                )
            return FetchResult(
                statusCode = 200,
                headers = emptyMap(),
                body = body,
                truncated = spec.url in truncatedUrls,
                elapsedMs = 1,
            )
        }
    }

    private fun routes(
        version: String? = null,
        apk: ByteArray? = null,
        sidecar: String? = null,
    ): Map<String, ByteArray> = buildMap {
        version?.let { put("$base/${UpdateProtocol.VERSION_PATH}", it.toByteArray(Charsets.UTF_8)) }
        apk?.let { put("$base/${UpdateProtocol.APK_PATH}", it) }
        sidecar?.let { put("$base/${UpdateProtocol.SIDECAR_PATH}", it.toByteArray(Charsets.UTF_8)) }
    }

    private fun checkerFor(source: FakeSource) = UpdateChecker(fetch = source::fetch)

    @Test
    fun `an up-to-date source costs one small request`() = runTest {
        val source = FakeSource(routes(version = "0.2.6\n"))
        val result = checkerFor(source).check(base, "0.2.6-debug")

        assertEquals(UpdateChecker.UpdateCheck.UpToDate("0.2.6-debug", "0.2.6"), result)
        assertEquals(listOf("$base/${UpdateProtocol.VERSION_PATH}"), source.requested)
    }

    @Test
    fun `a newer source is downloaded and verified`() = runTest {
        val source = FakeSource(
            routes(version = "0.2.7", apk = apkBytes, sidecar = "$apkHash  dsh.apk\n"),
        )
        val result = checkerFor(source).check(base, "0.2.6-debug")

        assertTrue("expected a verified download, got $result", result is UpdateChecker.UpdateCheck.Downloaded)
        result as UpdateChecker.UpdateCheck.Downloaded
        assertEquals("0.2.7", result.available)
        assertEquals(apkBytes.toList(), result.bytes.toList())
        assertEquals(
            listOf(
                "$base/${UpdateProtocol.VERSION_PATH}",
                "$base/${UpdateProtocol.APK_PATH}",
                "$base/${UpdateProtocol.SIDECAR_PATH}",
            ),
            source.requested,
        )
    }

    @Test
    fun `a gzipped transfer is unwrapped before it is hashed`() = runTest {
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(apkBytes) }
        }.toByteArray()
        val source = FakeSource(routes(version = "0.2.7", apk = gzipped, sidecar = apkHash))

        val result = checkerFor(source).check(base, "0.2.6-debug")

        assertTrue("expected a verified download, got $result", result is UpdateChecker.UpdateCheck.Downloaded)
        assertEquals(apkBytes.toList(), (result as UpdateChecker.UpdateCheck.Downloaded).bytes.toList())
    }

    @Test
    fun `a missing version file is a failure, not an assumed update`() = runTest {
        val source = FakeSource(routes(apk = apkBytes, sidecar = apkHash))
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a version file with no version in it is a failure`() = runTest {
        val source = FakeSource(routes(version = "<html>directory listing</html>"))
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNPARSABLE),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `an empty base is rejected before anything is fetched`() = runTest {
        val source = FakeSource(emptyMap())
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE),
            checkerFor(source).check("   ", "0.2.6"),
        )
        assertEquals(emptyList<String>(), source.requested)
    }

    @Test
    fun `a package that will not download is a failure`() = runTest {
        val source = FakeSource(routes(version = "0.2.7", sidecar = apkHash))
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.DOWNLOAD_FAILED),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a truncated package is a failure even though its prefix may be valid`() = runTest {
        // The provider fetched a body it had to cut off at the cap. A cut-off zip
        // is exactly the kind of "looks fine at a glance" result that must never
        // be hashed and installed, so truncation is a refusal on its own.
        val source = FakeSource(
            routes = routes(version = "0.2.7", apk = apkBytes, sidecar = apkHash),
            truncatedUrls = setOf("$base/${UpdateProtocol.APK_PATH}"),
        )

        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.DOWNLOAD_FAILED),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a truncated version file is a failure rather than a shorter version`() = runTest {
        val source = FakeSource(
            routes = routes(version = "0.2.7", apk = apkBytes, sidecar = apkHash),
            truncatedUrls = setOf("$base/${UpdateProtocol.VERSION_PATH}"),
        )

        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a missing sidecar refuses the download`() = runTest {
        val source = FakeSource(routes(version = "0.2.7", apk = apkBytes))
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.NO_SIDECAR),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a sidecar with no hash in it refuses the download`() = runTest {
        val source = FakeSource(routes(version = "0.2.7", apk = apkBytes, sidecar = "404 not found"))
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.NO_SIDECAR),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a hash that does not match the bytes refuses the download`() = runTest {
        val wrong = UpdateProtocol.sha256Hex("something else entirely".toByteArray(Charsets.UTF_8))
        val source = FakeSource(routes(version = "0.2.7", apk = apkBytes, sidecar = wrong))

        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.SIDECAR_MISMATCH),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `an HTML page where the package should be is refused before hashing`() = runTest {
        val page = "<html><body>captive portal</body></html>".toByteArray(Charsets.UTF_8)
        // The sidecar is even made to *agree* with the page, which is what a
        // hostile or broken mirror would do. The shape check still refuses it.
        val source = FakeSource(
            routes(version = "0.2.7", apk = page, sidecar = UpdateProtocol.sha256Hex(page)),
        )
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.NOT_AN_APK),
            checkerFor(source).check(base, "0.2.6"),
        )
    }

    @Test
    fun `a transport that throws is reported as a failure`() = runTest {
        val checker = UpdateChecker(fetch = { throw java.io.IOException("no route to host") })
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE),
            checker.check(base, "0.2.6"),
        )
    }

    @Test
    fun `a transport error inside the result is a failure`() = runTest {
        val checker = UpdateChecker(
            fetch = {
                FetchResult(
                    statusCode = 0,
                    headers = emptyMap(),
                    body = ByteArray(0),
                    truncated = false,
                    elapsedMs = 1,
                    // A message id, not a sentence: the domain carries resource ids.
                    error = io.github.zero6689.tailnetbyok.core.text.TextRef(res = 0),
                )
            },
        )
        assertEquals(
            UpdateChecker.UpdateCheck.Failed(UpdateFailure.VERSION_UNREADABLE),
            checker.check(base, "0.2.6"),
        )
    }

    @Test
    fun `a custom base is used verbatim, including a path`() = runTest {
        val mirror = "http://192.0.2.10:8089/mirror"
        val source = FakeSource(
            mapOf(
                "$mirror/${UpdateProtocol.VERSION_PATH}" to "9.9.9".toByteArray(Charsets.UTF_8),
                "$mirror/${UpdateProtocol.APK_PATH}" to apkBytes,
                "$mirror/${UpdateProtocol.SIDECAR_PATH}" to apkHash.toByteArray(Charsets.UTF_8),
            ),
        )
        val result = checkerFor(source).check("$mirror/", "0.2.6")

        assertTrue(result is UpdateChecker.UpdateCheck.Downloaded)
        assertEquals("$mirror/${UpdateProtocol.VERSION_PATH}", source.requested.first())
    }
}
