# Privacy Policy

**tailnet-byok** · Last updated: 2026-09-13

> **Not affiliated with DeepSeek, and not affiliated with Tailscale Inc.** This is an
> independent, unofficial client. Its name and icon are used descriptively, to say what it
> connects to; they are trademarks of their respective owner, not this project's. Neither
> company operates this app, receives anything from it, or has reviewed it. See the README's
> [Not affiliated with DeepSeek](README.md#not-affiliated-with-deepseek) section.

## The short version

This app collects nothing. It has no server, no account system, no analytics, no
advertising, no crash reporter, and no third-party SDK that phones home. Everything
you enter stays on your device, and the only network traffic it generates is the
traffic you asked for.

If that were all you needed to read, you can stop here. The rest of this document
explains precisely what that means, because "we collect nothing" is a claim that
should be falsifiable.

---

## What the app stores, and where

| Data | Where it lives | Encrypted? | Leaves the device? |
|---|---|---|---|
| Tailscale auth key | App-private storage, as ciphertext | **Yes** — AES-256-GCM, key in the Android Keystore | Only to the control plane you configure, as part of joining your tailnet |
| Target host, port, scheme, path | App-private storage, plain | No | Only in the requests you make to it |
| Node hostname | App-private storage, plain | No | Posted to your control plane when the node registers |
| Control server URL | App-private storage, plain | No | Used to reach that server |
| Tailscale node identity | `noBackupFilesDir`, app-private | Node key, written by `tsnet` | To your control plane, as part of the protocol |
| Session cookie issued by your target host | App-private WebView cookie store | No | Back to your target host only, on requests to it |

Both stores are inside the app's private sandbox. On a stock device no other app
can read them.

### Encryption detail

The auth key is encrypted with a randomly generated AES-256-GCM key. That key is
created inside the Android Keystore and is marked non-exportable: the app holds a
handle, not the bytes. On a device with a Trusted Execution Environment or
StrongBox, the key material never enters the app's address space at all. The app
reports which of the two situations you are in, on the settings screen, rather
than implying the stronger guarantee.

`setRandomizedEncryptionRequired(true)` is set, so the platform refuses to reuse
an IV — the one mistake that actually breaks GCM.

`setUnlockedDeviceRequired(true)` is set on Android 9 and above, which makes the
stored ciphertext undecryptable while the screen is locked.

**What this protects against:** someone who obtains a copy of the app's data
directory — a rooted-device file copy, a forensic image, a backup, a bug report
that zipped the sandbox.

**What it does not protect against:** an attacker running code as this app, on
your unlocked device. No app-level encryption can. If your device is rooted and
hostile, no amount of Keystore use in any app will save a key that the app itself
must be able to read.

## What the app does not do

- **No telemetry.** No usage statistics, no feature flags, no A/B testing.
- **No analytics SDK.** Not Firebase, not Google Analytics, not Sentry, not
  Crashlytics. There is no crash reporting at all: if the app crashes, that fact
  is not transmitted anywhere.
- **No advertising identifier.** The app never touches `AdvertisingIdClient`.
- **No account.** There is nothing to sign up for, because there is no service.
- **No server.** The project operates no backend. There is no endpoint in this
  codebase that is not either your control plane or your own target host.
- **No location access.** Not declared in the manifest, not requested at runtime.
- **No contact, calendar, SMS, camera, microphone, or broad file access.** The
  DSH screen can upload a file, but only one you hand it: the page's file input
  opens the system picker through the Storage Access Framework, you choose a
  file, and the app receives a handle to that file and nothing else. It cannot
  browse your storage and it reads nothing you did not pick.
- **No enumerating your installed apps.** `QUERY_ALL_PACKAGES` is not declared.

The complete permission list is three entries, and `AndroidManifest.xml`
documents the justification for each next to the declaration.

## Network traffic, exhaustively

The app makes exactly two kinds of outbound connection:

1. **To your control plane.** Key exchange, node registration, status polling —
   the standard Tailscale protocol, performed by the embedded `tsnet` node. If
   you leave the control-server field empty this is Tailscale's hosted control
   plane; if you fill it in, it is your own headscale server.
2. **To the target host you configured.** When you press *Test connection*, and
   for every request the DSH screen makes on your behalf while it is open.

That is the entire list. There is no third destination, and the app has no
mechanism to contact one: `TailnetAddressPolicy` refuses to dial any address
outside the tailnet ranges, and no code path constructs a URL to a host the user
did not supply.

### The one listener it opens

To show you your server's own UI, the app runs a small reverse proxy inside its
own process. A web view cannot use the embedded node directly — the socket into
your tailnet is created in Go, and Android's HTTP stack has no route to it — so
the web view talks to `127.0.0.1`, and that proxy makes the real connection.

- It binds **`127.0.0.1`, on a random port**, never a routable address. Nothing
  on the Wi-Fi network your phone is on can reach it.
- It requires a **random per-session token**. Loopback is not a security
  boundary on Android: any other app on the device can connect to that port, so
  without the token the request is refused before any connection to your server
  is made. The token is generated from the platform's cryptographic random
  source, is never logged, never written to disk, and never leaves the device.
- It stops, and the port closes, when you leave the DSH screen, when you press
  *Forget key and reset node*, and when the app process ends.
- It drops the `Domain` attribute if the server sets one, so the cookie stays
  scoped to that loopback port. It is deleted when you leave the screen, and the
  login handshake simply repeats the next time you open it.
- It does not forward `X-Forwarded-For`, `Forwarded`, or any other
  client-address header. Which process on your phone made the request is the
  app's business, not your server's.

## Network security configuration

Cleartext HTTP is permitted **only** for tailnet destinations, and refused by
default everywhere else. The reasoning: traffic to a `100.x` address is encrypted
by WireGuard from the app to the destination node, so there is no point on the
path where a passive observer — including the Wi-Fi network the phone is on —
sees the plaintext. Requiring HTTPS on top of that would push users toward
self-signed certificates and "install this CA" flows, which is a strictly worse
security story.

When you point the app at a public hostname, it must use HTTPS.

## Data retention and deletion

Nothing is retained anywhere except on your device, so deletion is entirely in
your hands:

- **Forget key and reset node** in the app deletes the stored ciphertext *and*
  destroys the Keystore key, which makes any prior copy of the ciphertext
  permanently unreadable. It also clears the node's state directory.
- **Uninstalling the app** removes the sandbox. The Keystore key goes with it.
- To revoke the credential's *authority*, delete the auth key in your tailnet's
  admin console. Nothing on the device can do that for you, and this app cannot
  prevent a key you already copied elsewhere from working.

## Backups

`android:allowBackup="false"` is set, and the backup and device-transfer rules
exclude every domain. Nothing this app stores is included in a cloud backup or a
device-to-device transfer.

This is not merely caution: the Keystore key that decrypts the auth key is
hardware-bound to one device and cannot travel. The only thing a backup could
carry is an unreadable blob, so excluding it costs nothing and removes a class of
accident.

## Children

The app is a network configuration tool. It is not directed at children, and it
collects no data from anyone of any age.

## Third-party components

| Component | Purpose | Data it receives |
|---|---|---|
| [`tailscale.com/tsnet`](https://pkg.go.dev/tailscale.com/tsnet) (BSD-3-Clause) | The embedded Tailscale node | Your auth key, node hostname, and the control-plane protocol traffic — i.e. exactly what joining a tailnet requires |
| [OkHttp](https://square.github.io/okhttp/) (Apache-2.0) | HTTP for the system-network fallback provider only | Your requests to the target |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Apache-2.0) | UI, lifecycle, DataStore | None |
| Android System WebView (a platform component, not bundled) | Renders the DSH screen | The page served by your target host, and the session cookie issued for it |

No component in this list is an analytics or advertising SDK. The full resolved
dependency list for any build is in `gradle/libs.versions.toml`, and
`./gradlew :app:dependencies` prints it.

## Changes to this policy

Material changes will be recorded in [`CHANGELOG.md`](CHANGELOG.md) and in this
document's history. There is no mailing list to notify you on, because the
project holds no contact information about you — that is a consequence of the
design, not an oversight.

## Contact

For privacy questions, open an issue at
<https://github.com/zero6689/tailnet-byok/issues>. For security vulnerabilities,
use the private channel described in [`SECURITY.md`](SECURITY.md).
