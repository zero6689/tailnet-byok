#!/usr/bin/env node
/**
 * Builds the gomobile AAR that gives the app its embedded Tailscale node.
 *
 * # Why a script per platform is not the answer
 *
 * The upstream projects this borrows from (`netbirdio/android-client`,
 * `tailscale/tailscale-android`) each ship a build script, and each script is
 * shell-specific. The steps are identical on every platform, only the paths
 * differ, so this is one cross-platform script with the platform differences
 * isolated in `platformPaths()`.
 *
 * # The four things that actually matter here
 *
 *   1. **gomobile and gobind must come from the same x/mobile revision.**
 *      Installing them separately is the classic failure: the generated Java
 *      bindings describe an ABI the runtime library does not implement, and the
 *      error surfaces at the first call rather than at build time.
 *   2. **`-androidapi` must match the app's minSdk.** Binding for a lower API
 *      promises support the app does not offer.
 *   3. **`-javapkg` must match the import in `TsnetConnectivityProvider.kt`.**
 *      Change one without the other and you get a hundred unresolved references.
 *   4. **`CGO_ENABLED=0`.** `tsnet` runs on a userspace network stack — there is
 *      no tun device to open — so nothing needs cgo, and leaving it on would
 *      make the build depend on the host toolchain.
 *
 * It also injects the Tailscale version stamps with `-ldflags` and checks that
 * they landed in the native library; without them the node reports its version as
 * "1.102.4-ERR-BuildInfo". See the comment at "Version stamps" for why gomobile
 * cannot produce a version on its own.
 *
 * Usage:
 *   node scripts/build-bridge.mjs
 *   node scripts/build-bridge.mjs --gomobile-version v0.0.0-20250101000000-abcdef123456
 *   node scripts/build-bridge.mjs --check        # report the toolchain, build nothing
 */

