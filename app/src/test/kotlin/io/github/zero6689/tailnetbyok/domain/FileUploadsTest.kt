package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the file-upload bridge.
 *
 * Each case here is a way a real page shapes its `<input type="file">`, and each
 * one decides something the user sees: whether the picker opens with anything in
 * it, whether several files can be chosen, and whether "take a photo" appears.
 */
class FileUploadsTest {

    private fun request(
        vararg accept: String,
        multiple: Boolean = false,
        capture: Boolean = false,
    ): FileUploadRequest = FileUploadRequest.of(accept, multiple, capture)

    @Test
    fun `a single concrete type becomes the intent type`() {
        val r = request("image/*")

        assertEquals("image/*", r.pickerType)
        assertTrue(r.extraMimeTypes.isEmpty())
        assertTrue(r.imagesOnly)
    }

    @Test
    fun `several types go in the extras behind a wildcard type`() {
        val r = request("image/png", "image/jpeg")

        assertEquals(FileUploadRequest.WILDCARD, r.pickerType)
        assertEquals(listOf("image/png", "image/jpeg"), r.extraMimeTypes)
        assertTrue(r.imagesOnly)
    }

    @Test
    fun `extension hints are dropped, not passed to the picker`() {
        val r = request("application/pdf", ".csv")

        assertEquals(listOf("application/pdf"), r.mimeTypes)
        assertEquals("application/pdf", r.pickerType)
        assertFalse(r.imagesOnly)
    }

    @Test
    fun `an accept list of only extension hints filters nothing`() {
        val r = request(".csv", ".tsv")

        assertTrue(r.mimeTypes.isEmpty())
        assertEquals(FileUploadRequest.WILDCARD, r.pickerType)
        assertTrue(r.extraMimeTypes.isEmpty())
    }

    @Test
    fun `blank entries are ignored`() {
        val r = request("", "   ", "image/*")

        assertEquals(listOf("image/*"), r.mimeTypes)
    }

    @Test
    fun `no accept attribute means anything goes`() {
        val r = request()

        assertTrue(r.mimeTypes.isEmpty())
        assertEquals(FileUploadRequest.WILDCARD, r.pickerType)
        assertFalse(r.imagesOnly)
    }

    @Test
    fun `the camera is offered for an image-only input`() {
        assertTrue(request("image/*", capture = true).offersCamera)
        assertTrue(request("image/png", "image/jpeg", capture = true).offersCamera)
    }

    @Test
    fun `the camera is offered when the input constrains nothing`() {
        assertTrue(request(capture = true).offersCamera)
        // ...including when the page never asked for a capture source: this is the
        // shape the DSH composer's attach button has, and the point of the feature.
        assertTrue(request().offersCamera)
    }

    @Test
    fun `the camera is not offered when the page cannot accept a photo`() {
        assertFalse(request("application/pdf").offersCamera)
        assertFalse(request("image/*", "application/pdf").offersCamera)
    }

    @Test
    fun `an explicit capture request wins even for a type a photo cannot satisfy`() {
        assertTrue(request("application/pdf", capture = true).offersCamera)
    }

    @Test
    fun `the multiple flag is passed through`() {
        assertTrue(request("image/*", multiple = true).allowMultiple)
        assertFalse(request("image/*", multiple = false).allowMultiple)
    }
}
