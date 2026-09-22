package io.github.zero6689.tailnetbyok.domain

import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.net.ProviderId
import java.net.URLDecoder

/**
 * A configuration link: `dshbyok://setup?target=…&mode=…`.
 *
 * # Why this exists
 *
 * Typing a MagicDNS name, a port, a mode and an update source on a phone keyboard
 * is four chances to get it wrong, and it is the first thing anyone has to do
 * after installing. The idiomatic answer in self-hosted software is not to bake an
 * address into the app — that would publish the maintainer's topology and be wrong
 * for every other deployment — but to let the *server* hand the client its
 * configuration. This is that hand-off, in the smallest form that works: a URI the
 * server can print, put in a QR code, or paste into a chat.
 *
 * # The boundary this file draws, and why it is drawn here
 *
 * A configuration link is **untrusted input from outside the app**: anyone can
 * write one, print it, and put it in front of a user. So:
 *
 *  * **A link may carry configuration and nothing else.** If any field name looks
 *    like a credential (`key`, `token`, `secret`, `password`, …) the *whole link*
 *    is refused rather than the field ignored. A link that carries a credential is
 *    either a mistake or an attempt to make this app transmit a secret somewhere,
 *    and both deserve a refusal the user can see. Credentials are entered by hand
 *    or through the control plane's own login; never through a link, never through
 *    a QR code, never through anything that can be photographed off a screen.
 *  * **Nothing here is applied by parsing.** [SetupLink.applyTo] produces a merged
 *    configuration for the UI to *show and confirm*; applying it is a separate,
 *    explicit action. A link that silently re-pointed the app at another host is
 *    the attack (the user then types their auth key into it), and the defence is
 *    that the user sees where the link points before anything changes.
 *  * **The address policy still applies afterwards.** This parser checks shape
 *    only — length, characters, port range. Whether the host is reachable at all is
 *    `TailnetAddressPolicy`'s question, asked on the merged configuration, and a
 *    target it rejects is one the UI will refuse to apply.
 */
data class SetupLink(
    val host: String? = null,
    val port: Int? = null,
    val scheme: String? = null,
    val path: String? = null,
    val provider: ProviderId? = null,
    val updateUrl: String? = null,
    val controlUrl: String? = null,
    val nodeHostname: String? = null,
) {

    /** True when the link names nothing at all — an empty `dshbyok://setup`. */
    val isEmpty: Boolean
        get() = host == null && port == null && scheme == null && path == null &&
            provider == null && updateUrl == null && controlUrl == null && nodeHostname == null

    /**
     * The configuration this link describes, on top of [base].
     *
     * Fields the link does not name are left exactly as they were: a link that only
     * changes the connection method must not also wipe the target, and a link that
     * only sets a target must not touch a credential.
     */
    fun applyTo(base: AppConfig): AppConfig = base.copy(
        hostInput = host ?: base.hostInput,
        port = port ?: base.port,
        scheme = scheme ?: base.scheme,
        path = path ?: base.path,
        provider = provider ?: base.provider,
        updateUrl = updateUrl ?: base.updateUrl,
        controlUrl = controlUrl ?: base.controlUrl,
        nodeHostname = nodeHostname ?: base.nodeHostname,
    )
}

/** Why a string was not accepted as a configuration link. */
enum class SetupLinkRejection {
    /** Not a `dshbyok://setup` URI at all. */
    NOT_A_SETUP_LINK,

    /** It carries a field whose name looks like a credential. Refused whole. */
    CREDENTIAL_FIELD,

    /** A recognised field with a value that cannot be used. */
    MALFORMED_VALUE,

    /** It names nothing — there is no configuration in it. */
    EMPTY_LINK,
}

sealed interface SetupLinkParse {
    data class Parsed(val link: SetupLink) : SetupLinkParse
    data class Rejected(val reason: SetupLinkRejection) : SetupLinkParse
}

/**
 * Parses configuration links.
 *
 * Deliberately `java.net`-only, no `android.net.Uri`: the whole security-relevant
 * decision table is then testable on the JVM, which is where it is tested.
 */
object SetupLinkParser {

    const val SCHEME = "dshbyok"
    const val HOST = "setup"

    /** The link a deployment prints. Kept here so the format has one definition. */
    fun format(target: String, mode: String? = null, updateUrl: String? = null): String =
        buildString {
            append("$SCHEME://$HOST?target=").append(encode(target))
            mode?.let { append("&mode=").append(encode(it)) }
            updateUrl?.let { append("&update=").append(encode(it)) }
        }

