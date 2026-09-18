<!--
Thanks for the pull request.

Two things before you submit:

  1. Read your own diff for credentials. No test, fixture, log line or commit message may
     contain a real auth key. CI checks tracked files, but it cannot check a screenshot or a
     commit message it has already seen.
  2. If this changes the security model — a new permission, a new outbound destination, more
     data stored — update docs/SECURITY-MODEL.md in this same pull request. A document that
     drifts from the code is worse than no document, because it is trusted.
-->

## What this changes

<!-- One or two sentences. What is different after this merges? -->

## Why

<!--
The failure mode being fixed, not just the change being made.
"Fixes a case where the provider swallowed a timeout and reported success" beats
"improve error handling".
-->

## What a user will notice

<!--
Or "nothing user-visible". If this is a behaviour change, say what someone will see
differently.
-->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor, with no behaviour change
- [ ] Documentation
- [ ] Build, CI or tooling
- [ ] Security-relevant (requires a note in `docs/SECURITY-MODEL.md`)

## How it was verified

<!--
Be specific. "Ran ./gradlew testDebugUnitTest" is a real answer. "Should work" is not.
If you could not test something — no device, no Go toolchain — say so plainly. That is
useful information, not a weakness.
-->

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintDebug` passes
- [ ] `./gradlew assembleDebug` produces an APK
- [ ] `./gradlew assembleDebug -PwithTsnet=true` passes (if the Go bridge changed)
- [ ] `node site/build.mjs` passes (if `site/` changed)
- [ ] Verified on a real device — Android version and model:

## Checklist

- [ ] **No real credentials anywhere in this diff, its commit messages, or any attached screenshot.**
- [ ] New logging goes through `SafeLog`, not `android.util.Log`.
- [ ] New credential formats are added to `Redact` **and** covered by a test in `RedactTest`.
- [ ] No new outbound network destination (or: documented in `PRIVACY.md` with reviewer sign-off).
- [ ] No new Android permission (or: justified in `AndroidManifest.xml` and `docs/SECURITY-MODEL.md`).
- [ ] If a class is loaded by `Class.forName`, a ProGuard keep rule was added — otherwise the
      failure is release-only and the feature silently disappears.
- [ ] Domain code (`domain/`) still references no Android types.
- [ ] Comments explain *why*. A comment that only restates the code should be deleted instead.
