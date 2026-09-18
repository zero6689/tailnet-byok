package io.github.zero6689.tailnetbyok.domain

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Where the app is allowed to dial.
 *
 * This is the one place that decides whether a user-supplied destination is
 * acceptable, and it runs before every dial. Two independent reasons it exists:
 *
 *  1. **Security.** A target is user-supplied input that ends up in a socket
 *     call. Pinning it to the tailnet ranges means a typo, a pasted public
 *     hostname, or a malicious QR-code share cannot turn this app into a
 *     general-purpose SSRF primitive that reaches arbitrary internet hosts with
 *     the user's credentials.
 *  2. **Honesty.** Several plausible-looking inputs *cannot* work, and the app
 *     must say so at validation time rather than after a 30-second timeout.
 *     The most important is a bare MagicDNS short name: tsnet resolves through
 *     its own netstack, and bare-name resolution depends on search domains that
 *     are not guaranteed to be pushed to an embedded node (see docs/TSNET.md).
 *
 * The policy returns a verdict rather than a boolean because "valid but risky"
 * is a real and common state that deserves a visible warning, not a silent pass.
 */
object TailnetAddressPolicy {

    /** Tailscale's IPv4 range, carved out of CGNAT. */
    private val tailnetV4 = Cidr("100.64.0.0", 10)

    /** Tailscale's IPv6 ULA prefix. */
    private val tailnetV6 = Cidr("fd7a:115c:a1e0::", 48)

    /** The MagicDNS zone Tailscale hands out. */
    private const val MAGIC_DNS_SUFFIX = ".ts.net"

    /** Tailscale's own DNS resolver, reachable only from inside the tailnet. */
    const val TAILNET_DNS = "100.100.100.100"

    sealed interface Verdict {
        val address: TargetAddress

        /** Dial it. */
        data class Allowed(override val address: TargetAddress) : Verdict

        /** Dial it, but tell the user what is likely to go wrong. */
        data class AllowedWithWarning(
            override val address: TargetAddress,
            val warning: Warning,
        ) : Verdict

        /** Do not dial. [reason] is user-facing. */
        data class Rejected(
            override val address: TargetAddress,
            val reason: Reason,
        ) : Verdict
    }

    enum class Warning {
        /** Bare name: needs a search domain that an embedded node may not have. */
        BARE_SHORT_NAME,

        /** FQDN outside `*.ts.net`: fine for headscale, but it must resolve in-tailnet. */
        NON_MAGIC_DNS_NAME,

        /** No port given, so the scheme default is being assumed. */
        DEFAULTED_PORT,
    }

    enum class Reason {
        EMPTY,
        MALFORMED,
        SCHEME_PRESENT,
        PUBLIC_ADDRESS,
        LOOPBACK,

        /**
         * An IPv6 literal was written without brackets.
         *
         * This is rejected rather than guessed at, and the reason is worth
         * stating because it looks pedantic. `fd7a:115c:a1e0::1:3080` is a
         * *syntactically valid IPv6 address* — the trailing `3080` is a hex
         * group, not a port — so a parser cannot tell "address" from
         * "address plus port", and the platform's own `InetAddress` resolves it
         * happily as an address. The user, meanwhile, almost certainly meant the
         * port.
         *
         * Requiring brackets removes the ambiguity permanently rather than
         * resolving it wrongly half the time. `[fd7a:115c:a1e0::1]:3080` has
         * exactly one reading.
         */
        IPV6_NEEDS_BRACKETS,

        UNPARSEABLE_HOST,

        /**
         * The transport and the destination disagree: plain HTTP was chosen for
         * a host that is not a tailnet destination.
         *
         * Android's network security config matches hostnames, not CIDR blocks,
         * so the rule that file documents — cleartext for the tailnet, HTTPS for
         * everything else — cannot be expressed there for the `100.64.0.0/10`
         * range the app actually dials. It is enforced here instead, where the
         * destination is known. See `res/xml/network_security_config.xml`, which
         * permits cleartext at the platform level precisely because this check
         * exists.
         */
        CLEARTEXT_REQUIRES_HTTPS,
    }

    /**
     * Name suffixes that can only resolve inside a private network, and so may
     * be spoken to in cleartext. Mirrors the allowlist the network security
     * config can express, plus `ts.net` for MagicDNS names.
     */
    private val cleartextNameSuffixes = listOf(
        MAGIC_DNS_SUFFIX,
        ".local",
        ".internal",
        ".lan",
        ".home.arpa",
    )

