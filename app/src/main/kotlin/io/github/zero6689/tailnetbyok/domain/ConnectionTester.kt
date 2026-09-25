package io.github.zero6689.tailnetbyok.domain

import androidx.annotation.StringRes
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.Redact
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.HttpRequestSpec
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderStatus
import io.github.zero6689.tailnetbyok.net.TailnetConfig
import io.github.zero6689.tailnetbyok.net.TailnetCredentials

/**
 * The "test connection" button, as a sequence of named checks.
 *
 * A single boolean would be useless. When a connection fails, the user needs to
 * know *which* of five independent things broke, because the fixes are different:
 *
 *   1. the address they typed is not a tailnet address at all  → fix the field
 *   2. this build has no embedded node                          → rebuild, or pick the other provider
 *   3. no auth key is stored                                    → paste one
 *   4. the node came up but the host refused                    → the service is down, or the port is wrong
 *   5. the port answered but HTTP did not                       → wrong path, or it is not an HTTP service
 *
 * So the test reports per-step results, each with a duration, and stops at the
 * first hard failure while still marking the remaining steps as skipped. That is
 * the difference between "connection failed" and a screen the user can act on.
 *
 * The whole sequence is bounded: each step has its own timeout and the provider
 * applies them, so a black-holed tailnet cannot hang the UI.
 *
 * # Text
 *
 * Nothing here builds a sentence. Every step title and detail is a resource id
 * (or a [TextRef] for the interpolated ones), resolved by the Compose layer — so
 * this file stays free of Android and still translates. See `core.text.TextRef`.
 */
class ConnectionTester {

    // `@param:` for the reason spelled out in `core.text.TextRef`: explicit beats a
    // module-wide -Xannotation-default-target, and this annotation is about the
    // argument each step is constructed with.
    enum class Step(@param:StringRes val labelRes: Int) {
        VALIDATE_ADDRESS(R.string.step_validate_address),
        RESOLVE_PROVIDER(R.string.step_resolve_provider),
        CHECK_CREDENTIAL(R.string.step_check_credential),
        START_NODE(R.string.step_start_node),
        PROBE_TCP(R.string.step_probe_tcp),
        PROBE_HTTP(R.string.step_probe_http),
    }

    enum class Outcome { PASSED, WARNED, FAILED, SKIPPED }

    /**
     * How far [run] should go.
     *
     * [FULL] is the connection test: all six steps, all the way to an HTTP
     * request.
     *
     * [BRING_UP] stops the moment the provider is up. It exists for "open the
     * DSH UI": the embedded node's loopback proxy binds its port whether or not
     * the node is running, and answers every request with a 502 until it is — so
     * the node has to come up first, and the four steps that get it there
     * (address, provider, credential, node) must not be written a second time.
     * The TCP and HTTP steps are not reached in this mode; they were never asked
     * for, which is why the resulting [Report] is a success.
     */
    enum class Scope { FULL, BRING_UP }

    data class StepResult(
        val step: Step,
        val outcome: Outcome,
        val detail: TextRef,
        val elapsedMs: Long? = null,
    ) {
        constructor(
            step: Step,
            outcome: Outcome,
            @StringRes detailRes: Int,
            elapsedMs: Long? = null,
        ) : this(step, outcome, TextRef.of(detailRes), elapsedMs)

        constructor(
            step: Step,
            outcome: Outcome,
            @StringRes detailRes: Int,
            vararg detailArgs: Any,
        ) : this(step, outcome, TextRef.of(detailRes, *detailArgs), null)
    }

    data class Report(
        val steps: List<StepResult>,
        val succeeded: Boolean,
        val targetUrl: String?,
        val resolvedAddress: String?,
    ) {
        val firstFailure: StepResult?
            get() = steps.firstOrNull { it.outcome == Outcome.FAILED }

        val warnings: List<StepResult>
            get() = steps.filter { it.outcome == Outcome.WARNED }

        /**
         * A one-line verdict for the status row.
         *
         * The warning count is a *quantity* string, so it comes from `R.plurals`
         * (a `<plurals>` resource is not in `R.string` at all - referring to it
         * there is a compile error, which is how this annotation-free version
         * earns its keep). Everything else is a plain string id.
         */
        fun summary(): Summary = when {
            succeeded && warnings.isEmpty() -> Summary(R.string.test_summary_connected)
            succeeded -> Summary(
                R.plurals.test_summary_connected_with_warnings,
                listOf(warnings.size),
                isQuantity = true,
            )
            else -> Summary(
                R.string.test_summary_failed,
                detail = firstFailure?.detail,
            )
        }
    }

    /** The summary line, still unresolved: the UI picks the language. */
    data class Summary(
        val res: Int,
        val args: List<Any> = emptyList(),
        /**
         * True when [res] is a `<plurals>` id (so the UI must call
         * `pluralStringResource`). There is no single `@StringRes`-style
         * annotation that covers both tables, so the distinction travels as a
         * flag instead of a lie in an annotation.
         */
        val isQuantity: Boolean = false,
        val detail: TextRef? = null,
    )

