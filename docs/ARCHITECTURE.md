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
| UI | `ui/setup`, `ui/theme` | Compose screen, one `UiState`, one view model | domain, data |
| Domain | `domain` | Address policy, the ordered connection test | nothing (pure Kotlin) |
| Network | `net`, `net/tsnet` | Two ways to reach the target, behind one interface | domain |
| Data | `data/config`, `data/crypto` | Persistence and the Keystore vault | — |
| Native | `tailnet/` (Go) | The embedded node | tsnet |

`domain` deliberately references no Android type. That is what lets
`TailnetAddressPolicyTest` run on the JVM in milliseconds, with no Robolectric and no device —
and the address policy is exactly the code you most want covered by fast tests.

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
| UI strings are not extracted | The app cannot be translated without moving copy into resources. Deliberate: each warning currently sits next to the condition that triggers it. |
| No `abiFilters` | All four ABIs ship, which is what makes the debug APK 183 MB. See `docs/TSNET.md` for the measured numbers and the one-line change. |
| No reproducible builds | Release APKs are signed in CI but not verified bit-for-bit reproducible. `go.sum` and the Gradle version catalog do pin the inputs, so this is achievable. |
| The bridge is compiled, not audited | `tsnet` v1.102.4 is a large dependency, neither vendored nor reviewed here. |
| No foreground service | Work happens while the screen is on. A long-lived node in the background would need one, plus a notification. |
| The UI has never been rendered on a device | It compiles and the `@Preview`s describe the intended states, but no screenshot in this repository came from a running app. |
| **The name and marks are DeepSeek's** | The display name is "DeepSeek Harness" and the icon depicts DeepSeek's whales. **Mitigated, not solved:** the project carries an "independent, unofficial client, no claim to these marks" disclaimer in the README, in every page's footer on the docs site, and inside the app's setup screen. A disclaimer establishes good faith; it is not a licence. Copyright and trademark are separate questions, and MIT answers only the first. `branding/README.md` lays out the alternative — a mark of your own. |
| No themed-icon (monochrome) layer | Material You needs a single-colour silhouette. This mark is two-tone by design — the subject *is* the contrast between the two whales — so a threshold-based one reads as damage. Needs real artwork, not a filter. |

## What the first real build established

These were open questions in the skeleton. They are now answered, and the answers changed the
code — which is the point of writing them down rather than leaving them as assumptions.

| Question | Answer |
|---|---|
| Does a prebuilt `libtailscale` AAR exist? | No — not on Maven Central, JitPack, or the GitHub releases. It must be built, and `docs/TSNET.md` explains why we bind `tsnet` ourselves rather than reuse Tailscale's. |
| Does the bridge build end to end? | Yes. `tailnet.aar` is 59.7 MB, contains `io.github.zero6689.tailnetbyok.mobile.Mobile`, and `libgojni.so` lands in all four ABI directories of the APK. |
| What does it cost in size? | **More than everything else combined.** Four ABIs of `libgojni.so` total 161.2 MB against a 162.6 MB release APK — the Go runtime is ~99% of this app's download. An `arm64-v8a`-only build is roughly 46 MB. |
| Which version of `tsnet` is embedded? | `tailscale.com v1.102.4`, resolved by `go mod tidy` and committed in `go.mod` + `go.sum`. |

That size result is the most consequential thing this project learned, and it changes the
release advice: **ship one ABI, or ship an App Bundle.** It is also the strongest available
argument for the `SYSTEM_NETWORK` provider — for a user who already runs the official Tailscale
app, that path costs nothing at all.

Four things were wrong in the skeleton and were caught only by building it: the `-javapkg`
prefix, the Go `int` / Java `long` boundary, the javac charset trap, and the working directory
`gomobile bind` requires. All four are written up in `docs/TSNET.md`, because each one fails
with an error message that points at something other than the cause.

