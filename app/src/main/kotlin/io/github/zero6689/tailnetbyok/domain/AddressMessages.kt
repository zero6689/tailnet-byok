package io.github.zero6689.tailnetbyok.domain

import androidx.annotation.StringRes
import io.github.zero6689.tailnetbyok.R

/**
 * User-facing text for the address policy's verdicts.
 *
 * The policy itself stays a pure function over strings — no Android, no
 * resources — and returns an enum. Turning that enum into a sentence is a
 * presentation concern, so it lives here rather than inside the policy: it is
 * the single table that the settings screen's inline verdict line and the
 * connection tester's step detail both read from, which is what keeps the two
 * from ever wording the same rejection differently.
 */
internal object AddressMessages {

    @StringRes
    fun reason(reason: TailnetAddressPolicy.Reason): Int = when (reason) {
        TailnetAddressPolicy.Reason.EMPTY -> R.string.reject_empty
        TailnetAddressPolicy.Reason.MALFORMED -> R.string.reject_malformed
        TailnetAddressPolicy.Reason.SCHEME_PRESENT -> R.string.reject_scheme_present
        TailnetAddressPolicy.Reason.PUBLIC_ADDRESS -> R.string.reject_public_address
        TailnetAddressPolicy.Reason.LOOPBACK -> R.string.reject_loopback
        TailnetAddressPolicy.Reason.IPV6_NEEDS_BRACKETS -> R.string.reject_ipv6_needs_brackets
        TailnetAddressPolicy.Reason.UNPARSEABLE_HOST -> R.string.reject_unparseable_host
        TailnetAddressPolicy.Reason.CLEARTEXT_REQUIRES_HTTPS ->
            R.string.reject_cleartext_requires_https
    }

    @StringRes
    fun warning(warning: TailnetAddressPolicy.Warning): Int = when (warning) {
        TailnetAddressPolicy.Warning.BARE_SHORT_NAME -> R.string.warn_bare_short_name
        TailnetAddressPolicy.Warning.NON_MAGIC_DNS_NAME -> R.string.warn_non_magic_dns
        TailnetAddressPolicy.Warning.DEFAULTED_PORT -> R.string.warn_defaulted_port
    }
}