    /**
     * Runs the sequence, invoking [onStep] as each step settles so the UI can
     * show progress instead of freezing.
     *
     * [credentials] is read lazily and only when the provider chosen actually
     * needs it — the system-network provider has no use for an auth key, and
     * decrypting a secret that will not be used is a needless exposure.
     */
    suspend fun run(
        config: AppConfig,
        nodeStateDir: String,
        provider: ConnectivityProvider?,
        readCredentials: suspend () -> CredentialLookup,
        onStep: (StepResult) -> Unit = {},
        scope: Scope = Scope.FULL,
    ): Report {
        val results = mutableListOf<StepResult>()
        var resolvedAddress: String? = null
        var targetUrl: String? = null

        fun record(result: StepResult) {
            results += result
            onStep(result)
        }

        fun skipRemaining(from: Step, @StringRes reasonRes: Int) {
            val rest = Step.entries.drop(Step.entries.indexOf(from))
            for (step in rest) {
                if (results.none { it.step == step }) {
                    record(StepResult(step, Outcome.SKIPPED, reasonRes))
                }
            }
        }

        // -- 1. Address ------------------------------------------------------
        val verdict = TailnetAddressPolicy.classify(config.hostInput, config.port, config.scheme)
        val address = when (verdict) {
            is TailnetAddressPolicy.Verdict.Rejected -> {
                record(
                    StepResult(
                        Step.VALIDATE_ADDRESS,
                        Outcome.FAILED,
                        AddressMessages.reason(verdict.reason),
                    ),
                )
                skipRemaining(Step.RESOLVE_PROVIDER, R.string.step_address_rejected)
                return Report(results, succeeded = false, targetUrl = null, resolvedAddress = null)
            }
            is TailnetAddressPolicy.Verdict.AllowedWithWarning -> {
                val warningRes = AddressMessages.warning(verdict.warning)
                record(
                    StepResult(
                        Step.VALIDATE_ADDRESS,
                        Outcome.WARNED,
                        if (verdict.warning == TailnetAddressPolicy.Warning.DEFAULTED_PORT) {
                            TextRef.of(warningRes, config.port)
                        } else {
                            TextRef.of(warningRes)
                        },
                    ),
                )
                verdict.address
            }
            is TailnetAddressPolicy.Verdict.Allowed -> {
                record(
                    StepResult(Step.VALIDATE_ADDRESS, Outcome.PASSED, R.string.step_address_ok),
                )
                verdict.address
            }
        }
        targetUrl = address.url(config.scheme, config.path)

        // -- 2. Provider -----------------------------------------------------
        if (provider == null) {
            record(
                StepResult(
                    Step.RESOLVE_PROVIDER,
                    Outcome.FAILED,
                    R.string.step_no_provider,
                ),
            )
            skipRemaining(Step.CHECK_CREDENTIAL, R.string.step_skip_no_provider)
            return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
        }
        record(
            StepResult(
                Step.RESOLVE_PROVIDER,
                Outcome.PASSED,
                R.string.step_using_provider,
                provider.id.labelRes,
            ),
        )

        // -- 3. Credential ---------------------------------------------------
        var credentials: TailnetCredentials = TailnetCredentials.EMPTY
        if (provider.id == ProviderId.EMBEDDED_TSNET) {
            when (val lookup = readCredentials()) {
                is CredentialLookup.Present -> {
                    credentials = lookup.credentials
                    record(
                        StepResult(
                            Step.CHECK_CREDENTIAL,
                            Outcome.PASSED,
                            R.string.step_key_present,
                            Redact.fingerprint(lookup.credentials.reveal()),
                        ),
                    )
                }
                CredentialLookup.Absent -> {
                    record(
                        StepResult(
                            Step.CHECK_CREDENTIAL,
                            Outcome.FAILED,
                            R.string.step_key_absent,
                        ),
                    )
                    skipRemaining(Step.START_NODE, R.string.step_skip_no_credential)
                    return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
                }
                is CredentialLookup.Unreadable -> {
                    record(
                        StepResult(Step.CHECK_CREDENTIAL, Outcome.FAILED, lookup.reason),
                    )
                    skipRemaining(Step.START_NODE, R.string.step_skip_credential_unreadable)
                    return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
                }
            }
        } else {
            record(
                StepResult(
                    Step.CHECK_CREDENTIAL,
                    Outcome.SKIPPED,
                    R.string.step_key_not_used,
                ),
            )
        }

        // -- 4. Node ---------------------------------------------------------
        val nodeConfig = TailnetConfig(
            hostname = config.nodeHostname,
            controlUrl = config.controlUrlForNode(),
            stateDir = nodeStateDir,
            ephemeral = config.ephemeral,
        )
        val nodeStarted = System.nanoTime()
        val status = try {
            provider.start(nodeConfig, credentials, forceLogin = false)
        } catch (e: Exception) {
            SafeLog.eScrubbed(
                TAG,
                "provider failed to start",
                e,
                credentials.reveal().takeIf { it.isNotEmpty() },
            )
            ProviderStatus.Error(
                provider.id,
                e.message ?: "provider failed to start",
                messageRes = TextRef.of(R.string.step_provider_start_failed),
            )
        }
        val nodeElapsed = (System.nanoTime() - nodeStarted) / 1_000_000

        when (status) {
            is ProviderStatus.Running -> {
                val detail = when {
                    status.ipv4.isEmpty() -> TextRef.of(R.string.step_node_up)
                    status.tailnetName.isNullOrEmpty() ->
                        TextRef.of(R.string.step_node_up_at, status.ipv4)
                    else ->
                        TextRef.of(R.string.step_node_up_in, status.ipv4, status.tailnetName)
                }
                record(StepResult(Step.START_NODE, Outcome.PASSED, detail, nodeElapsed))
            }
            is ProviderStatus.Error -> {
                // The library's own message is passed through verbatim inside a
                // translated frame; the login hint is appended when we have one.
                val detail = if (status.loginUrl != null) {
                    TextRef.of(R.string.step_node_needs_login, status.message, status.loginUrl)
                } else {
                    status.messageRes ?: TextRef.of(R.string.net_failed_with_detail, status.message)
                }
                record(StepResult(Step.START_NODE, Outcome.FAILED, detail, nodeElapsed))
                skipRemaining(Step.PROBE_TCP, R.string.step_skip_node_not_up)
                return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
            }
            is ProviderStatus.Unavailable -> {
                record(StepResult(Step.START_NODE, Outcome.FAILED, status.reason, nodeElapsed))
                skipRemaining(Step.PROBE_TCP, R.string.step_skip_provider_unavailable)
                return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
            }
            else -> {
                record(
                    StepResult(
                        Step.START_NODE,
                        Outcome.FAILED,
                        R.string.step_provider_not_running,
                        status::class.simpleName ?: "unknown",
                    ),
                )
                skipRemaining(Step.PROBE_TCP, R.string.step_skip_node_not_up)
                return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
            }
        }

        // The node is up. Everything past this point interrogates the *service*
        // rather than the transport, which is the connection test's question and
        // not the web screen's — see [Scope.BRING_UP].
        if (scope == Scope.BRING_UP) {
            return Report(results, succeeded = true, targetUrl = targetUrl, resolvedAddress = null)
        }

        // -- 5. TCP ----------------------------------------------------------
        val probe = provider.probe(address)
        resolvedAddress = probe.resolvedAddress
        if (!probe.ok) {
            record(
                StepResult(
                    Step.PROBE_TCP,
                    Outcome.FAILED,
                    probe.error ?: TextRef.of(R.string.step_tcp_failed),
                    probe.elapsedMs,
                ),
            )
            skipRemaining(Step.PROBE_HTTP, R.string.step_skip_no_tcp)
            return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = null)
        }
        record(
            StepResult(
                Step.PROBE_TCP,
                Outcome.PASSED,
                if (probe.resolvedAddress != null) {
                    TextRef.of(R.string.step_tcp_connected_resolved, probe.elapsedMs, probe.resolvedAddress)
                } else {
                    TextRef.of(R.string.step_tcp_connected, probe.elapsedMs)
                },
                probe.elapsedMs,
            ),
        )

