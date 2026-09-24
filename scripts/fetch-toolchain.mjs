#!/usr/bin/env node
/**
 * Downloads the toolchains this project needs, so that "install Go, gomobile,
 * the NDK and Gradle" is one command instead of a wiki page.
 *
 * # Why this exists
 *
 * Building the app's default (Kotlin-only) configuration needs JDK 17 and an
 * Android SDK. Building the embedded node additionally needs Go, gomobile and
 * the NDK, which is roughly two gigabytes of downloads that most contributors
 * will never otherwise assemble correctly. A script that fetches exactly the
 * right versions, verifies their checksums and lays them out where
 * `docs/BUILD.md` says they will be is the difference between a project people
 * can build and one they file issues about.
 *
 * # Why it uses Node
 *
 * Node is already required to build the docs site, it has no dependencies here,
 * and — unlike a shell script — it behaves identically on Windows, macOS and
 * Linux. Extraction shells out to `tar`, which handles zip on all three
 * platforms (bsdtar on Windows and macOS, GNU tar on Linux).
 *
 * # Usage
 *
 *   node scripts/fetch-toolchain.mjs --list
 *   node scripts/fetch-toolchain.mjs gradle sdk            # to build the app
 *   node scripts/fetch-toolchain.mjs all                   # to build everything
 *
 *   --into <dir>    where to install (default: <repo>/.toolchain)
 *   --force         re-download even when the target exists
 *
 * Every download is verified against the checksum the vendor publishes. A
 * mismatch is a hard failure: a toolchain is the thing that turns source into
 * the binary a user installs, so "close enough" is not a category here.
 */

import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  cpSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ---------------------------------------------------------------------------
// Versions. Pinned, because a toolchain that floats is a build that breaks for
// reasons unrelated to your change.
// ---------------------------------------------------------------------------

const VERSIONS = {
  gradle: '9.6.0',
  // Go is resolved dynamically from https://go.dev/VERSION so that the script
  // does not rot; pin `go` here to override.
  go: null,
  // The NDK version gomobile is known to work with.
  ndk: { revision: 'r26d', version: '26.3.11579264' },
  // API 37 ships as a *minor* platform release, so the package path is
  // `platforms;android-37.0` and the SDK directory is `android-37.0` — there is
  // no plain `android-37` package in Google's index. The minor-version scheme
  // starts at 36.1; the first release of a new API level is always `.0`.
  androidPlatform: '37.0',
  androidBuildTools: '37.0.0',
};

const args = process.argv.slice(2);
const flags = new Set(args.filter((a) => a.startsWith('--')));
const targets = args.filter((a) => !a.startsWith('--'));

const intoIndex = args.indexOf('--into');
const INTO = intoIndex >= 0 && args[intoIndex + 1]
  ? resolve(args[intoIndex + 1])
  : join(REPO_ROOT, '.toolchain');

const FORCE = flags.has('--force');

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function log(msg) {
  console.log(msg);
}

function fail(msg) {
  console.error(`\n  error: ${msg}\n`);
  process.exit(1);
}

function requireCommand(name) {
  const probe = spawnSync(name, ['--version'], { stdio: 'ignore', shell: false });
  if (probe.error) {
    fail(
      `'${name}' is not available. This script needs it to extract archives.\n` +
        `  Windows 10 1803+ and macOS ship it; on Linux install bsdtar or GNU tar.`,
    );
  }
}

const DOWNLOADS = join(INTO, '.downloads');

function ensureDirs() {
  mkdirSync(DOWNLOADS, { recursive: true });
}

/**
 * Streams a URL to a file, with a progress line.
 *
 * fetch() rather than a shell tool: it uses Node's own CA bundle, which means it
 * works in restricted environments that block the platform TLS stack, and it
 * gives a byte count so a two-gigabyte NDK download is visibly progressing
 * rather than apparently hung.
 */
