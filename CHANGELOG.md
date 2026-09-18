# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Because the app ships no server component, a security fix reaches users only when they install
a new release. The app has no auto-update mechanism and does not phone home to check for one —
that is a consequence of the privacy design, not an oversight, and it is worth knowing when you
read a security entry below.

---

## [Unreleased]

_Nothing yet._

## [0.2.5] — 2026-09-18

The version the app reports (`versionCode` 5), and the first release with a public repository.
The sizes quoted throughout the docs were re-measured for it.

### Added

- **The DSH screen: your server's own UI, inside the app.** Until now the app could prove the
  socket worked and nothing more, because the connection into the tailnet is created in Go and
  Android's HTTP stack has no route to it — a WebView simply cannot dial it. The bridge now runs
  a small reverse proxy on `127.0.0.1` (random port, random per-session token, loopback only) and
  the WebView talks to that. Four details are what make it work rather than almost work, and each
  one was a failure before it was a fix: `Host`/`Origin` are restated as the target's authority
  (the auth proxy only mints its cookie for the authority it expects), the response stream is
  never buffered (the UI holds an event stream open), `Content-Encoding` is left exactly as sent
  (Go's transparent gunzip desynchronises the header from the body and renders a blank page), and
  the session cookie is handed to the WebView rather than kept in Go — with any `Domain` attribute
  dropped, since a cookie scoped to the target's hostname is rejected outright by a page served
  from loopback. The cookie is cleared when you leave the screen. `tailnet/proxy.go`,
  `proxy_test.go`.
- **File upload from the DSH screen**, through the Storage Access Framework, so the app receives a
  handle to one chosen file and no broader access.
- **The app icon.** An adaptive icon generated from `branding/mark-taiji.png` — two whales in
  a taiji — replacing the placeholder vector ring. `scripts/make-icons.ps1` generates all five
  densities, can crop a mark out of a raw screenshot (`-AutoCrop`), and writes a Play Store
  listing icon.
- **The trademark disclaimer.** The display name is "DeepSeek Harness" and the icon depicts
  DeepSeek's whales, so the project now states plainly, in the README, on every page of the
  docs site and **inside the app's setup screen**, that it is an independent, unofficial client
  and claims no rights to those marks. MIT covers the code; it does not cover a brand, and the
  two are separate questions.
- `branding/README.md` — how the mark was cropped, why the plate is white, why the mark is 72%
  of the canvas, and why there is no themed-icon layer. Includes the three options for a public
  release, since "MIT" and "you may use this name and logo" are not the same permission.
- **`docs/RELEASING.md`** — what a release consists of, the record every release notes file has to
  carry (tag and commit, Go, the `x/mobile` revision, the `tailscale.com` version, the AAR and APK
  hashes, the signing certificate fingerprint), what to run before tagging, and the four rules
  that exist because the alternative is worse.
- **`docs/DEPENDENCIES.md`** — the pin, which three modules are load-bearing rather than routine,
  how to move the pin safely, and which gates will object if you get it wrong.
- **`docs/PROVENANCE.md`** — what is original here, what is merely linked, and what was read for
  design inspiration without being copied, with the checks that back each claim up.
- **A manual reproducibility check** (`.github/workflows/reproducible-build.yml`) that builds the
  bridge twice on one runner with the pinned toolchain and compares the **native library**, not
  the `.aar` container, whose zip metadata is expected to differ between builds.

### Fixed

- **The login handshake could not complete, on either provider.** A hosted DSH host answers the
  first request with `303` plus `Set-Cookie`, and only serves the page when the follow-up carries
  that cookie. Neither HTTP client kept one. OkHttp defaults to `CookieJar.NO_COOKIES`, so the
  chain ran to its redirect cap (`Too many follow-up requests`); the Go side had the same hole
  and a cap of three hops on top. Both now have a jar — memory only, host-scoped, created and
  dropped with the session, because that cookie is a credential.
- **Cleartext HTTP to a tailnet address was refused by the app's own security policy.** The
  network security config tried to express "plain HTTP is fine inside this tailnet" as a
  `<domain>` entry for `100.64.0.0/10` — but that file matches hostnames and has no notion of a
  CIDR, so the only real node it never matched was the user's. Since the app's default scheme is
  `http`, the primary path was blocked outright. The rule now lives in code, where it can be
  expressed: cleartext is allowed only for tailnet literals and tailnet-ish names, and everything
  else must be HTTPS.
- **A trailing-dot MagicDNS name raised a false warning.** `tailnet-name.ts.net.` is a valid FQDN
  spelling. One code path normalised it and the other did not, so the address was correctly
  allowed but labelled "not a MagicDNS name". The address is now normalised once, at
  classification, instead of being judged twice from two different strings.
- **The embedded node reported its version as `1.102.4-ERR-BuildInfo`.** That string is
  `version.Long()`'s documented fallback for a build with no version information, and it is what
  the device showed in the tailnet admin console. Committing harder could not fix it: gomobile
  compiles a *copy* of the module in a temp work directory, where the Go tool finds no VCS data
  to embed. The two stamps (`version.shortStamp`, `version.longStamp`) are now injected with
  `-ldflags`, and the build **fails** if the stamped string is not found inside the resulting
  native libraries — an `-X` naming a symbol that does not exist is not an error to the linker,
  so a silent regression here would surface only on a phone, months later. `Version()` returns
  `version.Long()` rather than a placeholder, so the app can print the same string the node
  reports. `scripts/build-bridge.mjs`, `tailnet/tailnet.go`.
- **Scripts are executable in the repository.** `git` on Windows records `core.filemode=false`,
  so `gradlew` was committed as mode `100644` — which on Linux, macOS and CI makes `./gradlew` a
  permission error, as the first CI run on the new repository would have discovered.
- **The toolchain and size tables described an older build.** The README said version 0.1.0 while
  the app reported 0.2.5, and the documented AAR and APK sizes predated the security work. Both
  were re-measured with the build they describe (see `docs/TSNET.md`).
- **`go get -tool golang.org/x/mobile/cmd/gobind`, without a revision**, asked the module proxy for
  the latest version: a second floating pin, and a hard failure in a warm, offline checkout
  (`module lookup disabled by GOPROXY=off`) even when the module was already cached.
- **A real tailnet device address was in the docs site's sample transcript.** `site/index.html`
  showed one in the "sample run" block; it now uses the documented placeholder, like every other
  example in the repository. It was found by running the tailnet-address guardrail by hand — the
  guardrail had been reporting it, and that check excluding markdown is why nothing else did.

### Security

- **Third-party licence obligations are met, and generated rather than remembered.** The native
  bridge statically links 31 Go modules — `tailscale.com` (BSD-3-Clause), `gvisor.dev/gvisor`
  (Apache-2.0), and MIT or BSD-2-Clause for the rest — and both of those first licences attach
  conditions to *binary* distribution. `THIRD-PARTY-NOTICES.md` and `licenses/` reproduce the
  notices and the full texts; `scripts/third-party-licenses.mjs` regenerates them from
  `go list -deps`, and fails if a linked module has no licence text, so the list cannot drift into
  being wrong or quietly incomplete. A CI job re-runs it and fails when the committed files differ.
- **The build's dependency pin can no longer move silently.** `go mod tidy` runs on every build
  path, and if it changes `go.mod` or `go.sum` the script restores them and fails; the deliberate
  path is `--write-mod`, which keeps the change so that it lands as a diff somebody reviews. An
  AAR built from a graph that matches no commit is an AAR nobody else can reproduce.
- **`gomobile` and `gobind` are installed from the revision pinned in `go.mod`**, not from
  `@latest`. That is what `gomobile init` would otherwise do behind the build's back, installing a
  tool whose generated bindings describe an ABI the runtime does not implement — a mismatch that
  surfaces at the first call rather than at build time.
- **The credential guardrail was skipping the files most likely to contain a pasted key.** It
  filtered hits with `grep -viE '…|test|\.md:'`, and because `git grep -n` prints
  `path:line:text`, `test` matched the **path**: every file under a `test/` directory was skipped,
  which is exactly where someone pastes a key while debugging, along with every markdown file.
  Measured against the tree it was written for, it discarded all seven credential-shaped strings
  in the repository — and would have discarded a real one just as quietly. It now has no
  path-based exclusions, and lets a line through only when it carries an explicit `not-a-secret`
  marker or an obvious placeholder word. The tailnet-address check scans documentation too now,
  for the same reason.

- **CI publishes exactly two artifacts, on purpose.** The AAR and the debug APK from the bridge
  workflow are uploaded, because that is how a build gets onto a phone to be tested without a
  local Go toolchain; the two AARs the reproducibility check produces are still not uploaded, as
  the hashes in its log are the evidence. Neither artifact is a release: no tag, no signing
  record, no checksum, and both expire. Users get builds from a tagged release
  (`docs/RELEASING.md`). The unit-test and lint reports remain uploaded as diagnostics.

### Notes

- The `tailnet-bridge` workflow has now been exercised on a runner, and the first run failed
  where this note predicted it would: `yes | sdkmanager` under `set -o pipefail` reports the
  licence-acceptance pipe as failed (`yes: standard output: Broken pipe`) after the NDK has in
  fact installed. The same three lines were in `release.yml` and `reproducible-build.yml`, so
  all three workflows died before reaching a build. Fixed in all three: accept licences
  best-effort, then install without a pipe, so a real failure stays a real failure.
- `GOMOBILE_VERSION: latest` was removed from the bridge workflow's environment. The build
  script never read it — the revision comes from `tailnet/go.mod` — so its only effect was to
  print a value in every run that looked like the build floats its toolchain, which is the
  opposite of what it does.

---

## [0.1.0] — 2026-09-12

The first release. A skeleton with a working spine: the security layer, the connection test and
the address policy are complete and tested; the embedded node's Go source is complete but its
AAR is produced only by CI.

### Added

**Configuration and storage**

- `AppConfig` — host, port, scheme, path, control server, node hostname, ephemeral flag. Holds
  a boolean `hasStoredKey`, never the credential itself.
- `ConfigRepository` — DataStore-backed persistence, with the credential split out into a
  separate encrypted store. Settings and secrets change for different reasons, and a single
  "save" that writes both is how a key ends up clobbered by a form that only changed the port.
- `KeystoreSecretVault` — AES-256-GCM under a non-exportable Android Keystore key, with
  `setRandomizedEncryptionRequired(true)` and `setUnlockedDeviceRequired(true)` on API 28+.
  Reports whether the key is hardware-backed rather than assuming it is.
- `ClearAuthKey` destroys the Keystore key as well as the ciphertext, so pre-existing copies of
  the ciphertext become permanently unreadable.

**Reaching the target**

- `ConnectivityProvider` — the seam. Two implementations, no branching in the UI.
- `SystemNetworkProvider` — plain sockets over whatever network the device already has, with
  distinct messages for timeout, refused, no-route and DNS failure.
- `TsnetConnectivityProvider` — the embedded node, behind a `gomobile` bridge.
- `TailnetAddressPolicy` — the security boundary that decides what may be dialled, with
  `Allowed` / `AllowedWithWarning` / `Rejected` verdicts rather than a boolean, because "valid
  but fragile" is a real and common state.

**The native bridge**

- `tailnet/` — a Go package wrapping `tsnet`, following one rule: every exported function takes
  and returns only `String`, `Int`, `Bool` or `error`. Structured data crosses as JSON.
- Bounded in-memory log ring, redacted before storage. `Logf` and `UserLogf` both feed it, so
  the interactive login URL surfaces as `Status().loginURL`.
- Explicit `forceLogin`, because `tsnet` silently ignores a new auth key when node state already
  exists — the most confusing symptom this project can produce.

**The test**

- `ConnectionTester` — six named steps, each with a duration, stopping at the first hard
  failure and marking the rest skipped. A single boolean would be useless: the fixes for a bad
  address, a missing key, a dead service and a wrong path are all different.
- Any HTTP status counts as proof of life. A 401 is a pass, because the app is not
  authenticated to the target and should not be.

**Security and privacy**

- `SafeLog` and `Redact` — one logging entry point, scrubbed, with the credential shapes this
  project actually handles covered by tests. Release builds strip `v`/`d`/`i` at the bytecode
  level.
- `allowBackup="false"` with backup and device-transfer rules excluding every domain.
- Network security config permitting cleartext **only** for tailnet destinations.
- Three permissions total. No `BIND_VPN_SERVICE`, no `QUERY_ALL_PACKAGES`, no location.
- Exactly one exported component.

**Project**

- GitHub Actions: `ci` (guardrails, tests, lint, assemble), `tailnet-bridge` (build the AAR and
  prove the integration), `release` (signed APK on tag), `docs-pages` (docs site to Pages),
  `sync-personal-site` (manual mirror, dry-run by default).
- `scripts/fetch-toolchain.mjs` — checksum-verified download of Gradle, the Android SDK, Go and
  the NDK, so that "install the toolchain" is one command instead of a wiki page.
- `scripts/build-bridge.mjs` — the gomobile bind, with the four load-bearing flags documented
  and the generated Java facade verified by name.
- A dependency-free static docs site with a link validator that fails the build on a broken
  internal link.
- MIT license, `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`, and a threat model that states
  its own limits.

### Known limitations

These shipped in 0.1.0 and are listed rather than left to be discovered:

- The bridge compiles and packages, but has never been *run* on a device in CI.
- No instrumented tests. Keystore behaviour and the native bridge are verified by hand.
- UI strings are not extracted, so the app cannot yet be translated.
- No `abiFilters`, so all four ABIs ship and the release APK is 162.6 MB. Four ABIs of
  `libgojni.so` account for 161.2 MB of that, so an `arm64-v8a`-only build is roughly 46 MB.
- Release builds are signed but not verified bit-for-bit reproducible.
- The plaintext auth key exists in the process heap between decryption and the node coming up,
  because Kotlin strings cannot be zeroed. Documented in `docs/SECURITY-MODEL.md`.
- No screenshot in this repository came from a running app.

### Verified by build

Every claim above was checked against a real toolchain, not by inspection:

| Check | Result |
|---|---|
| `testDebugUnitTest` | 30 tests, all passing |
| `lintDebug` | 0 errors, 0 warnings |
| `assembleDebug` | APK produced, `libgojni.so` in all four ABI directories |
| `assembleDebug -PwithTsnet=true` | APK produced |
| `assembleRelease -PwithTsnet=true` | 162.6 MB, unsigned, R8-minified |
| `go vet ./...` | clean |
| gomobile facade assertion | `io.github.zero6689.tailnetbyok.mobile.Mobile` present |

Resolved versions, for the record: Go 1.27.1, `tailscale.com` v1.102.4, NDK r26d,
AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1.


[Unreleased]: https://github.com/zero6689/tailnet-byok/compare/v0.2.5...HEAD
[0.2.5]: https://github.com/zero6689/tailnet-byok/releases/tag/v0.2.5
[0.1.0]: https://github.com/zero6689/tailnet-byok/releases/tag/v0.1.0
