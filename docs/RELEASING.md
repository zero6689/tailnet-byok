# Releasing

What a release of this project is, what it has to record, and what must be true before
it is tagged.

The short version: **a release nobody can pin down is not a release.** A user who
reports a bug has to be able to say which build they have, and anybody has to be able
to rebuild that build from the tag and get the same native library. Everything below
exists to make those two sentences true.

## What a release consists of

| Artefact | Where it comes from |
|---|---|
| A git tag | the commit the rest was built from |
| The native bridge | `node scripts/build-bridge.mjs`, producing `app/libs/tailnet.aar` |
| A signed APK | `./gradlew assembleRelease -PwithTsnet=true` with the release keystore |
| A release record | the table below, in the release notes |

The AAR is **not** committed (it is tens of megabytes and cannot be reviewed in a diff).
The record is what stands in for it.

## The signing identity

This project has its **own** key, separate from the DSH shell's. It is not in the
repository and never will be:

| | |
|---|---|
| Keystore | `.cache/keys/tailnet-byok-release.keystore` (PKCS12, RSA 4096, alias `tailnetbyok`), outside the tree |
| Certificate SHA-256 | `4D:7F:05:E2:4A:FD:70:91:79:63:AD:21:3A:B3:82:B6:B0:AC:91:3F:00:93:3D:1F:52:6F:23:E3:B5:81:97:90` |
| How the build reads it | `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`, or a git-ignored `keystore.properties` — see [`BUILD.md`](BUILD.md) |
| Where the passwords live | `.cache/keys/tailnet-byok-keystore.properties`, outside the tree, 0600-equivalent |

The fingerprint above is public on purpose: it is how a user checks that the next
release will install over the one they have (see the rule below). The keystore is
not: anyone holding it can sign an update that Android will accept.

## Publishing what users actually download

Two things are published, and they are different files:

1. **The release asset.** Every release attaches the APK under the **same asset
   name**, `tailnet-byok-arm64.apk`, so that
   `https://github.com/zero6689/tailnet-byok/releases/latest/download/tailnet-byok-arm64.apk`
   is a permanent link. `site/provisioning.html` links exactly there; renaming the
   asset breaks the download button on the page, silently, for everyone.
2. **The deployment build.** A private build with a target pre-filled
   (`-PdefaultTarget=…`, `-PdefaultProvisioningUrl=…`, `-PdefaultUpdateUrl=…`) is for
   one server's own users. It is **never** the release asset: it names a real
   machine, and the public build's defaults are empty on purpose, with CI asserting
   that they stay empty.

### The release asset must not name the build machine

A Go binary records the paths it was compiled from, and `libgojni.so` is a Go
binary. `build-bridge.mjs` passes `GOFLAGS=-trimpath`, which rewrites source-file
paths to module-relative form — but it does **not** rewrite the C toolchain's
include paths or the main module's directory in the build info. Those are absolute
paths taken from wherever the NDK and the module happen to live.