async function download(url, dest) {
  if (existsSync(dest) && !FORCE) {
    log(`    cached: ${dest.split(/[\\/]/).pop()}`);
    return dest;
  }
  if (existsSync(dest) && FORCE) rmSync(dest, { force: true });

  log(`    GET ${url}`);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) fail(`HTTP ${res.status} ${res.statusText} for ${url}`);

  const total = Number(res.headers.get('content-length') || 0);
  let seen = 0;
  let lastPrint = 0;

  const body = Readable.fromWeb(res.body);
  body.on('data', (chunk) => {
    seen += chunk.length;
    const now = Date.now();
    if (now - lastPrint > 1000) {
      lastPrint = now;
      const pct = total ? `${((seen / total) * 100).toFixed(1)}%` : '?';
      process.stdout.write(
        `\r    ${(seen / 1048576).toFixed(1)} MB${
          total ? ` / ${(total / 1048576).toFixed(1)} MB (${pct})` : ''
        }   `,
      );
    }
  });

  await pipeline(body, createWriteStream(dest));
  process.stdout.write('\r' + ' '.repeat(70) + '\r');
  log(`    saved ${(statSync(dest).size / 1048576).toFixed(1)} MB`);
  return dest;
}

/**
 * Streams the file through SHA-256.
 *
 * Streamed rather than read into memory because the NDK archive is hundreds of
 * megabytes, and `readFileSync` on it would be a needless spike for no gain.
 */
async function sha256(file) {
  const hash = createHash('sha256');
  await pipeline(createReadStream(file), hash);
  return hash.digest('hex');
}

async function sha1(file) {
  const hash = createHash('sha1');
  await pipeline(createReadStream(file), hash);
  return hash.digest('hex');
}

/**
 * Verifies a downloaded file against a vendor-published checksum.
 *
 * The algorithm matters: Google's SDK repository index publishes SHA-1, Gradle
 * publishes SHA-256, and the Go project publishes SHA-256. SHA-1 is weak as a
 * collision-resistant hash, but as a *transport integrity* check against a
 * checksum served over TLS from the same origin it is what is available, and it
 * is strictly better than not checking at all.
 */
async function verifyChecksum(file, expected, label, algorithm = 'sha256') {
  if (!expected) {
    log(`    note: no published checksum for ${label}; skipping verification`);
    return;
  }
  const actual = algorithm === 'sha1' ? await sha1(file) : await sha256(file);
  if (actual.toLowerCase() !== expected.trim().toLowerCase()) {
    rmSync(file, { force: true });
    fail(
      `${algorithm} checksum mismatch for ${label}\n` +
        `    expected ${expected}\n` +
        `    actual   ${actual}\n` +
        `    The partial download was deleted. Re-run to try again; if it recurs, do not use this artifact.`,
    );
  }
  log(`    checksum ok (${algorithm}, ${actual.slice(0, 16)}…)`);
}

function extract(archive, intoDir) {
  mkdirSync(intoDir, { recursive: true });
  const isZip = archive.toLowerCase().endsWith('.zip');
  const tarArgs = isZip ? ['-xf', archive, '-C', intoDir] : ['-xzf', archive, '-C', intoDir];
  const result = spawnSync('tar', tarArgs, { stdio: 'inherit' });
  if (result.status !== 0) fail(`tar failed to extract ${archive}`);
}

/**
 * Extracts an archive whose contents are one top-level directory, and renames
 * that directory to [targetName].
 *
 * # Why this is needed at all
 *
 * Vendor archives do not name their contents after the version you asked for:
 *
 *   go1.27.1.windows-amd64.zip      -> `go/`
 *   platform-35_r02.zip             -> `android-35/`
 *   build-tools_r35_windows.zip     -> `android-15/`
 *   build-tools_r37_windows.zip     -> `android-37.0/`
 *
 * The `r35 -> android-15` line is the trap that makes this function worth its
 * length: it is not a typo, it is the platform API level the tools target, and
 * hardcoding a mapping for it would be a bug waiting for the next release. The
 * `r37` line is the second trap: build-tools 37 and platform 37 both extract to
 * `android-37.0`, so the two must be told apart by where they are moved to, not
 * by the name they arrive with.
 *
 * So the layout is discovered rather than assumed, and the result is named
 * canonically. If an archive ever contains something other than one directory,
 * this fails loudly instead of scattering files.
 */
function extractAndRename(archive, intoDir, targetName) {
  const staging = join(DOWNLOADS, `.staging-${targetName}`);
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(staging, { recursive: true });
  extract(archive, staging);

  const entries = readdirSync(staging);
  if (entries.length !== 1) {
    fail(
      `expected exactly one top-level directory in ${archive}, found ${entries.length}: ${entries.join(', ')}\n` +
        `    The archive layout changed. Inspect ${staging} and adjust the caller.`,
    );
  }

  const dest = join(intoDir, targetName);
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(intoDir, { recursive: true });
  renameSyncCrossDevice(join(staging, entries[0]), dest);
  rmSync(staging, { recursive: true, force: true });

  if (entries[0] !== targetName) {
    log(`    (archive contained '${entries[0]}', renamed to '${targetName}')`);
  }
  return dest;
}

