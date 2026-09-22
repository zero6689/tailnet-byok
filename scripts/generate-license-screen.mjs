#!/usr/bin/env node
/**
 * Generates the in-app licence index from `THIRD-PARTY-NOTICES.md` + `licenses/`.
 *
 * # Why this file exists
 *
 * `app/build.gradle.kts` excludes `META-INF/LICENSE`, `META-INF/NOTICE` and
 * `META-INF/DEPENDENCIES` from the APK, because the gomobile AAR carries Go
 * licence files that collide with AGP's packaging. That keeps the build working
 * and removes the notice from the *binary* — which is exactly where BSD-3-Clause
 * clause 1 and Apache-2.0 section 4 require it to be. The repository's notices
 * are complete; an APK built from it carried none of them.
 *
 * The fix is a screen in the app that reproduces the same texts. This script
 * produces what that screen reads, from the same committed sources the release
 * page links to, so the two cannot drift: if `licenses/` gains a file or the
 * notices table changes, `--check` fails until the assets are regenerated.
 *
 * # Why the input is the notices file and not `go list -deps` again
 *
 * `scripts/third-party-licenses.mjs` already resolves the module graph and is
 * the file that knows how to resolve it (it needs Go, a platform, and a module
 * cache). This script deliberately does not repeat that: it reads the generated
 * notices, which are committed, and fails loudly if a table row has no licence
 * file or an entry has no module. One resolver, two consumers.
 *
 * # Why TSV and plain-text files
 *
 * The app has no JSON dependency and no Android runtime in its unit tests. A
 * tab-separated index parsed by a pure Kotlin function is testable on the JVM,
 * and the licence bodies stay byte-identical copies of `licenses/*` rather than
 * being re-encoded through a serialiser.
 *
 * Usage:
 *   node scripts/generate-license-screen.mjs            # write the assets
 *   node scripts/generate-license-screen.mjs --check    # report only, write nothing
 */

import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const noticesPath = join(root, 'THIRD-PARTY-NOTICES.md');
const licencesDir = join(root, 'licenses');
const projectLicencePath = join(root, 'LICENSE');
const assetsDir = join(root, 'app/src/main/assets/licenses');
const indexName = 'notices.tsv';

const check = process.argv.includes('--check');
const problems = [];
const fail = (message) => problems.push(message);