import {
  existsSync,
  mkdirSync,
  openSync,
  closeSync,
  readdirSync,
  rmSync,
  statSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GO_MODULE = join(REPO_ROOT, 'tailnet');
const OUT_AAR = join(REPO_ROOT, 'app', 'libs', 'tailnet.aar');

// `-javapkg` is a PREFIX, not a replacement. gomobile appends the Go package
// name, so binding the Go package `mobile` with this value yields
//
//     io.github.zero6689.tailnetbyok.mobile.Mobile
//
// which is what `TsnetConnectivityProvider.kt` imports. (Getting this wrong is
// the classic integration failure: the AAR builds fine, and the Kotlin side
// reports a hundred unresolved references with no mention of the real cause.
// The facade check at the end of this script exists to catch it.)
const JAVAPKG = 'io.github.zero6689.tailnetbyok';

/** The Go package inside tailnet/ that gets bound. Must match `package mobile`. */
const GO_PACKAGE = 'mobile';

/** Fully-qualified Java name of the generated facade, for verification. */
const FACADE_CLASS = `${JAVAPKG}.${GO_PACKAGE}.Mobile`;

const ANDROID_API = 26; // must equal minSdk in gradle/libs.versions.toml

/**
 * Go module proxies to try, in order, when GOPROXY is not already set.
 *
 * The official proxy first, then two mirrors that are reachable from networks
 * where `proxy.golang.org` is not. All of these serve the *same* modules: a Go
 * module proxy is a content-addressed cache, and every module it returns is
 * verified against the checksum database unless you explicitly disable that, so
 * whatever the Go toolchain downloads is the same bytes the official proxy
 * would have served. The mirror is a transport, not a source of trust.
 */
const GOPROXY_CANDIDATES = [
  'https://proxy.golang.org,direct',
  'https://goproxy.cn,direct',
  'https://goproxy.io,direct',
];

const args = process.argv.slice(2);
const CHECK_ONLY = args.includes('--check');
const versionFlag = args.indexOf('--gomobile-version');
const GOMOBILE_VERSION = versionFlag >= 0 && args[versionFlag + 1] ? args[versionFlag + 1] : 'latest';

const isWindows = process.platform === 'win32';

function log(msg) {
  console.log(msg);
}

function fail(msg) {
  console.error(`\n  error: ${msg}\n`);
  process.exit(1);
}

function run(command, commandArgs, options = {}) {
  log(`\n$ ${command} ${commandArgs.join(' ')}`);
  const result = spawnSync(command, commandArgs, {
    stdio: 'inherit',
    shell: isWindows,
    ...options,
  });
  return result.status === 0;
}

/**
 * Runs a command and returns its stdout, without ever creating a pipe.
 *
 * # Why not just `encoding: 'utf8'`
 *
 * Setting `encoding` makes Node capture the child's output through a pipe, and
 * pipes are blocked outright in some restricted environments — CI sandboxes,
 * containers with a seccomp profile, hardened developer machines — with a bare
 * `EPERM` that names `cmd.exe` and tells you nothing about why.
 *
 * Redirecting the child's stdout to an open file descriptor and reading the file
 * afterwards produces the same result using only the filesystem. It is slightly
 * more code and it works everywhere, which for a build script is the whole
 * argument.
 *
 * Returns null when the command fails, so callers can treat "could not
 * determine" as a distinct case from "determined it is wrong".
 */
function captureToFile(command, commandArgs, options = {}) {
  const outFile = join(REPO_ROOT, '.toolchain', `.capture-${process.pid}-${Date.now()}.txt`);
  mkdirSync(dirname(outFile), { recursive: true });

  let fd;
  try {
    fd = openSync(outFile, 'w');
  } catch (e) {
    log(`  warning: could not open a capture file (${e.code}); continuing without the value`);
    return null;
  }

  try {
    const result = spawnSync(command, commandArgs, {
      stdio: ['ignore', fd, fd],
      shell: isWindows,
      ...options,
    });
    if (result.status !== 0) return null;
    return readFileSync(outFile, 'utf8').trim() || null;
  } finally {
    closeSync(fd);
    rmSync(outFile, { force: true });
  }
}

/**
 * Locates the pieces of the toolchain, preferring a checked-out `.toolchain/`
 * (what `fetch-toolchain.mjs` produces) and falling back to the environment.
 */
function platformPaths() {
  const toolchain = join(REPO_ROOT, '.toolchain');
  const dev = {
    goBin: null,
    sdkRoot: process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || null,
    ndkHome: process.env.ANDROID_NDK_HOME || null,
  };

  // Go: .toolchain/go*/bin
  if (existsSync(toolchain)) {
    for (const entry of safeReaddir(toolchain)) {
      if (!entry.startsWith('go')) continue;
      const bin = join(toolchain, entry, 'bin');
      if (existsSync(join(bin, isWindows ? 'go.exe' : 'go'))) dev.goBin = bin;
    }
    const sdk = join(toolchain, 'android-sdk');
    if (existsSync(sdk)) {
      dev.sdkRoot = dev.sdkRoot || sdk;
      const ndkDir = join(sdk, 'ndk');
      if (existsSync(ndkDir)) {
        const versions = safeReaddir(ndkDir).sort().reverse();
        if (versions.length > 0) dev.ndkHome = dev.ndkHome || join(ndkDir, versions[0]);
      }
    }
  }

  return dev;
}

function safeReaddir(dir) {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

/**
 * Finds a JDK — not a JRE.
 *
 * `gomobile bind` shells out to `javac` to compile the Java bindings it
 * generates, so a runtime is not enough and the failure is unambiguous once you
 * see it: `javac: executable file not found in %PATH%`, after gomobile has
 * already done all the expensive Go compilation.
 *
 * Resolution order: `JAVA_HOME`, then a `jdk*` directory under `.toolchain/`
 * (what a self-contained checkout would have), then whatever is on PATH.
 * Returns null when nothing was found locally, in which case gomobile will use
 * PATH itself — and the caller reports that rather than failing early, because a
 * JDK on PATH is a perfectly normal setup.
 */
function findJdk() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);

  const toolchain = join(REPO_ROOT, '.toolchain');
  if (existsSync(toolchain)) {
    for (const entry of safeReaddir(toolchain)) {
      if (/^jdk/i.test(entry)) candidates.push(join(toolchain, entry));
    }
  }

  for (const home of candidates) {
    const javac = join(home, 'bin', isWindows ? 'javac.exe' : 'javac');
    if (existsSync(javac)) return home;
  }
  return null;
}