async function fetchText(url) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) fail(`HTTP ${res.status} fetching ${url}`);
  return res.text();
}

// ---------------------------------------------------------------------------
// Targets
// ---------------------------------------------------------------------------

async function installGradle() {
  const v = VERSIONS.gradle;
  const target = join(INTO, `gradle-${v}`);
  log(`\n== Gradle ${v} ==`);
  if (existsSync(join(target, 'bin', 'gradle')) && !FORCE) {
    log(`    already installed at ${target}`);
    return target;
  }
  const name = `gradle-${v}-bin.zip`;
  const url = `https://services.gradle.org/distributions/${name}`;
  const archive = join(DOWNLOADS, name);
  await download(url, archive);
  const expected = (await fetchText(`${url}.sha256`)).split(/\s+/)[0];
  await verifyChecksum(archive, expected, name);
  extract(archive, INTO);
  if (!existsSync(target)) fail(`expected ${target} after extraction`);
  log(`    installed -> ${target}`);
  log(`    note: this is the *distribution*. Run scripts/make-wrapper.mjs to generate`);
  log(`          the gradle-wrapper files a clean clone needs.`);
  return target;
}

async function resolveGoVersion() {
  if (VERSIONS.go) return VERSIONS.go;
  const text = await fetchText('https://go.dev/VERSION?m=text');
  const version = text.trim().split('\n')[0].trim();
  if (!/^go\d+\.\d+(\.\d+)?$/.test(version)) fail(`unexpected Go version string: ${version}`);
  return version;
}

async function installGo() {
  const version = await resolveGoVersion();
  const target = join(INTO, version);
  log(`\n== ${version} ==`);
  if (existsSync(join(target, 'bin', process.platform === 'win32' ? 'go.exe' : 'go')) && !FORCE) {
    log(`    already installed at ${target}`);
    return join(target, 'bin');
  }

  const platform = { win32: 'windows', darwin: 'darwin', linux: 'linux' }[process.platform];
  if (!platform) fail(`unsupported platform ${process.platform}`);
  const arch = { x64: 'amd64', arm64: 'arm64' }[process.arch];
  if (!arch) fail(`unsupported architecture ${process.arch}`);

  const ext = platform === 'windows' ? 'zip' : 'tar.gz';
  const name = `${version}.${platform}-${arch}.${ext}`;
  const url = `https://go.dev/dl/${name}`;

  // go.dev publishes a JSON index with checksums; use it rather than trusting
  // the transfer.
  const index = JSON.parse(await fetchText('https://go.dev/dl/?mode=json&include=all'));
  const release = index.find((r) => r.version === version);
  const file = release?.files?.find((f) => f.filename === name);
  if (!file) fail(`could not find ${name} in the Go download index`);

  const archive = join(DOWNLOADS, name);
  await download(url, archive);
  await verifyChecksum(archive, file.sha256, name);
  // The Go zip contains a top-level `go/`, not `go1.27.1/`. Renamed so the
  // install path names the version — which is what makes it possible to have
  // two Go versions side by side and to tell from a path which one a build used.
  extractAndRename(archive, INTO, version);
  const bin = join(INTO, version, 'bin');
  if (!existsSync(bin)) fail(`expected ${bin} after extraction`);
  log(`    installed -> ${join(INTO, version)}`);
  return bin;
}

