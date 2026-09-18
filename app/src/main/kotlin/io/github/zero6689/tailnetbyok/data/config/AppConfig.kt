package io.github.zero6689.tailnetbyok.data.config

import io.github.zero6689.tailnetbyok.net.ProviderId

/**
 * Everything the user configured, minus the secret.
 *
 * Splitting the secret out is not decoration: this object is what the settings
 * screen holds in state, what a "share my setup" export would serialise, and
 * what ends up in a bug report. Keeping the auth key out of it means none of
 * those paths can leak by construction, rather than by remembering to redact.
 *
 * The auth key lives in exactly two places: encrypted at rest via
 * `KeystoreSecretVault`, and in memory as `TailnetCredentials` for as long as the
 * node needs it. [hasStoredKey] is the only trace of it here, and it is a
 * boolean.
 */
data class AppConfig(
    /** Which way traffic reaches the target. */
    val provider: ProviderId = ProviderId.EMBEDDED_TSNET,

    /** `http` or `https`. */
    val scheme: String = DEFAULT_SCHEME,

    /**
     * Exactly what the user typed into the host box — not normalised.
     *
     * Normalising on write would hide their typo from them; validation happens
     * on read, through `TailnetAddressPolicy`, and its verdict is shown inline.
     */
    val hostInput: String = "",

    val port: Int = DEFAULT_PORT,

    /** Path appended when building a URL, e.g. `/` or `/health`. */
    val path: String = DEFAULT_PATH,

    /**
     * Where this app looks for a newer build of itself.
     *
     * Empty means "the target's own origin", which is the convention the DSH host
     * already serves: `<origin>/dsh.apk.version`, `/dsh.apk` and
     * `/dsh.apk.sha256`. Set it to any base URL that serves those same three
     * names — a mirror, a different port, a directory on the same host — and the
     * check follows it there. Only the base moves; the three names are fixed, by
     * `UpdateProtocol`.
     *
     * It has a default rather than being required because the common case is a
     * phone pointed at one DSH host that publishes both.
     */
    val updateUrl: String = "",

    /**
     * Control plane base URL. Empty means Tailscale's hosted control plane;
     * anything else is a self-hosted headscale.
     */
    val controlUrl: String = "",

    /** The name this device's node takes in the tailnet. */
    val nodeHostname: String = DEFAULT_NODE_HOSTNAME,

    /**
     * Whether the node deregisters itself on disconnect.
     *
     * Defaults to true because on Android below 12 a persistent userspace node
     * cannot guarantee custody of its node key, and an ephemeral node is the
     * honest choice — it leaves no stale machine entry behind when the app is
     * uninstalled. See `docs/TSNET.md`.
     */
    val ephemeral: Boolean = true,

    /** Whether a credential is present in the vault. Never the credential itself. */
    val hasStoredKey: Boolean = false,

    /** Whether the user has been told what this app does and accepted it. */
    val acknowledgedSecurityModel: Boolean = false,
) {

    val targetUrl: String
        get() {
            val cleanPath = if (path.isBlank()) "/" else path.let { if (it.startsWith('/')) it else "/$it" }
            return "$scheme://$hostInput:$port$cleanPath"
        }

    /** True when the embedded provider was chosen but no key has been stored. */
    val isIncomplete: Boolean
        get() = hostInput.isBlank() ||
            (provider == ProviderId.EMBEDDED_TSNET && !hasStoredKey)

    /**
     * The base URL an update is fetched from: the configured one, or — when the
     * user has not set one — the target's origin.
     *
     * The target's *origin*, not its `targetUrl`: `/health` or `/` are the web
     * UI's paths and have nothing to do with where `dsh.apk.version` lives. The
     * origin is the one thing both conventions agree on.
     */
    val updateBase: String
        get() = updateUrl.trim().trimEnd('/').ifEmpty { "$scheme://$hostInput:$port" }

    /** True when this configuration points at a self-hosted control plane. */
    val isSelfHostedControlPlane: Boolean
        get() = controlUrl.isNotBlank() && !isHostedControlPlane(controlUrl)

    /**
     * The control URL to hand to the embedded node.
     *
     * `tsnet` treats an empty `ControlURL` as "use Tailscale's hosted control
     * plane". Passing the hosted URL explicitly is not equivalent — it is treated
     * as a custom server — so the translation happens here, once, instead of at
     * every call site.
     */
    fun controlUrlForNode(): String =
        controlUrl.trim().takeIf { it.isNotEmpty() && !isHostedControlPlane(it) } ?: ""

    companion object {
        const val DEFAULT_SCHEME = "http"
        const val DEFAULT_PORT = 3080
        const val DEFAULT_PATH = "/"
        const val DEFAULT_NODE_HOSTNAME = "android-byok"
        const val SUPPORTED_SCHEMES = "http,https"

        /** Tailscale's hosted control plane. */
        const val HOSTED_CONTROL_URL = "https://controlplane.tailscale.com"

        fun isHostedControlPlane(url: String): Boolean {
            val normalised = url.trim().trimEnd('/').lowercase()
            return normalised.isEmpty() || normalised == HOSTED_CONTROL_URL
        }

        /** Used by the export path, to make clear what is and is not included. */
        val EXPORT_EXCLUDED_FIELDS = listOf("authKey", "ciphertext", "keystoreAlias")
    }
}
