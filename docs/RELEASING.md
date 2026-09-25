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
   (`-PdefaultTarget=…`, `-PdefaultUpdateUrl=…`) is for
   one server's own users. It is **never** the release asset: it names a real
   machine, and the public build's address defaults are empty on purpose, with CI
   asserting that they stay empty. `-PdefaultProvisioningUrl=…` is the exception in both
   directions: the public build gives it a value (the project's own public page,
   because a first-run card with no way to reach any page is a missing feature, not a
   neutral default — 0.4.0), and a deployment overrides it with its own.
3. **The mirror on the docs site.** `docs-pages.yml` copies the newest release asset
   to `site/dist/byok/tailnet-byok-arm64.apk` (plus a `.sha256`) every time it
   deploys, so `https://<site>/byok/tailnet-byok-arm64.apk` serves the same bytes
   from GitHub Pages. This exists because the release asset and the docs site are
   served by different hosts — the asset by `release-assets.githubusercontent.com`
   (Azure Blob), the site by Pages (Fastly) — and on some networks the asset host is
   unusable while the page loads fine. That is the symptom it was added for: the
   provisioning page opens on the phone, the 45 MB APK behind it crawls.
   It is an **alternative host, not a proven speedup**. Measured 2026-09-25 with
   nothing else in flight, 4 MB ranges x3: mirror 2.6 / 13.3 / 14.0 MB/s against the
   release asset's 2.9 / 7.7 / 7.7. An earlier 0.03 MB/s reading was taken while a
   134 MB Gradle download saturated the same link, and is not a baseline.
   The workflow therefore also runs on `release: published`: a mirror that keeps
   serving the *previous* release after a new one is published is worse than no
   mirror, and nothing else would refresh it when a release changes no file under
   `site/`.
   Nothing about the mirror is rebuilt or re-signed — it is the release asset, byte
   for byte. That is checked rather than assumed: the deployed file was downloaded
   whole and hashed (2026-09-25: 45,921,396 B, `70d7cd1d…6706f` — the release
   digest), and the checksum published beside it is the one to verify any other copy
   against.

### The release asset must not name the build machine

A Go binary records the paths it was compiled from, and `libgojni.so` is a Go
binary. `build-bridge.mjs` passes `GOFLAGS=-trimpath`, which rewrites source-file
paths to module-relative form — but it does **not** rewrite the C toolchain's
include paths or the main module's directory in the build info. Those are absolute
paths taken from wherever the NDK and the module happen to live.

Measured on 2026-09-20: an AAR bound on the maintainer's machine carried
`<checkout>\…` 65 times (module directory + NDK include paths),
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

Then check the two hand-maintained lines on the download page — `site/provisioning.html`
states which version and how many megabytes the "Download the APK" button serves
(`id="apk-version"` and `id="apk-size"`, once per language), and nothing updates them
automatically. A page that says `v0.2.9` while the button serves `v0.3.2` is the kind of
small lie that costs a user a bug report, so bump them in the same commit as the version,
then `node site/build.mjs`. The size is the asset's size on the release, not the local
deployment build (they differ: the public one is minified-off but single-ABI with an empty
configuration, the deployment build has a target baked in). The gap is wider than the baked
target accounts for — for v0.3.9 the published asset was 43.8 MiB while the deployment build of
the same commit measured 54.2 MiB, because the locally bound `libgojni.so` carried about 11 MB
more DWARF than the library the workflow builds from `scripts/build-bridge.mjs`. Take the number
from the published asset, never from a local build. (v0.4.0 is 35.1 MiB: the workflow sets
`ANDROID_NDK_HOME`, so AGP strips the native library's `.debug_*`, `.symtab` and `.strtab` — about
9 MB, with the code sections and the dynamic symbols unchanged. A local build keeps them.)

`README.md` is the other hand-maintained place a version number lives, and it is the one a
stranger reads first: its `## Status` line names the version and `versionCode`, and its size
table names the shipped artefact and how large it is. It read `0.2.9` for eight releases
because no step in this file said to look at it, so now one does.

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

## Releases are pruned to the latest

The Releases page carries one release: the newest one a user can actually run. Earlier
builds are not kept as downloads, because two entries can share a label and a
`versionName` and be indistinguishable on a phone — that is not hypothetical, it is how a
fresh handset came to run a build with the operator's own tailnet address already in the
target field. One consequence to respect when editing: a `CHANGELOG.md` link for a
withdrawn version points at the releases page rather than at a tag URL, because the tag is
gone with the release.

Withdrawing one is `gh release delete <tag> --cleanup-tag`, and only *after* the asset has
been archived and its digest checked — `.cache/byok-repo-cleanup/` holds the v0.2.9 asset
and its release metadata for that reason. Deleting a tag ref is also what makes GitHub
revert the release it names to a draft, so delete the release itself, then confirm that
`/releases/latest` still resolves and that the surviving release is not a draft.

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
