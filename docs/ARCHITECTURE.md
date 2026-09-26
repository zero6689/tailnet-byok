# Architecture

Four layers, one seam that matters, and a list of popular libraries that are deliberately
absent. The rendered version is at
<https://zero6689.github.io/tailnet-byok/architecture.html>.

---

## The seam: `ConnectivityProvider`

```
                 ui/setup            Compose screen + view model
                     │
              domain/ConnectionTester      the six-step test
              domain/TailnetAddressPolicy  what may be dialled, and why not
                     │
              net/ConnectivityProvider     ← the seam
                   ╱        ╲
   net/SystemNetworkProvider   net/tsnet/TsnetConnectivityProvider
        (plain sockets)          (embedded node, gomobile bridge)
                                        │
                                tailnet/tailnet.go   (Go, tsnet)
```

The UI never branches on which implementation is active. It reads a status flow and calls
`start` / `probe` / `fetch` / `stop`. Three things follow:

1. **The embedded node is genuinely optional.** The default build has no implementation
   registered for it, and the UI explains that rather than failing.
2. **Testing is possible without a device.** A fake provider in a JVM unit test exercises the
   whole tester sequence.
3. **A third provider is a local change.** See *Extending it* below.

### Why `fetch` and not a socket

A Go `net.Conn` has no Java representation, so no JNI binding can hand the tunnel's socket
back. HTTP therefore has to happen *inside* Go, through the tailnet's own network stack, and
come back as bytes. `ConnectivityProvider.fetch()` takes a request object rather than exposing
a client for that reason — it is a consequence of the boundary, not a design preference.

The alternative was `tsnet.Server.Loopback()`, which does provide a SOCKS5 proxy on loopback.
Rejected: it puts a listening socket and a proxy credential into the process to solve a problem
that a request-shaped API solves with none.

---

## Layers

