#!/usr/bin/env node
/**
 * Generates `THIRD-PARTY-NOTICES.md` and `licenses/` from the Go module graph
 * that is actually linked into the native library.
 *
 * # Why this file exists
 *
 * The AAR ships `libgojni.so`, which statically links the whole `tsnet` world:
 * `tailscale.com` (BSD-3-Clause), `gvisor.dev/gvisor` (Apache-2.0),
 * `wireguard-go`, and a few dozen smaller modules. BSD-3-Clause clause 1 and
 * Apache-2.0 section 4 attach *conditions to binary distribution*: the copyright
 * notice, the list of conditions, and the disclaimer have to travel with the
 * binary, and a NOTICE file — where the upstream module has one — has to be
 * reproduced too. A repository with no third-party licence text satisfies
 * neither, however readable its own `LICENSE` is.
 *
 * # Why the module list comes from `go list -deps`
 *
 * `go.mod` lists what the module *may* use; `go list -deps` lists what this
 * package *does* use, for one target platform. Only the second is the set of
 * modules whose code is in the binary, and notice files are supposed to name the
 * software that is actually there. The platform matters as well: `GOOS=android`
 * pulls in files that a host build does not compile, and a licence obligation is
 * not platform-dependent.
 *
 * # Why pipes are avoided
 *
 * Every child process here writes to an open file descriptor rather than a pipe.
 * Pipes are denied outright in some sandboxes and containers, with an `EPERM`
 * that names the shell and explains nothing; a file descriptor uses only the
 * filesystem and works everywhere. Same reason as `build-bridge.mjs`.
 *
 * Usage:
 *   node scripts/third-party-licenses.mjs            # write the notices
 *   node scripts/third-party-licenses.mjs --check    # report only, write nothing
 */

import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GO_MODULE = join(REPO_ROOT, 'tailnet');
const NOTICES = join(REPO_ROOT, 'THIRD-PARTY-NOTICES.md');
const LICENSE_DIR = join(REPO_ROOT, 'licenses');

/**
 * The platforms whose dependency closure we describe.
 *
 * All four of the AAR's ABIs, unioned. A licence obligation does not depend on
 * the architecture, and a module reached only through, say, 386-specific
 * assembly would be missing from an arm64-only list.
 */
const TARGETS = ['arm64', 'arm', 'amd64', '386'];

/**
 * Modules the audit called out by name, with the licence each must resolve to.
 *
 * These are asserted rather than merely printed: they are the two obligations
 * that motivated this script, and a future `go mod tidy` that swapped one for
 * something permissive should be a loud failure, not a quieter notices file.
 */
const REQUIRED = {
  'tailscale.com': 'BSD-3-Clause',
  'gvisor.dev/gvisor': 'Apache-2.0',
};

const isWindows = process.platform === 'win32';
const CHECK_ONLY = process.argv.includes('--check');

function log(msg) {
  console.log(msg);
}

function fail(msg) {
  console.error(`\n  error: ${msg}\n`);
  process.exit(1);
}

/**
 * Runs a command with its stdout redirected to a file; never opens a pipe.
 *
 * Returns `{ status, text }` — the captured output is handed back even when the
 * command failed, because "go list failed" without Go's own message is the least
 * useful error a build script can print.
 */
function captureToFile(command, commandArgs, options = {}) {
  const scratch = join(REPO_ROOT, '.toolchain');
  mkdirSync(scratch, { recursive: true });
  const outFile = join(scratch, `.capture-${process.pid}-${Date.now()}.txt`);

  let fd;
  try {
    fd = openSync(outFile, 'w');
  } catch (e) {
    return { status: -1, text: `could not open a capture file (${e.code})` };
  }
  try {
    const result = spawnSync(command, commandArgs, {
      stdio: ['ignore', fd, fd],
      // A shell is only needed to resolve a *bare* command name on Windows. Going
      // through cmd.exe re-parses the arguments, and `go list -f` templates are
      // full of characters cmd.exe treats as its own (`|`, quotes) — which turns
      // a template into "not recognized as an internal or external command".
      shell: isWindows && !/[\\/]/.test(command),
      ...options,
    });
    return { status: result.status ?? -1, text: readFileSync(outFile, 'utf8') };
  } finally {
    closeSync(fd);
    rmSync(outFile, { force: true });
  }
}

