# Contributing

Thanks for considering it. This document is short and mostly about two things: not leaking a
credential, and not making the security claims in this repository untrue over time.

---

## The one rule that matters most

> **No test, fixture, log line, screenshot, issue, pull request or commit message may contain a
> real auth key.**

Use obvious dummies: `tskey-auth-EXAMPLEKEYFORTESTING` and so on. If you paste a real key by
accident, **rotate it first** — before you do anything else, in your tailnet's admin console —
and then clean up. The order matters: rewriting history does not un-leak anything, because a key
that reached a public repository is already scraped.

CI enforces this on tracked files, but CI only sees what is committed. It cannot help with a
screenshot.

---

## Getting set up

```bash
git clone https://github.com/zero6689/tailnet-byok.git
cd tailnet-byok
node scripts/fetch-toolchain.mjs gradle sdk
node scripts/make-wrapper.mjs
./gradlew assembleDebug
```

That builds the default configuration, which is what most changes need. For the embedded node
you additionally need Go and the NDK — see [docs/BUILD.md](docs/BUILD.md).

---

## What CI will check

| Job | Checks |
|---|---|
| Guardrails | No `android.util.Log` outside `SafeLog.kt`; no credential-shaped string in tracked files; warns on a specific tailnet literal in code |
| Unit tests | `./gradlew testDebugUnitTest` |
| Lint | `./gradlew lintDebug` |
| Assemble | The debug APK builds, and is not implausibly small |
| Tailnet bridge | When `tailnet/**` changes: builds the AAR, asserts the generated Java facade exists, and assembles with `-PwithTsnet=true` |

Run these before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
node site/build.mjs          # if you touched site/
node scripts/build-bridge.mjs --check   # if you touched tailnet/
```

---

## House rules for code

These are not style preferences; each one exists because of a specific failure mode.

- **All logging goes through `SafeLog`.** A bare `android.util.Log` call is review-blocking. The
  redaction happens inside `SafeLog`, so a call that bypasses it is a call that can leak.
- **New secret-shaped strings go into `Redact` and get a test in `RedactTest`.** If you add a
  credential format, add its pattern and a case that proves it is scrubbed.
- **No new outbound destination.** If a change needs one, it requires a justification in
  `PRIVACY.md` and explicit reviewer sign-off. This app's central claim is that it has no
  destination we control; that claim is a feature, not an accident.
- **Security-relevant changes update `docs/SECURITY-MODEL.md` in the same pull request.** A
  document that drifts from the code is worse than no document, because it is trusted.
- **Domain code stays free of Android types.** `domain/` is pure Kotlin so it can be tested on
  the JVM without Robolectric.
- **Prefer a comment that explains why.** The codebase is commented heavily, and almost always
  with the reasoning or the failure mode, not a restatement of the code. If you find a comment
  that only restates the code, deleting it is a valid contribution.

---

## Pull requests

- One logical change per pull request.
- Describe the failure mode you are fixing, not just the change. "Fixes a case where the
  provider swallowed a timeout" beats "improve error handling".
- If you changed behaviour a user can see, say what they will notice.
- If you are unsure whether something is in scope, open an issue first. It is cheaper for
  everyone than a rejected pull request.

## Adding a provider

See `docs/ARCHITECTURE.md` → *Extending it*. Four steps. If your provider needs a heavy
dependency, follow the `tsnet` pattern: its own source set, a `Class.forName` hook in
`ProviderRegistry`, and a ProGuard keep rule.

Do not forget the keep rule. Its absence produces a release-only failure where the feature
silently disappears.

## Reporting bugs

Include:

- what you expected and what happened;
- the **Diagnostics** output from the app (it is redacted before display, so it is safe to
  paste — but read it before you do);
- Android version and device model;
- whether you built with `-PwithTsnet=true`;
- if it is a build problem, the output of `node scripts/build-bridge.mjs --check`.

## Security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

---

## License

Contributions are accepted under the [MIT License](LICENSE). By opening a pull request you agree
that your contribution may be distributed under those terms.
