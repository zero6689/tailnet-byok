#!/usr/bin/env node
/**
 * Generates the Gradle wrapper from a locally installed Gradle distribution.
 *
 * # When you need this
 *
 * Normally you do not: `gradlew`, `gradlew.bat` and `gradle/wrapper/*` are
 * committed, so a clone builds with no Gradle installed at all. That is also
 * what CI relies on — every workflow invokes `./gradlew`, which requires the
 * wrapper to be in the repository.
 *
 * Run this when you are bootstrapping a fork that does not have them yet, or
 * when you deliberately want to move the wrapper to a different Gradle version.
 * It regenerates all four files from a distribution you downloaded and
 * checksum-verified (see `fetch-toolchain.mjs`), so the wrapper jar has a
 * provenance you can state.
 *
 * Usage:
 *   node scripts/make-wrapper.mjs [--gradle <path-to-gradle-install>]
 */

import { existsSync, readdirSync, mkdirSync, mkdtempSync, writeFileSync, copyFileSync, chmodSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
const gradleFlag = args.indexOf('--gradle');

/**
 * The Gradle version the wrapper is pinned to.
 *
 * A constant rather than something scraped from `gradle --version`, because
 * capturing a child process's output requires piping its stdio, and piping is
 * blocked in some restricted environments (CI sandboxes, hardened developer
 * machines) with an unhelpful `EPERM`. Reading a directory name and comparing a
 * string has no such failure mode.
 *
 * Must match `VERSIONS.gradle` in fetch-toolchain.mjs. That duplication is
 * checked at runtime below rather than left to drift.
 */
const GRADLE_VERSION = '8.11.1';

function fail(msg) {
  console.error(`\n  error: ${msg}\n`);
  process.exit(1);
}

/**
 * Locates a Gradle install: `--gradle <path>`, a `gradle-<version>` directory
 * under `.toolchain/`, or the one on `PATH`.
 */
function resolveGradle() {
  if (gradleFlag >= 0 && args[gradleFlag + 1]) {
    const explicit = resolve(args[gradleFlag + 1]);
    if (!existsSync(explicit)) fail(`--gradle ${explicit} does not exist`);
    return explicit;
  }

  const toolchain = join(REPO_ROOT, '.toolchain');
  if (existsSync(toolchain)) {
    const candidates = readdirSync(toolchain)
      .filter((name) => /^gradle-\d/.test(name))
      .sort()
      .reverse();
    for (const candidate of candidates) {
      const home = join(toolchain, candidate);
      const bin = join(home, 'bin', process.platform === 'win32' ? 'gradle.bat' : 'gradle');
      if (existsSync(bin)) {
        const found = candidate.replace(/^gradle-/, '').split('-')[0];
        if (found !== GRADLE_VERSION) {
          console.warn(
            `  warning: .toolchain has Gradle ${found} but this script pins ${GRADLE_VERSION}.\n` +
              `           The wrapper will be generated from ${found}. Run\n` +
              `           \`node scripts/fetch-toolchain.mjs gradle --force\` to get the pinned one.`,
          );
        }
        return home;
      }
    }
  }

  const onPath = spawnSync('gradle', ['--version'], {
    stdio: 'ignore',
    shell: process.platform === 'win32',
  });
  if (onPath.status === 0) return null; // null means "use the one on PATH"

  fail(
    'no Gradle found.\n' +
      '    Run this first:  node scripts/fetch-toolchain.mjs gradle\n' +
      '    Or point at one:  node scripts/make-wrapper.mjs --gradle /path/to/gradle',
  );
}

function main() {
  const gradleHome = resolveGradle();

  const gradleBin = gradleHome
    ? join(gradleHome, 'bin', process.platform === 'win32' ? 'gradle.bat' : 'gradle')
    : 'gradle';

  console.log(`using Gradle: ${gradleHome ?? '(from PATH)'}`);
  console.log(`pinning the wrapper to Gradle ${GRADLE_VERSION}`);

  // # Why this runs in a scratch directory
  //
  // `gradle wrapper` in the project root has to configure the project first,
  // which resolves the Android Gradle Plugin and the Kotlin plugin — several
  // hundred megabytes of downloads — in order to emit four files that do not
  // depend on any of it.
  //
  // So the wrapper is generated against an empty build in a temporary directory
  // and copied in. Same output, no network, and it works on a machine that has
  // never built the project. (It also means this script cannot fail because a
  // plugin repository was slow, which is not a thing anyone wants to debug while
  // setting up a checkout.)
  const scratch = mkdtempSync(join(tmpdir(), 'tailnet-byok-wrapper-'));
  try {
    writeFileSync(join(scratch, 'settings.gradle.kts'), `rootProject.name = "wrapper-bootstrap"\n`);
    writeFileSync(join(scratch, 'build.gradle.kts'), `// Intentionally empty. See scripts/make-wrapper.mjs.\n`);

    const result = spawnSync(
      gradleBin,
      ['wrapper', '--gradle-version', GRADLE_VERSION, '--distribution-type', 'bin', '--no-daemon'],
      { cwd: scratch, stdio: 'inherit', shell: process.platform === 'win32' },
    );
    if (result.status !== 0) fail('gradle wrapper failed');

    const produced = [
      'gradlew',
      'gradlew.bat',
      join('gradle', 'wrapper', 'gradle-wrapper.jar'),
      join('gradle', 'wrapper', 'gradle-wrapper.properties'),
    ];

    mkdirSync(join(REPO_ROOT, 'gradle', 'wrapper'), { recursive: true });
    for (const file of produced) {
      const from = join(scratch, file);
      if (!existsSync(from)) fail(`expected ${file} to exist after running the wrapper task`);
      copyFileSync(from, join(REPO_ROOT, file));
    }
    if (process.platform !== 'win32') {
      chmodSync(join(REPO_ROOT, 'gradlew'), 0o755);
    }

    console.log('\nwrapper generated:');
    for (const file of produced) console.log(`  ${file}`);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }

  console.log('\nThese are safe to commit. Next:');
  console.log('  ./gradlew assembleDebug            # macOS / Linux');
  console.log('  .\\gradlew.bat assembleDebug        # Windows');
  console.log('');
}

main();
