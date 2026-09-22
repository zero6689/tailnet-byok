package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR codec, both directions, without a device.
 *
 * The round-trip test is the one that matters, and it is a *real* round trip: the
 * matrix is painted into an ARGB buffer by this file — the same convention the
 * Compose renderer uses, dark modules on a light ground — and handed back to the
 * decoder. If the renderer and the decoder disagreed about which value means
 * "dark", or about the quiet zone, this is where it would show up rather than on
 * a phone at arm's length.
 */
class QrCodeTest {

    private val link = "dshbyok://setup?target=100.101.102.103&port=3080&scheme=http&path=%2F&mode=embedded_tsnet"

    /** Paints [code] the way the screen does, [scale] pixels per module. */
    private fun paint(code: QrCode, scale: Int): Pair<IntArray, Int> {
        val side = code.size * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code.isDark(x, y)) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        pixels[(y * scale + dy) * side + (x * scale + dx)] = 0xFF000000.toInt()
                    }
                }
            }
        }
        return pixels to side
    }

    @Test
    fun `a link survives a trip through a rendered code`() {
        val code = QrEncoder.encode(link)
        assertNotNull(code)

        val (pixels, side) = paint(code!!, scale = 4)
        assertEquals(link, QrDecoder.decodeArgb(pixels, side, side))
    }

    @Test
    fun `the quiet zone is part of the grid`() {
        val code = QrEncoder.encode(link)!!
        val bare = QrEncoder.encode(link, margin = 0)!!

        assertEquals(4, code.margin)
        assertEquals(0, bare.margin)
        assertEquals(bare.size + 8, code.size)

        // The quiet zone is light: it is the thing that lets a reader find the
        // edge of the code, and a dark border there would be a code some
        // scanners never see.
        for (i in 0 until code.size) {
            assertTrue(!code.isDark(i, 0))
            assertTrue(!code.isDark(0, i))
            assertTrue(!code.isDark(i, code.size - 1))
        }
        // And the code itself starts one module in from the margin.
        assertTrue(code.isDark(4, 4))
    }

    @Test
    fun `nothing to encode, and something too long, both return null`() {
        assertNull(QrEncoder.encode(""))
        assertNull(QrEncoder.encode("x".repeat(5000)))
    }

    @Test
    fun `a longer payload produces a bigger code`() {
        val small = QrEncoder.encode("dshbyok://setup?target=a")!!
        val large = QrEncoder.encode(link + "&control=" + "h".repeat(200))!!
        assertTrue("expected ${large.size} > ${small.size}", large.size > small.size)
    }

    // -- Rotation ------------------------------------------------------------

    /**
     * A 2x3 frame whose bytes are all distinct, so a wrong mapping shows up as
     * the wrong number rather than as a plausible-looking picture.
     */
    private fun frame(): LuminanceFrame =
        LuminanceFrame(byteArrayOf(1, 2, 3, 4, 5, 6), width = 2, height = 3)

    @Test
    fun `a quarter turn clockwise moves the last column to the first row`() {
        val rotated = LuminanceRotator.rotate(frame(), 90)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        // e c a / f d b  for  a b / c d / e f
        assertEquals(listOf(5, 3, 1, 6, 4, 2), rotated.data.map { it.toInt() })
    }

    @Test
    fun `a half turn reverses both axes`() {
        val rotated = LuminanceRotator.rotate(frame(), 180)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), rotated.data.map { it.toInt() })
    }

    @Test
    fun `three quarter turns are the other way round`() {
        val rotated = LuminanceRotator.rotate(frame(), 270)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        // b d f / a c e
        assertEquals(listOf(2, 4, 6, 1, 3, 5), rotated.data.map { it.toInt() })
    }

    @Test
    fun `no turn and a full turn leave the frame alone`() {
        assertEquals(frame().data.toList(), LuminanceRotator.rotate(frame(), 0).data.toList())
        val full = LuminanceRotator.rotate(frame(), 360)
        assertEquals(frame().data.toList(), full.data.toList())
        assertEquals(2, full.width)
    }

    @Test
    fun `an angle that is not a quarter turn is left alone rather than guessed at`() {
        val odd = LuminanceRotator.rotate(frame(), 45)
        assertEquals(2, odd.width)
        assertEquals(frame().data.toList(), odd.data.toList())
    }

    @Test
    fun `a code rendered turned is still read when the camera's rotation is applied`() {
        val code = QrEncoder.encode(link)!!
        val (pixels, side) = paint(code, scale = 4)

        // Turn the picture into a greyscale frame, then turn the frame 90
        // degrees: the decoder is told what the camera reported and undoes it.
        val grey = ByteArray(side * side) { i ->
            if (pixels[i] == 0xFF000000.toInt()) 0 else 255.toByte()
        }
        val turned = LuminanceRotator.rotate(LuminanceFrame(grey, side, side), 90)

        assertEquals(link, QrDecoder.decode(turned, rotationDegrees = 270))
    }

    @Test
    fun `a frame with no code in it is not an error`() {
        // Uniform grey: the normal case for a camera pointed at nothing.
        assertNull(QrDecoder.decode(LuminanceFrame(ByteArray(64 * 64) { 128.toByte() }, 64, 64)))
    }

    // -- What a scanner can hand over ----------------------------------------

    @Test
    fun `a code that carries a label as well still yields the link`() {
        val withLabel = "Scan me: $link\n"
        assertEquals(link, ScannedText.setupLinkIn(withLabel))
    }

    @Test
    fun `wrapping characters are not part of the link`() {
        assertEquals(link, ScannedText.setupLinkIn("<$link>"))
        assertEquals(link, ScannedText.setupLinkIn("\"$link\""))
        assertEquals(link, ScannedText.setupLinkIn("  $link  "))
    }

    @Test
    fun `text with no link in it comes back trimmed, so the parser can explain itself`() {
        assertEquals("https://example.org/", ScannedText.setupLinkIn("  https://example.org/ "))
        assertEquals("", ScannedText.setupLinkIn("   "))
    }
}