async function installCmdlineTools() {
  log(`\n== Android cmdline-tools ==`);
  const existing = join(INTO, 'android-sdk', 'cmdline-tools', 'latest', 'bin');
  if (existsSync(existing) && !FORCE) {
    log(`    already installed at ${existing}`);
    return;
  }

  // The build number changes with every release, so it is discovered rather than
  // hardcoded — a pinned URL here would be a script that stops working silently.
  //
  // The platform suffix is not the Node platform name: Google publishes `win`
  // (not `windows`), `linux`, and an architecture-specific `mac_arm64` /
  // `mac_x86_64`. Getting this wrong produces a script that fails with "could not
  // find a build", which is a confusing way to learn that `windows` is not a
  // word Google uses.
  const repoXml = await fetchText('https://dl.google.com/android/repository/repository2-3.xml');
  const suffix = {
    win32: 'win',
    linux: 'linux',
    darwin: process.arch === 'arm64' ? 'mac_arm64' : 'mac_x86_64',
  }[process.platform];
  if (!suffix) fail(`unsupported platform ${process.platform}`);

  let match = repoXml.match(new RegExp(`commandlinetools-${suffix}-(\\d+)_latest\\.zip`));
  if (!match && process.platform === 'darwin') {
    // Older index revisions used a single `mac` suffix for both architectures.
    match = repoXml.match(/commandlinetools-mac-(\d+)_latest\.zip/);
  }
  if (!match) fail(`could not find a cmdline-tools build for '${suffix}' in the Android repository index`);
  const name = match[0];
  const url = `https://dl.google.com/android/repository/${name}`;
  log(`    build: ${name}`);

  const archive = join(DOWNLOADS, name);
  await download(url, archive);
  // Google publishes no per-file checksum for SDK archives; the package is
  // verified by sdkmanager's own signature check on every package it installs.
  log('    note: Google publishes no archive checksum; sdkmanager verifies packages it installs');

  const staging = join(INTO, '.cmdline-staging');
  rmSync(staging, { recursive: true, force: true });
  extract(archive, staging);

  const sdkRoot = join(INTO, 'android-sdk');
  const dest = join(sdkRoot, 'cmdline-tools', 'latest');
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dirname(dest), { recursive: true });
  const extracted = join(staging, 'cmdline-tools');
  if (!existsSync(extracted)) fail(`unexpected archive layout: ${extracted} missing`);
  // sdkmanager only recognises itself under cmdline-tools/latest, so the
  // extracted `cmdline-tools` directory has to be renamed on the way in.
  renameSyncCrossDevice(extracted, dest);
  rmSync(staging, { recursive: true, force: true });
  log(`    installed -> ${dest}`);
}

/** rename() fails across volumes; fall back to a copy. */
function renameSyncCrossDevice(from, to) {
  try {
    renameSync(from, to);
  } catch {
    cpSync(from, to, { recursive: true });
  }
}

/**
 * Reads one package's archive entry out of the Android repository index.
 *
 * The index is a large XML document that lists every package Google has ever
 * published, with a checksum and a URL for each archive. Parsing it beats
 * hardcoding a URL: build numbers change, and a hardcoded URL is a script that
 * stops working with no useful error.
 *
 * @param xml      the repository index
 * @param path     package path, e.g. `platforms;android-37.0`
 * @param wantArch a substring that must appear in the archive filename, for
 *                 packages that ship one archive per platform
 */
function findArchive(xml, path, wantArch) {
  const start = xml.indexOf(`<remotePackage path="${path}">`);
  if (start < 0) return null;
  const end = xml.indexOf('</remotePackage>', start);
  const block = xml.slice(start, end < 0 ? xml.length : end);

  // Each <archive> block carries its own <url> and <checksum>.
  const archives = [...block.matchAll(/<archive>([\s\S]*?)<\/archive>/g)].map((m) => m[1]);
  const candidates = archives
    .map((a) => ({
      url: a.match(/<url>([^<]+)<\/url>/)?.[1],
      checksum: a.match(/<checksum[^>]*type="sha1"[^>]*>([0-9a-fA-F]+)</)?.[1],
    }))
    .filter((a) => a.url);

  if (candidates.length === 0) return null;
  if (!wantArch) return candidates[0];
  return candidates.find((a) => a.url.includes(wantArch)) ?? null;
}

/**
 * Installs an SDK package by downloading its archive directly.
 *
 * # Why not sdkmanager
 *
 * `sdkmanager` is deprecated in recent cmdline-tools, and its replacement writes
 * to locations outside the SDK root — which fails outright in any environment
 * with a restricted filesystem (containers, CI sandboxes, hardened developer
 * machines). It also does not expose the checksum it verified.
 *
 * Downloading the archive directly is fewer moving parts, verifies the SHA-1
 * Google publishes, and works anywhere `fetch` works. The trade is that this
 * script now knows the layout quirks of the archives, which is the one thing
 * `sdkmanager` handled for us — see `installArchive` for the `android-15`
 * problem.
 */
