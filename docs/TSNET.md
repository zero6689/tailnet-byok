# The embedded node, and the traps

How `tailnet-byok` runs a Tailscale node inside an Android app, and the specific things that
will waste your afternoon if nobody writes them down.

The rendered version of this page is at
<https://zero6689.github.io/tailnet-byok/guide.html>; this file is the GitHub-browsable copy.

---

## The decision, and the two alternatives it beat

### Not the official Tailscale app

Requiring the user to install `com.tailscale.ipn` is the cheapest possible implementation,
and this project deliberately does not do it. Two reasons:

1. **It cannot be given an auth key.** There is no API, intent or content provider through
   which one Android app can set an auth key or a control server on the official app. The user
   must type it into Tailscale's own UI. For an app whose entire purpose is "paste a key and
   provision this device", that is disqualifying.
2. **Split-tunnel exclusions take DNS with them.** When Tailscale is disabled for an app, its
   traffic *and* its DNS queries stop going through the tunnel, so such an app reaches neither
   MagicDNS names nor `100.x` addresses. Some apps are excluded by default (Android Auto,
   Google Cast, Sonos, RCS). Also, only one `VpnService` can be active at a time.

The `SYSTEM_NETWORK` provider in this app exists for exactly these users. It is honest about
the trade: it ignores the auth key, and the UI says so when you select it.

### Not Tailscale's Android AAR

`tailscale/tailscale-android` builds a gomobile AAR and it is the right artifact *for that
app*. Using it here would be a mistake:

- **It exposes no `Dial` and no `GetIP` to Java.** Control and introspection go through a local
  HTTP API (`/localapi/v0/{prefs,start,login-interactive,status,…}`) marshalled over JNI.
- **Using it commits you to the system VPN slot.** Its Kotlin side must implement
  `libtailscale.IPNService` (a `VpnService`) and `libtailscale.AppContext`; the Go side calls
  back into them via `Libtailscale.requestVPN(service)`. You would be building device-wide
  routing, split-tunnel policy and a foreground service in order to open one socket.
- **There is no prebuilt AAR to download anyway.** Checked: Maven Central returns zero results
  for `libtailscale`; JitPack's builds of `com.github.tailscale/tailscale-android` are all
  errors; the GitHub releases ship only APKs.

Note also that `tailscale/libtailscale` — the repo whose name suggests otherwise — is a **C
library**, built with `go build -buildmode=c-archive|c-shared`. Its Makefile has no Android
target and no gomobile, and its `tailscale_dial` returns a pipe file descriptor, which
gomobile could not express even if it were intended to.

### So: bind `tsnet` yourself

`tailnet/` is a small Go package that wraps `tailscale.com/tsnet` and is compiled by
`gomobile bind` into `app/libs/tailnet.aar`.