    /**
     * Classifies [input] for the transport in [scheme].
     *
     * Accepts, in the shape the user is likely to paste:
     *  * `100.101.102.103`
     *  * `100.101.102.103:3080`
     *  * `phone.tailnet-name.ts.net`
     *  * `phone.tailnet-name.ts.net:3080`
     *  * `[fd7a:115c:a1e0::1]:3080` — brackets are required for IPv6
     *  * a bare name (`phone`) — allowed with [Warning.BARE_SHORT_NAME]
     *
     * [scheme] is part of the verdict, not just the URL: plain `http` is only
     * legal for destinations inside the tailnet. See
     * [Reason.CLEARTEXT_REQUIRES_HTTPS] for why that rule lives here rather
     * than in the platform's network security config.
     */
    fun classify(input: String, defaultPort: Int, scheme: String): Verdict {
        val verdict = classifyAddress(input, defaultPort)
        if (verdict is Verdict.Rejected) return verdict
        // Anything but plain http is a transport the platform already protects:
        // https is authenticated, and the embedded node's own dials never leave
        // the tunnel. Only cleartext needs the destination check.
        if (!scheme.equals("http", ignoreCase = true)) return verdict
        if (cleartextPermitted(verdict.address.host)) return verdict
        return Verdict.Rejected(verdict.address, Reason.CLEARTEXT_REQUIRES_HTTPS)
    }

    /**
     * Whether `http` may be used for [host].
     *
     * An IP literal only reaches this point if the tailnet-range check in
     * [classifyAddress] accepted it, so any literal here is already a tailnet
     * address by construction. Names are matched on suffix, because resolving
     * them to judge the answer would (a) block on DNS inside a validator and
     * (b) be wrong for the embedded node, whose tsnet netstack resolves names
     * that the system resolver cannot see.
     */
    private fun cleartextPermitted(host: String): Boolean {
        if (parseLiteralOrNull(host) != null) return true
        // classifyAddress already stripped the root label, so this trim is only
        // a guard for any future caller that arrives with a raw host.
        val name = host.trimEnd('.').lowercase()
        if (name == "localhost") return true
        return cleartextNameSuffixes.any { name.endsWith(it) }
    }

    private fun classifyAddress(input: String, defaultPort: Int): Verdict {
        val raw = input.trim()
        if (raw.isEmpty()) {
            return Verdict.Rejected(TargetAddress.empty(), Reason.EMPTY)
        }
        if (raw.contains("://")) {
            // The form has separate scheme and host fields; a pasted URL in the
            // host box is a user error worth naming precisely.
            return Verdict.Rejected(TargetAddress.empty(), Reason.SCHEME_PRESENT)
        }
        if (raw.any { it.isWhitespace() }) {
            return Verdict.Rejected(TargetAddress.empty(), Reason.MALFORMED)
        }

        // Reject unbracketed IPv6 before anything tries to parse it. See the
        // note on Reason.IPV6_NEEDS_BRACKETS: an IPv6 literal with more than one
        // colon is ambiguous with `host:port`, and resolving it as an address is
        // right about half the time.
        if (!raw.startsWith('[') && raw.count { it == ':' } > 1) {
            return Verdict.Rejected(TargetAddress.empty(), Reason.IPV6_NEEDS_BRACKETS)
        }

        val split = splitHostPort(raw)
            ?: return Verdict.Rejected(TargetAddress.empty(), Reason.MALFORMED)

        // Brackets come off first, then the root label. A trailing dot is the
        // fully-qualified spelling of the same name, and MagicDNS reports names
        // that way. Normalising exactly once, here, is deliberate: the warning
        // classifier below and the cleartext check both inspect the host shape,
        // and while they disagreed about the trailing dot the same destination
        // came back with two different verdicts (a real MagicDNS name was
        // labelled NON_MAGIC_DNS_NAME, and a bare "." survived as a host).
        val host = split.host.trim('[', ']').removeSuffix(".")
        if (host.isEmpty()) {
            return Verdict.Rejected(TargetAddress.empty(), Reason.MALFORMED)
        }
        val explicitPort = split.port
        val port = explicitPort ?: defaultPort
        if (port !in 1..65535) {
            return Verdict.Rejected(TargetAddress.empty(), Reason.MALFORMED)
        }

        val address = TargetAddress(host = host, port = port)
        val defaultedPort = explicitPort == null

        val literal = parseLiteralOrNull(host)
        if (literal != null) {
            return when {
                literal.isLoopbackAddress ->
                    Verdict.Rejected(address, Reason.LOOPBACK)
                tailnetV4.contains(literal) || tailnetV6.contains(literal) ->
                    if (defaultedPort) {
                        Verdict.AllowedWithWarning(address, Warning.DEFAULTED_PORT)
                    } else {
                        Verdict.Allowed(address)
                    }
                else ->
                    Verdict.Rejected(address, Reason.PUBLIC_ADDRESS)
            }
        }

        // Not an IP literal: decide by name shape.
        val isFqdn = host.contains('.')
        val warning = when {
            !isFqdn -> Warning.BARE_SHORT_NAME
            host.endsWith(MAGIC_DNS_SUFFIX, ignoreCase = true) && !defaultedPort -> null
            host.endsWith(MAGIC_DNS_SUFFIX, ignoreCase = true) -> Warning.DEFAULTED_PORT
            defaultedPort -> Warning.DEFAULTED_PORT
            else -> Warning.NON_MAGIC_DNS_NAME
        }
        return when (warning) {
            null -> Verdict.Allowed(address)
            // A bare name is a warning, not a rejection: some tailnets do push
            // the search domain and it works. The user is told what to do if not.
            else -> Verdict.AllowedWithWarning(address, warning)
        }
    }

