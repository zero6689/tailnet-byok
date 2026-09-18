package io.github.zero6689.tailnetbyok.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import java.io.File

/**
 * Puts a verified package where the system installer can read it, and asks the
 * system to install it.
 *
 * # Why this is not just "start an intent"
 *
 * Three things have to be true before an install intent is worth sending, and
 * each of them is a real failure mode on a phone:
 *
 *  1. **The file has to be readable by the installer.** Since Android 7 a
 *     `file://` URI crossing a process boundary throws `FileUriExposedException`,
 *     so the staged APK goes out as a `content://` URI from a [FileProvider]
 *     declared in the manifest and granted read permission for exactly one
 *     intent. Nothing is world-readable and nothing is exported.
 *  2. **It has to be *this* app's package.** A verified SHA-256 proves the bytes
 *     are the ones the update source published; it does not prove they are a
 *     build of this application. Reading the archive's package name is cheap, and
 *     it turns "install whatever the server had at that path" into "install a
 *     build of this app" — which matters precisely because the default update
 *     source is a host the app does not own.
 *  3. **Android may require the user's consent first.** From API 26, an app
 *     installing a package needs the user to have allowed installs from that app
 *     (`canRequestPackageInstalls`). That is reported as a distinct outcome so the
 *     UI can say so and offer the right settings screen, rather than failing with
 *     an unexplained intent error.
 *
 * # The staged file
 *
 * `cacheDir/updates/dsh.apk`, written atomically (`.part` then rename) so a
 * process death mid-download cannot leave a half-file that a later install would
 * pick up. `cacheDir` is the right home: the file is a disposable transfer, the
 * system may reclaim it, and Android will not let anything else read it through
 * the provider even while it exists. That the cache can be cleared is why
 * [InstallRequest.MissingFile] exists as a normal outcome rather than a panic.
 */
class UpdateInstaller(private val context: Context) {

    /** What happened when the install was requested. */
    sealed interface InstallRequest {

        /** The system installer was handed the package. */
        data object Launched : InstallRequest

        /** The staged file is gone — the cache was cleared, or nothing was staged. */
        data object MissingFile : InstallRequest

        /** Android needs the user to allow installs from this app first. */
        data object NeedsPermission : InstallRequest

        /** The archive declares a different application. */
        data class WrongPackage(val packageName: String?) : InstallRequest

        /** Nothing on the device accepted the intent. */
        data object NoHandler : InstallRequest
    }

    /** Where a downloaded package is staged. Deterministic, so it survives a restart. */
    fun stagedFile(): File = File(File(context.cacheDir, UPDATE_DIR), UpdateProtocol.APK_PATH)

    /**
     * Writes verified [bytes] to the staged file.
     *
     * Atomic on purpose: `writeBytes` onto the final name would leave a truncated
     * file behind if the process were killed, and the next launch would find a
     * file that exists, has a plausible size, and is not installable.
     */
    fun stage(bytes: ByteArray): File {
        val file = stagedFile()
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            // A stale `.part` from an interrupted earlier run is the likely cause;
            // delete and retry once, then give up loudly.
            file.delete()
            if (!part.renameTo(file)) {
                part.delete()
                throw java.io.IOException("could not move the downloaded package into place")
            }
        }
        return file
    }

    /**
     * Hands the staged package to the system installer.
     *
     * The order of the checks is the order of their cost and their
     * consequence: existence, then identity, then permission, then the intent.
     */
    fun requestInstall(): InstallRequest {
        val apk = stagedFile()
        if (!apk.isFile || apk.length() == 0L) return InstallRequest.MissingFile

        val declared = archivePackageName(apk)
        if (declared != context.packageName) return InstallRequest.WrongPackage(declared)

        if (!context.packageManager.canRequestPackageInstalls()) return InstallRequest.NeedsPermission

        val uri: Uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            // Read permission for this one intent, and NEW_TASK because this is
            // launched from the application context rather than an activity.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            SafeLog.i(TAG, "handed ${apk.length()} bytes to the package installer")
            InstallRequest.Launched
        } catch (e: ActivityNotFoundException) {
            SafeLog.w(TAG, "no activity can install a package", e)
            InstallRequest.NoHandler
        }
    }

    /**
     * Opens the per-app "install unknown apps" screen.
     *
     * Offered after [InstallRequest.NeedsPermission] rather than opened
     * automatically: it takes the user out of the app, and doing that without
     * them asking is the kind of thing this app does not do.
     */
    fun openInstallPermissionSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            SafeLog.w(TAG, "no settings screen for unknown app sources", e)
            false
        }
    }

    /**
     * The package name declared inside [file], or null when it cannot be read.
     *
     * `getPackageArchiveInfo` is the documented way to ask this without
     * installing anything. The deprecated int-flags overload is used because the
     * `PackageInfoFlags` replacement only exists from API 33 and this app's
     * `minSdk` is 26.
     */
    @Suppress("DEPRECATION")
    private fun archivePackageName(file: File): String? =
        runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName }
            .onFailure { SafeLog.w(TAG, "could not read the archive's package name", it) }
            .getOrNull()

    companion object {
        private const val TAG = "UpdateInstaller"
        private const val UPDATE_DIR = "updates"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /**
         * The FileProvider authority.
         *
         * Derived from the running package name rather than hardcoded, so the
         * debug variant's `.debug` suffix — and the `applicationId` placeholder in
         * the manifest — cannot drift apart from the string used here.
         */
        fun authority(context: Context): String = "${context.packageName}.updates"
    }
}