function main() {
  const dev = platformPaths();

  // --- Report ---------------------------------------------------------------

  log('== toolchain ==');
  const goPath = dev.goBin ? join(dev.goBin, isWindows ? 'go.exe' : 'go') : 'go';
  const goVersion = captureToFile(goPath, ['version']);
  if (!goVersion) {
    fail(
      'Go not found.\n' +
        '    Run:  node scripts/fetch-toolchain.mjs go\n' +
        '    Or install Go and put it on PATH.',
    );
  }
  log(`  go:        ${goVersion} (${goPath})`);

  const requiredGo = readFileSync(join(GO_MODULE, 'go.mod'), 'utf8').match(/^go\s+([\d.]+)/m)?.[1];
  log(`  go.mod:    requires >= ${requiredGo}`);

  if (!dev.sdkRoot || !existsSync(dev.sdkRoot)) {
    fail(
      'Android SDK not found.\n' +
        '    Run:  node scripts/fetch-toolchain.mjs sdk\n' +
        '    Or set ANDROID_HOME.',
    );
  }
  log(`  SDK:       ${dev.sdkRoot}`);

  if (!dev.ndkHome || !existsSync(dev.ndkHome)) {
    fail(
      'Android NDK not found.\n' +
        '    gomobile needs it to link the JNI shim.\n' +
        '    Run:  node scripts/fetch-toolchain.mjs ndk',
    );
  }
  log(`  NDK:       ${dev.ndkHome}`);

  // gomobile needs javac, and it needs it late — after the Go side has already
  // been compiled. Report it here, at the top, so a missing JDK is a one-line
  // problem instead of a five-minute one.
  const jdkHome = findJdk();
  const javacOnPath = captureToFile(isWindows ? 'javac.exe' : 'javac', ['-version']);
  if (!jdkHome && !javacOnPath) {
    fail(
      'no JDK found.\n' +
        '    gomobile compiles the generated Java bindings with javac, so a JDK is\n' +
        '    required — a JRE is not enough.\n' +
        '    Install one and set JAVA_HOME, or put javac on PATH.',
    );
  }
  log(`  JDK:       ${jdkHome ?? '(javac found on PATH)'}`);

  const env = {
    ...process.env,
    PATH: [
      jdkHome ? join(jdkHome, 'bin') : null,
      dev.goBin,
      dev.sdkRoot ? join(dev.sdkRoot, 'platform-tools') : null,
      process.env.PATH,
    ]
      .filter(Boolean)
      .join(process.platform === 'win32' ? ';' : ':'),
    ANDROID_HOME: dev.sdkRoot,
    ANDROID_SDK_ROOT: dev.sdkRoot,
    ANDROID_NDK_HOME: dev.ndkHome,
    CGO_ENABLED: '0',
    GOTOOLCHAIN: 'local',

    // Keep Go's own config and telemetry writes inside the checkout.
    //
    // `go` resolves its per-user config directory from %APPDATA% (or
    // $XDG_CONFIG_HOME/$HOME elsewhere), and where that directory is read-only it
    // prints, on *every* invocation:
    //
    //   error acquiring upload token: creating token file: ... Access is denied.
    //
    // Nothing is wrong with the build, but the line lands in captured command
    // output and in build logs where it reads like a failure -- it has already
    // been mistaken for one. `GOTELEMETRY=off` does not stop it: this toolchain
    // ignores that variable (measured -- `go env GOTELEMETRY` still answers
    // "local" while the warning keeps coming). Redirecting the directory does, and
    // as a side effect a build no longer writes outside the workspace.
    APPDATA: join(REPO_ROOT, '.toolchain', 'appdata'),
    XDG_CONFIG_HOME: join(REPO_ROOT, '.toolchain', 'xdg-config'),

    // Force javac to read gomobile's generated Java as UTF-8.
    //
    // gomobile copies each exported Go function's doc comment verbatim into the
    // Java it emits, and javac otherwise uses the *platform* default charset --
    // GBK on a Chinese Windows, windows-1252 on a US one. One non-ASCII
    // character in a Go doc comment (an em dash, a curly quote, an ellipsis, or
    // any CJK text) then fails the bind with "unmappable character" errors in a
    // file nobody wrote by hand, after the whole Go tree has already compiled.
    //
    // `tailnet/tailnet.go` is ASCII-only for the same reason. This makes the
    // build survive someone adding a comment in their own language, which is a
    // reasonable thing for a contributor to do and should not be a build error.
    JDK_JAVAC_OPTIONS: '-encoding UTF-8',
  };

  // Go's caches default to locations under the user's home directory, which is
  // not writable in a container, a CI sandbox, or any environment where `$HOME`
  // is read-only — and the resulting failure is a permission error deep inside
  // the module downloader, which does not obviously point at the cause.
  // Redirecting them into `.toolchain/` keeps a checkout self-contained, which
  // is also what makes it safe to delete.
  //
  // Only when unset: a developer with a warm, shared module cache should keep
  // using it, because populating a fresh one means re-downloading the entire
  // tsnet dependency graph.
  const localState = join(REPO_ROOT, '.toolchain');
  mkdirSync(env.APPDATA, { recursive: true });
  mkdirSync(env.XDG_CONFIG_HOME, { recursive: true });
  if (existsSync(localState)) {
    for (const [name, subdir] of [
      ['GOPATH', 'gopath'],
      ['GOCACHE', 'gocache'],
      ['GOMODCACHE', join('gopath', 'pkg', 'mod')],
    ]) {
      if (!process.env[name]) env[name] = join(localState, subdir);
    }
    if (env.GOPATH) {
      log(`\n  workspace-local Go cache: ${env.GOPATH}`);
      log(`  (set GOPATH yourself to reuse an existing module cache instead)`);
    }
  }

  // --- Locate the installed Go tools ---------------------------------------
  //
  // This must run *after* the GOPATH redirect above, using the same environment
  // that `go install` will use. Resolving it from the ambient GOPATH instead
  // produces a path that looks plausible, points at a directory that does not
  // contain the tools, and fails later with "The system cannot find the path
  // specified" from a shell rather than from anything that names the real
  // problem. That was this script's bug, and it is the reason the two are
  // deliberately adjacent.
  const gopath = env.GOPATH || captureToFile(goPath, ['env', 'GOPATH'], { env });
  const binDir = gopath ? join(gopath, 'bin') : null;
  const gomobilePath = binDir ? join(binDir, isWindows ? 'gomobile.exe' : 'gomobile') : null;
  const hasGomobile = Boolean(gomobilePath && existsSync(gomobilePath));
  log(`  gomobile:  ${hasGomobile ? gomobilePath : `not installed yet (will be installed into ${binDir})`}`);

  if (!binDir) {
    fail('could not determine GOPATH, so there is nowhere to install gomobile into');
  }

  // `--check` reports the whole toolchain, including whether the Go tools are
  // present, and stops before anything slow or mutating. Everything above is
  // discovery; everything below downloads or builds.
  if (CHECK_ONLY) {
    log('\n--check: toolchain report only, nothing built.\n');
    return;
  }

  // --- Module proxy ---------------------------------------------------------
  //
  // `proxy.golang.org` is unreachable from some networks — most notably in
  // mainland China, where the connection simply times out after the standard TCP
  // retry window. The failure is indistinguishable from "the internet is down"
  // unless you already know, and it lands on the very first command a new
  // contributor runs.
  //
  // So: if GOPROXY is already set, use it and do not second-guess the user. If it
  // is not, try the official proxy first and fall back to mirrors, keeping
  // whichever one works for every later `go` command in this run.
  if (!process.env.GOPROXY) {
    log(`\n== Go module proxy ==`);
    log(`  GOPROXY is not set; probing candidates`);
    for (const candidate of GOPROXY_CANDIDATES) {
      log(`\n  trying ${candidate}`);
      const probe = spawnSync(
        goPath,
        ['mod', 'download', '-x', 'golang.org/x/mobile@latest'],
        {
          cwd: GO_MODULE,
          stdio: 'ignore',
          shell: isWindows,
          env: { ...env, GOPROXY: candidate, GOFLAGS: '-mod=mod' },
        },
      );
      if (probe.status === 0) {
        env.GOPROXY = candidate;
        log(`  ok: using GOPROXY=${candidate}`);
        break;
      }
      log(`  unreachable, trying the next candidate`);
    }
    if (!env.GOPROXY) {
      fail(
        'no Go module proxy is reachable.\n' +
          `    Tried: ${GOPROXY_CANDIDATES.join(', ')}\n` +
          '    Set GOPROXY yourself, e.g. GOPROXY=direct (fetch straight from the\n' +
          '    origin over git) or a mirror you trust.',
      );
    }
  } else {
    log(`\n== Go module proxy ==\n  using GOPROXY=${process.env.GOPROXY} from the environment`);
  }

  // --- gomobile -------------------------------------------------------------

  if (!hasGomobile) {
    log(`\n== installing gomobile and gobind @ ${GOMOBILE_VERSION} ==`);
    // Both from the same revision, on purpose. See the header.
    if (!run(goPath, ['install', `golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}`], { env })) {
      fail('failed to install gomobile');
    }
    if (!run(goPath, ['install', `golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}`], { env })) {
      fail('failed to install gobind');
    }
  }

  env.PATH = `${binDir}${process.platform === 'win32' ? ';' : ':'}${env.PATH}`;

  // --- Dependencies ---------------------------------------------------------

  log('\n== resolving Go modules ==');
  if (!run(goPath, ['mod', 'tidy'], { cwd: GO_MODULE, env })) fail('go mod tidy failed');
  if (!run(goPath, ['mod', 'download'], { cwd: GO_MODULE, env })) fail('go mod download failed');

  // gomobile must be recorded as a *tool* dependency of the module being bound.
  //
  // Since Go 1.24 a module can declare the command-line tools it needs with a
  // `tool` directive, and `gomobile bind` insists on finding `x/mobile` there —
  // an ordinary import is not enough, and a plain `go mod tidy` will drop the
  // directory again. Without this the bind fails with "requires
  // golang.org/x/mobile in the current module, but it is not in the module
  // dependency graph", which is a clear message but arrives only after the whole
  // dependency tree has been downloaded.
  //
  // `go get -tool` is idempotent, and the resulting `tool` directive is
  // committed, so this is a no-op on a warm checkout.
  log('\n== recording gomobile as a module tool ==');
  if (!run(goPath, ['get', '-tool', 'golang.org/x/mobile/cmd/gobind'], { cwd: GO_MODULE, env })) {
    fail('could not record golang.org/x/mobile as a tool dependency');
  }
  // Re-tidy so go.mod and go.sum reflect the tool directive as well.
  if (!run(goPath, ['mod', 'tidy'], { cwd: GO_MODULE, env })) fail('go mod tidy failed after go get -tool');

  const tsnetVersion = captureToFile(goPath, ['list', '-m', 'tailscale.com'], { cwd: GO_MODULE, env });
  log(`  resolved: ${tsnetVersion ?? 'unknown'}`);

  // --- Version stamps -------------------------------------------------------
  //
  // Without these the AAR reports its Tailscale version as
  // "1.102.4-ERR-BuildInfo", and that placeholder is read by people, not just by
  // logs: it is the node's own startup line
  // (`control: [v1] HostInfo: {"IPNVersion":"1.102.4-ERR-BuildInfo",...}`), the
  // version column of this device in the tailnet admin console, and
  // HostInfo.IPNVersion on the control plane. Nothing is broken when it appears
  // -- but a bug report that says "ERR-BuildInfo" names no release, which is the
  // entire purpose of reporting a version.
  //
  // Where it comes from: version.Long()/Short() fall back to the Go tool's
  // embedded VCS data (tailscale.com/version/version.go), and gomobile builds
  // from a *copy* of the module in a temp work dir (its own tests show
  // `PWD=$WORK/ios/src-arm64`), where there is no .git for the Go tool to read.
  // No amount of committing in this repository fixes that, so the stamps have to
  // be handed to the linker -- and `gomobile bind` forwards -ldflags to every
  // `go build` it runs (golang.org/x/mobile/cmd/gomobile/build.go).
  //
  // The values are the forms documented on version.Long() itself, not invented
  // ones:
  //
  //   shortStamp  "1.102.4"                       the release form, x.y.z
  //   longStamp   "1.102.4-devYYYYMMDD-t<9hex>"   the plain `go build` form
  //
  // Short() deliberately keeps its clean release form: it is the string the
  // library parses rather than prints (version.IsUnstableBuild splits on "." and
  // reads the minor; the auto-updater compares it against release versions), and
  // a suffix buys nothing there. Long() is the one that gets displayed, so the
  // provenance goes there. One difference from upstream, which reads both fields
  // out of Tailscale's own checkout: the date and the commit below describe THIS
  // repository, because this is the checkout the bridge was built from.
  // The version is read off the module line rather than searched for loose
  // numbers in the output: `captureToFile` sends a command's stderr into the same
  // file as its stdout, and this `go` build prints a telemetry warning to stderr
  // on every invocation in a sandbox whose `%APPDATA%` is read-only. Anchoring on
  // "tailscale.com vX.Y.Z" is what keeps that warning from being read as part of
  // the version.
  const tsRelease =
    /^tailscale\.com v(\d+\.\d+\.\d+)$/m.exec(tsnetVersion ?? '') ??
    /^\s*tailscale\.com\s+v(\d+\.\d+\.\d+)\s*$/m.exec(readFileSync(join(GO_MODULE, 'go.mod'), 'utf8'));
  if (!tsRelease) {
    fail(
      `could not read an x.y.z version out of \`go list -m tailscale.com\` ` +
        `(got ${JSON.stringify(tsnetVersion)})`,
    );
  }
  const tsShortStamp = tsRelease[1];

  // The commit that the AAR is being built from, when this is a git checkout.
  // `captureToFile` returns null both for "the command failed" and for "the
  // command printed nothing", so the two cases are split here rather than
  // guessed at: no commit means no provenance is claimed at all.
  const bridgeCommit = captureToFile('git', ['rev-parse', '--short=9', 'HEAD'], { cwd: REPO_ROOT });
  // `git status --porcelain` prints nothing for a clean tree, so a non-null
  // result here means the working tree had uncommitted changes at build time.
  // Worth carrying into the version string: it is the difference between "built
  // from a revision anyone can check out" and "built from somebody's work in
  // progress", and the phone is where that question gets asked.
  const bridgeDirty =
    bridgeCommit !== null &&
    captureToFile('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: REPO_ROOT }) !== null;
  const bridgeDate =
    (captureToFile('git', ['show', '-s', '--format=%cs', 'HEAD'], { cwd: REPO_ROOT }) ?? '').replace(/-/g, '') ||
    new Date().toISOString().slice(0, 10).replace(/-/g, '');

  const tsLongStamp =
    `${tsShortStamp}-dev${bridgeDate}` +
    (bridgeCommit ? `-t${bridgeCommit}` : '') +
    (bridgeDirty ? '-dirty' : '');
  const linkerStamps = [
    `-X tailscale.com/version.shortStamp=${tsShortStamp}`,
    `-X tailscale.com/version.longStamp=${tsLongStamp}`,
  ].join(' ');

  log('\n== version stamps (handed to the linker; see the comment in this script) ==');
  log(`  shortStamp: ${tsShortStamp}`);
  log(`  longStamp : ${tsLongStamp}`);
  if (bridgeCommit === null) log('  note: not a git checkout (or git is unavailable) -- no commit is claimed');
  // Same file the phone-side check reads, so "what should be in this AAR" never
  // has to be retyped (and therefore never drifts from what was built).
  writeFileSync(
    join(REPO_ROOT, '.toolchain', 'bridge-version.json'),
    `${JSON.stringify(
      {
        tailscale: tsShortStamp,
        shortStamp: tsShortStamp,
        longStamp: tsLongStamp,
        commit: bridgeCommit,
        dirty: bridgeDirty,
        builtAt: new Date().toISOString(),
      },
      null,
      2,
    )}\n`,
  );

  // Record every version that ends up inside the AAR. This runs here, after the
  // module graph is resolved, because `gomobile version` only reports anything
  // from inside a module — before this point it prints a module-resolution error
  // instead of a version, which looks like a broken toolchain and is not one.
  log('\n== recording the resolved versions (pin these for reproducibility) ==');
  run(goPath, ['version'], { env });
  const gomobileVersion = captureToFile(
    join(binDir, isWindows ? 'gomobile.exe' : 'gomobile'),
    ['version'],
    { cwd: GO_MODULE, env },
  );
  log(`  gomobile:  ${gomobileVersion ?? '(no version reported)'}`);

  log('\n== vet ==');
  if (!run(goPath, ['vet', './...'], { cwd: GO_MODULE, env })) fail('go vet found problems');

  // --- Bind -----------------------------------------------------------------

  log('\n== gomobile init ==');
  if (!run(join(binDir, isWindows ? 'gomobile.exe' : 'gomobile'), ['init'], { cwd: GO_MODULE, env })) {
    fail('gomobile init failed');
  }

  // The bind runs from INSIDE the Go module, binding `.`.
  //
  // This is not cosmetic. gomobile resolves "the current module" by looking for
  // a go.mod in the working directory or one of its parents, and it needs that
  // module to hold the `tool` directive above. Invoking it from the repository
  // root — where there is no go.mod, and where the package path would be
  // `./tailnet` — produces "requires golang.org/x/mobile in the current module,
  // but it is not in the module dependency graph" even when the directive is
  // present and correct. The message points at the dependency; the problem is
  // the working directory.
  log(`\n== gomobile bind -> ${OUT_AAR} ==`);
  mkdirSync(dirname(OUT_AAR), { recursive: true });
  const bound = run(
    join(binDir, isWindows ? 'gomobile.exe' : 'gomobile'),
    [
      'bind',
      '-target=android',
      `-androidapi=${ANDROID_API}`,
      `-javapkg=${JAVAPKG}`,
      // The version stamps, forwarded by gomobile to every `go build` it runs.
      '-ldflags',
      linkerStamps,
      '-o',
      OUT_AAR,
      '.',
    ],
    // `shell: false` on purpose, even on Windows and against this script's own
    // default: `linkerStamps` is the one argument here that contains spaces, and
    // a shell would split it into five, leaving gomobile with a -ldflags value of
    // "-X" and four stray arguments. gomobile is invoked by absolute path, so no
    // shell is needed for anything else either.
    { cwd: GO_MODULE, env, shell: false },
  );
  if (!bound) fail('gomobile bind failed');

  // --- Verify ---------------------------------------------------------------

  if (!existsSync(OUT_AAR)) fail(`${OUT_AAR} was not produced`);
  const size = statSync(OUT_AAR).size;
  log(`\nAAR: ${(size / 1048576).toFixed(1)} MB`);
  if (size < 1_000_000) {
    fail('the AAR is implausibly small — gomobile probably produced an empty binding');
  }

  // The generated facade is what TsnetConnectivityProvider imports. Check it by
  // name: a missing facade produces a hundred unresolved references, and the
  // actual cause is easy to miss in that noise.
  log('\n== verifying the generated Java facade ==');
  const jar = join(REPO_ROOT, '.toolchain', 'classes.jar');
  mkdirSync(dirname(jar), { recursive: true });
  if (!run('tar', ['-xf', OUT_AAR, '-C', join(REPO_ROOT, '.toolchain'), 'classes.jar'], { cwd: REPO_ROOT })) {
    log('  note: could not extract classes.jar (tar may not read jars on this platform)');
  } else {
    const listing = captureToFile('tar', ['-tf', jar], { cwd: REPO_ROOT });
    const expected = `${FACADE_CLASS.replace(/\./g, '/')}.class`;
    if (listing && listing.includes(expected)) {
      log(`  ok: ${FACADE_CLASS} present`);
    } else {
      log(`  WARNING: ${expected} not found in the AAR.`);
      log(`  The Kotlin side imports ${FACADE_CLASS} — if this is missing, the app will not compile.`);
      log(`  Check that -javapkg and the Go package name above still both match.`);
    }
  }

  // --- The stamps really made it in -----------------------------------------
  //
  // The injection above can stop working without saying a word: a gomobile
  // release that no longer forwards -ldflags, a renamed stamp variable upstream,
  // a quoting mistake that splits the value. In every one of those cases the
  // build still succeeds and the only symptom appears later, on a phone, as a
  // version that reads ERR-BuildInfo again. So the shipped native library is
  // searched for the literal, which is a check that cannot pass by accident.
  //
  // Only longStamp is searched for. The short form is a poor witness: the module
  // embeds its own version.txt ("1.102.4"), so that byte sequence is in the
  // library whether or not anything was stamped.
  log('\n== verifying the version stamp is inside the AAR ==');
  const aarListing = captureToFile('tar', ['-tf', OUT_AAR], { cwd: REPO_ROOT });
  const nativeLibs = (aarListing ?? '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => /^jni\/[^/]+\/libgojni\.so$/.test(line));
  if (nativeLibs.length === 0) {
    log('  warning: the AAR lists no jni/*/libgojni.so entries -- stamp check skipped');
  } else {
    const checkDir = join(REPO_ROOT, '.toolchain', 'aar-check');
    rmSync(checkDir, { recursive: true, force: true });
    mkdirSync(checkDir, { recursive: true });
    const extracted = run('tar', ['-xf', OUT_AAR, '-C', checkDir, ...nativeLibs], { cwd: REPO_ROOT });
    if (!extracted) {
      log('  warning: could not extract the native libraries -- stamp check skipped');
    } else {
      const needle = Buffer.from(tsLongStamp, 'utf8');
      const missing = nativeLibs.filter((lib) => !readFileSync(join(checkDir, lib)).includes(needle));
      // The extracted copies are deleted either way: left behind, they are a
      // directory that later checks can compare against without noticing it came
      // from an older AAR.
      rmSync(checkDir, { recursive: true, force: true });
      if (missing.length > 0) {
        fail(
          'the version stamp did not reach the native library.\n' +
            `    missing ${JSON.stringify(tsLongStamp)} in: ${missing.join(', ')}\n` +
            '    The node would report 1.102.4-ERR-BuildInfo on the device.',
        );
      }
      log(`  ok: ${tsLongStamp} present in all ${nativeLibs.length} native libraries`);
    }
  }

  log(`\nDone. Build the app with the embedded node:`);
  log(`  ./gradlew assembleDebug -PwithTsnet=true`);
  log('');
}

main();

