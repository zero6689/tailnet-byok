# Dependencies

How the dependency graph is pinned, what moves it, and which parts of it are security
relevant rather than routine.

## The pin

`tailnet/go.mod` and `tailnet/go.sum` **are** the pin. They are `go mod tidy` output,
they are what the AAR is built from, and they are what `THIRD-PARTY-NOTICES.md` is
generated from. Nobody hand-edits them.

`go mod tidy` still runs on every build path, because a cold checkout needs it and the
`tool` directive only survives it. What it may not do is change anything quietly:
`scripts/build-bridge.mjs` keeps a copy of both files, and if tidy rewrites either one it
restores them and fails, saying which file moved. The deliberate path is:

```bash
node scripts/build-bridge.mjs --write-mod   # accept the new pin
```

which keeps the change so that it lands as a diff somebody reviews and commits. A build
whose graph is not the committed graph produces an AAR that matches no revision anybody
can check out, which is the thing this rule exists to prevent.

The Android side has its own pin, in `gradle/libs.versions.toml`. The same reasoning
applies to it, but nothing enforces it mechanically: a Gradle version bump is a diff in
that file, not a resolved graph.

## What is load-bearing

Three dependencies are not routine upgrades. A change in any of them is a change to the
security model, and should be reviewed as one:

| Module | Why |
|---|---|
| `tailscale.com` | the embedded node: the control protocol, the WireGuard peer, the userspace stack it runs on |
| `golang.org/x/mobile` | both the binding tool **and** the runtime the generated Java calls into; the two have to be the same revision or the bindings describe an ABI that does not exist |
| `gvisor.dev/gvisor` | the network stack everything is dialled through; a bug here is a bug in the boundary |

Everything else in the graph is a support library, and the ordinary bar applies: does the
diff justify itself, and do the tests still pass.

## `gomobile init` does not respect the pin

`gomobile init` installs gobind itself, from a hardcoded `@latest`
(`cmd/gomobile/init.go`), and `gomobile bind` then runs whichever gobind sits next to the
gomobile binary. Two consequences, both measured rather than assumed:

- **It needs a reachable module proxy**, even in a checkout where every module is already
  in the cache. This is the one step of the build that cannot run offline:
  `go install .../gobind@latest` has to ask a proxy, and answers with
  `module lookup disabled by GOPROXY=off` when it cannot.
- **Online, it can install a revision that is not the pin** — exactly the tool/runtime
  mismatch the build script's header warns about, arriving through the back door.

`scripts/build-bridge.mjs` therefore reads both binaries' own build metadata after `init`
with `go version -m` (which works offline) and reinstalls whichever one is not at the
pinned revision. When the pair already matches — the usual case, since the pin is normally
the latest revision — it reinstalls nothing.

## Moving the pin

```bash
cd tailnet
go get tailscale.com@vX.Y.Z        # or edit the requirement
cd ..
node scripts/build-bridge.mjs --write-mod   # lets tidy settle it, keeps the result
node scripts/third-party-licenses.mjs       # regenerate the notices
cd tailnet && go test ./... && cd ..
git diff                                    # read this before committing
```

What to look for in the diff:

- **A moved `tailscale.com` version** means the embedded node changed. Re-read
  `docs/TSNET.md` for traps that a new version can reintroduce, and run the bridge
  against a real target (`TAILNET_INTEGRATION_TARGET=… go test -run TestProxyAgainstTheRealServer`)
  rather than trusting the unit tests alone.
- **A new module** must bring a licence file, or the notices generator refuses to
  produce anything: a module with no licence text is not redistributable, and the
  generator fails the build rather than shipping a notices file with a hole in it.
- **A module that disappears** changes the notice, which is why the generated files are
  committed: the diff shows exactly which obligation was added or retired.

## What will react if you get it wrong

| Gate | Where | What it catches |
|---|---|---|
| Pin assertion | `scripts/build-bridge.mjs` | tidy moving `go.mod`/`go.sum` without a commit |
| `licences` job | `.github/workflows/ci.yml` | notices that no longer match `go list -deps` |
| `guardrails` job | `.github/workflows/ci.yml` | credential-shaped strings, a bare `android.util.Log`, tailnet literals |
| Bridge workflow | `.github/workflows/tailnet-bridge.yml` | the AAR does not build, or does not land in all four ABIs |
| Real-entry test | `tailnet/integration_test.go` | the proxy stops working against an actual server |

Dependabot (`.github/dependabot.yml`) opens the version PRs. A Dependabot PR that touches
`tailnet/go.mod` or `tailnet/go.sum` is **not** mergeable as-is: the notices have to be
regenerated in the same PR, and the `licences` job is what says so. That is deliberate —
it is the one dependency chore that is easy to defer forever.

## Android and Kotlin dependencies

They are a separate graph (`gradle/libs.versions.toml`, resolved by Gradle) and are **not**
covered by `scripts/third-party-licenses.mjs`, which reads the Go module graph. They are
mostly Apache-2.0 (AndroidX, Kotlin) and MIT. Producing the equivalent inventory for them
requires a Gradle-side licence report and is tracked as its own task; until it exists, the
APK's third-party inventory is incomplete, and this file says so rather than implying
otherwise.

## Vulnerabilities

Report and handle them as described in [`../SECURITY.md`](../SECURITY.md). One rule about
dependencies specifically: **a security fix that requires moving `tailscale.com` is a
release, not a patch bump in passing.** It changes what the node is, so it gets the record
in [`RELEASING.md`](RELEASING.md), a changelog entry, and a version of its own — because a
user has to be able to tell which behaviour they are running.