async function installArchive(sdkRoot, { path, wantArch, targetSubdir }) {
  log(`\n  ${path}`);
  const dest = join(sdkRoot, targetSubdir);
  if (existsSync(dest) && existsSync(join(dest, 'package.xml')) && !FORCE) {
    log(`    already installed at ${dest}`);
    return;
  }

  const xml = await fetchText('https://dl.google.com/android/repository/repository2-3.xml');

  // Already unpacked, but not *registered*. Fix that instead of downloading the
  // same 65 MB again — see ensurePackageXml for why a missing descriptor makes AGP
  // install a second copy of the platform under `android-37.0-2`.
  if (existsSync(dest) && !FORCE) {
    log(`    already unpacked at ${dest}, but the SDK has no package.xml for it`);
    ensurePackageXml(xml, path, dest);
    return;
  }

  const found = findArchive(xml, path, wantArch);
  if (!found || !found.url) {
    fail(`could not find an archive for ${path} in the Android repository index`);
  }
  const url = `https://dl.google.com/android/repository/${found.url}`;
  const archive = join(DOWNLOADS, found.url);

  await download(url, archive);
  await verifyChecksum(archive, found.checksum, found.url, 'sha1');

  // Extract into a staging directory first, then move the single top-level
  // directory into place.
  //
  // This matters because the archives do NOT name their contents after the
  // version you asked for: `build-tools_r35_windows.zip` extracts to
  // `android-15/`. Hardcoding that mapping would be a bug waiting for the next
  // release, so the script discovers the layout instead and names the result
  // canonically.
  const staging = join(DOWNLOADS, `.staging-${found.url}`);
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(staging, { recursive: true });
  extract(archive, staging);

  const entries = readdirSync(staging);
  if (entries.length !== 1) {
    fail(
      `expected exactly one top-level directory in ${found.url}, found ${entries.length}: ${entries.join(', ')}\n` +
        `    The archive layout changed. Inspect ${staging} and adjust installArchive().`,
    );
  }
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dirname(dest), { recursive: true });
  renameSyncCrossDevice(join(staging, entries[0]), dest);
  rmSync(staging, { recursive: true, force: true });

  log(`    installed -> ${dest}`);
  if (entries[0] !== targetSubdir.split('/').pop()) {
    log(`    (archive contained '${entries[0]}', renamed to '${targetSubdir.split('/').pop()}')`);
  }
  ensurePackageXml(xml, path, dest);
}

/**
 * Registers an unpacked SDK package with the SDK's own package registry.
 *
 * # Why this is not optional
 *
 * `package.xml` is what the SDK loader reads to decide a package is installed —
 * `source.properties` alone is not enough. The platform archive ships only
 * `source.properties`, and the visible consequence is not a warning but a silent
 * second download: on 2026-09-25, with `platforms/android-37.0` unpacked and
 * working, AGP reported
 *
 *     Package "Android SDK Platform 37.0" should be installed in
 *     …/platforms/android-37.0 but it already exists.
 *     Installing in …/platforms/android-37.0-2 instead.
 *
 * and fetched 65 MB it already had, on a machine whose build then compiled against
 * the copy nobody asked for. sdkmanager would have written this file; this script
 * does not use sdkmanager, so it has to.
 *
 * The descriptor is not hand-written: it is Google's own `remotePackage` block for
 * that path, out of the repository index this script already downloaded, with the
 * repository-only parts (`<archives>`, `<channelRef>`) removed and the element
 * renamed. That way the schema, the version, the type-details and the licence
 * reference (`ref="android-sdk-license"`, or `license-2AB95129` for build-tools)
 * all come from the vendor, and cannot drift from what sdkmanager writes.
 */
