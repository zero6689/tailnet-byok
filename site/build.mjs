#!/usr/bin/env node
/**
 * Builds the documentation site.
 *
 * This script has no dependencies on purpose. `npx`-ing a markdown renderer or
 * installing a static-site framework would mean the page that explains how to
 * reach your own infrastructure can fail to deploy because a registry was slow.
 * The site is therefore hand-written HTML, and this script does the three things
 * that hand-written HTML actually needs:
 *
 *   1. **Link validation.** A docs site's most common defect is a link that used
 *      to work. Every internal href, and every `#anchor` into another page, is
 *      resolved against the files that will actually be served. This is the check
 *      that earns the script its place.
 *   2. **Structural checks.** Each page must link the shared stylesheet, declare
 *      a title, and carry a viewport meta, because a page that silently loses its
 *      styling looks like a broken deploy rather than a typo.
 *   3. **Stamping.** `{{VERSION}}`, `{{BUILD_SHA}}` and `{{BUILD_DATE}}` are
 *      substituted so a reader can tell which commit they are looking at, which
 *      matters when the docs describe a build they have not installed.
 *
 * # Templates vs. output
 *
 * `site/*.html` are TEMPLATES and are never modified by this script. The built
 * site goes to `site/dist/`, which is git-ignored.
 *
 * That split is not fastidiousness. An earlier version stamped the templates in
 * place, which meant the first build destroyed every `{{...}}` token and every
 * later build shipped the same frozen version string — a documentation site that
 * silently stops reporting the build it came from, which is worse than one that
 * reports nothing.
 *
 * Usage:  node site/build.mjs
 * Env:    GITHUB_SHA, GITHUB_REF_NAME, GITHUB_REPOSITORY (all optional; CI sets them)
 */

import {
  readFileSync,
  writeFileSync,
  readdirSync,
  existsSync,
  statSync,
  rmSync,
  mkdirSync,
  copyFileSync,
} from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SITE_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(SITE_DIR, '..');
const OUT_DIR = join(SITE_DIR, 'dist');

/** Assets copied verbatim into the output. Everything else must be a template. */
const STATIC_ASSETS = ['styles.css', '.nojekyll', 'qr.js', 'lang.js'];

const errors = [];
const warnings = [];

// ---------------------------------------------------------------------------
// Version: read from the single source of truth rather than duplicated here.
// ---------------------------------------------------------------------------
function readVersion() {
  const candidates = [
    join(REPO_ROOT, 'app', 'build.gradle.kts'),
    join(REPO_ROOT, 'build.gradle.kts'),
  ];
  for (const file of candidates) {
    if (!existsSync(file)) continue;
    const match = readFileSync(file, 'utf8').match(/versionName\s*=\s*"([^"]+)"/);
    if (match) return match[1];
  }
  warnings.push('could not read versionName from any Gradle file; falling back to 0.0.0');
  return '0.0.0';
}

const TEMPLATES = readdirSync(SITE_DIR)
  .filter((f) => f.endsWith('.html'))
  .sort();

// ---------------------------------------------------------------------------
// Link validation
// ---------------------------------------------------------------------------
function collectAnchors(html) {
  const ids = new Set();
  for (const m of html.matchAll(/\sid="([^"]+)"/g)) ids.add(m[1]);
  // A link may also target a legacy <a name="...">.
  for (const m of html.matchAll(/<a\s+name="([^"]+)"/g)) ids.add(m[1]);
  return ids;
}

function isExternal(href) {
  return /^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('//') || href.startsWith('data:');
}

function validateLinks(pageName, html, anchorsByPage) {
  for (const m of html.matchAll(/href="([^"]+)"/g)) {
    const href = m[1];

    if (href.startsWith('#')) {
      const id = href.slice(1);
      if (!anchorsByPage.get(pageName).has(id)) {
        errors.push(`${pageName}: href="${href}" points at an id that does not exist on this page`);
      }
      continue;
    }
    if (isExternal(href)) continue;

    const [pathPart, anchor] = href.split('#');
    if (pathPart === '') continue;

    // Root-absolute paths cannot be validated here, because the deployed prefix
    // is not knowable — a project Pages site lives under /<repo>/. Flag them so
    // the choice is deliberate rather than accidental.
    if (pathPart.startsWith('/')) {
      warnings.push(
        `${pageName}: href="${href}" is root-absolute; it breaks on project Pages sites. Use a relative link.`,
      );
      continue;
    }

    const targetPath = join(OUT_DIR, pathPart);
    if (!existsSync(targetPath)) {
      errors.push(`${pageName}: href="${href}" does not resolve to a file in the built site`);
      continue;
    }

    if (anchor && pathPart.endsWith('.html')) {
      const targetAnchors = anchorsByPage.get(pathPart);
      if (targetAnchors && !targetAnchors.has(anchor)) {
        errors.push(`${pageName}: href="${href}" — ${pathPart} has no id="${anchor}"`);
      }
    }
  }
}

function checkStructure(pageName, html) {
  if (!/<link[^>]+href="styles\.css"/.test(html)) {
    errors.push(`${pageName}: does not link styles.css — it would render unstyled`);
  }
  if (!/<title>[^<]{3,}<\/title>/.test(html)) {
    errors.push(`${pageName}: missing or empty <title>`);
  }
  if (!/<meta[^>]+name="viewport"/.test(html)) {
    errors.push(
      `${pageName}: missing viewport meta — unreadable on a phone, which is the point of this project`,
    );
  }
  if (!/<html[^>]+lang="/.test(html)) {
    warnings.push(`${pageName}: <html> has no lang attribute`);
  }
  checkLanguages(pageName, html);
}

