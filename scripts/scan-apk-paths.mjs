#!/usr/bin/env node
/**
 * Refuses to publish an APK that carries the machine it was built on.
 *
 * A Go binary records the paths its source files were compiled from, and
 * `libgojni.so` is a Go binary. `build-bridge.mjs` passes `GOFLAGS=-trimpath`,
 * which rewrites source paths to module-relative form — but it does **not**
 * rewrite the C toolchain's include paths or the main module's directory in the
 * build info, so those are absolute paths taken from wherever the NDK and the
 * module happened to live. Measured on the maintainer's machine: 65 occurrences
 * of one project directory inside `libgojni.so`, and an older library carried the
 * Windows user name as well.
 *
 * Neither is anybody's business, and a public repository is the wrong place to
 * find out. This runs as a release step, so the check is a gate rather than a
 * habit.
 *
 * What it looks for, and why these markers:
 *
 *   * `:\Users\` / `:/Users/`   — a Windows or macOS user profile path. The user
 *     name is in there, which is the one part that is unambiguously personal.
 *   * `.toolchain`              — this project's own fetched SDK/NDK location, so
 *     the marker appears whenever the toolchain travelled with the checkout.
 *   * `AppData/Local/Temp`      — `gomobile`'s temporary work directory, recorded
 *     before `-trimpath` ever gets a say.
 *   * `gomobile-work-`          — the same thing by its own name.
 *
 * A runner's equivalents (`/home/runner/…`, `/usr/local/lib/android/…`) name
 * nobody, which is why the fix is "build it on a runner" rather than "hide it".
 *
 * Usage: node scripts/scan-apk-paths.mjs <file.apk|file.aar>
 */

import fs from 'node:fs';
import zlib from 'node:zlib';

const MARKERS = [
  ':\\Users\\',
  ':/Users/',
  '.toolchain',
  'AppData\\Local\\Temp',
  'AppData/Local/Temp',
  'gomobile-work-',
];

/** Every entry in a zip, read the way the format actually says to read it. */
function entries(buffer) {
  // End of central directory: scan back from the end of the file.
  const EOCD = 0x06054b50;
  let eocd = -1;
  for (let i = buffer.length - 22; i >= 0 && i > buffer.length - 66000; i--) {
    if (buffer.readUInt32LE(i) === EOCD) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a zip: no end-of-central-directory record');

  const count = buffer.readUInt16LE(eocd + 10);
  let p = buffer.readUInt32LE(eocd + 16);
  const out = [];

  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(p) !== 0x02014b50) throw new Error(`bad central directory at entry ${i}`);
    const method = buffer.readUInt16LE(p + 10);
    const compressedSize = buffer.readUInt32LE(p + 20);
    const nameLength = buffer.readUInt16LE(p + 28);
    const extraLength = buffer.readUInt16LE(p + 30);
    const commentLength = buffer.readUInt16LE(p + 32);
    const localOffset = buffer.readUInt32LE(p + 42);
    const name = buffer.subarray(p + 46, p + 46 + nameLength).toString('utf8');

    if (!name.endsWith('/')) {
      // The local header repeats the name and extra lengths, and those are the
      // ones that locate the data: the central directory's copy can differ.
      const localNameLength = buffer.readUInt16LE(localOffset + 26);
      const localExtraLength = buffer.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + localNameLength + localExtraLength;
      const raw = buffer.subarray(start, start + compressedSize);
      try {
        const plain = method === 0 ? raw : method === 8 ? zlib.inflateRawSync(raw) : null;
        if (plain) out.push({ name, body: plain });
      } catch {
        // A member that will not inflate is not evidence of a leak; the APK's
        // own signatures and the installer will have plenty to say about it.
      }
    }
    p += 46 + nameLength + extraLength + commentLength;
  }
  return out;
}

const file = process.argv[2];
if (!file) {
  console.error('usage: node scripts/scan-apk-paths.mjs <file.apk|file.aar>');
  process.exit(2);
}

const found = new Map();
for (const entry of entries(fs.readFileSync(file))) {
  const text = entry.body.toString('latin1');
  for (const marker of MARKERS) {
    const at = text.indexOf(marker);
    if (at < 0) continue;
    const where = found.get(marker) ?? { entries: new Set(), sample: '' };
    where.entries.add(entry.name);
    if (!where.sample) {
      where.sample = text
        .slice(Math.max(0, at - 48), at + 48)
        .replace(/[^\x20-\x7e]/g, '.');
    }
    found.set(marker, where);
  }
}

if (found.size === 0) {
  console.log(`ok: ${file} names no build machine`);
  process.exit(0);
}

console.error(`::error::${file} records the machine it was built on.`);
for (const [marker, where] of found) {
  console.error(`  ${marker}  in ${[...where.entries].join(', ')}`);
  console.error(`    ...${where.sample}...`);
}
console.error('  Build the native library where the paths are neutral (CI) and rebuild.');
process.exit(1);
