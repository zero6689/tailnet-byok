# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting on this repository:
**Security → Report a vulnerability**
(<https://github.com/zero6689/tailnet-byok/security/advisories/new>).

If that is unavailable to you, open a public issue containing only the words
*"security report — please contact me"* and no technical detail, and a maintainer
will open a private channel. Never put a working exploit, a live credential, or
the name of an affected user in a public issue.

### What to include

- What the flaw is, and the impact you believe it has.
- The smallest reproduction you can manage: a code path, a sequence of taps, or
  a crafted input.
- The commit, tag, or version you tested against, and the Android version and
  device model.
- Whether you are willing to be credited in the advisory.

### What to expect

| Stage | Target |
|---|---|
| Acknowledgement of your report | 3 days |
| Initial assessment and severity | 10 days |
| Fix or a documented mitigation | 90 days, sooner for anything high-severity |
| Public advisory | Coordinated with you, after a fix ships |

This is an unpaid, single-maintainer project. Those are honest targets, not a
contract. If a report goes unanswered past the acknowledgement window, escalate
by opening the contact-only public issue described above.

We will credit you in the advisory unless you prefer otherwise.

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | Yes — fixes land here |
| Older | No |

Because the app ships with no server component, "a fix has shipped" means a new
release on the GitHub releases page. Users are responsible for updating; the app
has no auto-update mechanism and does not phone home to check for one.

## Threat model

The full, plain-language model is in
[`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md). The summary:

### In scope

| Threat | Mitigation |
|---|---|
| Credential stolen from a device image or backup | Auth key encrypted with an Android Keystore AES-256-GCM key; `allowBackup="false"`; node state in `noBackupFilesDir` |
| Credential leaked through logs | Single logging entry point (`SafeLog`) scrubbing through `Redact`; release builds strip `v`/`d`/`i` at the bytecode level; `RedactTest` covers the shapes in play |
| Credential leaked through a screenshot | The key field masks by default and requires an explicit reveal; `FLAG_SECURE` is deliberately not set, with the reasoning recorded in `docs/SECURITY-MODEL.md` |
| The app used as a general-purpose SSRF primitive | `TailnetAddressPolicy` refuses to dial anything outside `100.64.0.0/10`, `fd7a:115c:a1e0::/48` and named hosts — enforced before every dial, not merely in the UI |
| A malicious target host stealing another service's credential | The app authenticates to nothing. It sends no credential to the target, by design, so a hostile target cannot harvest one |
| Credential surviving "forget my key" | That action destroys the Keystore key as well as the ciphertext, so pre-existing copies of the ciphertext become unreadable |
| Supply-chain compromise of the native bridge | The Go module is pinned via `go.mod` + `go.sum`; the AAR is built in CI from a tagged commit; Dependabot watches Gradle and Go dependencies |

### Out of scope

- **A rooted or compromised device.** An attacker running code as this app can
  read anything the app can read. No app-level mitigation exists.
- **A compromised control plane.** Whoever controls your control plane can
  re-key your nodes. That is inherent to the protocol, not to this app.
- **Traffic analysis.** The app does not attempt to hide that a Tailscale node
  is running.
- **The security of the target service.** This app is a client. If the thing you
  point it at is insecure, this app will reach it insecurely and competently.
- **Denial of service on the device.** An app that can be made to spin is an app
  that can drain a battery; that is a bug, not a vulnerability. Report it as an
  ordinary issue.
- **Physical coercion.** If someone can force you to unlock the device and open
  the app, they get the configuration. The key field is masked against shoulder
  surfing and nothing more.

## Known limitations, stated rather than discovered

1. **The plaintext key exists in the heap briefly.** Kotlin strings are
   immutable and cannot be zeroed, and the Go bridge takes a string. The
   mitigations — never persisting it in plaintext, never logging it, dropping it
   as soon as the node is up — are real but partial. A heap dump of a live
   process is a way to recover it.
2. **Software-backed Keystore on some devices.** Many devices and every emulator
   lack a TEE. The app reports which situation you are in rather than implying
   the stronger guarantee.
3. **The auth key's authority is not revocable from this app.** Deleting it from
   the device does not revoke it at the control plane.
4. **The native bridge is compiled, not audited.** `tsnet` is a large dependency
   and this project does not vendor or review it. If you need to trust every
   line, trust upstream.
5. **No reproducible-build guarantee yet.** Release APKs are signed in CI, but
   bit-for-bit reproducibility has not been established.

## Hardening checklist for users

- Create a **tagged, single-use, short-expiry** pre-auth key rather than a
  reusable one, and apply a tailnet ACL that grants the resulting node access to
  only the service you intend. The app cannot enforce this for you; your ACLs
  can.
- Set an expiry you will actually notice.
- Prefer a `100.x` address over a MagicDNS name when testing, so a failure means
  the network rather than the resolver.
- If you are on a device you do not fully trust, do not store a key on it.
- Delete the key in your admin console when you stop using the device.

## Hardening checklist for contributors

- No test, fixture, log line, screenshot, issue, or commit message may contain a
  real auth key. Use obvious dummies such as `tskey-auth-EXAMPLE…`.
- Security-relevant changes must add or update a note in
  `docs/SECURITY-MODEL.md` in the same pull request.
- Never introduce a new outbound destination. If a change requires one, it needs
  a documented justification in `PRIVACY.md` and a reviewer's explicit sign-off.
- New logging must go through `SafeLog`. A bare `android.util.Log` call is a
  review-blocking defect, and CI greps for it.