Measured on 2026-09-20: an AAR bound on the maintainer's machine carried
`L:\CodexProjects\tailnet-byok\…` 65 times (module directory + NDK include paths),
and an older one carried the Windows user name as well (`C:/Users/<name>/AppData/…`,
from `gomobile`'s temporary work directory). A CI runner's equivalents read
`/home/runner/…` and `/usr/local/lib/android/…`, which name nobody.

So: **build the native library where the paths are neutral, then check.** The
`Tailnet bridge` workflow already does the first half and uploads the AAR as an
artefact; the check is a scan of the APK before it is published:

```powershell
# No address, user name, host name or build-machine path may appear anywhere in the APK.
node .cache/scan-apk-privacy.mjs app/build/outputs/apk/release/app-release.apk
```

`CLEAN` is the only acceptable result for a public artefact. A hit inside
`libgojni.so` means the AAR came from a machine whose toolchain paths are not
neutral; rebuild it in CI and rebuild the APK with that AAR.

### Why the first public release is not minified

`assembleRelease` shrinks and obfuscates by default, and that is the right default.
It is also a bet on the keep rules being complete, and the only builds this project
has ever **run** are the ones it could install. For the first public release the bet
was not worth taking: `-PnoMinify=true` turns R8 and resource shrinking off, which
costs about 10 MB and makes the shipped code the same code path as the verified
debug build. Revisit once a minified build has been run on a device.

## The record

Every release notes file carries these, in this order. They are the answers to "what is
actually inside this binary".

| Field | How to obtain it |
|---|---|
| Tag and commit | `git describe --tags --exact-match`, `git rev-parse HEAD` |
| Go toolchain | `go version` from the toolchain the AAR was built with |
| `golang.org/x/mobile` revision | `node scripts/build-bridge.mjs --check` prints it |
| `tailscale.com` version | `cd tailnet && go list -m tailscale.com` |
| AAR SHA-256 | `sha256sum app/libs/tailnet.aar` |
| APK SHA-256 | `sha256sum app/build/outputs/apk/release/*.apk` |
| Signing certificate | `apksigner verify --print-certs <apk>`, SHA-256 fingerprint |
| Third-party notices | link to `THIRD-PARTY-NOTICES.md` at that tag |

The certificate fingerprint is not decoration: Android refuses to install an update
signed by a different key, so publishing it is how a user checks that the next release
will actually install over the last one.

## Before tagging

```bash
node scripts/third-party-licenses.mjs --check   # licences still match the graph
node scripts/build-bridge.mjs                   # refuses to build if the pin moved
cd tailnet && go test ./... && cd ..            # the bridge's own tests
./gradlew testDebugUnitTest lintDebug           # the app's tests and lint
./gradlew assembleRelease -PwithTsnet=true      # the artefact the tag will name
```

Then, and only then, `git tag -a vX.Y.Z` and push the tag.

Better, for anything that will be downloaded by someone else: take the AAR from the
`Tailnet bridge` workflow (Actions → the run → `tailnet-aar-<sha>`) rather than from
this machine, so the native library carries neutral paths, then build the APK with
that AAR and run the privacy scan above before attaching anything to a release.

## Rules that exist because the alternative is worse

- **Never tag a dirty tree.** `git status --porcelain` is empty before the tag, or the
  record above describes a commit plus some uncommitted edits.
- **Never build from a moved pin.** `build-bridge.mjs` refuses to continue if `go mod
  tidy` changes `go.mod` or `go.sum`, and restores them. If the pin genuinely has to
  move, `--write-mod` keeps it so that it lands as a reviewed commit first.
- **Never re-sign with a different key.** A new key cannot update an installed app; it
  can only be installed after an uninstall, which for this app means the user's stored
  credential and node identity are deleted too. If the key is ever lost, that is a
  documented, user-visible event, not a routine release step.
- **Never ship an AAR whose notices are stale.** The `licences` CI job fails when
  `THIRD-PARTY-NOTICES.md` or `licenses/` no longer match `go list -deps`.

## Reproducibility, honestly

The pin, the toolchain versions and the record above make a rebuild *possible to
attempt* and *possible to compare*. They are not a claim that two builds produce
bit-identical bytes: `gomobile bind` compiles through a temporary work directory, the Go
toolchain embeds its own build metadata, and the APK carries a signature whose padding
differs between runs. The commitment here is narrower and checkable: **the same commit,
the same recorded toolchain versions, and a native library whose embedded version
stamp identifies the source it came from** (`tailnet/tailnet.go` reports the
`longStamp` injected by `build-bridge.mjs`).

That narrower claim is itself checkable, so it is checked rather than asserted:
[`.github/workflows/reproducible-build.yml`](../.github/workflows/reproducible-build.yml)
is a manual workflow that builds the bridge twice on one runner with the pinned
toolchain and compares the **native library** byte for byte — not the `.aar` container,
whose zip metadata is expected to differ — and fails with an explanation if they
differ. Run it against a release commit before making claims about that release;
if it reports a difference, fix the cause or narrow this section.
