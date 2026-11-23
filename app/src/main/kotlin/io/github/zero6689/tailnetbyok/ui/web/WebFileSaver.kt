package io.github.zero6689.tailnetbyok.ui.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Save-only bridge for the page this app renders.
 *
 * # Why it exists
 *
 * A WebView cannot save an `<a download>` to a blob (nothing hands the bytes to
 * the download machinery), and this screen has no `DownloadListener`: a link
 * that would download does nothing at all. Files the page has already fetched
 * through its own session cookie can be streamed here instead and written to
 * the phone's Downloads collection, so "download to phone" works without
 * leaving the app or re-authenticating anywhere.
 *
 * # What it can do
 *
 * Create one new file under `Downloads/DeepSeek Harness`, append bytes the page
 * fetched, and publish it. It cannot read, list, delete, open or overwrite an
 * existing file: the protocol has no path, only a sanitized display name, and
 * MediaStore resolves that name itself.
 *
 * # The token
 *
 * `addJavascriptInterface` exposes these methods to *every* frame the WebView
 * renders, so every call carries a token that only the top document of this
 * app's own origin receives (see `injectSaveToken` and its origin check in
 * `WebScreen`). A foreign page or frame cannot guess it.
 *
 * Android 10+ writes through MediaStore, so the file lands in the normal
 * Downloads collection. Older devices write to the app's external Downloads
 * directory, which needs no storage permission.
 */
class WebFileSaver(context: Context, private val token: String) {

    private val context: Context = context.applicationContext

    private var out: OutputStream? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var displayName: String = "download"
    private var open: Boolean = false

    /** Begin one download. @return "ok:<name>" or "err:<reason>". */
    @JavascriptInterface
    @Synchronized
    fun start(token: String?, name: String?, mime: String?): String {
        if (token != this.token) return "err:forbidden"
        discard()
        displayName = sanitize(name)
        val type = if (mime.isNullOrBlank()) "application/octet-stream" else mime
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/DeepSeek Harness",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "err:cannot create download entry"
                val stream = context.contentResolver.openOutputStream(target)
                if (stream == null) {
                    context.contentResolver.delete(target, null, null)
                    return "err:cannot open output"
                }
                uri = target
                out = stream
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return "err:no external storage"
                val folder = File(dir, "DeepSeek Harness")
                if (!folder.exists() && !folder.mkdirs()) return "err:cannot create folder"
                val target = unique(folder, displayName)
                file = target
                displayName = target.name
                out = FileOutputStream(target)
            }
            open = true
            "ok:$displayName"
        } catch (error: Exception) {
            discard()
            "err:${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Append one base64 chunk. @return whether the bytes were written. */
    @JavascriptInterface
    @Synchronized
    fun chunk(token: String?, base64: String?): Boolean {
        if (token != this.token || !open) return false
        val stream = out ?: return false
        if (base64.isNullOrEmpty()) return true
        return try {
            stream.write(Base64.decode(base64, Base64.DEFAULT))
            true
        } catch (error: Exception) {
            false
        }
    }

    /** Close the file and publish it. @return "ok:<path>" or "err:<reason>". */
    @JavascriptInterface
    @Synchronized
    fun finish(token: String?): String {
        if (token != this.token) return "err:forbidden"
        val stream = out
        if (!open || stream == null) return "err:no active download"
        return try {
            stream.flush()
            stream.close()
            out = null
            val path: String
            val target = uri
            if (target != null) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(target, done, null, null)
                path = Environment.DIRECTORY_DOWNLOADS + "/DeepSeek Harness/" + displayName
                uri = null
            } else {
                path = file?.absolutePath ?: displayName
                file = null
            }
            open = false
            "ok:$path"
        } catch (error: Exception) {
            discard()
            "err:${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Discard an unfinished download. */
    @JavascriptInterface
    @Synchronized
    fun abort(token: String?) {
        if (token == this.token) discard()
    }

    private fun discard() {
        open = false
        out?.let { stream ->
            try {
                stream.close()
            } catch (ignored: Exception) {
                // already gone
            }
        }
        out = null
        uri?.let { target ->
            try {
                context.contentResolver.delete(target, null, null)
            } catch (ignored: Exception) {
                // already gone
            }
        }
        uri = null
        file?.let { target ->
            try {
                target.delete()
            } catch (ignored: Exception) {
                // already gone
            }
        }
        file = null
    }

    private companion object {

        /** Keep one path-free display name: no separators, no control characters. */
        fun sanitize(name: String?): String {
            val cleaned = StringBuilder()
            for (c in (name ?: "").trim()) {
                cleaned.append(
                    if (c.code < 0x20 || c.code == 0x7f || c in "\\/:*?\"<>|") '_' else c,
                )
            }
            var value = cleaned.toString().trim()
            while (value.startsWith(".")) value = value.substring(1)
            if (value.isEmpty()) value = "download"
            if (value.length > 120) {
                val dot = value.lastIndexOf('.')
                val extension = if (dot > 0 && value.length - dot <= 12) value.substring(dot) else ""
                value = value.substring(0, 120 - extension.length) + extension
            }
            return value
        }

        /** A sibling name that does not exist yet (older-device path only). */
        fun unique(folder: File, name: String): File {
            val first = File(folder, name)
            if (!first.exists()) return first
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            for (index in 1 until 1000) {
                val next = File(folder, String.format(Locale.ROOT, "%s (%d)%s", base, index, extension))
                if (!next.exists()) return next
            }
            return File(
                folder,
                String.format(Locale.ROOT, "%s-%d%s", base, System.currentTimeMillis(), extension),
            )
        }
    }
}
