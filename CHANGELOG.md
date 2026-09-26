# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Because the app ships no server component, a security fix reaches users only when they install
a new release. Updates are checked only when you ask for them: there is no timer, no check at
launch, and no request to any server that is not the update source you configured (by default,
the target host you already talk to). That is a consequence of the privacy design, not an
oversight, and it is worth knowing when you read a security entry below.

---

## [Unreleased]

### Changed

- **The phone screenshots now say which release each one came from.** The file names were the only place that
  said it, and a reader sees the pictures without them — so the version a capture belongs to, and the two places
  it still carries wording the app has since corrected (the 0.4.4 system-network note reading *"Android 13 or
  newer"*, which 0.4.5 corrected to Android 8, and the 0.4.3 provisioning page naming `v0.4.3` as the newest
  release) are written out under the table rather than cropped, painted over or guessed at.

### Fixed

- **`docs/images/dsh-screen.jpg` shipped its composer workspace chip unredacted.** The tablet captures
  had that name covered; the phone one did not, so a public file read the local checkout's folder name,
  and the README shows that file. The block now covers the whole chip. The earlier blob stays in history
  (added in `6379c36`), which only a rewrite of the branch would remove.

### Removed

- **Nine screenshots that no longer belong in the tree**, so that nothing published contradicts the current
  release. An app-info screen is the system's word for what is installed, which makes an old one a version
  claim a reader will believe rather than a historical nicety — and two of them made that claim about
  versions this project no longer ships: `app-info.jpg` (**0.3.9**, four releases back, already dropped from
  the README) and `tablet-app-info.jpg` (**0.4.1**, still shown in the tablet section in the same breath as
  the sentence saying the tablet runs 0.4.6). The other seven — `dsh-notice.jpg`,
  `notification-permission.jpg`, `phone-043-settings-top.jpg`, `phone-043-settings-bottom.jpg`,
  `phone-044-tailscale-card.jpg`, `provisioning-page.jpg`, `task-notifications.jpg` — were superseded
  captures of screens the README now shows from a newer build, and no file in the repository, the docs site
  or the workflows referenced them. With them gone, every file left in `docs/images/` is referenced by the
  README.

## [0.4.6] — 2026-09-26

### Added

- **One card that holds every way to get Tailscale**, right under the prerequisite card on the setup screen:
  the official site, the **stable** package-server APK, the **unstable** (preview) package-server APK, the
  official source repository on GitHub, and the Chinese explanation page. The prerequisite card keeps the
  wording and gives up its buttons, so the links live in exactly one place.
- **The Chinese page covers both tracks and the source repository**: a table of stable / unstable / source,
  with each file named, sized, linked, and the difference stated (released versus preview; both need Android 8,
  neither updates itself).

### Changed

- The README's phone screenshots say which release each row came from (0.4.4 captures; 0.4.5 added the third
  line to the same card, 0.4.6 moved the links into their own card).

## [0.4.5] — 2026-09-26

### Added

- **A Chinese translation of Tailscale's package-server page**, since that page is English-only and it is the
  fallback exactly when the good pages will not load: `site/tailscale-android.html` — the file and its size, the
  requirement (Android 8 or newer), the checksum trick (`<file>.sha256`), where older versions live, the
  Terms-of-Service and trademark notice, and which build to take (Play when it loads, this file when it does
  not). Linked from the docs home page and from the provisioning page.
- **A third line on the Tailscale card**, same alignment as the other two: *Package server, explained (Chinese)*,
  which opens that page. The card now reads: go to Tailscale → or take the APK directly → or read the same thing
  in Chinese first.

### Fixed

- **The system-network note still said "this app supports Android 13 or newer"** — a claim from 0.4.2 that the
  README and the docs site had already corrected to Tailscale's own floor of **Android 8**, and the app had not.
  It now says Android 8 or newer, and points at the card above for the APK when `tailscale.com` will not load.

### Changed

- **The phone screenshots are 0.4.4**, and they are the ones that show the real setup screen: the Tailscale card,
  the connection method and the test, then the DSH UI button, the update check and diagnostics.

## [0.4.4] — 2026-09-26

### Added

- **A second button under the Tailscale card: the package server, which is the APK itself.** Google Play and
  `tailscale.com` do not load on every network, and the one official place that hands out a *file* instead of a
  page is `pkgs.tailscale.com` — so the card now offers
  `tailscale-android-universal-1.102.4.apk` (universal, 100.6 MB) on its own line under the same card, in both
  languages, and its body says what that file is: the *stable* track, and it does not update itself.
- **A step-by-step first run**, in `README.md` and on the docs site: Tailscale on both ends → a configuration in
  (link, QR code, or a typed host) → connection method → an auth key only for the embedded node → connection
  test → *Open the DSH UI* → the manual update check → diagnostics at the bottom of the same screen.

### Changed

- **The screens section now shows 0.4.3.** The phone row is the real setup screen (top and bottom: the Tailscale
  card, the three ways in, the manual update check, diagnostics) plus the provisioning page with its Tailscale
  block; the tablet row gained the finish notice on a tablet's own shade.
- The docs site names the package-server APK too, so the fallback for a blocked Play Store is one tap from the
  page as well as from the app.

## [0.4.3] — 2026-09-26

### Added

- **A Tailscale card at the top of the settings list** (shown until a target is set): *First: Tailscale, on both
  ends* — install it on this device **and** on the computer running DSH, sign both into the same tailnet, and
  only then point this app at your server, with a button that opens
  <https://tailscale.com/download>. What 0.4.2 added lived in the first-run card (shown once, then acknowledged
  away) and under the system-network option (inside a section, below the fold) — which is exactly where nobody
  looks, as the devices showed. This one is at the top and unmissable, in both languages.
- **The provisioning page says it too**, above the download buttons, including where each end gets Tailscale
  (computer: tailscale.com/download; Android: Google Play `com.tailscale.ipn`, or the APK from Tailscale's own
  package server).

### Fixed

- **`provisioning.html` named the wrong release.** It hard-coded `v0.4.1` while the mirror it links to served
  `0.4.2`, and then told the reader that the buttons might therefore be a version behind — a page calling its own
  download stale. The version is now stamped at deploy time from the newest *published* release
  (`{{APK_VERSION}}`, supplied by `docs-pages.yml` from `gh release view`), so the page cannot name a release
  other than the one it serves. Hand-editing that number at each release is over.

## [0.4.2] — 2026-09-26

### Changed

