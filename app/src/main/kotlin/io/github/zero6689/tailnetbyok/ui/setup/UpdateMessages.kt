package io.github.zero6689.tailnetbyok.ui.setup

import androidx.annotation.StringRes
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.domain.UpdateFailure
import io.github.zero6689.tailnetbyok.domain.UpdateOutcome

/**
 * The sentences for the update panel.
 *
 * One mapping, used by both surfaces that need it: the panel itself, and the
 * diagnostics block (which resolves the same [TextRef] outside a composable).
 * Writing the table twice is how the two end up describing the same failure
 * differently — and a bug report quoting the diagnostics is exactly where that
 * would hurt.
 */
@StringRes
fun UpdateFailure.messageRes(): Int = when (this) {
    UpdateFailure.TRANSPORT_UNAVAILABLE -> R.string.update_fail_transport_unavailable
    UpdateFailure.VERSION_UNREADABLE -> R.string.update_fail_version_unreadable
    UpdateFailure.VERSION_UNPARSABLE -> R.string.update_fail_version_unparsable
    UpdateFailure.DOWNLOAD_FAILED -> R.string.update_fail_download_failed
    UpdateFailure.PACKAGE_TOO_LARGE -> R.string.update_fail_package_too_large
    UpdateFailure.NO_SIDECAR -> R.string.update_fail_no_sidecar
    UpdateFailure.SIDECAR_MISMATCH -> R.string.update_fail_sidecar_mismatch
    UpdateFailure.NOT_AN_APK -> R.string.update_fail_not_an_apk
    UpdateFailure.WRONG_PACKAGE -> R.string.update_fail_wrong_package_short
    UpdateFailure.APK_GONE -> R.string.update_fail_apk_gone
    UpdateFailure.NO_INSTALLER -> R.string.update_fail_no_installer
    UpdateFailure.INSTALL_BLOCKED -> R.string.update_fail_install_blocked
}

/**
 * An outcome as a message.
 *
 * Sizes are reported in whole mebibytes: the number a person can act on is "about
 * 59 MB", not the exact byte count, and the exact count is in the diagnostics
 * line for anyone who needs it.
 */
fun UpdateOutcome.describe(): TextRef = when (this) {
    UpdateOutcome.Unknown -> TextRef.of(R.string.update_none)
    is UpdateOutcome.UpToDate -> TextRef.of(R.string.update_uptodate, current, advertised)
    is UpdateOutcome.Ready ->
        TextRef.of(R.string.update_ready, available, sizeBytes / (1024 * 1024))
    is UpdateOutcome.Failed -> TextRef.of(reason.messageRes())
}