    /**
     * The link that reproduces an existing configuration on another device.
     *
     * This is the *writer* side of the format, and it is deliberately the same
     * door as the reader: what it produces is parsed again before it is returned,
     * and a link that does not come back as the configuration it was built from
     * is refused rather than printed. Printing a code that scans into a
     * *different* target is the one failure mode a "share my setup" feature must
     * not have, and it is exactly the failure mode that a hand-written encoder
     * develops quietly — a field renamed on one side, a port that does not
     * survive a round trip.
     *
     * # What it carries, and what it deliberately leaves out
     *
     * It carries how to *reach the deployment*: the host, port, scheme, path, the
     * transport, the update source, and a self-hosted control plane. It leaves out
     * the two things that belong to the device rather than to the server —
     * `nodeHostname` (two phones sharing one node name fight over it in the
     * tailnet) and `ephemeral` (there is no field for it at all) — and it never
     * carries a credential, because [AppConfig] does not contain one. The auth key
     * lives in the Keystore, is not part of this object, and cannot be put in a
     * link by accident: see the boundary at the top of this file.
     *
     * Returns null when there is no target to describe — an empty host is not a
     * configuration, and a code for it would only waste a scan.
     */
    fun format(config: AppConfig): String? {
        val host = config.hostInput.trim()
        if (host.isEmpty()) return null

        val query = buildList {
            add("target=" + encode(host))
            add("port=" + config.port)
            add("scheme=" + encode(config.scheme))
            add("path=" + encode(config.path))
            add("mode=" + encode(config.provider.storageKey))
            config.updateUrl.trim().takeIf { it.isNotEmpty() }
                ?.let { add("update=" + encode(it)) }
            config.controlUrl.trim().takeIf { it.isNotEmpty() && !AppConfig.isHostedControlPlane(it) }
                ?.let { add("control=" + encode(it)) }
        }.joinToString("&")

        val link = "$SCHEME://$HOST?$query"
        return if (reproduces(link, config)) link else null
    }

    /**
     * Whether [link] parses back into [config].
     *
     * Compared field by field against the configuration a receiver would end up
     * with — the link applied on top of an empty configuration, which is what a
     * second device is. Fields the link does not carry (`nodeHostname`,
     * `ephemeral`) are left at their defaults and deliberately not compared.
     */
    private fun reproduces(link: String, config: AppConfig): Boolean {
        val parsed = parse(link)
        if (parsed !is SetupLinkParse.Parsed) return false
        val applied = parsed.link.applyTo(AppConfig())
        return applied.hostInput == config.hostInput.trim() &&
            applied.port == config.port &&
            applied.scheme == config.scheme &&
            applied.path == config.path.ifEmpty { AppConfig.DEFAULT_PATH } &&
            applied.provider == config.provider &&
            applied.updateUrl == config.updateUrl.trim() &&
            applied.controlUrl == config.controlUrl.trim().takeIf { !AppConfig.isHostedControlPlane(it) }.orEmpty()
    }

    /**
     * The configuration a *build* may pre-fill, from `-PdefaultTarget` and friends.
     *
     * Run through the same parser a link goes through, which buys two things: a
     * malformed build property degrades to "no default" instead of shipping an app
     * whose first screen is a broken address, and a value that tries to smuggle an
     * extra field (`-PdefaultTarget="host&authkey=…"`) lands as a malformed target
     * rather than as a second field. The build property is written by whoever
     * builds the app, not by a stranger — but the same door should not be easier to
     * walk through than the public one.
     *
     * Returns a default (empty) [AppConfig] when nothing was configured, which is
     * what the public build does: this app ships with no server of its own.
     */
    fun parseDefaults(target: String, mode: String, updateUrl: String): AppConfig {
        val query = buildList {
            if (target.isNotBlank()) add("target=" + encode(target))
            if (mode.isNotBlank()) add("mode=" + encode(mode))
            if (updateUrl.isNotBlank()) add("update=" + encode(updateUrl))
        }.joinToString("&")
        if (query.isEmpty()) return AppConfig()
        return when (val result = parse("$SCHEME://$HOST?$query")) {
            is SetupLinkParse.Parsed -> result.link.applyTo(AppConfig())
            is SetupLinkParse.Rejected -> AppConfig()
        }
    }

    /**
     * Field names that make a link untrustworthy.
     *
     * Broader than the names this app happens to use, on purpose: a link is written
     * by somebody else, and what matters is whether a *reader* could mistake the
     * field for a credential — including a future field this app has not invented
     * yet.
     */
    private val CREDENTIAL_NAME = Regex(
        "(?i)(auth|key|pass|pwd|secret|token|credential|bearer|otp|pin)",
    )

    private const val MAX_TARGET = 255
    private const val MAX_URL = 2048
    private const val MAX_PATH = 512
    private const val MAX_HOSTNAME = 255

    private val HOST_SHAPE = Regex("^[A-Za-z0-9._-]+$")

