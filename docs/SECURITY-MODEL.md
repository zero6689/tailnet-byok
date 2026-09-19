# Security model

The rendered version is at <https://zero6689.github.io/tailnet-byok/security.html>. This file is
the GitHub-browsable copy, and it is normative: if the code and this document disagree, that is
a bug in one of them.

---

## What the encryption actually buys

The auth key is encrypted with a randomly generated AES-256-GCM key that is created inside the
Android Keystore and is non-exportable: the app holds a handle, not the bytes. On a device with
a TEE or StrongBox, the key material never enters the app's address space.

| Threat | Protected? | By what |
|---|---|---|
| Data directory copied — rooted file copy, forensic image, stray backup | **Yes** | Only ciphertext on disk; the decrypting key is hardware-bound |
| Ciphertext reaches a cloud backup | **Yes** | `allowBackup="false"` plus backup rules excluding every domain |
| Read from logcat | **Yes** | One scrubbed logging entry point; release builds strip `v`/`d`/`i`; CI fails on a bare `android.util.Log` |
| Decrypted while the device is locked | **Yes** | `setUnlockedDeviceRequired(true)` on API 28+ |
| App used to reach arbitrary internet hosts | **Yes** | `TailnetAddressPolicy` before every dial, plus the library's own target allowlist on both of its dial paths (`StartProxy`, `Fetch`, redirect hops included) |
| Credential survives "forget my key" | **Yes** | That action destroys the Keystore key too, so pre-existing ciphertext copies become unreadable |
| Attacker runs code as this app, device unlocked | **No** | Nothing can; the app must be able to read the key |
| Memory dump of the live process | **No** | See the heap caveat |
| Malicious control plane | **No** | Inherent to the protocol |
| Traffic analysis | **No** | The app does not hide that a Tailscale node is running |

## The heap caveat

Kotlin strings are immutable and cannot be zeroed; the Go bridge takes a string. The plaintext
key therefore exists in the process heap between decryption and the node coming up.

Mitigations, which are real but partial:

- never persisted in plaintext, so it does not survive a process restart;
- never logged, so it does not reach logcat or a bug report;
- dropped from UI state as soon as it is saved;
- `TailnetCredentials.toString()` prints `present`/`absent`, so an accidental `println` or a
  debugger pretty-print cannot leak it;
- intermediate `ByteArray`s handed to the vault are wiped after use.

One more credential lives on that heap, deliberately. When the target host sits behind an
authenticating proxy, the embedded node answers a `303` by storing the session cookie it was handed
and replaying it on the redirect (`tailnet/tailnet.go`: the `httpJar` created by `Start`, used by
`Fetch`). It is memory only — never written to disk, never logged — and it expires with the node
session, because `Stop` drops the jar. It is also host-scoped, so a redirect to a different host
arrives without it. This mirrors the Kotlin-side `SessionCookieJar`, which holds the same cookie for
the system-network path.

What this does not fix: an attacker who can dump the heap of a running, unlocked process can
recover the key. This is true of every Android app that holds a credential, including the
official clients.

## Software-backed Keystore

Many devices, and every emulator, have no TEE. There the KeyStore key is software-protected
rather than hardware-isolated. The app reports which situation applies, on the settings screen,
rather than implying the stronger guarantee. A security indicator that is always green is not
an indicator.

## Why the address policy is a security control

`TailnetAddressPolicy` is easy to mistake for input validation. It is the boundary that stops a
typo, a pasted public hostname, or a malicious shared configuration from turning the app into a
general-purpose request forwarder that reaches arbitrary internet hosts through the user's node.

Allowed: `100.64.0.0/10`, `fd7a:115c:a1e0::/48`, and named hosts (with a warning when the name
is not a `*.ts.net` FQDN).

Refused: any literal outside those ranges, including `127.0.0.1`; a pasted URL; malformed input
including out-of-range ports.

The check runs in `ConnectionTester` before a provider is asked to dial, and `TargetAddress` —
the type providers accept — can only be constructed with a validated host and port. It is not
possible to reach a dial without passing through the policy, which is what makes it a control
rather than a hint.

`TailnetAddressPolicy` is the product's control. It is not the library's, and the library does
not assume it ran. The two Go paths that can open a socket — the loopback reverse proxy and
`Fetch` — both consult `SecurityConfig.AllowedTargets` first, through the same predicate, before
anything else in the call can happen: `StartProxy` refuses to start against an origin that is not
listed, and `Fetch` checks its destination and then every redirect hop, so a 302 cannot walk past
the list. A policy that fails validation is refused whole rather than partially applied, which
means a rejected configuration leaves the previous one in force — or, on a first call, no policy
at all, and an empty allowlist allows nothing.

That second enforcement layer exists because the `.aar` can be embedded by any app. For this app
the Kotlin policy is the one that decides what the user may reach; the Go allowlist is what keeps
the library from becoming a general-purpose request forwarder if a caller gets that policy wrong.

## Permissions

