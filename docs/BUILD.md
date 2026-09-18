# Building

Two build modes. Pick the one you need.

| Mode | Needs | Gives you |
|---|---|---|
| **Default** | JDK 17, Android SDK | The app, without the embedded node. Fully functional except the auth-key path. |
| **Full** | JDK 17, Android SDK, Go, gomobile, NDK | The app with its own Tailscale node. |

A fresh clone builds in the default mode. That is deliberate: a project whose first build
requires two gigabytes of toolchain is a project nobody contributes to.

---

## Default build

```bash
git clone https://github.com/zero6689/tailnet-byok.git
cd tailnet-byok

./gradlew assembleDebug          # macOS / Linux
.\gradlew.bat assembleDebug      # Windows
```

If you do not have Gradle installed, or the wrapper has not been generated yet:

```bash
node scripts/fetch-toolchain.mjs gradle sdk   # ~400 MB, checksum-verified
node scripts/make-wrapper.mjs                 # generates gradlew and gradle/wrapper/*
./gradlew assembleDebug
```

`fetch-toolchain.mjs` installs into `.toolchain/` inside the repo (git-ignored), which keeps a
clone self-contained and keeps you from having to remember what you installed where.

### Pointing Gradle at the fetched SDK

Create `local.properties` (git-ignored) from `local.properties.example` and set `sdk.dir` to
either your own SDK or `.toolchain/android-sdk`:

```properties
sdk.dir=C\:\\path\\to\\tailnet-byok\\.toolchain\\android-sdk
```

Note the doubled backslashes and the escaped colon: this is a Java properties file.

### SDK level

The default is `compileSdk 35` / `targetSdk 35`. To build against an older SDK:

```bash
./gradlew assembleDebug -PcompileSdk=34 -PtargetSdk=34
```

AndroidX 1.15+ requires `compileSdk 35`, so this only helps if you also lower the versions in
`gradle/libs.versions.toml`.

---

## Full build, with the embedded node

```bash
node scripts/fetch-toolchain.mjs go ndk   # ~800 MB
node scripts/build-bridge.mjs             # gomobile bind -> app/libs/tailnet.aar
./gradlew assembleDebug -PwithTsnet=true
```

`build-bridge.mjs --check` prints what it can find without building anything, which is the
fastest way to learn what is missing. It also prints the revision that `gomobile` and
`gobind` were built from, next to the revision `tailnet/go.mod` pins — the two have to be
the same one.

**The one step that needs the network is `gomobile init`**, which installs gobind from
`@latest` regardless of the pin. Everything else runs from a warm cache, and the build
reinstalls either tool if `init` left it at the wrong revision. See
[`DEPENDENCIES.md`](DEPENDENCIES.md) for why.

If you set `-PwithTsnet=true` and `app/libs/tailnet.aar` is absent, the build fails
immediately with the command to produce it — rather than with ten thousand "unresolved
reference" lines.

### Toolchain versions

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | AGP 8.7 requires 17 |
| Gradle | 8.11.1 | set by `make-wrapper.mjs` |
| AGP | 8.7.3 | `gradle/libs.versions.toml` |
| Kotlin | 2.1.0 | with the Compose compiler plugin |
| Android SDK | platform 35, build-tools 35.0.0 | |
| Go | the `go` line in `tailnet/go.mod` (1.26.6) | CI installs exactly that; `fetch-toolchain.mjs` resolves the newest Go unless you pin one |
| Android NDK | r26d (26.3.11579264) | pinned in CI; gomobile is NDK-sensitive |
| gomobile / gobind | the `golang.org/x/mobile` revision in `tailnet/go.mod` | see below |

**gomobile and gobind must come from the same `golang.org/x/mobile` revision.** Different
revisions generate bindings that describe an ABI the runtime does not implement, and the
failure shows up at the first call rather than at build time. `scripts/build-bridge.mjs`
therefore installs both from the revision pinned in `tailnet/go.mod` — not from `latest` —
and `--check` prints which one that is before anything is downloaded.
`--gomobile-version <rev>` overrides it when you are deliberately moving both.

---

## Build variants

```bash
# Default: no embedded node
./gradlew assembleDebug

# With the embedded node
./gradlew assembleDebug -PwithTsnet=true

# Tests and lint
./gradlew testDebugUnitTest
./gradlew lintDebug

# Release (needs signing; see below)
./gradlew assembleRelease -PwithTsnet=true
```

---

## Release signing

Resolution order, in `app/build.gradle.kts`:

1. `keystore.properties` in the repo root (git-ignored) — for local release builds;
2. environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` —
   which is what CI uses;
3. no signing config at all, in which case `assembleRelease` still succeeds and produces an
   **unsigned** APK, with a warning in the build log.

No secret is ever read from a committed file. `.env.example` documents the CI variable names
and how to produce the base64 keystore value without leaking it.

To produce a signed release locally:

```properties
# keystore.properties  — git-ignored, never commit
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

---

## Continuous integration

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | push, PR | Guardrails (no bare `Log`, no committed credential), unit tests, lint, debug APK |
| `tailnet-bridge.yml` | changes under `tailnet/` or `app/src/tsnet/`, or manual | Builds the AAR, asserts the generated facade exists, assembles with `-PwithTsnet=true` |
| `release.yml` | tag `v*.*.*` | Builds a signed release APK and attaches it to a draft release |
| `docs-pages.yml` | push to `main` touching docs or `site/` | Builds and publishes the docs site to Pages |
| `sync-personal-site.yml` | manual only | Mirrors the site to a self-hosted copy, dry-run by default |

