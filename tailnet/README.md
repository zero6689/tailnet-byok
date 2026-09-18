# Tailnet bridge

The Go side of `tailnet-byok`: a [tsnet](https://pkg.go.dev/tailscale.com/tsnet) node wrapped
for [`gomobile bind`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile), which compiles it
into `app/libs/tailnet.aar`.

Read [docs/TSNET.md](../docs/TSNET.md) first: it explains why this exists instead of reusing
Tailscale's own Android AAR, and lists the traps.

---

## Building

From the repository root:

```bash
node scripts/fetch-toolchain.mjs go ndk
node scripts/build-bridge.mjs
```

Or check what the toolchain looks like without building anything:

```bash
node scripts/build-bridge.mjs --check
```

The binding command, for reference:

```bash
CGO_ENABLED=0 gomobile bind \
  -target=android \
  -androidapi 26 \
  -javapkg=io.github.zero6689.tailnetbyok \
  -o app/libs/tailnet.aar \
  ./tailnet
```

Run it **from inside `tailnet/`**, binding `.` — gomobile looks for a
`go.mod` in the working directory or a parent, and the repository root has none.

Three of those flags are load-bearing:

- **`-androidapi 26`** must equal the app's `minSdk`. Binding for a lower API promises support
  the app does not offer.
- **`-javapkg`** is a **prefix**, not a replacement. gomobile appends the Go package name, so
  binding the Go package `mobile` under this value yields
  `io.github.zero6689.tailnetbyok.mobile.Mobile` — which is what
  `app/src/tsnet/kotlin/.../TsnetConnectivityProvider.kt` imports. Change one without the
  other and you get a hundred unresolved references with no mention of the real cause.
- **`CGO_ENABLED=0`** because `tsnet` runs on a userspace network stack — there is no tun device
  to open, so nothing needs cgo, and leaving it on would make the build depend on the host
  toolchain.

## Testing

```bash
cd tailnet
go vet ./...
go build ./...
```

There are no Go tests yet. The package is a thin translation layer over `tsnet`, and its
interesting behaviour is the redaction and the error mapping, which are exercised from the
Kotlin side. Adding tests for `redact()` and `firstHTTPURL()` would be a genuinely welcome
first contribution — they are pure functions with real edge cases, and `redact()` is on the
path that protects the credential.

## The binding rules

> **Every exported function takes and returns only `String`, `Int`, `Bool` or `error`.**

gomobile cannot express a Go interface or a `net.Conn` in Java, and multi-value returns bind
differently across gomobile revisions. Structured data crosses as JSON; byte payloads are
base64 inside that JSON. Keeping the surface primitive means the generated Java is stable,
reviewable, and independent of the binding generator's mood.

A Go `error` return is mapped by gomobile to a thrown Java exception.

## Exported surface

```go
func Start(stateDir, authKey, controlURL, hostname string,
           ephemeral, forceLogin bool, timeoutMs int) error
func Stop() error
func ClearState(stateDir string) error
func Status() string   // JSON
func Logs() string     // JSON array of already-redacted lines
func Probe(targetAddr string, timeoutMs int) string
func Fetch(targetURL, method, headersJSON, bodyBase64 string,
           timeoutMs, maxBodyBytes int) string
func Redact(value string) string
func Version() string  // the Tailscale library version this bridge was built against
```

`doc.go` restates this in Go syntax, guarded by `//go:build ignore` so it is never compiled.

## Design notes

**A single global node.** A phone has one tailnet identity in this app. Making the lifetime
explicit (`Start`/`Stop` are idempotent) removes a class of "two nodes, two state directories,
two sets of keys" bugs that a per-call `Server` would invite.

**Logs go to a bounded in-memory ring, never to disk.** `Logf` and `UserLogf` both feed it, and
every line is redacted before it is stored. `UserLogf` is also where the interactive login URL
arrives, which is the only way to obtain it — `Status()` surfaces it as `loginURL`.

**`Probe` does not throw on a failed connection.** A refused connection is a normal,
informative answer for a test button, so it is reported in the returned document rather than as
an exception.

**`forceLogin` is a parameter, not ambient state.** `tsnet` ignores the supplied auth key when
usable state exists on disk, unless `TSNET_FORCE_LOGIN=1` is set. That produces the most
confusing symptom in the project ("I pasted a new key and nothing changed"), so the switch is
wired to an explicit argument and the Kotlin side clears node state when the credential
changes.

**No dependency pin in `go.mod`.** Dependencies are resolved by `go mod tidy`, which every build
path runs first. `tsnet` pins its own graph tightly and moves fast, and a hand-written `require`
in a generated skeleton is a stale pin waiting to break someone's first build. To make a build
reproducible, run `go mod tidy` once and commit the resulting `go.mod` and `go.sum` — CI logs
the resolved `tailscale.com` version so you know what you tested against.