/** Parse the notices table: `## <licence> (N modules)` groups, each with `### `licenses/<file>``. */
function parseNotices(text) {
  const entries = [];
  let licence = null;
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd();
    const group = line.match(/^## (\S+) \((\d+) modules?\)$/);
    if (group) {
      licence = group[1];
      continue;
    }
    const heading = line.match(/^### `licenses\/(.+?)`$/);
    if (heading) {
      if (!licence) {
        fail(`a licence file is listed under no licence group: ${heading[1]}`);
        continue;
      }
      entries.push({ licence, file: heading[1], modules: [] });
      continue;
    }
    const row = line.match(/^\| `([^`]+)` \| [^|]+ \|$/);
    if (row && entries.length > 0 && licence) {
      entries[entries.length - 1].modules.push(row[1]);
    }
  }
  return entries;
}

const notices = readFileSync(noticesPath, 'utf8');
const entries = parseNotices(notices);

if (entries.length === 0) fail('no licence entries found in THIRD-PARTY-NOTICES.md');
for (const entry of entries) {
  if (entry.modules.length === 0) fail(`${entry.file} names no module`);
  const source = join(licencesDir, entry.file);
  if (!existsSync(source)) {
    fail(`${entry.file} is named in the notices but missing from licenses/`);
    continue;
  }
  const body = readFileSync(source);
  if (body.length < 200) fail(`${entry.file} is suspiciously short (${body.length} bytes)`);
  if (body.includes(0)) fail(`${entry.file} contains a NUL byte`);
}

// The app's own licence goes first: it is the licence a user is most likely to be
// looking for, and it is the only entry here that is not third-party.
const projectLicence = readFileSync(projectLicencePath);
if (projectLicence.length < 200) fail('LICENSE is suspiciously short');

const index = [];
const bodies = new Map();

bodies.set('project-mit.txt', projectLicence);
index.push({ key: 'project-mit', licence: 'MIT', modules: 'This app', asset: 'project-mit.txt' });

for (const entry of entries) {
  const asset = entry.file;
  bodies.set(asset, readFileSync(join(licencesDir, entry.file)));
  index.push({
    key: entry.file.replace(/\.txt$/, ''),
    licence: entry.licence,
    modules: entry.modules.join(', '),
    asset,
  });
}

const notes = [
  'This app ships a native library, `libgojni.so`, that statically links the Go',
  'modules listed here. BSD-3-Clause and Apache-2.0 attach conditions to binary',
  'distribution: the copyright notice, the list of conditions, the disclaimer, and',
  'any upstream NOTICE, so the texts travel inside the app rather than only in the',
  'repository. The same files are published at THIRD-PARTY-NOTICES.md.',
  '',
  'Scope: the Go modules linked into the native bridge. The Android and Kotlin',
  'dependencies that also ship in this APK come from the Gradle graph and are not',
  'inventoried here yet, so this list is the bridge, not a claim of completeness.',
];

const lines = [
  ...notes.map((note) => (note === '' ? '#' : `# ${note}`)),
  ...index.map((e) => [e.key, e.licence, e.modules, e.asset].join('\t')),
];
const indexText = `${lines.join('\n')}\n`;

if (!/^[\x09\x0a\x20-\x7e]*$/.test(indexText)) fail('the generated index is not pure ASCII');
for (const [key, licence, , asset] of index.map((e) => [e.key, e.licence, e.modules, e.asset])) {
  if (!key || !licence || !asset) fail(`an index row is missing a field: ${key} / ${licence} / ${asset}`);
  if (/\t/.test(licence) || /\t/.test(asset)) fail(`a field contains a tab: ${key}`);
}

const byLicence = new Map();
for (const entry of index) byLicence.set(entry.licence, (byLicence.get(entry.licence) ?? 0) + 1);

if (problems.length > 0) {
  console.error('generate-license-screen: refusing to write');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

function existingAssets() {
  if (!existsSync(assetsDir)) return new Map();
  const files = new Map();
  for (const name of readdirSync(assetsDir)) {
    if (name.endsWith('.txt') || name === indexName) {
      files.set(name, readFileSync(join(assetsDir, name)));
    }
  }
  return files;
}

const wanted = new Map(bodies);
wanted.set(indexName, Buffer.from(indexText, 'utf8'));

if (check) {
  const current = existingAssets();
  const drift = [];
  for (const [name, body] of wanted) {
    const have = current.get(name);
    if (!have) drift.push(`missing: ${name}`);
    else if (!have.equals(body)) drift.push(`differs: ${name}`);
  }
  for (const name of current.keys()) {
    if (!wanted.has(name)) drift.push(`stale (no longer generated): ${name}`);
  }
  if (drift.length > 0) {
    console.error('generate-license-screen --check: the in-app licence assets are out of date');
    for (const item of drift) console.error(`  - ${item}`);
    console.error('  run: node scripts/generate-license-screen.mjs');
    process.exit(1);
  }
  console.log(
    `generate-license-screen --check: up to date (${index.length} entries, ` +
      `${[...wanted.values()].reduce((n, b) => n + b.length, 0)} bytes)`,
  );
} else {
  rmSync(assetsDir, { recursive: true, force: true });
  mkdirSync(assetsDir, { recursive: true });
  for (const [name, body] of wanted) writeFileSync(join(assetsDir, name), body);
  const summary = [...byLicence.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([licence, count]) => `${licence} ${count}`)
    .join(', ');
  console.log(`generate-license-screen: wrote ${wanted.size} files to app/src/main/assets/licenses`);
  console.log(`  ${index.length} entries (${summary})`);
  console.log(`  index: ${Buffer.byteLength(indexText, 'utf8')} bytes`);
}