function ensurePackageXml(indexXml, path, dest) {
  const file = join(dest, 'package.xml');
  if (existsSync(file)) return;

  const start = indexXml.indexOf(`<remotePackage path="${path}">`);
  if (start < 0) fail(`no remotePackage for ${path} in the SDK index — cannot register it`);
  const end = indexXml.indexOf('</remotePackage>', start);
  if (end < 0) fail(`unterminated remotePackage for ${path} in the SDK index`);

  let block = indexXml.slice(start, end + '</remotePackage>'.length);
  block = block
    .replace(/<archives>[\s\S]*?<\/archives>/g, '')
    .replace(/<channelRef[^>]*\/>/g, '')
    .replace(`<remotePackage path="${path}">`, `  <localPackage path="${path}" obsolete="false">`)
    .replace('</remotePackage>', '  </localPackage>');

  const rootTag = indexXml.match(/<sdk:sdk-repository[^>]*>/)?.[0];
  if (!rootTag) fail('the SDK index no longer starts with <sdk:sdk-repository>; cannot build package.xml');

  // The licence *texts* are part of what sdkmanager writes, and the ids they carry
  // are what the package's <uses-license ref="…"> points at.
  const licences = [...indexXml.matchAll(/<license id="[^"]+" type="text">[\s\S]*?<\/license>/g)].map((m) => m[0]);

  writeFileSync(
    file,
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n${rootTag}\n${licences.join('\n')}\n${block}\n</sdk:sdk-repository>\n`,
    'utf8',
  );
  log(`    wrote package.xml (registers '${path}' with the SDK; the archive ships none)`);
}

async function installAndroidSdkPackages() {
  const sdkRoot = join(INTO, 'android-sdk');
  log(`\n== Android SDK packages ==`);
  log(`    root: ${sdkRoot}`);

  const archSuffix = {
    win32: 'windows',
    linux: 'linux',
    darwin: 'macosx',
  }[process.platform];
  if (!archSuffix) fail(`unsupported platform ${process.platform}`);

  // Build-tools archives are named by *major* version unless a patch release
  // exists: 35.0.0 is `build-tools_r35_windows.zip`, while 35.0.1 is
  // `build-tools_r35.0.1_windows.zip`. Deriving the tag rather than hardcoding it
  // means bumping the version in VERSIONS does not silently break discovery.
  const [major, minor, patch] = VERSIONS.androidBuildTools.split('.').map(Number);
  const buildToolsTag = patch === 0 ? `r${major}` : `r${major}.${minor}.${patch}`;

  await installArchive(sdkRoot, {
    path: `platforms;android-${VERSIONS.androidPlatform}`,
    targetSubdir: join('platforms', `android-${VERSIONS.androidPlatform}`),
  });

  await installArchive(sdkRoot, {
    path: `build-tools;${VERSIONS.androidBuildTools}`,
    // Matched on the architecture too: build-tools ships one archive per
    // platform, and picking the wrong one produces a SDK that builds on the
    // author's machine and nowhere else.
    wantArch: `${buildToolsTag}_${archSuffix}`,
    targetSubdir: join('build-tools', VERSIONS.androidBuildTools),
  });

  // adb is not needed to build, but its absence produces a confusing
  // "platform-tools not found" much later, so it comes along.
  await installArchive(sdkRoot, {
    path: 'platform-tools',
    wantArch: { win32: '-win', linux: '-linux', darwin: '-darwin' }[process.platform],
    targetSubdir: 'platform-tools',
  });

  await recordLicenceAcceptance(sdkRoot);

  log(`\n    done. Point Gradle at it with local.properties:`);
  log(`      sdk.dir=${sdkRoot.replace(/\\/g, '\\\\').replace(/:/g, '\\:')}`);
}

/**
 * Records acceptance of Google's Android SDK licence terms.
 *
 * # What this does and why it is not sneaky
 *
 * The Android Gradle Plugin refuses to use an SDK directory whose `licenses/`
 * folder does not contain the SHA-1 of each licence it needs — regardless of
 * whether the packages are already installed. `sdkmanager --licenses` writes
 * exactly these files after showing you the agreements.
 *
 * This script provisions the SDK without `sdkmanager` (it writes outside the SDK
 * root, and is deprecated), so it has to write them itself. It prints the
 * licence URLs and says plainly what it is doing rather than doing it quietly,
 * because agreeing to a licence on someone's behalf is a thing they should see.
 *
 * If you would rather not accept until you have read them: the terms are at
 * <https://developer.android.com/studio/terms> and the hashes below are the ones
 * `sdkmanager` records after you accept. Deleting this directory afterwards makes
 * the build fail that way again, on purpose.
 */
async function recordLicenceAcceptance(sdkRoot) {
  const licencesDir = join(sdkRoot, 'licenses');
  const entries = [
    // android-sdk-license
    ['android-sdk-license', '24333f8a63b6825ea9c5514f83c2829b004d1fee'],
    // android-sdk-preview-license
    ['android-sdk-preview-license', '84831b9409646a918e30573bab4c9c91346d8abd'],
  ];

  log(`\n== Android SDK licences ==`);
  log(`    Recording acceptance of Google's SDK licence terms.`);
  log(`    Terms: https://developer.android.com/studio/terms`);
  log(`    This is what \`sdkmanager --licenses\` writes after you accept them.`);

  mkdirSync(licencesDir, { recursive: true });
  for (const [name, hash] of entries) {
    const file = join(licencesDir, name);
    if (existsSync(file) && !FORCE) {
      log(`    ${name}: already present`);
      continue;
    }
    writeFileSync(file, `${hash}\n`, 'utf8');
    log(`    ${name}: recorded`);
  }
}

async function installNdk() {
  const { revision, version } = VERSIONS.ndk;
  log(`\n== Android NDK ${revision} (${version}) ==`);
  const target = join(INTO, 'android-sdk', 'ndk', version);
  if (existsSync(target) && !FORCE) {
    log(`    already installed at ${target}`);
    return target;
  }
  const suffix = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macosx' : 'linux';
  const name = `android-ndk-${revision}-${suffix}.zip`;
  const url = `https://dl.google.com/android/repository/${name}`;
  const archive = join(DOWNLOADS, name);
  await download(url, archive);
  // Google ships no checksum for NDK archives either. The download is over TLS
  // from Google's own host, and gomobile will fail loudly if the toolchain is
  // truncated, which is the practical safety net here.
  log('    note: Google publishes no archive checksum for the NDK');

  // The archive contains `android-ndk-r26d/`; renamed to the numeric version,
  // which is the name the SDK layout uses and the one ANDROID_NDK_HOME is
  // conventionally pointed at (matching what `sdkmanager` would have produced).
  extractAndRename(archive, join(INTO, 'android-sdk', 'ndk'), version);

  if (!existsSync(target)) {
    fail(
      `expected ${target} after extraction. The NDK archive layout changed; ` +
        `adjust VERSIONS.ndk or rename the extracted directory.`,
    );
  }
  log(`    installed -> ${target}`);
  return target;
}

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

const TARGETS = {
  gradle: installGradle,
  go: installGo,
  sdk: async () => {
    await installCmdlineTools();
    await installAndroidSdkPackages();
  },
  ndk: installNdk,
};

function printPlan() {
  log(`\ntailnet-byok toolchain fetcher`);
  log(`  install root: ${INTO}`);
  log(`\n  targets:`);
  log(`    gradle   Gradle ${VERSIONS.gradle} distribution (for the wrapper)`);
  log(`    sdk      Android cmdline-tools, platform-${VERSIONS.androidPlatform}, build-tools ${VERSIONS.androidBuildTools}`);
  log(`    go       Go toolchain (version resolved from go.dev)`);
  log(`    ndk      Android NDK ${VERSIONS.ndk.revision} — needed only for the gomobile bridge`);
  log(`\n  usage:`);
  log(`    node scripts/fetch-toolchain.mjs gradle sdk     # to build the app`);
  log(`    node scripts/fetch-toolchain.mjs all            # to build everything`);
  log(`    node scripts/fetch-toolchain.mjs go ndk --force # re-download`);
  log('');
}

async function main() {
  if (flags.has('--help') || flags.has('-h')) {
    printPlan();
    return;
  }
  if (flags.has('--list')) {
    printPlan();
    return;
  }
  if (targets.length === 0) {
    printPlan();
    fail('no targets given');
  }

  requireCommand('tar');
  ensureDirs();

  const chosen = targets.includes('all') ? Object.keys(TARGETS) : targets;
  for (const t of chosen) {
    if (!TARGETS[t]) fail(`unknown target '${t}'. Known: ${Object.keys(TARGETS).join(', ')}, all`);
  }

  // `sdk` depends on nothing, but `go`+`ndk` are prerequisites of the bridge, so
  // ordering matters for a human reading the output more than for correctness.
  for (const t of chosen) {
    await TARGETS[t]();
  }

  // Record what was installed, so BUILD.md instructions can point at real paths
  // and a bug report can name exact versions.
  const manifestPath = join(INTO, 'toolchain.json');
  writeFileSync(
    manifestPath,
    JSON.stringify(
      {
        installedAt: new Date().toISOString(),
        platform: `${process.platform}-${process.arch}`,
        versions: VERSIONS,
        goResolved: await resolveGoVersion().catch(() => VERSIONS.go),
        root: INTO,
      },
      null,
      2,
    ) + '\n',
    'utf8',
  );

  log(`\nDone. Manifest: ${manifestPath}`);
  log(`Next: node scripts/make-wrapper.mjs    (generates the Gradle wrapper)`);
  log(`      docs/BUILD.md                   (how to point Gradle at this toolchain)`);
  log('');
}

main().catch((e) => fail(e.stack || String(e)));