The bridge workflow is deliberately not part of `ci.yml`: it needs Go, gomobile and the NDK,
which is minutes of setup, and it only matters when the Go side changed.

---

## Troubleshooting

**`SDK location not found`** — create `local.properties` with `sdk.dir`. On CI this comes from
`ANDROID_HOME`.

**`Could not initialize native services. Failed to load native library 'native-platform.dll'`** —
Gradle cannot unpack its native libraries. It writes them to `$GRADLE_USER_HOME/native`, which
defaults to `~/.gradle`. In a container, a CI sandbox, or any environment where the home
directory is not writable, point it somewhere it can write:

```bash
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew assembleDebug
```

**`Couldn't open current thread, error = 5` from the file watcher** — the native file watcher
cannot start. Gradle recovers, but noisily. `gradle.properties` already sets
`org.gradle.vfs.watch=false` for this reason; set it to `true` if your machine supports it and
you want faster incremental builds.

**`withTsnet=true but app/libs/tailnet.aar is missing`** — this is the intended error, not a
bug. Run `node scripts/build-bridge.mjs`.

**`gomobile: command not found`** — `go install golang.org/x/mobile/cmd/gomobile@latest` puts
it in `$(go env GOPATH)/bin`, which is often not on `PATH`. `build-bridge.mjs` handles this
itself.

**`go: go.mod requires go >= 1.23`** — use a newer Go, or set
`GOTOOLCHAIN=auto` and let Go fetch the right one. CI sets `GOTOOLCHAIN=local` so a mismatch
fails loudly instead of silently downloading a different toolchain mid-build.

**`ndk` not found by gomobile** — export `ANDROID_NDK_HOME`. `build-bridge.mjs` sets it from
`.toolchain/android-sdk/ndk/<version>` automatically if you fetched via
`fetch-toolchain.mjs`.

**Build fails with hundreds of `unresolved reference: Mobile`** — the AAR is present but its
Java facade has a different package than the one `TsnetConnectivityProvider.kt` imports. Check
that `-javapkg` in `scripts/build-bridge.mjs` matches the import in that file.

**`UnsatisfiedLinkError` at runtime on an emulator** — the AAR lacks the x86_64 ABI. Rebind
without trimming ABIs, or test on a device.

---

## Building in a restricted environment

`scripts/fetch-toolchain.mjs` was written against, and tested in, an environment that blocks
most of what a shell script would assume. The constraints are worth knowing because they are
not unusual — they apply to most CI sandboxes, containers, and networks outside North America:

| Constraint | Consequence |
|---|---|
| TLS via the platform stack fails (`SEC_E_NO_CREDENTIALS` on Windows) | Downloads use Node's `fetch`, which carries its own CA bundle |
| `proxy.golang.org` is unreachable (times out in mainland China) | `build-bridge.mjs` probes it, then `goproxy.cn`, then `goproxy.io`, and uses whichever answers. Set `GOPROXY` to override. |
| `sdkmanager` writes outside the SDK root, and is deprecated | The script downloads package archives directly from Google's repository index and verifies the published SHA-1, then records the licence acceptance the plugin requires |
| Child processes cannot have their stdio captured (pipe → `EPERM`) | Scripts never scrape a subprocess's output; versions are constants and directory names, and where a value really is needed it is redirected to a file and read back |
| `$HOME` is not writable | `GRADLE_USER_HOME`, `GOPATH`, `GOCACHE` and `GOMODCACHE` are all redirected into the workspace |
| `ANDROID_PREFS_ROOT` is honoured differently from `ANDROID_USER_HOME` | Setting both breaks AGP's `AndroidLocationsBuildService` at plugin-apply time. Set `ANDROID_USER_HOME` only. |
| Archive layouts do not match their names | `build-tools_r35_windows.zip` extracts to `android-15/`; `go1.27.1.windows-amd64.zip` extracts to `go/`. Extraction goes to a staging directory and the single top-level directory is renamed canonically. |
| Every outbound HTTPS handshake is reset, so the network is not a source at all | Builds run with `--offline` / a pinned local toolchain. `GOPROXY=off` is *not* enough for `go get -tool`: that needs version resolution and fails with `module lookup disabled by GOPROXY=off`. Point `GOPROXY` at the module cache's `cache/download` directory as a `file://` URL instead (with `GOSUMDB=off`); it is laid out in the proxy protocol, so resolution works with zero network. |
| `gomobile bind` shells out to `javac`, and the build environment has no JDK on `PATH` | It fails in under a second with `error: no JDK found.` Set `JAVA_HOME` (and put its `bin` on `PATH`) before calling `build-bridge.mjs`; a JRE is not sufficient. |

None of these are Windows-specific, and none are worked around by asking the user to do
something different. If a script here fails on your machine, the fix belongs in the script.

### A note on the app's own toolchain files

Three files exist purely so the app builds the same way here as it does on a runner:

- `.toolchain/` (git-ignored) — everything `fetch-toolchain.mjs` installs, plus the Go caches
  and the `toolchain.json` manifest recording exact versions.
- `local.properties` (git-ignored) — `sdk.dir`, pointing at `.toolchain/android-sdk`.
- `.gradle-home/` (git-ignored) — `GRADLE_USER_HOME`, because Gradle unpacks native libraries
  there and a read-only `$HOME` fails at startup with
  `Could not initialize native services`.

