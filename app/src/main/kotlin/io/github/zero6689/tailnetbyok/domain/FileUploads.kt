package io.github.zero6689.tailnetbyok.domain

/**
 * A page's file input, reduced to the few things the picker needs to know.
 *
 * The WebView hands over the raw `accept` attribute plus two flags. Turning that
 * into the shape an Android chooser wants is pure string work, which is why it
 * lives here: no `android.*`, so the rules below are unit-tested on the JVM
 * instead of being discovered on a phone.
 *
 * The rules, and why each one is the way it is:
 *
 *  * **Extension hints are dropped.** `accept=".csv"` is legal HTML and matches
 *    no content provider as a MIME type; passing it through produces a picker
 *    that opens with nothing in it, which the user reads as "upload is broken".
 *  * **One concrete type goes in `type`, several go in `EXTRA_MIME_TYPES` with a
 *    wildcard `type`.** That is the documented pairing: a picker that gets
 *    `type="image/png"` plus two extras filters by the extras only on some
 *    devices, while the wildcard plus extras filters everywhere.
 *  * **The camera is offered when a photo is something the page accepts** — an
 *    image-only input, or one that constrains nothing at all (which is the shape
 *    the DSH composer's attach button has). A page that explicitly asked for a
 *    capture source gets one too. A photo cannot satisfy
 *    `accept="application/pdf"`, and a chooser entry that can only produce
 *    something the page will reject is worse than no entry.
 */
class FileUploadRequest private constructor(
    /** Accept entries that are real MIME types, in the order the page listed them. */
    val mimeTypes: List<String>,
    /** The page asked for more than one file. */
    val allowMultiple: Boolean,
    /** The page asked for a capture source (`capture` attribute). */
    val captureEnabled: Boolean,
) {
    /** The intent's `type`. */
    val pickerType: String = if (mimeTypes.size == 1) mimeTypes.first() else WILDCARD

    /** `EXTRA_MIME_TYPES`: only worth setting when it actually narrows the filter. */
    val extraMimeTypes: List<String> = if (mimeTypes.size > 1) mimeTypes else emptyList()

    /** Every accepted type is an image (or there is no type at all — see [offersCamera]). */
    val imagesOnly: Boolean = mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("image/") }

    /**
     * Whether to add "take a photo" next to the picker.
     *
     * Three ways in: the page accepts only images, the page constrains nothing
     * (so anything, including a photo, is acceptable), or the page explicitly
     * asked for a capture source. A capture entry the page would then reject is
     * a trap, so an input that names non-image types does not get one.
     */
    val offersCamera: Boolean = captureEnabled || mimeTypes.isEmpty() || imagesOnly

    override fun equals(other: Any?): Boolean =
        other is FileUploadRequest &&
            other.mimeTypes == mimeTypes &&
            other.allowMultiple == allowMultiple &&
            other.captureEnabled == captureEnabled

    override fun hashCode(): Int =
        (mimeTypes.hashCode() * 31 + allowMultiple.hashCode()) * 31 + captureEnabled.hashCode()

    override fun toString(): String =
        "FileUploadRequest(mimeTypes=$mimeTypes, allowMultiple=$allowMultiple, " +
            "captureEnabled=$captureEnabled)"

    companion object {
        const val WILDCARD = "*/*"

        /**
         * Reads a WebView file chooser request.
         *
         * `acceptTypes` is the page's raw list and may be null (no `accept`
         * attribute), empty, or full of extension hints.
         */
        fun of(
            acceptTypes: Array<out String>?,
            allowMultiple: Boolean,
            captureEnabled: Boolean,
        ): FileUploadRequest = FileUploadRequest(
            mimeTypes = (acceptTypes ?: emptyArray())
                .map { it.trim() }
                .filter { it.contains('/') && it.isNotBlank() },
            allowMultiple = allowMultiple,
            captureEnabled = captureEnabled,
        )
    }
}
