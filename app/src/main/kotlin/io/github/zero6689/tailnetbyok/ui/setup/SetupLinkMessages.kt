package io.github.zero6689.tailnetbyok.ui.setup

import androidx.annotation.StringRes
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.domain.SetupLink
import io.github.zero6689.tailnetbyok.domain.SetupLinkRejection

/**
 * The sentences for configuration links.
 *
 * One table, used by the banner that reports a refusal and by the panel that asks
 * for confirmation. A refusal reason that reads differently in the two places is
 * how a user ends up not knowing whether the link was refused or ignored.
 */
@StringRes
fun SetupLinkRejection.messageRes(): Int = when (this) {
    SetupLinkRejection.NOT_A_SETUP_LINK -> R.string.setup_link_not_a_link
    SetupLinkRejection.CREDENTIAL_FIELD -> R.string.setup_link_credential
    SetupLinkRejection.MALFORMED_VALUE -> R.string.setup_link_malformed
    SetupLinkRejection.EMPTY_LINK -> R.string.setup_link_empty
}

/** True when the link would change the connection method, for the panel's summary. */
val SetupLink.changesProvider: Boolean get() = provider != null
