package io.github.zero6689.tailnetbyok.core.log

/**
 * Redaction of secret-shaped substrings.
 *
 * The rule this file exists to enforce: **no secret ever reaches a log sink**.
 * It is deliberately implemented as pure functions over [String] so that it can
 * be unit-tested without an Android runtime, and so that every other layer can
 * funnel through it instead of inventing its own masking.
 *
 * It is a *second* line of defence. The first is that nothing in this codebase
 * passes a key to a log call in the first place — see [SafeLog].
 */
object Redact {

    const val MASK = "***"

    /**
     * One redaction rule: what to match, and what to put in its place.
     *
     * A regex alone is not enough, because the right replacement depends on the
     * shape. A query parameter should keep its name (`token=***`, so the log
     * still says *which* parameter carried the secret); a bare token should not
     * keep anything. Encoding that as a per-rule lambda is what makes each case
     * testable on its own.
     */
    private class Rule(val pattern: Regex, val replacement: (MatchResult) -> String)

    /**
     * Ordered. Earlier rules win, so the narrow, structural patterns run before
     * the broad "anything that looks like an opaque blob" one.
     *
     * Every rule here corresponds to a credential shape this project can
     * actually encounter, and each has a test in `RedactTest`. If you add a rule,
     * add its test: a redactor that is only mostly right is a redactor you
     * cannot rely on.
     */
    private val RULES: List<Rule> = listOf(
        // Tailscale-issued pre-auth keys and API keys. The prefix is kept so a
        // log still says what kind of credential it was.
        Rule(Regex("""\btskey-[A-Za-z0-9_-]{4,}""")) { "tskey-$MASK" },

        // Headscale pre-auth keys.
        Rule(Regex("""\bhskey-[A-Za-z0-9_-]{4,}""")) { "hskey-$MASK" },

        // OAuth client secrets, which tsnet also accepts.
        Rule(Regex("""\btsclientsecret-[A-Za-z0-9_-]{4,}""")) { "tsclientsecret-$MASK" },

        // Query-string credentials. Matches only up to the next `&`, so the rest
        // of the query survives and the log stays useful.
        Rule(Regex("""(?i)([?&](?:token|key|authkey|auth_key|secret|apikey|api_key|password)=)[^&\s]+""")) { m ->
            m.groupValues[1] + MASK
        },

        // Authorization headers: `Authorization: Bearer <token>`, `Bearer <token>`,
        // `Authorization: Basic <blob>`.
        //
        // The optional scheme word is part of the pattern on purpose: matching
        // only `\S+` after the keyword stops at the space in `Bearer <token>`,
        // which masks the word "Bearer" and leaves the actual token in the log.
        // That was this rule's original bug, caught by its test.
        Rule(
            Regex("""(?i)\b(authorization|bearer)\s*[:=]?\s*(?:(?:bearer|basic)\s+)?([A-Za-z0-9._~+/=\-]{6,})"""),
        ) { m -> m.groupValues[1] + ": " + MASK },

        // `password: …`, `SECRET=…`, `api_key=…` in any diagnostic dump.
        Rule(Regex("""(?i)\b(password|secret|api[-_]?key|auth[-_]?key)\s*[:=]\s*([^\s&,;]{4,})""")) { m ->
            m.groupValues[1] + ": " + MASK
        },

        // A lone 43-character base64url run: the shape of a DSH web session
        // token, and of any 32 random bytes encoded without padding. Broad by
        // design — a false positive costs one masked word, a false negative
        // costs a leaked credential.
        Rule(Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])""")) { MASK },
    )

    /**
     * Redacts every secret-shaped substring in [message].
     *
     * Non-secret context is preserved so the log stays useful: the point is to
     * be able to debug a failing connection without the log being a credential
     * dump.
     */
    fun scrub(message: String): String {
        var out = message
        for (rule in RULES) {
            out = rule.pattern.replace(out, rule.replacement)
        }
        return out
    }

    /**
     * Replaces an occurrence of [secret] in [message] with [MASK].
     *
     * Call this when the caller *knows* what the secret is — for example before
     * putting an exception message into the UI, where the key itself may have
     * been echoed back by the control plane.
     */
    fun scrubKnown(message: String, vararg secrets: String?): String {
        var out = message
        for (secret in secrets) {
            if (!secret.isNullOrEmpty()) out = out.replace(secret, MASK)
        }
        return scrub(out)
    }

    /**
     * Shows enough of [host] to be recognisable in a log without publishing the
     * exact address. Hostnames and tailnet IPs are not secrets — they are
     * pointers to a private network — so only IPv6 and long names are trimmed.
     */
    fun hostLabel(host: String): String = when {
        host.isEmpty() -> "(empty)"
        host.length <= 24 -> host
        else -> host.take(12) + "…" + host.takeLast(8)
    }

    /** A stable, non-reversible fingerprint, for correlating log lines. */
    fun fingerprint(value: String): String {
        var h = -0x340d631b // FNV offset basis
        for (b in value.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return "fp:" + (h.toUInt().toString(16)).padStart(8, '0')
    }
}
