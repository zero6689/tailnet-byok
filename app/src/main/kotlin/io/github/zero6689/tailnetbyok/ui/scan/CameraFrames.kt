package io.github.zero6689.tailnetbyok.ui.scan

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageProxy
import io.github.zero6689.tailnetbyok.domain.LuminanceFrame
import kotlin.math.max

/**
 * Bridges the camera's frames and the photo picker to the decoder.
 *
 * Both conversions are here rather than in the decoder because they are the two
 * places that know about Android: `ImageProxy` and `BitmapFactory` do not exist
 * on the JVM, and keeping them out of `domain/QrScan.kt` is what lets every
 * decision the scanner makes — rotation, thresholding, what counts as a code —
 * be tested without a device.
 */
object CameraFrames {

    /**
     * The luminance plane of [image], packed row by row, or null when the frame
     * cannot be read.
     *
     * The packing is the whole point. `ImageProxy` hands over the Y plane in a
     * buffer whose rows are `rowStride` bytes apart, which on most devices is
     * *wider* than the image: the padding at the end of each row is real memory
     * and is not part of the picture. A decoder handed that buffer as if it were
     * contiguous reads a picture that is skewed a little more with every row, and
     * the symptom is a scanner that works on one phone and never on another.
     */
    fun luminance(image: ImageProxy): LuminanceFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        // The Y plane of a YUV image is one byte per pixel by definition; a
        // stride that says otherwise means this is not the format assumed here,
        // and guessing would be worse than giving up on the frame.
        if (plane.pixelStride != 1) return null

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val last = buffer.position() + (height - 1) * rowStride + width
        if (rowStride < width || buffer.capacity() < last) return null

        val packed = ByteArray(width * height)
        val base = buffer.position()
        for (row in 0 until height) {
            val start = base + row * rowStride
            for (col in 0 until width) {
                packed[row * width + col] = buffer.get(start + col)
            }
        }
        return LuminanceFrame(packed, width, height)
    }
}

/** An image the user picked, as the decoder wants it. */
class ArgbImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Reads a picture the user chose, at a size that will not exhaust the heap.
 *
 * A modern phone camera writes 12 megapixels, and the same bitmap as ARGB is
 * 48 MB. The decoder does not need any of that: a QR code photographed on a
 * screen is legible at a fraction of it. The image is measured first and then
 * decoded with a power-of-two sample size, which is how `BitmapFactory` is meant
 * to be used and the reason it exposes `inJustDecodeBounds`.
 */
object ImageFiles {

    /** Longest side kept when reading a picked image. */
    const val MAX_SIDE = 1600

    fun read(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): ArgbImage? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull() ?: return null

        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbImage(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
