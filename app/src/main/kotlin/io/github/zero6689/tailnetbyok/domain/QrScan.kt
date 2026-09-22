package io.github.zero6689.tailnetbyok.domain

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * One frame of greyscale, ready to be decoded.
 *
 * Greyscale rather than a colour image because that is what a QR code is: a
 * luminance threshold problem. It is also what the camera already hands over —
 * the Y plane of a YUV frame *is* a greyscale image — so building a colour
 * bitmap first would be work, allocation and precision thrown away before the
 * decoder saw it.
 */
class LuminanceFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "a frame has a size" }
        require(data.size >= width * height) { "the buffer covers the frame" }
    }
}

/**
 * Rotates a [LuminanceFrame] by the camera's reported rotation.
 *
 * This exists because the two halves of the pipeline disagree about which way is
 * up: a camera sensor delivers frames in its own landscape orientation, while
 * everything downstream of finding a QR code's three corner markers assumes the
 * grid runs with the image. A detector can *locate* a code at any angle — the
 * markers are rotation-invariant — but the sampling that follows is much better
 * behaved on an upright picture, and this costs one pass over the luminance
 * plane per frame.
 *
 * Only whole quarter turns are meaningful here; the camera reports 0, 90, 180 or
 * 270 and nothing else. Any other value is treated as no rotation rather than
 * guessed at.
 */
object LuminanceRotator {

    fun rotate(frame: LuminanceFrame, degrees: Int): LuminanceFrame {
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) return frame

        val source = frame.data
        val w = frame.width
        val h = frame.height

        return when (turn) {
            90 -> {
                // Clockwise: the first output row is the first input column, read
                // bottom to top.
                val out = ByteArray(w * h)
                val newWidth = h
                for (y in 0 until w) {
                    for (x in 0 until h) {
                        out[y * newWidth + x] = source[(h - 1 - x) * w + y]
                    }
                }
                LuminanceFrame(out, newWidth, w)
            }

            180 -> {
                val out = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        out[y * w + x] = source[(h - 1 - y) * w + (w - 1 - x)]
                    }
                }
                LuminanceFrame(out, w, h)
            }

            270 -> {
                val out = ByteArray(w * h)
                val newWidth = h
                for (y in 0 until w) {
                    for (x in 0 until h) {
                        out[y * newWidth + x] = source[x * w + (w - 1 - y)]
                    }
                }
                LuminanceFrame(out, newWidth, w)
            }

            else -> frame
        }
    }
}

/**
 * Reads QR codes.
 *
 * Two entry points, because the two sources are different problems: a camera
 * frame is greyscale and arrives dozens of times a second, and a picture the
 * user chose is colour, arrives once, and is worth spending more time on.
 *
 * `QRCodeReader` rather than `MultiFormatReader`: this app scans exactly one
 * format, and a reader that also tries to find EAN-13 barcodes in a photograph
 * of a screen is a reader that returns the wrong string confidently. Decoding
 * never throws — a frame with no code in it is the normal case, not an error.
 */
object QrDecoder {

    /**
     * The text of the QR code in [frame], or null when there is none.
     *
     * [rotationDegrees] is applied first; pass the camera's reported value.
     * [thorough] trades time for recall and is for still images, not frames.
     */
    fun decode(
        frame: LuminanceFrame,
        rotationDegrees: Int = 0,
        thorough: Boolean = false,
    ): String? {
        val upright = LuminanceRotator.rotate(frame, rotationDegrees)
        val source = PlanarYUVLuminanceSource(
            upright.data,
            upright.width,
            upright.height,
            0,
            0,
            upright.width,
            upright.height,
            false,
        )
        return read(source, thorough)
    }

    /** The text of the QR code in an ARGB pixel buffer, or null when there is none. */
    fun decodeArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        thorough: Boolean = true,
    ): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        // ZXing's argument order here is (width, height, pixels), which is not
        // the order the rest of this file uses; passed positionally by name.
        return read(RGBLuminanceSource(width, height, pixels), thorough)
    }

    private fun read(source: com.google.zxing.LuminanceSource, thorough: Boolean): String? {
        val hints = if (thorough) mapOf(DecodeHintType.TRY_HARDER to true) else null
        return try {
            // A fresh reader per call: `QRCodeReader` is not documented as
            // thread-safe, and the analyzer and the gallery path can overlap.
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: com.google.zxing.FormatException) {
            null
        } catch (_: com.google.zxing.ChecksumException) {
            null
        }
    }
}

/**
 * Pulls a configuration link out of whatever a scanner actually returned.
 *
 * A QR code carries exactly the string that was encoded, but the string can
 * still arrive wrapped — a code that encodes a link *and* a label, a screenshot
 * decoder that adds a newline, a chat client that wraps it in angle brackets.
 * The rule here is narrow on purpose: find the scheme, keep everything up to the
 * first character that cannot be part of a URI. Nothing is repaired, nothing is
 * completed, and a string with no `dshbyok://` in it is handed back unchanged so
 * the parser can say *why* it was refused.
 */
object ScannedText {

    private const val SCHEME_PREFIX = "dshbyok://"

    fun setupLinkIn(scanned: String): String {
        val trimmed = scanned.trim()
        val start = trimmed.indexOf(SCHEME_PREFIX, ignoreCase = true)
        if (start < 0) return trimmed
        val rest = trimmed.substring(start)
        val end = rest.indexOfFirst { it.isWhitespace() || it == '"' || it == '<' || it == '>' }
        return if (end < 0) rest else rest.substring(0, end)
    }
}