/**
 * The Go environment the build uses, so this script runs in the same places.
 *
 * Go's caches and its config directory default to locations under `$HOME`, which
 * are read-only in a sandbox or a container; the resulting failure surfaces as a
 * permission error deep inside the toolchain. `build-bridge.mjs` redirects them
 * into `.toolchain/` for the same reason — mirrored here, and only when unset,
 * so a developer with a warm shared cache keeps using it.
 */
function goEnv(goarch) {
  const env = {
    ...process.env,
    GOOS: 'android',
    GOARCH: goarch,
    CGO_ENABLED: '0',
    GOTOOLCHAIN: 'local',
    APPDATA: join(REPO_ROOT, '.toolchain', 'appdata'),
    XDG_CONFIG_HOME: join(REPO_ROOT, '.toolchain', 'xdg-config'),
  };
  mkdirSync(env.APPDATA, { recursive: true });
  mkdirSync(env.XDG_CONFIG_HOME, { recursive: true });

  const localState = join(REPO_ROOT, '.toolchain');
  if (existsSync(localState)) {
    for (const [name, subdir] of [
      ['GOPATH', 'gopath'],
      ['GOCACHE', 'gocache'],
      ['GOMODCACHE', join('gopath', 'pkg', 'mod')],
    ]) {
      if (!process.env[name]) env[name] = join(localState, subdir);
    }
  }
  return env;
}

/** Prefers the checked-out toolchain, exactly as `build-bridge.mjs` does. */
function goBinary() {
  const toolchain = join(REPO_ROOT, '.toolchain');
  if (existsSync(toolchain)) {
    for (const entry of safeReaddir(toolchain)) {
      if (!entry.startsWith('go')) continue;
      const candidate = join(toolchain, entry, 'bin', isWindows ? 'go.exe' : 'go');
      if (existsSync(candidate)) return candidate;
    }
  }
  return 'go';
}