Declared: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`.
Each with its justification next to the declaration in `AndroidManifest.xml`.

`REQUEST_INSTALL_PACKAGES` exists for the in-app updater, and it is worth being precise about what
it does and does not allow. It lets this app *ask* the system installer to install a file it has
already downloaded and verified; it does not let the app install anything silently. Android still
requires the user to have allowed installs from this app — a state the app checks and reports
rather than assumes — and the system installer always shows the package and asks for confirmation.
The file itself is staged in `cacheDir` and handed over as a one-shot read grant from an unexported
`FileProvider`, and before the intent is sent the archive's declared package name is compared with
this app's: a verified download that turns out to be a different application is not offered at all.

Not declared, and why:

| Permission | Reason |
|---|---|
| `BIND_VPN_SERVICE` | The embedded node dials one destination inside its own process. It never intercepts device-wide traffic, so it must not take the VPN slot — which would also break any other VPN the user runs. |
| `QUERY_ALL_PACKAGES` | Nothing needs to enumerate other apps. |
| `FOREGROUND_SERVICE` | No long-lived background component. |
| Location, storage, camera, contacts, phone | Unused. A future export must go through the Storage Access Framework, which grants exactly one file. |

**No camera, and therefore no in-app QR scanner.** This is a choice, not an
omission. A scanner exists to serve one setup step, and it would cost the camera
permission on every install forever; the system camera (or any QR application)
already opens a `dshbyok://` link, because that is what a custom URI scheme is for.
Setup codes are scanned by the app the user already trusts with the camera, and
this app never sees a frame.

## No IPC surface

Exactly one exported component: the launcher activity. The only content provider is the updater's
`FileProvider`, which is `exported="false"` and may serve exactly one directory under `cacheDir`
to a URI that this app put in an intent it sent. There is no exported service or receiver, so there
is nothing for another app to bind to, query, or send an intent to.

The activity does answer one kind of inbound intent besides its launcher entry: a
`dshbyok://setup` VIEW (`domain/SetupLink.kt`). That is a real door into the app, so it is worth
being precise about what can come through it.

| A configuration link can | A configuration link cannot |
|---|---|
| set the target, port, scheme, path, connection method, update source, control-plane URL, or node name — after the user confirms the merged result on screen | carry a credential, in any form: a field name that looks like one (`authkey`, `key`, `token`, `password`, `secret`, …) makes the app refuse the whole link, and the user is told why |
| point at a host the user could have typed | reach a host the address policy rejects — the merged configuration gets the same `TailnetAddressPolicy` verdict a hand-typed one does, and the Apply button is disabled when it is negative |
| be ignored: the link is parsed and *shown*, never applied | change anything by itself: applying is an explicit tap, which is what turns "a QR code silently aims the app at a stranger's server, and the next thing the user types is their key" into a visible question |
| be replayed by anyone who has it (it is not a secret) | touch the stored auth key, which no link can read, set or clear |

Links are the one place this app takes instructions from outside itself, and the
design rule is the same one the rest of the app follows: refuse what cannot be
proven, and show the user what is about to happen.

## On not setting `FLAG_SECURE`

Screenshots are permitted. The connection-test output is the main thing a user needs to share
when asking for help, and the only secret on screen sits behind a password-masked field that
requires an explicit reveal. A fork that disagrees changes one line in
`app/src/main/res/values/themes.xml`.

## Revocation is the user's job

Deleting the key from the device does **not** revoke it at the control plane. If it was copied
anywhere beforehand, it still works. Revocation happens in the tailnet admin console.

**Forget key and reset node** deletes the ciphertext, destroys the Keystore key (making prior
ciphertext copies unreadable), and clears the node state directory — which is necessary anyway,
because a stale node identity causes `tsnet` to ignore a freshly pasted key.

## Hardening checklist

- Use a tagged, single-use, short-expiry pre-auth key, and write an ACL granting the resulting
  node access to only the intended service. The app cannot enforce this.
- Prefer a `100.x` address when testing, so a failure means the network rather than the
  resolver.
- Do not store a key on a device you do not fully trust.
- Delete the key in the admin console when you stop using the device.

## Contributor rules

- No test, fixture, log line, screenshot, issue or commit message may contain a real auth key.
  Use `tskey-auth-EXAMPLE…`. CI enforces this on tracked files.
- Security-relevant changes must update this document in the same pull request.
- Never introduce a new outbound destination. If a change requires one, it needs a documented
  justification in `PRIVACY.md` and explicit reviewer sign-off.
- New logging goes through `SafeLog`. A bare `android.util.Log` call is review-blocking, and CI
  greps for it.

## Out of scope

- **A rooted or compromised device.** An attacker running code as this app can read what the app
  can read.
- **A compromised control plane.** Whoever controls it can re-key your nodes.
- **Traffic analysis.**
- **The security of the target service.** This is a client; if the target is insecure, it will
  be reached insecurely and competently.
- **Denial of service on the device.** Battery drain is a bug, not a vulnerability.
- **Physical coercion.** The masked field resists shoulder surfing and nothing more.