    fun parse(raw: String): SetupLinkParse {
        val trimmed = raw.trim()
        val prefix = "$SCHEME://"
        if (trimmed.length <= prefix.length || !trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            return SetupLinkParse.Rejected(SetupLinkRejection.NOT_A_SETUP_LINK)
        }

        // Authority is everything up to the query or fragment; anything else there
        // is a different link that merely starts with our scheme.
        val afterScheme = trimmed.substring(prefix.length)
        val authority = afterScheme.takeWhile { it != '?' && it != '#' && it != '/' }
        if (!authority.equals(HOST, ignoreCase = true)) {
            return SetupLinkParse.Rejected(SetupLinkRejection.NOT_A_SETUP_LINK)
        }

        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return SetupLinkParse.Rejected(SetupLinkRejection.EMPTY_LINK)

        var host: String? = null
        var port: Int? = null
        var scheme: String? = null
        var path: String? = null
        var provider: ProviderId? = null
        var updateUrl: String? = null
        var controlUrl: String? = null
        var nodeHostname: String? = null

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('=')).trim()
            val value = decode(pair.substringAfter('=', "")).trim()
            if (name.isEmpty()) continue

            // Checked before the field is looked up: an unknown credential-shaped
            // name must fail the link, not be quietly skipped.
            if (CREDENTIAL_NAME.containsMatchIn(name)) {
                return SetupLinkParse.Rejected(SetupLinkRejection.CREDENTIAL_FIELD)
            }
            if (value.isEmpty()) continue

            when (name.lowercase()) {
                "target", "host" -> {
                    if (value.length > MAX_TARGET) return malformed()
                    val parsed = parseTarget(value) ?: return malformed()
                    host = parsed.host
                    port = parsed.port ?: port
                    scheme = parsed.scheme ?: scheme
                    path = parsed.path?.takeIf { it.isNotEmpty() } ?: path
                }
                "port" -> {
                    val parsed = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return malformed()
                    port = parsed
                }
                "scheme" -> {
                    val parsed = value.lowercase().takeIf { it == "http" || it == "https" } ?: return malformed()
                    scheme = parsed
                }
                "path" -> {
                    if (value.length > MAX_PATH) return malformed()
                    path = if (value.startsWith('/')) value else "/$value"
                }
                "mode", "provider" -> {
                    provider = when (value.lowercase()) {
                        "embedded", "embedded_tsnet", "tsnet", "node" -> ProviderId.EMBEDDED_TSNET
                        "system", "system_network", "network", "vpn" -> ProviderId.SYSTEM_NETWORK
                        else -> return malformed()
                    }
                }
                "update", "update_url" -> {
                    if (value.length > MAX_URL) return malformed()
                    updateUrl = value
                }
                "control", "control_url" -> {
                    if (value.length > MAX_URL) return malformed()
                    controlUrl = value
                }
                "node", "node_hostname", "hostname" -> {
                    if (value.length > MAX_HOSTNAME || !HOST_SHAPE.matches(value)) return malformed()
                    nodeHostname = value
                }
                // Unknown, non-credential fields are ignored rather than refused, so
                // a newer link format stays usable on an older build.
                else -> Unit
            }
        }

        val link = SetupLink(host, port, scheme, path, provider, updateUrl, controlUrl, nodeHostname)
        return if (link.isEmpty) {
            SetupLinkParse.Rejected(SetupLinkRejection.EMPTY_LINK)
        } else {
            SetupLinkParse.Parsed(link)
        }
    }

    /** A host, `host:port`, or an absolute `http(s)://host:port/path`. */
    private fun parseTarget(value: String): TargetParts? {
        if (value.contains("//")) {
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            val scheme = value.substringBefore("://").lowercase()
            val rest = value.substringAfter("://")
            val authority = rest.takeWhile { it != '/' }
            if (authority.isEmpty() || authority.contains('@') || authority.contains(' ')) return null
            val host = authority.substringBefore(':')
            if (!isUsableHost(host)) return null
            val port = authority.substringAfter(':', "").takeIf { it.isNotEmpty() }
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
            if (authority.contains(':') && port == null) return null
            val path = rest.substringAfter('/', "").let { if (it.isEmpty()) null else "/$it" }
            return TargetParts(host, port, scheme, path)
        }

        val host = value.substringBefore(':')
        if (!isUsableHost(host)) return null
        val port = value.substringAfter(':', "").takeIf { it.isNotEmpty() }
            ?.toIntOrNull()?.takeIf { it in 1..65535 }
        if (value.contains(':') && port == null) return null
        return TargetParts(host, port, null, null)
    }

    /**
     * Shape only: a host is ASCII letters, digits, dots, dashes and underscores,
     * at most 255 characters, and not something whose "host part" is empty.
     *
     * Whether it is a *tailnet* address is `TailnetAddressPolicy`'s judgement, made
     * on the merged configuration, and it is the one that decides whether the UI
     * will apply the link at all.
     */
    private fun isUsableHost(host: String): Boolean =
        host.isNotEmpty() && host.length <= MAX_TARGET && HOST_SHAPE.matches(host)

    private data class TargetParts(
        val host: String,
        val port: Int?,
        val scheme: String?,
        val path: String?,
    )

    private fun malformed(): SetupLinkParse =
        SetupLinkParse.Rejected(SetupLinkRejection.MALFORMED_VALUE)

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
