package io.github.zero6689.tailnetbyok.domain

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code as a grid of modules — the thing to draw, not the thing to show.
 *
 * This is deliberately not a bitmap. A QR code rendered by this app, by the
 * deployment's web page and by any phone camera has to agree cell for cell, and
 * the only version of it that can be compared in a test is the module grid: a
 * bitmap would drag in a scale factor, a colour space and an encoder, and every
 * assertion about it would end up being an assertion about the renderer.
 *
 * [margin] is part of the grid: the quiet zone is those modules, in the code, at
 * full size — not padding applied by whichever view happens to draw it. A code
 * drawn flush against a dark border is a code some scanners will not see, and
 * "remember to leave a margin" is not a rule that survives a second renderer.
 */
data class QrCode(
    /** Modules per side, including the quiet zone. */
    val size: Int,
    /** Width of the quiet zone, in modules, on each side. */
    val margin: Int,
    /** `modules[y][x]`, true for a dark module. */
    private val modules: Array<BooleanArray>,
) {

    /** Whether the module at ([x], [y]) is dark. Out-of-range reads are light. */
    fun isDark(x: Int, y: Int): Boolean =
        y in modules.indices && x in modules[y].indices && modules[y][x]

    /** The modules of one row, for renderers that walk the grid a row at a time. */
    fun row(y: Int): BooleanArray? = modules.getOrNull(y)

    /**
     * Row-by-row equality, so a test can say "these two codes are the same code"
     * without depending on `Array` identity, which [QrCode.equals] would.
     */
    fun sameModulesAs(other: QrCode): Boolean =
        size == other.size &&
            margin == other.margin &&
            modules.indices.all { y -> modules[y].contentEquals(other.modules[y]) }
}

/**
 * Turns text into a [QrCode].
 *
 * The encoder is ZXing's `qrcode.encoder`, which is the low-level half of the
 * library: it returns a module matrix and never touches an image, a canvas or
 * AWT. That matters twice over. It runs on Android, where `java.awt` does not
 * exist, and it runs in a JVM unit test, where the whole thing can be checked
 * without an emulator.
 *
 * Error correction is level M — the middle setting, and the right default for a
 * code that is scanned off a screen at arm's length. Level H would survive a
 * thumb over the corner and cost roughly a third more modules, which on a link
 * of ~120 characters means a denser code that is *harder* to scan, not easier.
 */
object QrEncoder {

    /** The quiet zone the specification asks for: four modules on every side. */
    const val DEFAULT_MARGIN = 4

    /**
     * The code for [text], or null when there is nothing to encode or the text
     * does not fit in a QR code at all.
     *
     * Null rather than an exception or a truncated code: a caller that gets a
     * code must be able to assume it round-trips, and the only two reasons this
     * returns null are "no input" and "input too long to be a QR code", both of
     * which the UI reports as such.
     */
    fun encode(text: String, margin: Int = DEFAULT_MARGIN): QrCode? {
        if (text.isEmpty()) return null
        val hints = mapOf(
            // The link is ASCII, but a target that is not — a MagicDNS name is,
            // a path need not be — must not be mangled on the way in.
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // The quiet zone is added here, in modules, rather than by the
            // encoder; see [QrCode.margin].
            EncodeHintType.MARGIN to 0,
        )
        val matrix = runCatching {
            Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
        }.getOrNull() ?: return null

        val side = matrix.width
        if (side <= 0 || matrix.height != side) return null
        val gap = margin.coerceAtLeast(0)
        val size = side + gap * 2

        val modules = Array(size) { BooleanArray(size) }
        for (y in 0 until side) {
            for (x in 0 until side) {
                // ByteMatrix encodes 1 as dark and 0 as light; anything else
                // (it uses -1 for "unset") is treated as light rather than
                // guessed at.
                modules[y + gap][x + gap] = matrix.get(x, y).toInt() == 1
            }
        }
        return QrCode(size = size, margin = gap, modules = modules)
    }
}