    /** Renders the canonical `scheme://host:port` form for display. */
    fun format(address: TargetAddress, scheme: String): String =
        "$scheme://${address.authority()}"

    /** Utility used by the direct fallback provider only. */
    fun resolveSystem(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList()
    } catch (_: UnknownHostException) {
        emptyList()
    }

    private data class HostPort(val host: String, val port: Int?)

    private fun splitHostPort(value: String): HostPort? {
        // Bracketed IPv6: [::1]:8080 or [::1]
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            return when {
                rest.isEmpty() -> HostPort(host, null)
                rest.startsWith(':') -> HostPort(host, rest.drop(1).toIntOrNull() ?: return null)
                else -> null
            }
        }
        val colons = value.count { it == ':' }
        return when (colons) {
            0 -> HostPort(value, null)
            // Unbracketed IPv6 literal without a port.
            1 -> {
                val host = value.substringBefore(':')
                val portText = value.substringAfter(':')
                if (host.isEmpty() || portText.isEmpty()) return null
                HostPort(host, portText.toIntOrNull() ?: return null)
            }
            else -> HostPort(value, null)
        }
    }

    private fun parseLiteralOrNull(host: String): InetAddress? {
        // Only attempt literal parsing when the shape is unambiguous, so that a
        // DNS lookup is never triggered from a validator.
        val looksV4 = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
        val looksV6 = host.contains(':')
        if (!looksV4 && !looksV6) return null
        return try {
            InetAddress.getByName(host).takeIf { it.hostAddress != null }
        } catch (_: UnknownHostException) {
            null
        }
    }

    /** Minimal CIDR containment, IPv4 and IPv6, no allocation per call. */
    private class Cidr(network: String, prefixLength: Int) {
        private val bytes: ByteArray = InetAddress.getByName(network).address
        private val prefix: Int = prefixLength

        init {
            require(prefix in 0..(bytes.size * 8)) { "bad prefix $prefix for ${bytes.size * 8} bits" }
        }

        fun contains(address: InetAddress): Boolean {
            val candidate = address.address
            if (candidate.size != bytes.size) return false
            var bitsLeft = prefix
            for (i in candidate.indices) {
                if (bitsLeft <= 0) return true
                val take = minOf(8, bitsLeft)
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((candidate[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
                bitsLeft -= take
            }
            return true
        }
    }
}

/** A validated destination inside the tailnet. */
data class TargetAddress(val host: String, val port: Int) {
    fun authority(): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /** tsnet's Dial wants `host:port`; it does its own name resolution. */
    fun dialString(): String = authority()

    /**
     * The URL of one HTTP request to this address.
     *
     * One function, because there used to be more than one. The connection test
     * built this string inline, `AppConfig.targetUrl` built a second version of
     * it from the raw field, and the loopback proxy has to build a third — and
     * three constructions of "where the target is" is how a health check and the
     * page a user is shown end up disagreeing. Everything now goes through here.
     *
     * An empty or blank [path] means `/`, as it does everywhere else in this app;
     * anything else is appended verbatim, exactly as the connection test has
     * always appended it.
     */
    fun url(scheme: String, path: String): String =
        "$scheme://${authority()}${path.ifBlank { "/" }}"

    companion object {
        fun empty() = TargetAddress("", 0)
    }
}
