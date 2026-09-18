# Provenance

Where the code in this repository comes from, and how that was checked.

This file exists because "is this a fork?" is the first question a licence reviewer,
a packager or a cautious user asks, and the answer should not require reading the
whole tree. It states what is original, what is merely linked, and what was read
for design inspiration without being copied.

## What is original here

Everything in this repository was written for this project:

- `app/` — the Android app: Kotlin, Jetpack Compose, and a small amount of
  platform glue. 27 Kotlin/Java sources under `io.github.zero6689.tailnetbyok`.
- `tailnet/` — the Go bridge (module `github.com/zero6689/tailnet-byok/tailnet`,
  package `mobile`): a `tsnet` node, a loopback reverse proxy, and the security
  configuration behind them.
- `scripts/`, `site/`, `docs/`, `branding/` — build, documentation and artwork.

There is no vendored third-party source in this repository. Nothing here is a
copy of another project's file.

## What is linked, not copied

The native bridge statically links the Go module graph — `tailscale.com` (the
`tsnet` package, BSD-3-Clause), `gvisor.dev/gvisor` (Apache-2.0),
`github.com/tailscale/wireguard-go` (MIT) and about two dozen more. Those are
dependencies resolved by `go mod tidy`, not source in this tree, and their
licences and copyright notices are reproduced in
[`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md).

Android and Kotlin dependencies (AndroidX, Compose, the Kotlin standard library)
come from the Gradle graph and are inventoried separately.

## What was read, and not taken

Two upstream projects solve adjacent problems and were read while designing this
one. Neither contributed code:

- [`tailscale/tailvisor`](https://github.com/tailscale/tailvisor) — `tsnet` without
  a system VPN. The same shape, on a desktop.
- [`netbirdio/android-client`](https://github.com/netbirdio/android-client) — a
  gomobile-bound tunnel inside a Kotlin app: the closest prior art for the
  build arrangement.
- [`tailscale/tailscale-android`](https://github.com/tailscale/tailscale-android) —
  the official client. It is the thing this project deliberately is not: it owns
  the device-wide VPN slot and exposes no API for handing it an auth key, which is
  the whole reason a second implementation exists. See
  [`docs/TSNET.md`](TSNET.md).

## How that was checked

Structural evidence, all of it reproducible from a checkout:

| Check | Result |
| --- | --- |
| Package names are this project's own (`io.github.zero6689.tailnetbyok.*`, `package mobile`) | no upstream package name appears as a namespace |
| `Copyright` / `SPDX-License-Identifier` headers in source files | zero — no file carries an upstream header, and none claims to be someone else's work |
| References to the official app's internals (`com.tailscale.ipn`, `IPNService`, `QuickToggleService`) | three hits, all comments explaining why that architecture is *not* used |
| `libtailscale` (the official AAR) | absent |
| `android.net.VpnService` implementation | absent; the manifest's only mention of `BIND_VPN_SERVICE` is a comment explaining that it is not requested |

**The limit of this check.** Structural evidence shows that no upstream package,
identifier or header was carried over; it is not a line-by-line diff against every
file of every project that might have been consulted, and no such diff can prove a
negative. If you believe a specific file here is derived from something without
attribution, open an issue naming the file and the original — it will be credited
properly or removed.

## Names and marks

The project's mark is the taiji (see [`branding/README.md`](../branding/README.md)).
The maintainer's personal build of the same tool uses a single blue whale, which is
why some screenshots differ. Neither is a claim to anyone else's trademark; the
disclaimers live in [`README.md`](../README.md) and [`PRIVACY.md`](../PRIVACY.md).