/**
 * Every page is bilingual, and the two halves live in the markup. A half that
 * was forgotten — a paragraph translated but its sibling left out, a section
 * translated in only one direction — reads as a *blank* line to whoever has
 * that language selected, which is the kind of defect nobody notices until a
 * reader does. Counting the pairs is what catches it.
 */
function checkLanguages(pageName, html) {
  if (!/<script[^>]+src="lang\.js"/.test(html)) {
    errors.push(`${pageName}: does not load lang.js — the language switch would do nothing`);
    return;
  }
  if (!/<html[^>]+data-lang=/.test(html)) {
    errors.push(`${pageName}: <html> has no data-lang — nothing decides which language shows`);
  }
  if (!/<html[^>]+data-title-zh="/.test(html)) {
    errors.push(`${pageName}: <html> has no data-title-zh — the title would stay English`);
  }
  if (!/id="lang-toggle"/.test(html)) {
    errors.push(`${pageName}: missing the #lang-toggle button`);
  }

  const en = (html.match(/class="[^"]*\bi18n-en\b/g) || []).length;
  const zh = (html.match(/class="[^"]*\bi18n-zh\b/g) || []).length;
  if (en === 0 && zh === 0) {
    errors.push(`${pageName}: carries no i18n-en/i18n-zh pairs — it is not translated`);
  } else if (en !== zh) {
    errors.push(`${pageName}: ${en} i18n-en against ${zh} i18n-zh — one half is missing somewhere`);
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
function main() {
  const sha = process.env.GITHUB_SHA || 'local';
  const repoSlug = process.env.GITHUB_REPOSITORY || 'zero6689/tailnet-byok';
  const context = {
    version: readVersion(),
    sha,
    shortSha: sha === 'local' ? 'local' : sha.slice(0, 7),
    date: new Date().toISOString().replace('T', ' ').slice(0, 16) + ' UTC',
    repoName: repoSlug.split('/')[1],
    repoUrl: `https://github.com/${repoSlug}`,
  };

  if (TEMPLATES.length === 0) errors.push('no .html templates found in site/');

  // Read and stamp the templates. The templates themselves are left untouched.
  const pages = TEMPLATES.map((name) => {
    const source = readFileSync(join(SITE_DIR, name), 'utf8');
    const stamped = source
      .replaceAll('{{VERSION}}', context.version)
      .replaceAll('{{BUILD_SHA}}', context.shortSha)
      .replaceAll('{{BUILD_SHA_FULL}}', context.sha)
      .replaceAll('{{BUILD_DATE}}', context.date)
      .replaceAll('{{REPO_URL}}', context.repoUrl)
      .replaceAll('{{REPO_NAME}}', context.repoName);
    return { name, source, html: stamped };
  });

  // Any placeholder left after stamping is one this script does not know about,
  // and would otherwise ship as a literal {{TOKEN}} on a public page.
  for (const page of pages) {
    for (const m of page.html.matchAll(/\{\{[A-Z_]+\}\}/g)) {
      errors.push(`${page.name}: unknown placeholder ${m[0]} (not in the known set)`);
    }
  }

  for (const page of pages) checkStructure(page.name, page.html);

  // Fail before writing anything if the templates are already broken, so a bad
  // build never leaves a half-updated dist/ behind.
  if (errors.length > 0) report();

  // --- Write the output -----------------------------------------------------

  rmSync(OUT_DIR, { recursive: true, force: true });
  mkdirSync(OUT_DIR, { recursive: true });

  for (const page of pages) {
    writeFileSync(join(OUT_DIR, page.name), page.html, 'utf8');
  }
  for (const asset of STATIC_ASSETS) {
    const from = join(SITE_DIR, asset);
    if (!existsSync(from)) {
      errors.push(`missing static asset: site/${asset}`);
      continue;
    }
    copyFileSync(from, join(OUT_DIR, asset));
  }

  if (errors.length > 0) report();

  // --- Validate what will actually be served --------------------------------

  const built = pages.map((page) => ({
    name: page.name,
    html: readFileSync(join(OUT_DIR, page.name), 'utf8'),
  }));
  const anchorsByPage = new Map(built.map((p) => [p.name, collectAnchors(p.html)]));
  for (const page of built) validateLinks(page.name, page.html, anchorsByPage);

  if (errors.length > 0) report();

  // --- Manifest -------------------------------------------------------------

  const manifest = built.map((p) => ({
    page: p.name,
    bytes: statSync(join(OUT_DIR, p.name)).size,
  }));

  // A machine-readable record of what was published, so a deploy can be
  // identified later without guessing from the page content.
  writeFileSync(
    join(OUT_DIR, 'build-info.json'),
    JSON.stringify(
      {
        version: context.version,
        commit: context.sha,
        builtAt: context.date,
        repo: context.repoUrl,
        pages: manifest,
      },
      null,
      2,
    ) + '\n',
    'utf8',
  );

  const totalBytes = manifest.reduce((sum, p) => sum + p.bytes, 0);
  console.log(`site build ok — v${context.version} @ ${context.shortSha}`);
  console.log(`  templates: ${TEMPLATES.length} (left unmodified)`);
  console.log(`  output:    ${OUT_DIR}`);
  console.log(`  pages:     ${manifest.length}, ${(totalBytes / 1024).toFixed(1)} KB of HTML`);
  for (const w of warnings) console.warn(`  warn:  ${w}`);
}

function report() {
  console.error('\nSite build FAILED\n');
  for (const e of errors) console.error(`  error: ${e}`);
  for (const w of warnings) console.warn(`  warn:  ${w}`);
  console.error('');
  process.exit(1);
}

main();