function safeReaddir(dir) {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

/**
 * The linked module set, as `path|version|directory` entries, unioned over the
 * AAR's ABIs. `.Module.Replace` is honoured because a replaced module's source —
 * and so its licence — lives in the replacement's directory, not the original's.
 */
function linkedModules(goPath) {
  const format =
    '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|' +
    '{{if .Module.Replace}}{{.Module.Replace.Dir}}{{else}}{{.Module.Dir}}{{end}}{{end}}';

  const modules = new Map();
  for (const goarch of TARGETS) {
    const { status, text } = captureToFile(goPath, ['list', '-deps', '-f', format, '.'], {
      cwd: GO_MODULE,
      env: goEnv(goarch),
    });
    if (status !== 0) {
      fail(
        `go list -deps failed for android/${goarch}:\n` +
          text
            .split(/\r?\n/)
            .filter(
              (line) =>
                !/^go\.exe :|^At line:|^\+|CategoryInfo|FullyQualifiedErrorId|^\s*$/.test(line),
            )
            .map((line) => `      ${line}`)
            .join('\n') +
          '\n    The module cache must be populated first:\n' +
          '      node scripts/build-bridge.mjs --check',
      );
    }
    for (const line of text.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      const [path, version, dir] = trimmed.split('|');
      if (!path || !version) continue; // standard library: no module
      if (!modules.has(path)) modules.set(path, { path, version, dir });
    }
  }
  return [...modules.values()].sort((a, b) => a.path.localeCompare(b.path));
}

const LICENSE_NAME = /^(licen[cs]e|copying|notice|unlicense|patents|legal|about)/i;

/**
 * True for `LICENSE`, `LICENSE.txt`, `COPYING.md` — but not `license.go`.
 *
 * A module's licence file is usually bare (`LICENSE`) and occasionally carries
 * one of a handful of extensions; anything else that merely starts with the word
 * is source code or data, not a licence.
 */
function looksLikeLicenseFile(name) {
  if (!LICENSE_NAME.test(name)) return false;
  const dot = name.lastIndexOf('.');
  if (dot < 0) return true;
  return /^\.(txt|md|markdown|rst|html)$/i.test(name.slice(dot));
}

/** Licence-bearing files at a module root; a one-level search is the fallback. */
function licenseFiles(dir) {
  const found = [];
  for (const entry of safeReaddir(dir)) {
    if (!looksLikeLicenseFile(entry)) continue;
    const full = join(dir, entry);
    try {
      if (statSync(full).isFile()) found.push(full);
    } catch {
      /* unreadable entry: ignore */
    }
  }
  if (found.length > 0) return found.sort();

  for (const sub of safeReaddir(dir)) {
    const nested = join(dir, sub);
    try {
      if (!statSync(nested).isDirectory()) continue;
    } catch {
      continue;
    }
    for (const entry of safeReaddir(nested)) {
      if (!looksLikeLicenseFile(entry)) continue;
      const full = join(nested, entry);
      try {
        if (statSync(full).isFile()) found.push(full);
      } catch {
        /* ignore */
      }
    }
  }
  return found.sort();
}

/**
 * Identifies a licence from its text.
 *
 * Deliberately a small set of distinctive phrases rather than a classifier: a
 * wrong answer here is a compliance statement, so anything unrecognised stays
 * `UNKNOWN` and gets looked at by a human instead of being guessed at.
 */
function classify(text) {
  const t = text.replace(/\s+/g, ' ');
  if (/Apache License\s+Version 2\.0/i.test(t)) return 'Apache-2.0';
  if (/Mozilla Public License\s+Version 2\.0/i.test(t)) return 'MPL-2.0';
  if (/Permission is hereby granted, free of charge/i.test(t)) return 'MIT';
  if (/Permission to use, copy, modify, and(\/or)? distribute this software/i.test(t)) return 'ISC';
  if (/Redistribution and use in source and binary forms/i.test(t)) {
    return /Neither the name/i.test(t) ? 'BSD-3-Clause' : 'BSD-2-Clause';
  }
  if (/This is free and unencumbered software released into the public domain/i.test(t)) {
    return 'Unlicense';
  }
  if (/CC0 1\.0 Universal/i.test(t)) return 'CC0-1.0';
  if (/Boost Software License - Version 1\.0/i.test(t)) return 'BSL-1.0';
  if (/This software is provided 'as-is', without any express or implied warranty/i.test(t)) {
    return 'Zlib';
  }
  if (/GNU (LESSER )?GENERAL PUBLIC LICENSE/i.test(t)) {
    return /LESSER/.test(t) ? 'LGPL' : 'GPL';
  }
  return 'UNKNOWN';
}

/**
 * The `Copyright …` lines a redistributor is obliged to reproduce.
 *
 * Anchored on a year when possible, because a licence body is full of sentences
 * that merely start with the word "copyright" — quoting "copyright notice that is
 * included in or attached to the work" back at a reader is noise, not attribution.
 * Apache-2.0's appendix of `[yyyy] [name of copyright owner]` placeholders is
 * dropped for the same reason.
 */
function copyrightLines(text) {
  const starts = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length < 200)
    .filter((line) => /^copyright\b/i.test(line) || /^\(c\)\s/i.test(line));

  const dated = starts.filter((line) => /^(copyright|\(c\))[^0-9]{0,12}\d{4}/i.test(line));
  const chosen =
    dated.length > 0
      ? dated
      : starts.filter(
          (line) => !/notice|licen[cs]e|law|owner|holder|included|attached|must/i.test(line),
        );

  return [...new Set(chosen)].slice(0, 4);
}

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function slug(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function normalize(text) {
  return text.replace(/\r\n/g, '\n').replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim() + '\n';
}

function main() {
  const goPath = goBinary();
  log('== third-party licences ==');
  log(`  target:    android/${TARGETS.join(', android/')}`);
  log(`  go:        ${goPath}`);

  const modules = linkedModules(goPath);
  if (modules.length === 0) fail('no modules found in the dependency closure');
  log(`  modules:   ${modules.length}`);

  // Group identical licence texts so a 50-module MIT pile produces one file.
  const groups = new Map(); // hash -> { text, id, modules: [] }
  const notices = []; // per-module NOTICE payloads, reproduced verbatim
  const missing = [];
  const unknown = [];

  for (const mod of modules) {
    const files = licenseFiles(mod.dir);
    if (files.length === 0) {
      missing.push(mod);
      continue;
    }

    let primary = null;
    for (const file of files) {
      const text = normalize(readFileSync(file, 'utf8'));
      const name = file.split(/[\\/]/).pop();
      if (/^notice/i.test(name)) {
        notices.push({ module: mod, text });
        continue;
      }
      if (!primary) primary = { file, name, text };
    }
    if (!primary) {
      missing.push(mod);
      continue;
    }

    const id = classify(primary.text);
    if (id === 'UNKNOWN') unknown.push(mod);
    const hash = sha256(primary.text);
    if (!groups.has(hash)) {
      groups.set(hash, { text: primary.text, id, files: new Set(), modules: [] });
    }
    const group = groups.get(hash);
    group.files.add(primary.name);
    group.modules.push({ ...mod, copyright: copyrightLines(primary.text) });
  }

  // --- Checks that must fail loudly -----------------------------------------
  if (missing.length > 0) {
    fail(
      `${missing.length} linked module(s) carry no licence file:\n` +
        missing.map((m) => `      ${m.path} ${m.version}  (${m.dir})`).join('\n') +
        '\n    A module with no licence is not redistributable. Resolve it before publishing.',
    );
  }
  for (const [path, expected] of Object.entries(REQUIRED)) {
    const group = [...groups.values()].find((g) => g.modules.some((m) => m.path === path));
    if (!group) fail(`${path} is no longer in the dependency closure; the notices would be wrong`);
    if (group.id !== expected) {
      fail(`${path} resolved to ${group.id}, expected ${expected}. Re-check the notices by hand.`);
    }
  }

  const byId = new Map();
  for (const group of groups.values()) {
    byId.set(group.id, (byId.get(group.id) ?? 0) + group.modules.length);
  }

  log('  licences:  ' + [...byId.entries()].map(([id, n]) => `${id}×${n}`).join(', '));
  if (unknown.length > 0) {
    log(`  unrecognised text in ${unknown.length} module(s): ` + unknown.map((m) => m.path).join(', '));
  }

  if (CHECK_ONLY) {
    log('  --check: nothing written');
    return;
  }

  // --- licenses/ ------------------------------------------------------------
  rmSync(LICENSE_DIR, { recursive: true, force: true });
  mkdirSync(LICENSE_DIR, { recursive: true });

  const fileForGroup = new Map();
  const usedNames = new Set();
  for (const [hash, group] of groups) {
    // Named after the first module that carries the text, not `MIT-3.txt`: two
    // modules under the same licence usually differ only in their copyright line,
    // and "which one is this?" is the question a reader actually has.
    const first = [...group.modules].sort((a, b) => a.path.localeCompare(b.path))[0];
    const base = `${group.id}--${slug(first.path)}`;
    let name = `${base}.txt`;
    for (let n = 2; usedNames.has(name); n++) name = `${base}-${n}.txt`;
    usedNames.add(name);
    writeFileSync(join(LICENSE_DIR, name), group.text, 'utf8');
    fileForGroup.set(hash, name);
  }
  for (const [i, notice] of notices.entries()) {
    const name = `NOTICE-${slug(notice.module.path)}${notices.length > 1 && i > 0 ? `-${i}` : ''}.txt`;
    writeFileSync(join(LICENSE_DIR, name), notice.text, 'utf8');
    notice.file = name;
  }

  // --- THIRD-PARTY-NOTICES.md ----------------------------------------------
  const lines = [];
  lines.push('# Third-party notices');
  lines.push('');
  lines.push(
    'This project links third-party software into the native library that ships inside',
  );
  lines.push(
    '`tailnet.aar` (`libgojni.so`). The table below lists every module that is actually',
  );
  lines.push(
    `linked for any of the AAR's ABIs (\`${TARGETS.join('`, `')}\`), with the licence each one is`,
  );
  lines.push('under, and the full licence text is reproduced in [`licenses/`](licenses/).');
  lines.push('');
  lines.push(
    'Generated by `scripts/third-party-licenses.mjs` from `go list -deps` — not written by',
  );
  lines.push('hand, and regenerated whenever the dependency graph moves.');
  lines.push('');
  lines.push(
    '**Scope.** This file covers the Go modules linked into the native bridge. The Android',
  );
  lines.push(
    'and Kotlin dependencies that ship in the APK come from the Gradle graph and are',
  );
  lines.push('inventoried separately.');
  lines.push('');
  lines.push('## Summary');
  lines.push('');
  lines.push('| Licence | Modules |');
  lines.push('| --- | ---: |');
  for (const [id, n] of [...byId.entries()].sort()) lines.push(`| ${id} | ${n} |`);
  lines.push(`| **total** | **${modules.length}** |`);
  lines.push('');

  if (notices.length > 0) {
    lines.push('## NOTICE files');
    lines.push('');
    lines.push(
      'These modules ship a `NOTICE` file. Apache-2.0 section 4(d) requires its contents to',
    );
    lines.push('be reproduced alongside the licence text, so they are kept verbatim.');
    lines.push('');
    for (const notice of notices) {
      lines.push(`- \`${notice.module.path}\` → [\`licenses/${notice.file}\`](licenses/${notice.file})`);
    }
    lines.push('');
  }

  lines.push('## Modules by licence');
  lines.push('');
  lines.push(
    'Modules under the same licence rarely carry byte-identical files — usually only the',
  );
  lines.push(
    'copyright line differs — so each distinct copy is reproduced verbatim in its own file.',
  );
  lines.push('');

  const groupsById = new Map();
  for (const [hash, group] of groups) {
    if (!groupsById.has(group.id)) groupsById.set(group.id, []);
    groupsById.get(group.id).push({ hash, group });
  }

  for (const id of [...groupsById.keys()].sort()) {
    const variants = groupsById.get(id);
    const moduleCount = variants.reduce((n, v) => n + v.group.modules.length, 0);
    lines.push(`## ${id} (${moduleCount} module${moduleCount === 1 ? '' : 's'})`);
    lines.push('');

    for (const { hash, group } of variants.sort((a, b) =>
      a.group.modules[0].path.localeCompare(b.group.modules[0].path),
    )) {
      const file = fileForGroup.get(hash);
      lines.push(`### \`licenses/${file}\``);
      lines.push('');
      const copyright = [...new Set(group.modules.flatMap((m) => m.copyright))];
      if (copyright.length > 0) {
        lines.push('Upstream copyright notice:');
        lines.push('');
        for (const line of copyright) lines.push(`> ${line}`);
      } else {
        lines.push(
          'The file carries the unmodified licence text, with no copyright line of its own.',
        );
      }
      lines.push('');
      lines.push('| Module | Version |');
      lines.push('| --- | --- |');
      for (const mod of [...group.modules].sort((a, b) => a.path.localeCompare(b.path))) {
        lines.push(`| \`${mod.path}\` | ${mod.version} |`);
      }
      lines.push('');
    }
  }

  writeFileSync(NOTICES, lines.join('\n'), 'utf8');
  log(`  wrote:     THIRD-PARTY-NOTICES.md (${lines.length} lines)`);
  log(`  wrote:     licenses/ (${groups.size + notices.length} files)`);
}

main();
