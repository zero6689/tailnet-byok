# tailnet-byok

**An Android app that carries its own Tailscale node.** You paste a pre-auth key
and a target host; the app joins *your* tailnet, on *your* terms, and talks to
that one service. No account with us, no server of ours, no shared relay.

> Bring-your-own-key, taken literally: the key is a Tailscale auth key, it is
> stored encrypted by the Android Keystore, and it never leaves your device
> except to the control plane you configure.

[![CI](https://github.com/zero6689/tailnet-byok/actions/workflows/ci.yml/badge.svg)](https://github.com/zero6689/tailnet-byok/actions/workflows/ci.yml)
[![Docs](https://github.com/zero6689/tailnet-byok/actions/workflows/docs-pages.yml/badge.svg)](https://zero6689.github.io/tailnet-byok/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC97.svg)](https://developer.android.com/about/versions/oreo)

> **Not affiliated with DeepSeek.** This is an independent, unofficial client. The name and
> the whale artwork are used descriptively, to say what the app connects to. See
> [Not affiliated with DeepSeek](#not-affiliated-with-deepseek).

---

## Why this exists

Reaching a service on your own tailnet from a phone normally means one of two
unsatisfying options:

1. **Install the official Tailscale app.** It works, and for most people it is
   the right answer. But it takes the device-wide VPN slot, changes DNS for every
   app on the phone, and — decisively — *no other app can hand it an auth key*.
   There is no API, intent or content provider for that. If your goal is "an app
   that provisions a device onto my tailnet from inside the app", this route
   cannot do it.
2. **Ship your own VPN service.** That means `BIND_VPN_SERVICE`, a foreground
   service, split-tunnel policy, and a permanent notification — a lot of
   surface, and a lot of ways to get it wrong, to open one socket to one host.

`tailnet-byok` takes a third path: it embeds a **userspace** Tailscale node with
[`tsnet`](https://pkg.go.dev/tailscale.com/tsnet) and dials the target through
it. The device's other traffic is untouched. No VPN permission is requested. The
app opens one connection, to one destination, and nothing else.

This is the same shape as [`tailvisor`](https://github.com/tailscale/tailvisor)
(tsnet without a system VPN) and
[`netbirdio/android-client`](https://github.com/netbirdio/android-client) (a
gomobile-bound tunnel in a Kotlin app), applied to the single-destination case.

---

## What it does

- **Configuration** — Tailscale auth key, target host (MagicDNS name or `100.x`
  address), port, scheme, path, node hostname, and an optional self-hosted
  control server for headscale.
- **Secure storage** — the auth key is encrypted with an AES-256-GCM key that
  lives in the Android Keystore. On devices with a TEE or StrongBox, that key
  never exists in the app's address space at all.
- **Connection test** — a six-step check that names the step that failed, with
  timings, instead of returning one unhelpful boolean.
- **Direct connection** — traffic reaches the target over the tailnet, from
  inside the app.
- **The target's own UI** — *Open the DSH UI* renders the target's web interface in a
  WebView. It goes through a `127.0.0.1` reverse proxy inside the app, because the socket
  into your tailnet is created in Go and Android's HTTP stack has no route to it. The
  listener is loopback-only, requires a random per-session token, and its session cookie
  is cleared when you leave the screen.
- **Updates, verified or refused** — *Check for updates* reads `dsh.apk.version` from the
  update source (by default the target's origin, and configurable), downloads `dsh.apk` when
  that is newer, and installs it only when the bytes match `dsh.apk.sha256` and the archive
  declares this app's package name. There is no timer and no background check; the result of
  the last attempt stays on the screen, including after the installer restarts the app.
- **Provisioning by link or QR code** — a deployment can hand the app its address as
  `dshbyok://setup?target=…&mode=…`. The app shows what the link would change and waits for a
  tap; a link that carries a credential-shaped field is refused outright, and one pointing at a
  host the address policy rejects cannot be applied. There is a generator that renders the QR
  code in your browser: [`site/provisioning.html`](https://zero6689.github.io/tailnet-byok/provisioning.html).
  See [`docs/PROVISIONING.md`](docs/PROVISIONING.md).

### A sample run

```
✓ Validate target address     target looks like a tailnet address
✓ Select connection method    using Embedded tailnet node
✓ Check stored credential     auth key present (fp:3f9a1c04)
✓ Bring up tailnet node       node up at 100.101.102.104 in phone.tailnet-name.ts.net   1840 ms
✓ Open TCP connection         connected in 142 ms (resolved to 100.101.102.103)          142 ms
✓ Send HTTP request           HTTP 200 in 187 ms                                      187 ms
Connected
```

And when it fails, it says which part:

```
✗ Validate target address     a public address is not reachable through a tailnet.
                              Use the 100.x address or the MagicDNS name.
```

---

## Screens

The setup screen is one scrollable screen, in this order: target → connection
method → credential → node → test. That order is the dependency order, so the
result of the test sits directly under the fields it describes.

From there, *Open the DSH UI* swaps in `WebScreen.kt`: a WebView onto the
target's own interface, reached through the loopback proxy described above.

`SetupScreen.kt` ships three `@Preview`s (empty, passed, rejected address), so
the states are reviewable in the IDE without a device.

---

## Quick start

```bash
git clone https://github.com/zero6689/tailnet-byok.git
cd tailnet-byok
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```

That builds the app **without** the embedded node — the pure-Kotlin path, which
needs only JDK 17 and an Android SDK. The app still runs; it will tell you the
embedded provider is not compiled in and offer the system-network fallback.

To get the real thing, build the native bridge first, then rebuild:

```bash
./scripts/build-tailnet-aar.sh   # needs Go, gomobile and the Android NDK
./gradlew assembleDebug -PwithTsnet=true
```

Full details, including the Windows script and the exact toolchain versions, are
in [`docs/BUILD.md`](docs/BUILD.md) and [`docs/TSNET.md`](docs/TSNET.md).

---

## Architecture

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

The seam that matters is `ConnectivityProvider`. The UI never branches on which
implementation is active, which is what makes the embedded node optional and a
third provider a local change.

There is no Hilt, no Retrofit, no Room, and no navigation library. Four objects
are wired by hand in `di/AppContainer.kt`, with the reasoning for each omission
written down in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

### Why the HTTP happens in Go

A Go `net.Conn` cannot be represented in Java, so no JNI bridge can hand a socket
across. The app therefore does its HTTP *inside* Go — through the tailnet's own
network stack — and passes the response back as JSON with a base64 body. That is
why `ConnectivityProvider.fetch()` returns bytes rather than a socket, and it is
not an accident of the implementation.

---

## Security

| Requirement | How it is met |
|---|---|
| Never hardcode private configuration | No credential exists in the source, the build, or `BuildConfig`. The only `buildConfigField` is a non-secret control-plane URL. |
| Key never written to disk in plain text | AES-256-GCM via the Android Keystore, `setRandomizedEncryptionRequired(true)`, `setUnlockedDeviceRequired(true)` on API 28+. The node's own state lives in `noBackupFilesDir`. |
| Key never written to logs | All logging goes through `SafeLog`, which scrubs through `Redact`; release builds strip `v`/`d`/`i` at the bytecode level. `RedactTest` covers the credential shapes this project actually handles. |
| No accidental egress | `TailnetAddressPolicy` rejects anything outside `100.64.0.0/10`, `fd7a:115c:a1e0::/48` and named hosts **before** a dial. |
| Nothing leaves the device | `android:allowBackup="false"`, backup and device-transfer rules exclude everything, no analytics, no crash reporter, no server. |
| Minimum permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES` (the updater asks the system installer — it cannot install silently). No `BIND_VPN_SERVICE`. No `QUERY_ALL_PACKAGES`. No location. |
| Updates are verified or refused | The updater downloads `dsh.apk` only after `dsh.apk.version` advertises something newer, and installs it only if its SHA-256 matches `dsh.apk.sha256` and the archive declares this app's package name. A missing or mismatched sidecar is a failure, never a silent pass. |

The honest limits of all of this — what Keystore does *not* protect against, and
why the plaintext key exists in the heap for a short window — are in
[`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md). Read it before you trust the
app with a key that can join a machine to your tailnet.

**Found a vulnerability?** Please do not open a public issue. See
[`SECURITY.md`](SECURITY.md).

---

## Privacy

No telemetry, no analytics, no crash reporting, no account, no server. The app's
own network traffic consists of exactly two things: the control-plane connection
that its Tailscale node makes, and the requests you ask it to make. Full text:
[`PRIVACY.md`](PRIVACY.md).

---

## Documentation

| | |
|---|---|
| [docs/BUILD.md](docs/BUILD.md) | Build the app, and the native bridge |
| [docs/RELEASING.md](docs/RELEASING.md) | What a release records, and what must be true first |
| [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) | How the graph is pinned, and what moving it costs |
| [docs/TSNET.md](docs/TSNET.md) | How the embedded node works, and the traps |
| [docs/PROVISIONING.md](docs/PROVISIONING.md) | Configuration links, QR codes, and pre-filled builds |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module layout and the decisions behind it |
| [docs/SECURITY-MODEL.md](docs/SECURITY-MODEL.md) | Threat model, in plain language |
| [docs/PROVENANCE.md](docs/PROVENANCE.md) | Where the code came from, and how that was checked |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | Every module linked into the native library |
| [tailnet/README.md](tailnet/README.md) | The Go bridge, and its binding rules |
| [Website](https://zero6689.github.io/tailnet-byok/) | The same docs, rendered |

---

## Status

Version **0.2.8** (`versionCode` 8) — a working spine, and it compiles.

**Verified by an actual build**, not by inspection: unit tests pass, Lint reports zero errors,
`assembleDebug` and `assembleRelease` both succeed, and the gomobile bridge produces a
60.2 MiB `tailnet.aar` whose `libgojni.so` lands in all four ABI directories of the APK.

| Artifact | Size |
|---|---|
| `tailnet.aar` | 60.2 MiB |
| Release APK, four ABIs | 164.1 MiB |
| Release APK, `arm64-v8a` only (estimated) | ~46 MiB |

> **The embedded node is essentially the whole app.** Four ABIs of `libgojni.so` total 162.4 MiB
> against a 164.1 MiB release APK. If that matters to you, either ship one ABI, ship an App
> Bundle, or use the `SYSTEM_NETWORK` provider and let the official Tailscale app own the
> tunnel. Details in [docs/TSNET.md](docs/TSNET.md#measured-sizes).

Sizes are MiB, measured on the build described in that document; they move when
`tailscale.com` moves, which is why they are stated with the version that produced them.

**Embedded at build time:** `tailscale.com v1.102.4`, resolved by `go mod tidy` and committed
in `tailnet/go.mod` + `go.sum`.

Not yet done, and listed honestly rather than discovered by you: the bridge compiles and
packages but has never been run on a device in CI; there are no instrumented tests; the UI
strings are not extracted for translation; and nothing in this repository is a screenshot from
a running app. See `docs/ARCHITECTURE.md → Known gaps`.

### What building it taught us

Four things in the original skeleton were wrong, and only a real build could find them. Each
one produces an error message that points somewhere other than the cause:

| Trap | Where it bites |
|---|---|
| `-javapkg` is a prefix, not a package | The AAR builds; Kotlin reports a hundred unresolved references |
| gomobile binds Go `int` as Java `long` | "actual type is Int, but Long was expected" |
| gomobile copies Go doc comments into Java, and javac uses the platform charset | `unmappable character` errors in a file nobody wrote |
| `gomobile bind` must run from inside the Go module | "requires golang.org/x/mobile in the current module" — when it is |

All four are written up in [docs/TSNET.md](docs/TSNET.md#traps-found-by-actually-building-it).

---

## Contributing

Issues and pull requests are welcome. Before opening a PR, please read
[`CONTRIBUTING.md`](CONTRIBUTING.md) — in particular the rule that **no test,
fixture, log line or commit message may contain a real auth key**, and the
requirement that security-relevant changes come with a note in
`docs/SECURITY-MODEL.md`.

## Not affiliated with DeepSeek

The app's display name is **"DeepSeek Harness"** and its launcher icon depicts the DeepSeek
whales — a blue one and a black orca, chasing each other into a circle. Both are used
**descriptively**, to say what this app is a client for. They are not this project's marks,
and no claim to them is made.

**DeepSeek and the DeepSeek whale are trademarks of their respective owner. This project is
an independent, unofficial client. It is not affiliated with, endorsed by, sponsored by, or
in any way officially connected to DeepSeek.**

The source code is licensed under MIT (see [LICENSE](LICENSE)). **That licence covers the
code only.** It grants no rights to any name, logo or trademark: copyright and trademark are
separate questions, and MIT is silent on the second one. Anyone redistributing this project —
a fork, a rebuild, a store listing — inherits the same position and should carry the same
disclaimer.

The maintainer's personal build of the same tool is deliberately distinct: it uses a single
blue whale, so the two are distinguishable on a launcher. The public project's mark is the
taiji. See [`branding/README.md`](branding/README.md).

If you are the rights holder and would prefer the name or the artwork changed, please open an
issue and it will be changed.

## License

[MIT](LICENSE). Use it, fork it, ship it — subject to the note above about marks.

The embedded node is [`tailscale.com/tsnet`](https://pkg.go.dev/tailscale.com/tsnet),
which is BSD-3-Clause. This project is not affiliated with Tailscale Inc.
"Tailscale" is their trademark; this app is an independent client for their
protocol and for headscale.

The native library statically links a few dozen Go modules, and BSD-3-Clause and
Apache-2.0 both attach conditions to *binary* distribution — the copyright notice,
the list of conditions and the disclaimer have to travel with the binary. Those
obligations are met in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), with the
full licence text in [`licenses/`](licenses/). Neither file is maintained by hand:
`scripts/third-party-licenses.mjs` regenerates them from `go list -deps`, so they
describe what is actually in the binary rather than what someone remembered to add.

Where the code came from — and how that was checked — is
[`docs/PROVENANCE.md`](docs/PROVENANCE.md).