        // -- 6. HTTP ---------------------------------------------------------
        val fetch = provider.fetch(
            HttpRequestSpec(
                url = targetUrl,
                method = "GET",
                timeoutMs = ConnectivityProvider.DEFAULT_FETCH_TIMEOUT_MS,
                maxBodyBytes = 8 * 1024,
            ),
        )
        if (fetch.error != null) {
            record(
                StepResult(Step.PROBE_HTTP, Outcome.FAILED, fetch.error, fetch.elapsedMs),
            )
            return Report(results, succeeded = false, targetUrl = targetUrl, resolvedAddress = resolvedAddress)
        }

        // Any HTTP status is a pass: the point of this step is that the service
        // answered. A 401 is proof of life, and reporting it as failure would be
        // wrong — the app is not authenticated to the target and should not be.
        val outcome = if (fetch.statusCode in 200..399) Outcome.PASSED else Outcome.WARNED
        record(
            StepResult(
                Step.PROBE_HTTP,
                outcome,
                if (fetch.truncated) {
                    TextRef.of(R.string.step_http_truncated, fetch.statusCode, fetch.elapsedMs)
                } else {
                    TextRef.of(R.string.step_http_result, fetch.statusCode, fetch.elapsedMs)
                },
                fetch.elapsedMs,
            ),
        )

        val succeeded = results.none { it.outcome == Outcome.FAILED }
        return Report(results, succeeded, targetUrl, resolvedAddress)
    }

    /** What the tester was able to read from the credential store. */
    sealed interface CredentialLookup {
        data class Present(val credentials: TailnetCredentials) : CredentialLookup
        data object Absent : CredentialLookup

        /** [reason] is already a user-facing message id; see `ConfigRepository.KeyRead`. */
        data class Unreadable(val reason: TextRef) : CredentialLookup
    }

    private companion object {
        const val TAG = "ConnTest"
    }
}