| Layer | Package | Responsibility | Depends on |
|---|---|---|---|
| UI | `ui/setup`, `ui/theme`, `ui/scan`, `ui/web` | Compose screens, one `UiState`, one view model; the scanner's camera plumbing; the WebView that renders the target's own interface | domain, data |
| Domain | `domain` | Address policy, the ordered connection test, the configuration link and its QR codec | nothing (pure Kotlin, and ZXing's JVM-only half) |
| Network | `net`, `net/tsnet` | Two ways to reach the target, behind one interface | domain |
| Data | `data/config`, `data/crypto` | Persistence and the Keystore vault | — |
| Native | `tailnet/` (Go) | The embedded node | tsnet |

`domain` deliberately references no Android type. That is what lets
`TailnetAddressPolicyTest` run on the JVM in milliseconds, with no Robolectric and no device —
and the address policy is exactly the code you most want covered by fast tests. The QR work
follows the same rule rather than escaping it: `domain/QrCode.kt` and `domain/QrScan.kt` hold the
encoder, the rotation and the decoder, all of which run on a bare JVM, while the two Android-only
conversions — an `ImageProxy` to a luminance frame, and a picked `content://` picture to ARGB
pixels — sit in `ui/scan/CameraFrames.kt`. So "does a code round-trip" and "is a padded sensor row
read correctly" are unit tests, and only "does the camera open" needs a phone.

---

## Decisions worth recording

### No Hilt

Four objects, one scope, one file of wiring in `di/AppContainer.kt`. A framework here would add
an annotation processor, a Gradle plugin, a generated component, and a second way to construct
every object — to remove four lines a reader can follow at a glance.

This is a judgement, not a principle. Add a background component, multiple flavours, or a
second graph that needs its own lifetimes, and the trade flips. The wiring lives in one class
precisely so that the swap is a one-file change.

### No Retrofit, no Room, no navigation library, no Timber

- **Retrofit** — one kind of request, to one host, that must go through the provider seam
  anyway. There is no large typed API surface to manage.
- **Room** — eleven scalar preferences in DataStore. A relational store is ceremony around a
  key-value workload.
- **Navigation** — one screen. A back stack for a single destination is a graph with one node.
- **Timber** — one fewer dependency in the code path that handles the credential. `SafeLog` is
  forty lines and does the redaction Timber would not.

### No analytics and no crash reporter

Not a cost decision. A crash reporter is an outbound network destination that would receive the
user's configuration, and this app's central claim is that it has no destination we control.
`PRIVACY.md` states that claim; `SECURITY.md` explains why adding one would be a policy change
rather than an implementation detail.

### Cleartext HTTP is permitted for tailnet destinations

Traffic to a `100.x` address is encrypted by WireGuard to the destination node, so no passive
observer on the path — including the local Wi-Fi — sees plaintext. Requiring HTTPS on top would
push users toward self-signed certificates and "install this CA" flows, which is worse. Public
hostnames must still use HTTPS.

Note that Android's network security config cannot express a CIDR, so the ranges are enforced
in code, in `TailnetAddressPolicy`, before every dial. That is the stronger location anyway.

### The plaintext key is never part of `UiState`

It exists in `keyDraft` only while the user is typing, is written to the vault on save, and is
read back only for the duration of a connection test. `AppConfig` holds a boolean
(`hasStoredKey`), not the credential. So a state dump, a test assertion, a debugger view or a
screenshot cannot contain it — by construction, not by remembering to redact.

`TailnetCredentials.toString()` is overridden to print `present`/`absent` for the same reason.

### The object graph is owned by `Application`

The root composable reads it from the application context instead of receiving it. A
configuration change therefore cannot produce a second graph — and therefore cannot produce a
second embedded node fighting over one state directory.

### One provider instance per `ProviderId`, created lazily

Nothing eagerly loads the native library. A build that has the bridge, in which the user never
selects the embedded provider, pays nothing at cold start.

---

## Why `src/tsnet` is a separate source set

`TsnetConnectivityProvider` compiles against classes that exist only after `gomobile bind`. The
default build has no such classes, so a direct reference from shared code would break the build
for everyone without a Go toolchain — i.e. every contributor's first clone.

`app/build.gradle.kts` adds `src/tsnet/kotlin` to the main source set only when
`-PwithTsnet=true` **and** `app/libs/tailnet.aar` exists. If the property is set and the AAR is
missing, the build fails immediately with the command to produce it.

`ProviderRegistry` resolves the installer with `Class.forName`, only when
`BuildConfig.TSNET_ENABLED` is true. A separate Gradle module per provider does not remove this
problem — the consumer still has to name the class — it only relocates the failure.

> **Consequence:** R8 cannot see the reference, so `app/proguard-rules.pro` keeps
> `**.net.tsnet.**` explicitly. Without that rule the failure is release-only and presents as
> "the embedded node feature silently disappeared". If you add a provider, add its keep rule.

---

## Extending it

A third provider, in four steps:

1. Add a value to `ProviderId`.
2. Implement `ConnectivityProvider`.
3. Register it in `ProviderRegistry.initialise()`.
4. If it needs a heavy dependency, put it in its own source set and add a `Class.forName` hook
   plus a ProGuard keep rule — the pattern `tsnet` uses.

No UI change is required: the selector is driven by `ProviderId.entries` and the registry's
availability list.

---

## Known gaps

Listed so they are not discovered by you.

| Gap | Impact |
|---|---|
| No instrumented tests | Keystore behaviour and the native bridge are verified by hand, not by CI. The bridge compiles and packages, but nothing has run it on a device in CI. |
| Only two locales | Every user-facing string lives in `res/values/strings.xml` with a complete `values-zh` translation (261 strings each). There is no third locale, and the copy is written to sit next to the condition that triggers it rather than to read as a standalone catalogue. |
| Four ABIs by default | `abiFilters` is empty in the build file, so a plain `assembleDebug` carries all four ABIs (roughly 180 MiB). The release passes `-PabiFilters=arm64-v8a`. It is a build flag rather than a default so that a build which forgets it is caught in review instead of silently shipping one architecture. See `docs/TSNET.md` for the measured numbers. |
| Reproducible *builds*, not bit-identical APKs | `reproducible-build.yml` is a manual workflow that builds the bridge twice on one runner with the pinned toolchain and compares the **native library** byte for byte. It is not run before every tag, and it claims nothing about the APK container, whose zip metadata is expected to differ. `docs/RELEASING.md` states the commitment at exactly that strength. |
| The bridge is compiled, not audited | `tsnet` v1.102.4 is a large dependency, neither vendored nor reviewed here. |
| The foreground service covers the task watch only | `TurnWatchService` runs while the DSH screen is open, so a task started there can be noticed finishing after the app goes off screen. A tailnet node kept up with the screen closed is still not on offer: that would mean keeping the routing state — and the session cookie — alive past the screen, which is the one thing this app is built not to do. |
| No screenshots from a running app | The UI does run on a phone, but every image in this repository is artwork or an icon; none of it was captured from the app. The `@Preview`s in `SetupScreen.kt` describe the intended states instead. |
| **The artwork depicts a DeepSeek character** | The display name is **"DSH BYOK"**, and the full "DeepSeek Harness" is used only in descriptive sentences, which is the form DeepSeek's own brand guidelines ask for. The launcher icon is this project's own composition — a blue whale holding a glowing phone — but the whale is DeepSeek's brand character, so the mark is used descriptively under a stated disclaimer rather than licensed. **Accepted, not solved:** the disclaimer is in the README, in every page's footer on the docs site, and inside the app; good faith is not permission. `branding/README.md` records the position and what a mark with no third-party character in it would cost. |
| No themed-icon (monochrome) layer | **Closed.** The artwork now ships a drawn silhouette (`branding/ic_launcher_monochrome.png`), installed by `scripts/install-icon-layers.ps1` and declared in `mipmap-anydpi/ic_launcher.xml`, so the launcher icon follows the system theme on Android 13 and later. It is drawn rather than thresholded because the coloured mark contains white markings, which a filter keeps as holes; the row stays here only so the earlier decision is not mistaken for an oversight. |

## What the first real build established

These were open questions in the skeleton. They are now answered, and the answers changed the
code — which is the point of writing them down rather than leaving them as assumptions.

| Question | Answer |
|---|---|
| Does a prebuilt `libtailscale` AAR exist? | No — not on Maven Central, JitPack, or the GitHub releases. It must be built, and `docs/TSNET.md` explains why we bind `tsnet` ourselves rather than reuse Tailscale's. |
| Does the bridge build end to end? | Yes. `tailnet.aar` is 60.0 MiB, contains `io.github.zero6689.tailnetbyok.mobile.Mobile`, and `libgojni.so` lands in all four ABI directories of the APK. |
| What does it cost in size? | **More than everything else combined.** Four ABIs of `libgojni.so` total 162.4 MiB against a 164.1 MiB release APK — the Go runtime is ~99% of this app's download. The published `arm64-v8a` asset for v0.4.4 is **35.1 MiB** (36,870,032 B; v0.4.3 was 36,852,856 B, and v0.4.1 was 36,850,464 B). It was 43.8 MiB for 0.3.9: the toolchain upgrade to AGP 9.4 put the NDK where AGP can find it in the release workflow, so the native library's debug and static symbol tables are stripped (~9 MB, `.text`/`.rodata`/`.gopclntab` unchanged, dynamic symbols identical). Built with R8 off so that the code that ships is the code the tests cover. A workstation build of the same commit measured 54.2 MiB with identical dex: same Go source, but the locally bound `libgojni.so` carries about 11 MB more DWARF than the library `scripts/build-bridge.mjs` produces on a runner, and the local Gradle driver does not set `ANDROID_NDK_HOME`, so nothing strips it there either. |
| Which version of `tsnet` is embedded? | `tailscale.com v1.102.4`, resolved by `go mod tidy` and committed in `go.mod` + `go.sum`. |

That size result is the most consequential thing this project learned, and it changes the
release advice: **ship one ABI, or ship an App Bundle.** It is also the strongest available
argument for the `SYSTEM_NETWORK` provider — for a user who already runs the official Tailscale
app, that path costs nothing at all.

Four things were wrong in the skeleton and were caught only by building it: the `-javapkg`
prefix, the Go `int` / Java `long` boundary, the javac charset trap, and the working directory
`gomobile bind` requires. All four are written up in `docs/TSNET.md`, because each one fails
with an error message that points at something other than the cause.