- **Tailscale is now named, linked and attributed everywhere the prerequisite is stated — including in the app
  itself.** The README carries a two-row table (computer → <https://tailscale.com/download>; phone/tablet → the
  official **Tailscale for Android** app, `com.tailscale.ipn`), and says where each comes from: **Google Play**
  first, because that is where new Android versions land; the APK file itself only from Tailscale's own
  package server (<https://pkgs.tailscale.com/stable/#android>), which does not self-update; and explicitly
  that Tailscale's GitHub releases page is *not* the Android channel and lags behind it. The maintainer runs
  **1.103.90** (© 2024 Tailscale Inc. — their app, not ours). Two numbers are stated separately and honestly:
  Tailscale's own page says *Android 8 or later*, while **this app's supported floor is Android 13 or newer**,
  because old devices ship old WebViews and that is exactly where the DSH interface breaks. In the app, the
  first-run card (`intro_body`) and the system-network option (`provider_system_explanation`) now say the same
  thing in both languages: install the official Tailscale app on this device *and* on the computer, sign both
  into the same tailnet, and get it from **tailscale.com/download** — the two are used together, not
  interchangeably. `README.md`, `site/index.html`, `app/src/main/res/values/strings.xml`,
  `app/src/main/res/values-zh/strings.xml`.

- **The prerequisite now says where to get Tailscale, and that the two are used together.** The README
  and the docs site link [tailscale.com/download](https://tailscale.com/download) at the point where the
  requirement is stated, say to install it on **both** ends (the computer running DSH and the phone or
  tablet) and sign both into the same tailnet, and add the sentence that was missing: **Tailscale is the
  network, this app is the client, and neither replaces the other.** `README.md` and `site/index.html`.

- **The prerequisite was stated vaguely and the scope was left implicit.** The page now says what you
  actually have to do: install Tailscale on **both** ends — the computer running DSH and the phone or
  tablet — and sign both into the **same** tailnet; the address the app takes is the one your DSH
  answers at inside that tailnet. The same paragraph says what this app is and is not: it does one
  thing, it is the client — there is nothing to install into DSH, no mobile protocol of its own, no
  tunnel service, no account, no relay. (Projects that take the other routes carry a gateway plugin
  and a protocol of their own, or run the whole runtime on the phone; this one deliberately does
  neither.) `README.md` and `site/index.html`.

- **"Why this exists" argued about VPN architecture and buried the actual reason.** The section
  opened with "two unsatisfying options" and closed by calling the embedded `tsnet` node *why the
  project exists* — which answers a question nobody asked and misses the point. The point is
  ordinary: a long job runs on your desktop and you have to leave, and what you want is not a
  status mail or a log tail but **the same DSH session, still able to take the next instruction
  from a phone on the way out the door**. That is stated first now. The everyday setup is named
  for what it is — the **official Tailscale app** signs the device in, this app is only the DSH
  client on top of it, no auth key involved — and the rest moved down into a *How it connects,
  and why it asks for no VPN permission* subsection, where the embedded node is described as what
  it is: a mode in the build, optional, and not the reason the project exists. The one-line pitch at the
  top of the README and the hero of the docs site now open with the same situation, so the first thing a
  visitor reads is the use case rather than a list of prerequisites. The section then says what actually
  lands in your hand — reply in the *same* session, answer the questions and tool approvals DSH asks,
  watch a long job stream while you are elsewhere, phone or tablet on one target — because until now the
  capability list was written from the app's plumbing outward and never named the thing a reader came for.
  Same edit in
  `README.md` and `site/index.html`. The docs site's "shape borrowed from tailvisor and
  netbirdio/android-client" line was also wrong and is fixed: tailvisor contains no Android code
  at all and netbirdio/android-client *does* take the device-wide VPN slot, so neither is the
  shape copied here — [`GlassHaven/Haven`](https://github.com/GlassHaven/Haven) is the closest,
  and it is AGPL-3.0, so it is evidence rather than a source of code for this MIT project.

- **The description of this project was wrong, and is now corrected.** The README, the docs
  site's headline, its search/social description and the GitHub repository description all said
  the app "carries its own Tailscale node", which reads as if the node — or the network — came
  from this project. It does not: **you bring the tailnet and the server**, and the path the
  maintainer actually uses is the **system network**, where the tunnel belongs to the official
  Tailscale app and no auth key is needed at all. The embedded-node mode is real and still
  documented ([docs/TSNET.md](docs/TSNET.md)) — it dials from inside the app with your own
  Tailscale auth key, which is what "BYOK" in the name refers to — but it is a *mode in the
  build*, not a service, and it is not how the project is used day to day. Corrected in
  `README.md`, `site/index.html` (headline, lead, `<title>`, `data-title-zh` and the
  `<meta name="description">`) and the repository description on GitHub.

## [0.4.1] — 2026-09-25

### Fixed

- **The update check read the wrong app's version file whenever the target host is itself a DSH
  host.** The update source defaults to the target's origin, and the DSH *shell* app keeps **its own**
  `dsh.apk.version` / `dsh.apk` / `dsh.apk.sha256` at that root — the same three names, a different
  application. Seen on a tablet running 0.4.0: the app announced an update to **1.59**, the shell's
  version, downloaded the shell's 14 MB APK, and verified its SHA-256 successfully, because it is a
  genuine release of *something*. Only the package-name comparison in `UpdateInstaller` stopped it
  from being installed, so nothing was harmed — but the prompt was wrong and 35 MB of somebody's
  bandwidth went nowhere. `UpdateChecker` now tries `<base>/byok` before the root **when the base is a
  bare origin**, which is exactly the derived default; a base that carries a path is still used
  verbatim, because a mirror at `…/mirror` is where its files are and appending `/byok` to somebody's
  chosen path would be guessing. The reference host serves this app's face at `/byok` on **both**
  ports for that reason (`3080/byok` was added: the same bytes the apk-server has served on 8089).

## [0.4.0] — 2026-09-25

Two things, and the first one is a behaviour fix that only shows up on a device nobody was
testing on: **a public install had no way to reach the provisioning page**, while the
maintainer's own build did. `DEFAULT_PROVISIONING_URL` was empty unless a build passed
`-PdefaultProvisioningUrl`, and `SetupScreen` draws its "open the provisioning page" button
only when the value is an `http(s)` URL — so on the tablet the first-run card simply had one
fewer entry than on the phone, with nothing on screen to explain why. It is now defaulted to
the public page and a deployment still overrides it.

The rest is the toolchain work that had been sitting in `[Unreleased]`: `compileSdk 37`, AGP
9.4.1, Gradle 9.6.0, Kotlin 2.2.10, and the documentation site's APK mirror.

### Fixed

- **The provisioning page could not be reached from a public install.** `DEFAULT_PROVISIONING_URL`
  ships as `https://zero6689.github.io/tailnet-byok/provisioning.html` rather than empty, and
  `ci.yml` now asserts *both* halves of the rule that used to be one assertion: the build carries no
  address of anybody's (`DEFAULT_TARGET`, `DEFAULT_MODE`, `DEFAULT_UPDATE_URL` all empty) **and** it
  does carry the public page. The old check demanded `DEFAULT_PROVISIONING_URL` be empty, which is
  what made "the config page is missing" a build that passed CI. Found by reading the two APKs'
  dex string tables side by side: the public asset had no `provisioning.html` literal at all, the
  deployment build had it plus a pre-filled target.

### Changed

- **The download got about 9 MB smaller and the code is the same.** The toolchain upgrade put the NDK
  where AGP can find it during the release build (`ANDROID_NDK_HOME`), and AGP's native-library strip
  therefore runs: `libgojni.so` loses its `.debug_*`, `.symtab` and `.strtab` sections — 9,072,480 B,
  which is the whole difference between the 0.3.9 asset (45,921,396 B) and this one (36,850,464 B).
  Checked rather than assumed: `.text`, `.rodata` and `.gopclntab` come out the same size in both, and
  the dynamic symbol table is identical, all 28 `Java_io_github_*` entry points at the same addresses.
  So the embedded node is the same library with the debug tables removed. A workstation build keeps
  them, because the local Gradle driver does not set `ANDROID_NDK_HOME` — which is why the two sizes
  differ by more than the DWARF they used to differ by.
- **The app is built with `compileSdk 37`, AGP 9.4.1, Gradle 9.6.0 and Kotlin 2.2.10.** One change
  rather than four, because it cannot be split: `androidx.core` 1.19.0 — the update Dependabot has
  been offering since 2026-09-20 — declares `minCompileSdk=37` and
  `minAndroidGradlePluginVersion=9.1.0` in its AAR metadata, and AGP's compatibility table ties the
  plugin to the other two (AGP 9.4 wants Gradle 9.6.0 and carries KGP 2.2.10). AGP 9.4 is also the
  first line that *knows* API 37: both 8.13 and 9.0 report `HIGHEST_KNOWN_STABLE_API = 36`.
- **Kotlin is compiled by AGP itself now.** AGP 9 enables built-in Kotlin by default and
  `org.jetbrains.kotlin.android` is incompatible with its new DSL, so that plugin is gone from both
  build files. The Compose and serialization compiler plugins stay: built-in Kotlin supplies the
  compiler, not those.
- API 37 ships as a *minor* platform release, so the SDK package is `platforms;android-37.0` and not
  `platforms;android-37`. `scripts/fetch-toolchain.mjs`, `scripts/make-wrapper.mjs`,
  `docs/BUILD.md` and the guide page follow the pin.
- Two Gradle 9 deprecations in `app/build.gradle.kts` are fixed rather than tolerated
  (`sourceSets[…] .kotlin.directories` instead of `srcDir`, and `tasks.register("name")` instead of
  the delegated `by tasks.registering`), and lint's version-advice check is disabled for the same
  reason `GradleDependency` and `OldTargetApi` already were: every version in this repository is a
  reviewed pin with a build behind it, not a suggestion to act on.

### Fixed

- `scripts/fetch-toolchain.mjs` now writes the `package.xml` that registers an unpacked SDK package
  with the SDK. The platform archive ships only `source.properties`, and without the descriptor AGP
  does not see the platform that is already on disk — it downloads the same 65 MB again and installs
  it as `platforms/android-37.0-2`, which is then the copy the build compiles against.

### Added

- **The documentation site serves the release APK itself.** The release asset and the docs site are
  served by different hosts — the asset by `release-assets.githubusercontent.com` (Azure Blob), the
  site by Pages (Fastly) — and on some networks the asset host is unusable while the page loads fine,
  which is exactly the reported symptom: the provisioning page opens on the phone and the 45 MB APK
  behind it crawls. `docs-pages.yml` now copies the newest release asset to `byok/` on every deploy
  **and** on `release: published` (a mirror that keeps serving the previous release would be worse
  than none), publishes its sha256 beside it, and refuses to publish a file that is implausibly
  small. It is an alternative host, not a proven speedup — measured 2026-09-25 with nothing else in
  flight, 4 MB ranges ×3: mirror 2.6 / 13.3 / 14.0 MB/s against the release asset's 2.9 / 7.7 / 7.7.
  The GitHub link stays on the page: both are the same bytes, verified by downloading the deployed
  mirror whole and comparing its sha256 against the release digest.

## [0.3.9] — 2026-09-23

Everything below this heading landed after 0.2.9. It ships as **0.3.9** rather than 0.3.8 because
0.3.8 was built and handed to a private deployment earlier the same day, and the in-app updater only
accepts a *higher* version: a build carrying the launcher icon, the app name and the licence screen
has to be a new number, or nobody who already has 0.3.8 is ever offered it.

### Fixed

- **The "a task finished" notice pops up instead of only reaching the notification shade.** The notice was posted to
  a channel created with `IMPORTANCE_DEFAULT`, and on Android 8 and later it is the *channel's* importance — not the
  notification's priority — that decides whether a notification is a heads-up banner: `DEFAULT` means it arrives,
  sounds, and never pops up. Worse, an app may lower a channel's importance but never raise it, so the constant could
  not simply be changed: every phone that already had that channel would have gone on not banner-ing. The notice now
  uses a new channel created with `IMPORTANCE_HIGH` (and a matching `PRIORITY_HIGH` for the pre-channel path), the old
  channel is deleted so the app's notification settings do not keep a dead row, and the channel is created when the
  task watch starts rather than at the first finish.
- **The diagnostics panel now says whether a notice can pop up at all.** "It only goes to the notification bar" and
  "it never arrived" look identical from the outside and have completely different fixes, so the panel gained a
  *Notice channel* line reporting the importance the **system** holds for this app's finish-notice channel: high (a
  banner pops up), default (shade only, no banner), low, or not created yet. The channel is the app's to create once;
  after that only Android's own notification settings can raise it, and the line says so rather than leaving it to be
  guessed.
- **This app's settings button no longer sits on the page's cost panel, and the bottom of the sidebar carries one
  settings entry instead of two.** 0.3.5 put a floating "this app's settings" button in the sidebar column, one
  thumb-reach above the bottom; on this layout it landed in the middle of the page's cost panel, and the page's own
  settings row ended up below the safe area. It was also a duplicate — it called the same callback as the arrow at the
  top of the screen, because the DSH screen is a child of the settings screen. The bottom of the sidebar now has a
  single settings entry: the page's own row, which keeps its own place inside the safe area with no strip reserved
  for anything of ours (cost panel, then "设置", directly above the navigation bar). Only one of the two could stay,
  and the page decides which: DSH renders its settings trigger in exactly one place, so removing that row would have
  left no way into DSH's settings at all — while the app's own settings is still one Back gesture (or one tap on the
  arrow) away. The screen also stops padding the page for the status bar and the keyboard alone: it now uses the safe
  drawing insets as a *union* (`systemBars + displayCutout + IME`, never a sum, since the IME frame already contains
  the navigation bar), which is what stops the composer's tool row and the page's footer from being drawn under the
  system navigation bar, visible and untappable.
- **The download page now says which build its button serves, and how big it is.**
  `site/provisioning.html` pointed at `releases/latest/download/tailnet-byok-arm64.apk` and left the reader to
  find out what that was — a question a user asked, reasonably, since the answer (the last published release, not
  the version in this repository) is not obvious from the page. It also warned about GitHub's release storage
  being slow on some networks, and the privacy note no longer claims the app requests no camera: it explains that
  the scanner asks at the moment it is opened, and that refusing costs nothing. Both lines are updated by hand at
  each release, and `docs/RELEASING.md` now says so in its "before tagging" list.
- **A configured phone no longer walks through onboarding every time.** The ways-in card
  (paste a link, scan a code, open the provisioning page) was shown until the security note had
  been acknowledged *as well as* while no target was set. A user who filled in a target, used the
  DSH screen and never tapped the note's button therefore saw an onboarding card on every visit —
  at the top of a long page, above the button that led back to the screen they were using. The
  card's condition is now "there is no target": that is when the ways in are needed, and the note
  is its own card with its own job.

### Changed

- **The app is called "DSH BYOK" rather than "DeepSeek Harness".** DeepSeek's own brand guidelines ask
  third-party projects not to use the full mark as a project name, and suggest the abbreviation `DSH`;
  the full name now appears only in descriptive sentences ("an independent client that works with
  DeepSeek Harness"). The launcher label, the app bar and the in-app disclaimer all follow the one name,
  and `branding/README.md` records the position — a disclaimer is good faith, not a licence.

### Added

- **The open-source licence texts travel inside the app.** `app/build.gradle.kts` excludes
  `META-INF/LICENSE`, `META-INF/NOTICE` and `META-INF/DEPENDENCIES` from the APK, because the gomobile
  AAR ships Go licence files that collide with AGP's packaging. That keeps the build working and takes
  the notices out of the binary — which is exactly where BSD-3-Clause clause 1 and Apache-2.0 section 4
  require them: the repository's notices were complete and the APK carried none of them. The same texts
  are now generated into `assets/licenses/` by `scripts/generate-license-screen.mjs` and shown on a
  screen reachable from the settings footer, and CI checks that the copy is regenerated rather than
  trusting anyone to remember.
- **A launcher icon with a themed-icon layer.** New artwork — a blue whale holding a glowing phone, on a
  dark plate — installed as three adaptive layers: a bitmap background (the plate is a gradient, not a
  flat fill), a transparent foreground, and a drawn monochrome silhouette. The silhouette is what makes
  the icon follow the system theme on Android 13 and later, and it is drawn rather than thresholded
  because the coloured mark contains white markings that a filter would keep as holes.
  `scripts/install-icon-layers.ps1` installs a composed layer set; `scripts/make-icons.ps1` handles the
  single-image path and refuses to derive a silhouette from art that is not already a shape.
- **The DSH screen is immersive, and the app's own settings moved into the left-hand column.** The screen used
  to carry a full app bar — a title nobody needs (the page says which screen you are on) and two icons — above a
  page that already draws its own header; on a 729px phone that is 8% of the viewport spent on chrome. The bar is
  gone: the page starts directly under the (transparent) status bar, the way out is a slim auto-hiding arrow in
  the corner, and this app's settings is a small translucent button at the bottom of the left column, where the
  page's own navigation lives and a thumb reaches one-handed. Both float over the page, so neither costs it a row.
- **A foreground service keeps the task watch alive while the app is off screen.** Android 12+ does not kill a
  backgrounded app, it *freezes* it — timers stop — so the poll that notices "a task finished" stopped with it,
  and the notification the user was waiting for never came. That is what the first real test of the feature hit.
  The watch now raises a foreground service (`dataSync`) when the DSH screen acquires its route and lowers it when
  the screen releases it, so the poll survives the app going to the background. It cannot outlive the screen, it
  has no binder and cannot be started by another app, and the notification it requires is on a silent,
  low-importance channel that says what it is for. This reverses an earlier decision — the app deliberately had no
  foreground service — and the reasoning is written down in `docs/SECURITY-MODEL.md`, because the permission
  surface changed: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`. Watching with the DSH screen *closed* is
  still not done: that would mean holding the route (and the session cookie) open with no screen, which is exactly
  the capability this app keeps short-lived.

- **The DSH screen now gets the page-level touches the sibling shell has.** The two apps load the *same*
  page from the same server, and the shell's own `tunePage()` was the last difference between what they
  render: this screen now adds a `viewport` meta with `viewport-fit=cover` when the page has none,
  `referrer: no-referrer` (the rule the Go proxy already enforces, told to the WebView too), and 16px form
  fields below 700px — the size at which mobile engines stop zooming a focused field, and the one change
  visible to the eye. The shell's keyboard mirror and drag-drop helpers are deliberately not ported: they
  exist for the shell's own chrome, not for the page.

- **The diagnostics panel now says why a "task finished" notification did or did not arrive.** Every
  reason the notice was declined used to be a silent `return` inside `TurnNotifications`, so the four
  causes — the app was on screen, notifications are off for this app, the runtime permission was never
  granted, the system refused the post — were indistinguishable from the outside, and "the app never
  told me my task finished" could not be investigated. The decision is now a pure, tested function
  (`domain/TurnNotificationDecision.kt`) whose outcome is returned and reported, and the panel carries
  three lines: whether Android would let this app post at all, what the task watch is doing right now
  (polling, how many sessions are running, how long ago the last answer was, or that it is not watching
  because the DSH screen is closed), and what happened to the last finish notice, with its time and the
  session title. Two of those facts have different fixes, which is why they are separate lines rather
  than one boolean.
- **The deployment's provisioning page is offered by the code section.** The first-run card — which
  used to carry the link to the page that generates codes — now appears exactly while there is no
  target, so a deployment build, whose target arrives pre-filled, had lost its only route to that
  page. The link belongs with the code section anyway: both ends are about handing a configuration
  to another device. A build with no provisioning URL (the public one) shows nothing, as before.
- **A launch opens the DSH screen by itself once a configuration works.** The DSH screen is the
  app; the settings page is where you go when something has to change. The walk happens once per
  process and only when there is nothing better to do: no target, an address the policy rejects, or
  no credential on the embedded route all stay on the fields, and a configuration link waiting to be
  confirmed — or a scanner holding the screen — always wins, because opening a screen on top of that
  decision is how the one control that makes links safe would get hidden. Leaving the DSH screen to
  change a setting does not re-arm it; the next launch does.
- **The settings top bar carries "open the DSH UI".** The same action already existed in a section
  near the bottom, below every field and every diagnostic line, so "step out of the DSH screen,
  change one thing, go back" turned into a scroll hunt.

- **A QR code in the app, both directions.** The settings screen draws this device's
  configuration as a code another phone can scan — with the same link as text beside it and a Copy
  button — and reads a code with the camera, or from a picture the user picks. The writer parses
  its own output back before drawing anything, so a code cannot describe a different target than
  the fields on screen; it carries the *deployment* (target, port, scheme, path, transport, update
  source, self-hosted control plane) and not the device (no node name, no ephemeral flag), and a
  credential is not representable in it because the auth key is not part of the configuration
  object at all. A scanned code goes through the same parse-show-confirm path as a pasted link:
  never applied by itself, and a credential-shaped field refuses the whole link. This **reverses an
  earlier decision** — the app deliberately had no camera — so the reasoning is written down rather
  than implied: see `docs/SECURITY-MODEL.md`. The short version is that the camera is requested at
  runtime by the scanner screen alone, is used to turn one code into text, stores and sends
  nothing, and is genuinely optional: the image path needs no permission at all, so the app is
  fully configurable with the camera permanently denied.
- **Two dependencies, both pinned.** `com.google.zxing:core` for the codec — the encoder and the
  luminance decoder are Java with no AWT, so the scanner's whole decision table is unit-tested on
  the JVM — and AndroidX CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view`) for the viewfinder, because binding a camera to a screen's lifecycle is the part
  of a scanner that fails on exactly one device family and never on the emulator.

- **The docs site is bilingual.** Every page now ships in English and Chinese at once: the two
  languages are siblings in the markup
  (`<span class="i18n-en">…</span><span class="i18n-zh">…</span>`) and `site/styles.css` hides one
  of them, so a page still reads correctly with JavaScript blocked and a translator can see both
  halves while editing. `site/lang.js` only decides which half is shown — `?lang=` first, then the
  saved choice, then the browser's own languages — with a toggle in the header. Nothing is sent
  anywhere. `site/build.mjs` now fails the build when the two halves are not the same size, because
  a forgotten half reads as a blank line to whoever has that language selected; `site/README.md`
  documents the convention and the shared terminology.

## [0.2.9] — 2026-09-19

The version the app reports is `0.2.9` (`versionCode` 9). It is the round that makes the phone
usable without a laptop open: uploads that can come from the camera, an update check the app runs
by itself, a way back to settings from the DSH screen — and a notification when a task finishes.

### Added

- **Uploads: several files, and the camera.** The page's file input is passed through with its own
  meaning intact — an `accept` list of concrete types becomes an `EXTRA_MIME_TYPES` filter behind a
  wildcard type, extension-only hints (`.csv`) are dropped rather than turned into a picker with
  nothing in it, `multiple` allows several files, and "take a photo" is offered when a photo is
  something the page can accept (an image-only input, or one that constrains nothing at all, which
  is the shape the DSH composer's attach button has). The camera writes through the app's
  FileProvider into a disposable cache file, so the app still holds no storage permission and the
  page receives an ordinary `content://` URI. No camera permission is requested: the system camera
  app takes the picture.
- **A start-up update check that reads one file.** The app asks the configured update source for
  `dsh.apk.version` once per process and, when it advertises something newer, shows a tappable row
  at the top of the settings screen. It never downloads on its own: the bytes are fetched only when
  that row is tapped, which runs the existing verified download. A failed check is silent — a
  question nobody asked must not be the reason an error appears. Nothing is requested at all until
  a target exists, because the app talks to nobody but the target it was given.
- **A way back to settings, and a first run that explains itself.** The DSH screen's top bar has a
  back arrow and a gear (the same destination, because the DSH UI is a child of the settings
  screen), and the "page did not load" card now offers *Open settings* next to *Retry* — the
  question a user has when a page will not load is where that page came from. A first run with no
  target gets a card with the ways in, including the one that needs no typing: paste a
  `dshbyok://setup?…` link. It goes through exactly the same parse-show-confirm path as a link that
  arrives from the operating system, which is the point — the confirmation exists so the *target*
  is what the user agrees to, not because of where the text came from. A build can point at its
  provisioning page with `-PdefaultProvisioningUrl=…`; the public build ships it empty and the card
  hides that line.
- **"A task finished" notifications.** While the DSH screen is open the app polls the target's own
  `session/list` RPC through the same loopback route the WebView uses (carrying the WebView's cookie
  and nothing else) and posts a notification when a session goes from running to not running. It is
  a transition, so it fires once, a session that was already idle at start-up is not news, and a
  session that disappears is not reported as finished. `session/list` is an endpoint that has
  survived several DSH releases, so no part of this reads or patches the page. Notifications are
  posted **only when the app is not in front of the user**, carry the session title and none of the
  conversation, and tapping one opens the app. The poll backs off to one every 20s while nothing is
  running. Android 13+ is asked for `POST_NOTIFICATIONS` the first time the DSH screen opens.

### Fixed

- The DSH screen no longer leaves a staged photo behind when the camera entry is offered and a
  document is chosen instead; the file is deleted when the request is answered without it, and
  when the screen goes away.

## [0.2.8] — 2026-09-19

The version the app reports is `0.2.8` (`versionCode` 8). It fixes the composer disappearing behind the
keyboard on the DSH screen.

### Fixed

- **The keyboard no longer covers the composer.** The DSH screen left the soft-keyboard inset to the
  page, and this engine (Chromium 116 WebView on Android 14) sometimes never tells the page that the
  keyboard came up: both `innerHeight` and `visualViewport.height` keep their keyboard-closed value, so
  a page-side layout fix has nothing to react to and the composer is drawn behind the keyboard. Even
  when the engine does report, the layout only gave up 287px of a keyboard that covers ~325px, which
  left the composer under the last ~42px of it. The screen now shrinks the WebView by the IME inset
  itself (`Modifier.imePadding()`, with the Scaffold's system-bar padding consumed first), so the page
  is handed a viewport that is already correct. Nothing about the page's own keyboard handling changed;
  it simply has a measurement it can trust now.

## [0.2.7] — 2026-09-19

The version the app reports is `0.2.7` (`versionCode` 7). It adds provisioning — and the first
thing provisioning does is refuse most of what it is offered.

### Added

- **Configuration links: `dshbyok://setup?target=…&mode=…`.** A deployment can hand the app its
  address instead of asking a user to type one, which is the difference between an app someone
  uses and an app someone puts down. The link carries the target, port, scheme, path, connection
  method, update source, control-plane URL and node name — and each field is optional, so a
  link that only switches the connection method leaves the target alone.
- **A QR generator that runs in the browser**, on the docs site:
  `site/provisioning.html`. Type a target, get a code and the link; it can also be prefilled from
  the URL fragment (`#target=…`), which browsers never transmit. The encoder is vendored
  (`site/qr.js`, MIT, Kazuhiro Arase's `qrcode.js` via the copy npm ships in `qrcode-terminal`)
  rather than loaded from a CDN, because the page that hands out an address must not need the
  network. `scripts/vendor-qr.mjs` builds it and **checks it against the upstream encoder** over a
  corpus of real links, so a bad flattening cannot ship as a QR code that no scanner reads.
- **`-PdefaultTarget` / `-PdefaultMode` / `-PdefaultUpdateUrl`**, so a private or branded build can
  pre-fill the first screen. The mechanism is upstream; the value is not — the public build's
  defaults are empty and CI enforces that.
- `docs/PROVISIONING.md` — the field table, the three rules, how to make a QR code, and why this
  app has no built-in address.

### Security

- **A link may carry configuration, never a credential.** If any field name looks like one
  (`authkey`, `key`, `token`, `password`, `secret`, `credential`, `bearer`, `otp`, `pin`) the
  **whole link is refused** — not quietly stripped — and the user is told which rule fired. A
  link can be photographed off a screen or pasted into a group chat, so a secret in one is a
  secret in public. Unknown *non-credential* fields are still ignored, so a newer link format
  stays usable on an older build.
- **A link never applies itself.** It is parsed, shown (target, port, path, method, update
  source) and waited on; applying is an explicit tap. The attack this designs against is a QR
  code that silently re-points the app at a stranger's server so the next thing the user types is
  their auth key — a confirmation step turns that into a visible question.
- **The address policy still applies to the merged configuration**, with the same inline verdict
  a hand-typed target gets, and the Apply button is disabled when the verdict is negative. A link
  cannot reach a host the user could not have typed.
- **No camera permission, and therefore no in-app scanner.** A scanner would cost the camera on
  every install to serve one setup step; the system camera already opens a `dshbyok://` link.
  The manifest's new `VIEW` filter is the only new inbound path into the app, and
  `docs/SECURITY-MODEL.md` now states what may and may not come through it.
- **Build-time defaults are parsed by the same parser**, so a malformed `-PdefaultTarget`
  degrades to "no default" rather than shipping a broken first screen, and a value that tries to
  smuggle a second field (`host&authkey=…`) lands as a malformed target.

### Fixed

- **The README claimed version 0.2.5 while the app reported 0.2.6.** Caught while writing this
  entry; the Status line is now part of the release checklist rather than something to remember.

## [0.2.6] — 2026-09-19

The version the app reports is `0.2.6` (`versionCode` 6). It adds an update checker, and the
first thing the checker does is refuse things.

### Added

- **In-app updates, from a source that has to prove itself.** The app reads
  `<update source>/dsh.apk.version`, downloads `<update source>/dsh.apk` when that advertises
  something newer than the installed `versionName`, and installs it through the system installer.
  The update source defaults to the **target's own origin** — the DSH host already publishes all
  three files next to its own UI — and is a configurable field in the app, so a mirror, another
  port, or a path on the same host works without a rebuild. Only the base URL moves; the three
  names are fixed (`UpdateProtocol`).

### Security

- **A missing or mismatched `dsh.apk.sha256` is a failure, never a pass.** Every path that does not
  end in a hash-verified zip ends in a named refusal, and the refusal is what the screen and the
  diagnostics report. Concretely: the version sidecar must contain a version; a body the provider
  had to truncate is refused rather than hashed; a gzip transfer is unwrapped before hashing (DSH's
  own static routes gzip bodies that the client did not ask to be gzipped); the payload must still
  carry zip magic, so an HTML error page or a captive portal cannot be installed even if its hash
  "matches"; and the sidecar must contain an actual 64-hex SHA-256. `domain/UpdateChecker.kt`.
- **A verified download must also be *this* app.** The staged archive's package name is read with
  `getPackageArchiveInfo` and compared against the running package before any install intent is
  sent. A hash proves the bytes are the ones the source published; it does not prove they are a
  build of this application — and the default source is a host the app does not own, so the two
  checks are answering different questions. A mismatch installs nothing. `domain/UpdateInstaller.kt`.
- **The install path asks for the minimum.** `REQUEST_INSTALL_PACKAGES` is the only permission
  added; there is no silent install (Android requires the user to have allowed installs from this
  app, and the system installer always shows the package), the staged file lives in `cacheDir` and
  goes out as a one-shot read grant from an unexported `FileProvider` whose paths file names exactly
  one directory, and a missing permission is reported as its own outcome with a button that opens
  the right settings screen rather than an unexplained intent error.
- **The outcome is recorded, because installing stops the process.** The result of the last attempt
  (`UpdateRecord`) is persisted as stable tokens — never a rendered sentence, so it stays
  translatable — which is what makes "downloaded and verified" survive the package installer
  killing the app, and what lets a bug report quote what actually happened.
- **A size ceiling per route, instead of one ceiling and a hopeful guess.** The embedded node hands
  response bodies across the gomobile boundary as base64 inside a JSON string, so a body of N bytes
  costs several times N in transient heap; a 60 MB package would be an out-of-memory kill rather
  than a failure message. The embedded route therefore asks for
  `UpdateProtocol.MAX_EMBEDDED_APK_BYTES` (32 MB) and reports anything larger as
  `PACKAGE_TOO_LARGE` — "switch to the system network" — while the system-network route keeps the
  200 MB ceiling. The real fix is to stream the body to a file across the bridge, which needs a new
  binding and a re-bound AAR; until then the bound is explicit rather than accidental. That is also
  why this release's own ~60 MB APK cannot be staged over the embedded route.

### Changed

- **`docs/` and the README no longer say the app has no update mechanism.** It has one now; the
  honest description is that it is user-initiated and talks only to the source you configured.

## [0.2.5] — 2026-09-18

The version the app reports (`versionCode` 5), and the first release with a public repository.
The sizes quoted throughout the docs were re-measured for it.

### Added

- **The DSH screen: your server's own UI, inside the app.** Until now the app could prove the
  socket worked and nothing more, because the connection into the tailnet is created in Go and
  Android's HTTP stack has no route to it — a WebView simply cannot dial it. The bridge now runs
  a small reverse proxy on `127.0.0.1` (random port, random per-session token, loopback only) and
  the WebView talks to that. Four details are what make it work rather than almost work, and each
  one was a failure before it was a fix: `Host`/`Origin` are restated as the target's authority
  (the auth proxy only mints its cookie for the authority it expects), the response stream is
  never buffered (the UI holds an event stream open), `Content-Encoding` is left exactly as sent
  (Go's transparent gunzip desynchronises the header from the body and renders a blank page), and
  the session cookie is handed to the WebView rather than kept in Go — with any `Domain` attribute
  dropped, since a cookie scoped to the target's hostname is rejected outright by a page served
  from loopback. The cookie is cleared when you leave the screen. `tailnet/proxy.go`,
  `proxy_test.go`.
- **File upload from the DSH screen**, through the Storage Access Framework, so the app receives a
  handle to one chosen file and no broader access.
- **The app icon.** An adaptive icon generated from `branding/mark-taiji.png` — two whales in
  a taiji — replacing the placeholder vector ring. `scripts/make-icons.ps1` generates all five
  densities, can crop a mark out of a raw screenshot (`-AutoCrop`), and writes a Play Store
  listing icon.
- **The trademark disclaimer.** The display name is "DeepSeek Harness" and the icon depicts
  DeepSeek's whales, so the project now states plainly, in the README, on every page of the
  docs site and **inside the app's setup screen**, that it is an independent, unofficial client
  and claims no rights to those marks. MIT covers the code; it does not cover a brand, and the
  two are separate questions.
- `branding/README.md` — how the mark was cropped, why the plate is white, why the mark is 72%
  of the canvas, and why there is no themed-icon layer. Includes the three options for a public
  release, since "MIT" and "you may use this name and logo" are not the same permission.
- **`docs/RELEASING.md`** — what a release consists of, the record every release notes file has to
  carry (tag and commit, Go, the `x/mobile` revision, the `tailscale.com` version, the AAR and APK
  hashes, the signing certificate fingerprint), what to run before tagging, and the four rules
  that exist because the alternative is worse.
- **`docs/DEPENDENCIES.md`** — the pin, which three modules are load-bearing rather than routine,
  how to move the pin safely, and which gates will object if you get it wrong.
- **`docs/PROVENANCE.md`** — what is original here, what is merely linked, and what was read for
  design inspiration without being copied, with the checks that back each claim up.
- **A manual reproducibility check** (`.github/workflows/reproducible-build.yml`) that builds the
  bridge twice on one runner with the pinned toolchain and compares the **native library**, not
  the `.aar` container, whose zip metadata is expected to differ between builds.

### Fixed

- **The login handshake could not complete, on either provider.** A hosted DSH host answers the
  first request with `303` plus `Set-Cookie`, and only serves the page when the follow-up carries
  that cookie. Neither HTTP client kept one. OkHttp defaults to `CookieJar.NO_COOKIES`, so the
  chain ran to its redirect cap (`Too many follow-up requests`); the Go side had the same hole
  and a cap of three hops on top. Both now have a jar — memory only, host-scoped, created and
  dropped with the session, because that cookie is a credential.
- **Cleartext HTTP to a tailnet address was refused by the app's own security policy.** The
  network security config tried to express "plain HTTP is fine inside this tailnet" as a
  `<domain>` entry for `100.64.0.0/10` — but that file matches hostnames and has no notion of a
  CIDR, so the only real node it never matched was the user's. Since the app's default scheme is
  `http`, the primary path was blocked outright. The rule now lives in code, where it can be
  expressed: cleartext is allowed only for tailnet literals and tailnet-ish names, and everything
  else must be HTTPS.
- **A trailing-dot MagicDNS name raised a false warning.** `tailnet-name.ts.net.` is a valid FQDN
  spelling. One code path normalised it and the other did not, so the address was correctly
  allowed but labelled "not a MagicDNS name". The address is now normalised once, at
  classification, instead of being judged twice from two different strings.
- **The embedded node reported its version as `1.102.4-ERR-BuildInfo`.** That string is
  `version.Long()`'s documented fallback for a build with no version information, and it is what
  the device showed in the tailnet admin console. Committing harder could not fix it: gomobile
  compiles a *copy* of the module in a temp work directory, where the Go tool finds no VCS data
  to embed. The two stamps (`version.shortStamp`, `version.longStamp`) are now injected with
  `-ldflags`, and the build **fails** if the stamped string is not found inside the resulting
  native libraries — an `-X` naming a symbol that does not exist is not an error to the linker,
  so a silent regression here would surface only on a phone, months later. `Version()` returns
  `version.Long()` rather than a placeholder, so the app can print the same string the node
  reports. `scripts/build-bridge.mjs`, `tailnet/tailnet.go`.
- **Scripts are executable in the repository.** `git` on Windows records `core.filemode=false`,
  so `gradlew` was committed as mode `100644` — which on Linux, macOS and CI makes `./gradlew` a
  permission error, as the first CI run on the new repository would have discovered.
- **The toolchain and size tables described an older build.** The README said version 0.1.0 while
  the app reported 0.2.5, and the documented AAR and APK sizes predated the security work. Both
  were re-measured with the build they describe (see `docs/TSNET.md`).
- **`go get -tool golang.org/x/mobile/cmd/gobind`, without a revision**, asked the module proxy for
  the latest version: a second floating pin, and a hard failure in a warm, offline checkout
  (`module lookup disabled by GOPROXY=off`) even when the module was already cached.
- **A real tailnet device address was in the docs site's sample transcript.** `site/index.html`
  showed one in the "sample run" block; it now uses the documented placeholder, like every other
  example in the repository. It was found by running the tailnet-address guardrail by hand — the
  guardrail had been reporting it, and that check excluding markdown is why nothing else did.

- **The diagnostics panel can be copied, refreshed, and now says which build it is.** It gains
  the app version and the version of the node library compiled into the APK
  (`ConnectivityProvider.libraryVersion`, answered from the native library itself), a copy
  button, a refresh button, and selectable text. It is also rendered when empty, because
  "no diagnostics" is itself information and a panel that only appears once something has
  already gone wrong cannot be refreshed on the way to finding out. A section rather than a
  screen: this app has no navigation library, and a second screen for a block of text is not a
  reason to add one.

### Security

- **Third-party licence obligations are met, and generated rather than remembered.** The native
  bridge statically links 31 Go modules — `tailscale.com` (BSD-3-Clause), `gvisor.dev/gvisor`
  (Apache-2.0), and MIT or BSD-2-Clause for the rest — and both of those first licences attach
  conditions to *binary* distribution. `THIRD-PARTY-NOTICES.md` and `licenses/` reproduce the
  notices and the full texts; `scripts/third-party-licenses.mjs` regenerates them from
  `go list -deps`, and fails if a linked module has no licence text, so the list cannot drift into
  being wrong or quietly incomplete. A CI job re-runs it and fails when the committed files differ.
- **The build's dependency pin can no longer move silently.** `go mod tidy` runs on every build
  path, and if it changes `go.mod` or `go.sum` the script restores them and fails; the deliberate
  path is `--write-mod`, which keeps the change so that it lands as a diff somebody reviews. An
  AAR built from a graph that matches no commit is an AAR nobody else can reproduce.
- **`gomobile` and `gobind` are installed from the revision pinned in `go.mod`**, not from
  `@latest`. That is what `gomobile init` would otherwise do behind the build's back, installing a
  tool whose generated bindings describe an ABI the runtime does not implement — a mismatch that
  surfaces at the first call rather than at build time.
- **The credential guardrail was skipping the files most likely to contain a pasted key.** It
  filtered hits with `grep -viE '…|test|\.md:'`, and because `git grep -n` prints
  `path:line:text`, `test` matched the **path**: every file under a `test/` directory was skipped,
  which is exactly where someone pastes a key while debugging, along with every markdown file.
  Measured against the tree it was written for, it discarded all seven credential-shaped strings
  in the repository — and would have discarded a real one just as quietly. It now has no
  path-based exclusions, and lets a line through only when it carries an explicit `not-a-secret`
  marker or an obvious placeholder word. The tailnet-address check scans documentation too now,
  for the same reason.
- **Nothing that identifies a person or a machine is committed, and CI now enforces it.** A
  build-machine path (a drive-rooted profile directory, or a POSIX home directory) fails the
  guardrails job, next to the existing checks for credential-shaped strings and tailnet addresses;
  `CONTRIBUTING.md` states the whole list in one table. Measured while writing it: the scan finds
  nothing in the tree today, and catches an injected profile-path line.
- **The native library no longer carries the checkout's source paths.**
  `scripts/build-bridge.mjs` passes `-trimpath` to `gomobile bind`, after measuring that
  `<checkout>\` appeared four times inside `libgojni.so` — bytes that go on
  into every published AAR and every APK built from it. It is an environment variable rather
  than a flag because `bind` does not register `-trimpath` (only `build` does), and it is scoped
  to that one call. Measured afterwards: source paths are module-relative, and two classes of
  path remain that the flag does not cover — the module's own directory in the build info, and
  the NDK's include paths. On a CI runner both read `/home/runner/…` and
  `/usr/local/lib/android/…`, i.e. nothing private; the limit is written down in the script
  rather than presented as "the library is path-free".

- **CI publishes exactly two artifacts, on purpose.** The AAR and the debug APK from the bridge
  workflow are uploaded, because that is how a build gets onto a phone to be tested without a
  local Go toolchain; the two AARs the reproducibility check produces are still not uploaded, as
  the hashes in its log are the evidence. Neither artifact is a release: no tag, no signing
  record, no checksum, and both expire. Users get builds from a tagged release
  (`docs/RELEASING.md`). The unit-test and lint reports remain uploaded as diagnostics.

### Notes

- The `tailnet-bridge` workflow has now been exercised on a runner, and the first run failed
  where this note predicted it would: `yes | sdkmanager` under `set -o pipefail` reports the
  licence-acceptance pipe as failed (`yes: standard output: Broken pipe`) after the NDK has in
  fact installed. The same three lines were in `release.yml` and `reproducible-build.yml`, so
  all three workflows died before reaching a build. Fixed in all three: accept licences
  best-effort, then install without a pipe, so a real failure stays a real failure.
- `GOMOBILE_VERSION: latest` was removed from the bridge workflow's environment. The build
  script never read it — the revision comes from `tailnet/go.mod` — so its only effect was to
  print a value in every run that looked like the build floats its toolchain, which is the
  opposite of what it does.

---

## [0.1.0] — 2026-09-12

The first release. A skeleton with a working spine: the security layer, the connection test and
the address policy are complete and tested; the embedded node's Go source is complete but its
AAR is produced only by CI.

### Added

**Configuration and storage**

- `AppConfig` — host, port, scheme, path, control server, node hostname, ephemeral flag. Holds
  a boolean `hasStoredKey`, never the credential itself.
- `ConfigRepository` — DataStore-backed persistence, with the credential split out into a
  separate encrypted store. Settings and secrets change for different reasons, and a single
  "save" that writes both is how a key ends up clobbered by a form that only changed the port.
- `KeystoreSecretVault` — AES-256-GCM under a non-exportable Android Keystore key, with
  `setRandomizedEncryptionRequired(true)` and `setUnlockedDeviceRequired(true)` on API 28+.
  Reports whether the key is hardware-backed rather than assuming it is.
- `ClearAuthKey` destroys the Keystore key as well as the ciphertext, so pre-existing copies of
  the ciphertext become permanently unreadable.

**Reaching the target**

- `ConnectivityProvider` — the seam. Two implementations, no branching in the UI.
- `SystemNetworkProvider` — plain sockets over whatever network the device already has, with
  distinct messages for timeout, refused, no-route and DNS failure.
- `TsnetConnectivityProvider` — the embedded node, behind a `gomobile` bridge.
- `TailnetAddressPolicy` — the security boundary that decides what may be dialled, with
  `Allowed` / `AllowedWithWarning` / `Rejected` verdicts rather than a boolean, because "valid
  but fragile" is a real and common state.

**The native bridge**

- `tailnet/` — a Go package wrapping `tsnet`, following one rule: every exported function takes
  and returns only `String`, `Int`, `Bool` or `error`. Structured data crosses as JSON.
- Bounded in-memory log ring, redacted before storage. `Logf` and `UserLogf` both feed it, so
  the interactive login URL surfaces as `Status().loginURL`.
- Explicit `forceLogin`, because `tsnet` silently ignores a new auth key when node state already
  exists — the most confusing symptom this project can produce.

**The test**

- `ConnectionTester` — six named steps, each with a duration, stopping at the first hard
  failure and marking the rest skipped. A single boolean would be useless: the fixes for a bad
  address, a missing key, a dead service and a wrong path are all different.
- Any HTTP status counts as proof of life. A 401 is a pass, because the app is not
  authenticated to the target and should not be.

**Security and privacy**

- `SafeLog` and `Redact` — one logging entry point, scrubbed, with the credential shapes this
  project actually handles covered by tests. Release builds strip `v`/`d`/`i` at the bytecode
  level.
- `allowBackup="false"` with backup and device-transfer rules excluding every domain.
- Network security config permitting cleartext **only** for tailnet destinations.
- Three permissions total. No `BIND_VPN_SERVICE`, no `QUERY_ALL_PACKAGES`, no location.
- Exactly one exported component.

**Project**

- GitHub Actions: `ci` (guardrails, tests, lint, assemble), `tailnet-bridge` (build the AAR and
  prove the integration), `release` (signed APK on tag), `docs-pages` (docs site to Pages),
  `sync-personal-site` (manual mirror, dry-run by default).
- `scripts/fetch-toolchain.mjs` — checksum-verified download of Gradle, the Android SDK, Go and
  the NDK, so that "install the toolchain" is one command instead of a wiki page.
- `scripts/build-bridge.mjs` — the gomobile bind, with the four load-bearing flags documented
  and the generated Java facade verified by name.
- A dependency-free static docs site with a link validator that fails the build on a broken
  internal link.
- MIT license, `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`, and a threat model that states
  its own limits.

### Known limitations

These shipped in 0.1.0 and are listed rather than left to be discovered:

- The bridge compiles and packages, but has never been *run* on a device in CI.
- No instrumented tests. Keystore behaviour and the native bridge are verified by hand.
- UI strings are not extracted, so the app cannot yet be translated.
- No `abiFilters`, so all four ABIs ship and the release APK is 162.6 MB. Four ABIs of
  `libgojni.so` account for 161.2 MB of that, so an `arm64-v8a`-only build is roughly 46 MB.
- Release builds are signed but not verified bit-for-bit reproducible.
- The plaintext auth key exists in the process heap between decryption and the node coming up,
  because Kotlin strings cannot be zeroed. Documented in `docs/SECURITY-MODEL.md`.
- No screenshot in this repository came from a running app.

### Verified by build

Every claim above was checked against a real toolchain, not by inspection:

| Check | Result |
|---|---|
| `testDebugUnitTest` | 30 tests, all passing |
| `lintDebug` | 0 errors, 0 warnings |
| `assembleDebug` | APK produced, `libgojni.so` in all four ABI directories |
| `assembleDebug -PwithTsnet=true` | APK produced |
| `assembleRelease -PwithTsnet=true` | 162.6 MB, unsigned, R8-minified |
| `go vet ./...` | clean |
| gomobile facade assertion | `io.github.zero6689.tailnetbyok.mobile.Mobile` present |

Resolved versions, for the record: Go 1.27.1, `tailscale.com` v1.102.4, NDK r26d,
AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1.


[Unreleased]: https://github.com/zero6689/tailnet-byok/compare/v0.4.6...HEAD
[0.4.6]: https://github.com/zero6689/tailnet-byok/releases/tag/v0.4.6
[0.4.5]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.4.4]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.4.3]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.4.2]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.4.1]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.4.0]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: 0.4.1 replaced it within the day"
[0.3.9]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.2.9]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.2.8]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.2.7]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.2.6]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.2.5]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
[0.1.0]: https://github.com/zero6689/tailnet-byok/releases "withdrawn: only the latest release is kept"
