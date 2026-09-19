# Provisioning: handing a phone its configuration

Installing this app is the easy half. The hard half is the first screen: a
MagicDNS name, a port, a connection method, and — if the embedded node is the
transport — an auth key. That is four chances to mistype something on a phone
keyboard, and it is the difference between an app someone uses and an app someone
puts down.

This document is the whole answer to that, and it has two halves: what the
**server** can hand out (a link, and a QR code containing it), and what a
**builder** can bake in (a default target, for their own build only).

There is deliberately no third half. This app has no server of ours and no
address in it, and it should stay that way — see
[Why not a built-in address](#why-not-a-built-in-address).

---

## The link

```
dshbyok://setup?target=phone.tailnet-name.ts.net:3080&mode=system
```

| Field | Accepted | Meaning |
|---|---|---|
| `target` | `host`, `host:port`, or `http(s)://host:port/path` | the host to reach. The most common field by far |
| `host` | as `target` | an alias, for links written by hand |
| `port` | `1`–`65535` | overrides the target's port |
| `scheme` | `http` or `https` | overrides the scheme |
| `path` | anything; a missing leading `/` is added | the path the DSH UI opens at |
| `mode` | `embedded` or `system` (plus a few synonyms) | the connection method |
| `update` | a base URL | where to look for a newer build of this app |
| `control` | a URL | a self-hosted control plane (headscale) |
| `node` | a hostname | the name this device takes in the tailnet |

Anything else is **ignored**, so a link written for a newer version still works on
an older one. Empty values are ignored too. A link that names nothing at all is
refused rather than applied as a no-op.

Fields the link does not mention are left exactly as they were: a link that only
sets a mode does not clear the target, and a link cannot touch a credential.

## Three rules, and they are the feature

**1. A link may carry configuration, never a credential.** If any field name looks
like one — `authkey`, `key`, `token`, `password`, `secret`, `credential`,
`bearer`, `otp`, `pin` — the **whole link is refused**, and the app says why. Not
"the field is ignored": refused. A link can be photographed off a screen, printed
on a wall, or pasted into a group chat, so a secret in one is a secret in public.
The auth key is typed into the app, or obtained through the control plane's own
login flow.

**2. A link never applies itself.** It is parsed, shown, and *waited on*. The app
displays the merged result — target, port, path, method, update source — and the
user taps Apply or Ignore. This is the control that makes the feature safe: the
attack worth designing against is a QR code that quietly re-points the app at a
stranger's server, so that the next thing the user types is their auth key. A
confirmation step turns that from a silent takeover into a visible question.

**3. The address policy still applies.** `TailnetAddressPolicy` is asked about the
*merged* configuration, exactly as it is about a hand-typed one: a link that points
at a public address is rejected by the same rules, with the same inline verdict, and
the Apply button is disabled. A link cannot talk the app into a target the user
could not have typed themselves.

## Making a QR code

The docs site has a generator: **<https://zero6689.github.io/tailnet-byok/provisioning.html>**

Type your target, and it renders a QR code and the link. Two properties of that
page are deliberate:

* **It runs entirely in your browser.** No analytics, no external font, no script
  from another host; the QR encoder is a vendored file (`site/qr.js`, MIT —
  see `scripts/vendor-qr.mjs` for how it is built and how its output is checked
  against the upstream encoder).
* **It can be prefilled from the URL fragment**:
  `…/provisioning.html#target=phone.tailnet-name.ts.net:3080&mode=system`.
  A fragment is never sent to a server, so a link to that page can be handed
  around without telling this project — or anyone on the network — which host it
  points at.

You can also put the code on your own page: `site/qr.js` exposes
`window.DSHQR.renderSvg(link, {scale, margin})`, and the page is plain HTML with no
build step of its own.

### Scanning it

Any camera or QR application will open the link — the scheme is `dshbyok`, so
Android asks which app handles it. **This app deliberately has no camera
permission and no in-app scanner**: a scanner would mean asking for the camera on
every install to serve a one-time setup step, and the system camera already does
the job. That decision is recorded in
[`SECURITY-MODEL.md`](SECURITY-MODEL.md).

### Pasting it

A code on a screen somewhere else is the common case, and the phone may be the
only device in the room — so the link can also be **pasted**. A first run with no
target shows a card with a box for it, and whatever goes in there takes exactly the
same path a link from the operating system takes: parsed, shown with the target it
would produce, applied only on a tap. That is deliberate. The confirmation exists
so that the *target* is what the user agrees to, not because of where the text came
from, and "the user typed it" is not a reason to skip it.

A build can point at its own provisioning page with
`-PdefaultProvisioningUrl=https://…` (see [`BUILD.md`](BUILD.md)); the card then
offers that page as a tappable link. The public build ships the property empty, so
the card shows only the paste box.

## Why not a built-in address?

The obvious alternative is to compile a default address into the app. It is not
what this project does, for three reasons:

1. **It would publish somebody's topology.** A committed tailnet address names a
   real device. This repository's own CI guardrails scan for exactly that, and a
   default target is the most natural place for one to slip in.
2. **It would be wrong for everyone else.** One app, many deployments: a different
   host, a different domain, headscale instead of the hosted control plane, a
   rebuild on a different network. The server always knows who it is; a compiled
   constant cannot.
3. **It would not actually be zero-effort.** The user still has to enter a
   credential in the embedded mode, so the typing does not disappear — it just
   moves.

So: the mechanism to pre-fill a target exists (below), the *default build's* values
are empty, and CI enforces that they stay empty.

## Pre-filling a target in your own build

```bash
./gradlew assembleDebug \
  -PdefaultTarget=phone.tailnet-name.ts.net:3080 \
  -PdefaultMode=system \
  -PdefaultUpdateUrl=http://192.0.2.10:8089
```

The full table is in [`BUILD.md`](BUILD.md#pre-filling-a-target-your-own-build-only).
Two things to keep in mind:

* It is a **default, not a policy.** The moment the user saves any setting, the
  stored value wins.
* The values go through the **same parser a link does**, so a malformed one
  degrades to "no default" instead of shipping an app whose first screen is a
  broken address — and a value that tries to smuggle a second field
  (`-PdefaultTarget="host&authkey=…"`) lands as a malformed target rather than as
  a credential.

## What the user sees

1. The link is opened (QR, chat, or a tap) and the app comes to the foreground.
2. A **Configuration link** panel shows what would change, and the address verdict
   for the resulting target.
3. **Apply** writes it; **Ignore** leaves everything as it was. A refusal — a
   credential field, a malformed value, a target the address policy rejects — is
   reported in the same place, in the user's language.

None of it needs the network, and none of it needs an account.