This is the same approach as
[`netbirdio/android-client`](https://github.com/netbirdio/android-client)
(`CGO_ENABLED=0 gomobile bind -androidapi 26 -javapkg=io.netbird.gomobile`) and the same
*concept* as [`tailscale/tailvisor`](https://github.com/tailscale/tailvisor) (tsnet without a
system VPN), applied to one destination instead of a fleet.

---

## The binding rules

gomobile cannot express a Go interface or a `net.Conn` in Java, and multi-value returns bind
differently across gomobile revisions. Both traps are dodged by one rule:

> **Every exported function takes and returns only `String`, `Int`, `Bool` or `error`.**

Structured data crosses as JSON. Byte payloads are base64 inside that JSON. The generated Java
surface is therefore stable, reviewable, and independent of the binding generator's mood.

```go
func Start(stateDir, authKey, controlURL, hostname string,
           ephemeral, forceLogin bool, timeoutMs int) error
func Stop() error
func ClearState(stateDir string) error
func Status() string  // JSON
func Logs() string    // JSON array, already redacted
func Probe(targetAddr string, timeoutMs int) string
func Fetch(targetURL, method, headersJSON, bodyBase64 string,
           timeoutMs, maxBodyBytes int) string
func Redact(value string) string
func Version() string  // the Tailscale library version this bridge was built against
```

A Go `error` return is mapped by gomobile to a thrown Java exception, so the Kotlin side wraps
every call. One consequence worth knowing: **`Probe` does not throw on a failed connection.**
A refused connection is a normal, informative answer for a test button, so it comes back as
`{"ok":false,"error":"connection refused"}`.

### Why HTTP lives in Go

The socket has to be created by the tailnet's network stack. Go cannot hand that socket to
Java. The alternatives were a SOCKS5 proxy on loopback (which `tsnet.Server.Loopback()` does
provide) or a request-shaped Go API returning bytes. This project chose the latter: it removes
a listening socket and a proxy credential from the process. The cost is that HTTP exists in
two implementations, which is why `ConnectivityProvider.fetch()` takes a request object
rather than exposing a client.

---

## The traps

### 1. A stale node identity silently overrides a new auth key

`tsnet` ignores the `AuthKey` you pass when usable state already exists in its store, **unless
`TSNET_FORCE_LOGIN=1`** is set.

This produces the single most confusing symptom in the project: *"I pasted a new key and
nothing changed."*

Handled in two places:

- `TailnetByok`'s **Forget key and reset node** deletes the ciphertext *and* calls
  `ClearState`, which removes the node state directory. Changing the key or the control server
  must clear state; there is no correct way to keep it.
- The Go bridge takes an explicit `forceLogin` parameter which sets or clears
  `TSNET_FORCE_LOGIN` before `Up()`, rather than leaving it to ambient environment.

### 2. A bare MagicDNS short name is not guaranteed to resolve

`tsnet` resolves through its own netstack. FQDNs such as `host.tailnet.ts.net` work. A bare
name like `phone` depends on the search domain having been pushed into that netstack, which
is not guaranteed for an embedded node.

The app therefore **accepts a bare name with a visible warning** rather than rejecting it —
some tailnets do push the domain — and the troubleshooting page recommends the `100.x`
address first, then the FQDN, then the short name.

### 3. Persistent nodes and Android version

A userspace `tsnet` node that wants to keep its node identity across restarts needs somewhere
to keep the node key. On Android below 12 this is not reliably available, and the honest
configuration there is `Ephemeral: true`. The app defaults `ephemeral` to true for this
reason, and the setting is exposed rather than hidden.

### 4. Android's network security config cannot express a CIDR

Cleartext HTTP is permitted for tailnet destinations, since WireGuard already encrypts that
path. But `<domain-config>` matches hostnames, not ranges, so `100.64.0.0/10` cannot be written
there. The ranges are enforced **in code**, in `TailnetAddressPolicy`, which is the only thing
allowed to hand a destination to a socket factory. That is a stronger place for the check
anyway: it runs before every dial, not merely at the platform's TLS layer.

### 5. gomobile needs gobind from the same revision

Installing `gomobile` and `gobind` from different `golang.org/x/mobile` revisions produces
bindings that describe an ABI the runtime library does not implement. The failure appears at
the **first call**, not at build time. `scripts/build-bridge.mjs` installs both from one
`--gomobile-version` and prints the resolved revisions so you can pin them afterwards.

### 6. `-javapkg` and the Kotlin import must agree

`gomobile bind -javapkg=io.github.zero6689.tailnetbyok.gomobile` yields the class
`io.github.zero6689.tailnetbyok.gomobile.Mobile`. That is what
`TsnetConnectivityProvider.kt` imports. Change one without the other and you get a hundred
unresolved references with no mention of the real cause. `build-bridge.mjs` extracts
`classes.jar` from the produced AAR and asserts the facade is present by name.

### 7. ABI mismatches look like a missing feature

An arm64-only AAR installs fine on an x86_64 emulator and then throws
`UnsatisfiedLinkError` on first use. `TsnetProviderInstaller` checks
`Build.SUPPORTED_ABIS` before registering, so the failure becomes a clear "no native library
for this device ABI" message in the UI.

---

## Traps found by actually building it

Everything in this section was hit, diagnosed and fixed while producing the first real AAR and
APK. They are listed separately because they are the ones that cost time — each is a failure
where the error message points somewhere other than the cause.

### 8. `-javapkg` is a prefix, not a package

```
-javapkg=io.github.zero6689.tailnetbyok   binding Go package `mobile`
        -> io.github.zero6689.tailnetbyok.mobile.Mobile
```

gomobile **appends the Go package name**. Setting `-javapkg` to the full package you want
produces `…tailnetbyok.gomobile.mobile.Mobile`, which is not what anyone writes down.

The failure mode is nasty: the AAR builds perfectly, and the Kotlin side reports roughly a
hundred unresolved references, none of which mention the real problem.
`scripts/build-bridge.mjs` extracts `classes.jar` from the produced AAR and asserts the facade
exists **by fully-qualified name**, which is what caught this.

### 9. gomobile binds Go `int` as Java `long`

Every integer parameter crossing the bridge is 64-bit. `Mobile.probe(addr, timeoutMs)` takes a
`long`, not an `int`, even though the Go signature says `timeoutMs int`.

```kotlin
Mobile.probe(target.dialString(), timeoutMs.toLong())
```

The Go source looks correct and the Kotlin looks correct; only the compiler knows.

### 10. gomobile copies your Go doc comments into Java, and javac uses the platform charset

The generated `Mobile.java` contains each exported function's doc comment verbatim. On a
Chinese Windows, `javac` reads it as **GBK** unless told otherwise, so a single non-ASCII
character — an em dash, a curly quote, an ellipsis, or any CJK text — fails the bind with
`unmappable character` errors in a file nobody wrote by hand, *after* the whole Go tree has
already compiled.

Two mitigations, both applied:

- `tailnet/tailnet.go` is ASCII-only, deliberately;
- `scripts/build-bridge.mjs` sets `JDK_JAVAC_OPTIONS=-encoding UTF-8`, so a contributor who
  writes a comment in their own language does not break the build.

### 11. `gomobile bind` must run from inside the Go module

gomobile resolves "the current module" by looking for a `go.mod` in the working directory or a
parent. Invoking it from the repository root fails with:

```
gomobile bind requires golang.org/x/mobile in the current module,
but it is not in the module dependency graph.
```

…even when the dependency *is* declared. The message points at the dependency; the problem is
the working directory. The bind runs with `cwd: tailnet/` and binds `.`.

### 12. gomobile needs a `tool` directive, not just an import

Since Go 1.24 a module declares the command-line tools it needs:

```
go get -tool golang.org/x/mobile/cmd/gobind
```

Without it, `gomobile bind` refuses even though `golang.org/x/mobile` appears in `go.mod` as a
regular requirement. The directive is committed, so this is a no-op on a warm checkout, and
`build-bridge.mjs` runs the command idempotently.

### 13. A JDK is required, not a JRE

`gomobile bind` shells out to `javac` to compile the bindings it just generated — after the
expensive Go compilation is already done. `build-bridge.mjs` checks for `javac` up front, via
`JAVA_HOME` or a `jdk*` directory under `.toolchain/`, so a missing JDK is a one-line problem
instead of a five-minute one.

### 14. `proxy.golang.org` is unreachable from some networks

Most notably mainland China, where the connection simply times out. The failure is
indistinguishable from "the internet is down" unless you already know, and it lands on the very
first command a new contributor runs.

`build-bridge.mjs` probes `proxy.golang.org`, then `goproxy.cn`, then `goproxy.io`, and uses
whichever answers. A Go module proxy is a content-addressed cache — every module it returns is
verified against the checksum database — so the mirror is a transport, not a change of trust.
Setting `GOPROXY` yourself overrides the probing entirely.

---

## Measured sizes

From the first real build: Go 1.27.1, `tailscale.com` v1.102.4, NDK r26d, all four ABIs.

| Artifact | Size |
|---|---|
| `tailnet.aar` | 59.7 MB |
| Debug APK, four ABIs | 183.0 MB |
| Release APK (R8, four ABIs, unsigned) | 162.6 MB |
| `libgojni.so` — `arm64-v8a` | 40.9 MB |
| `libgojni.so` — `armeabi-v7a` | 38.8 MB |
| `libgojni.so` — `x86` | 39.2 MB |
| `libgojni.so` — `x86_64` | 42.4 MB |
| Four ABIs of `libgojni.so` combined | **161.2 MB** |

Read that last row against the release APK size above it. The embedded node is not a
significant part of this app's download — **it is essentially all of it**. 161.2 MB of a
162.6 MB APK is Go runtime and linked `tsnet`.

So the honest guidance is: **ship one ABI.** Every Android device sold in the last five years
is `arm64-v8a`, and restricting to it turns a 162 MB download into roughly 46 MB:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

`abiFilters` is deliberately *not* set in this skeleton, because a single-ABI build silently
refuses to install on an x86_64 emulator — and "the app will not install" is a worse first-run
experience than a large APK. Decide it per release, not per clone.

A published release should also use an App Bundle: Play then delivers only the ABI each device
needs, which gets the same 46 MB without excluding anyone. That is why
`release.yml` builds an APK for direct installation and you should add `bundleRelease` before
publishing to Play.

---

## headscale

Point the app's **Control server** field at your headscale base URL, and create the pre-auth
key there rather than with Tailscale:

```bash
headscale preauthkeys create --user <USER_ID>
headscale preauthkeys create --tags tag:phone --expiration 1h
```

`tsnet` maps this to its `ControlURL` field (or the `TS_CONTROL_URL` environment variable).

Two things to be careful about:

- **An empty `ControlURL` means Tailscale's hosted control plane.** Passing the hosted URL
  *explicitly* is not equivalent — `tsnet` treats a non-empty value as a custom server. The
  app normalises this in `AppConfig.controlUrlForNode()` so no call site has to remember.
- **Keys are not interchangeable.** A Tailscale-issued `tskey-auth-…` will not work against
  headscale, and a headscale key will not work against Tailscale. The server and the key must
  belong to the same control plane.

---

## Building the bridge

```bash
node scripts/fetch-toolchain.mjs go ndk
node scripts/build-bridge.mjs
node scripts/build-bridge.mjs --check   # report the toolchain, build nothing
```

`scripts/build-tailnet-aar.sh` and `.ps1` are thin wrappers around the same Node script; they
exist because a `.sh` name is what people type.

Requirements: Go 1.23+, the Android NDK (r26d is what CI pins), and gomobile. See
[BUILD.md](BUILD.md).

---

## References

Primary sources, so you can check any claim above:

- `tsnet` package documentation — <https://pkg.go.dev/tailscale.com/tsnet>
- `tailscale/libtailscale` (the C library) — <https://github.com/tailscale/libtailscale>
- `tailscale/tailscale-android` — <https://github.com/tailscale/tailscale-android>
  (in particular `libtailscale/interfaces.go` and `android/src/main/AndroidManifest.xml`)
- `tailscale/tailvisor` — <https://github.com/tailscale/tailvisor>
- `netbirdio/android-client` — <https://github.com/netbirdio/android-client>
- Tailscale split tunnelling on Android —
  <https://tailscale.com/docs/features/client/android-app-split-tunneling>
- MagicDNS — <https://tailscale.com/docs/features/magicdns>
- headscale registration —
  <https://github.com/juanfont/headscale/blob/main/docs/ref/registration.md>
- gomobile — <https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile>
