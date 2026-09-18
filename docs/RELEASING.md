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
