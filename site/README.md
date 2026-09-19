# About this site

Hand-written static HTML, plus `build.mjs`.

## Why not a static-site framework

The obvious choice for a project like this is VitePress, Astro or Jekyll. This site does not
use one, and the reasoning is worth recording because it is a trade, not a preference.

The page's job is to explain how to reach your own infrastructure. If a docs deploy fails
because an npm registry was slow, or because a framework released a breaking major, the
failure lands on the one artifact a person needs in order to decide whether to trust this app
with a key. A dependency tree is a liability proportional to how much you need the output.

So: four HTML pages and one stylesheet. `build.mjs` has no dependencies and does the three
things hand-written HTML actually needs.

## What `build.mjs` does

1. **Validates every internal link.** Each `href` is resolved against the *built* output, and
   `page.html#anchor` is checked against the `id`s actually present in that page. A docs site's
   most common defect is a link that used to work, and this is the check that catches it.
2. **Checks structure.** Every page must link `styles.css`, declare a `<title>`, carry a
   viewport meta and a `lang`. A page that silently loses its styling looks like a broken
   deploy rather than a typo.
3. **Stamps the build.** `{{VERSION}}` comes from `app/build.gradle.kts` (single source of
   truth), and `{{BUILD_SHA}}` / `{{BUILD_DATE}}` from the environment. It also writes
   `build-info.json`, so a deploy can be identified later without reading page content.

It fails the build on any error, and CI additionally asserts the four expected pages exist in
the output and that no `{{TOKEN}}` survived stamping.

## Templates vs. output

```
site/
  index.html          templates, with {{PLACEHOLDER}} tokens — committed
  guide.html
  provisioning.html
  security.html
  architecture.html
  styles.css          copied verbatim into the output
  lang.js             ditto (the language switch)
  qr.js               ditto (vendored encoder, built by scripts/vendor-qr.mjs)
  .nojekyll           ditto (tells Pages not to run Jekyll)
  build.mjs           the build
  dist/               the built site — generated, git-ignored
```

**The build never writes to the templates.** An earlier version stamped them in place, which
meant the first build destroyed every `{{...}}` token and every later build shipped the same
frozen version string — a docs site that quietly stops reporting the build it came from, which
is worse than one that reports nothing. Templates in, `dist/` out, every time.

Both `.github/workflows/docs-pages.yml` and `sync-personal-site.yml` publish `site/dist`.

## Languages

Every page ships in English and Chinese at once, and the reader picks.

- **Text lives in the markup, twice**, as sibling elements:
  `<span class="i18n-en">…</span><span class="i18n-zh">…</span>`. `styles.css` hides one of
  them. The page therefore reads correctly even if `lang.js` never loads, and a translator can
  see both halves side by side. Inside a `<p>`, use `<span>`; when a whole block has to differ,
  duplicate the block and put `i18n-en` / `i18n-zh` on the element itself.
- **`lang.js` only decides which half.** It reads `?lang=` first, then the saved choice, then
  the browser's own languages, and keeps `<html lang>`, the document title (from
  `data-title-zh` on `<html>`) and the toggle in step. It is loaded from `<head>` after
  `<title>` so a Chinese reader never sees the English page flash first.
- **Two things markup cannot hold** take the Chinese form in an attribute instead: an
  `<option>` uses `data-zh="…"`, an input placeholder uses `data-zh-placeholder="…"`. The
  English form stays where it was; the script reads it back on the first swap.
- **A page that draws its own text** assigns `window.DSHSiteLang.onApply = render` in its
  script and picks its strings from a small `TEXT = { en: {…}, zh: {…} }` table — see
  `provisioning.html`.
- **Never translated:** code, identifiers, field and file names, URLs, `{{PLACEHOLDER}}`
  tokens, and the `hero`/section ids that `build.mjs` validates as anchors.
- **Keep the two halves equivalent.** `build.mjs` validates *every* `href` it can see,
  including the hidden half, so a link that exists in only one language is still a build
  error — which is the point.

Terminology, so the pages agree with each other and with the app's own Chinese strings:
auth key = 认证密钥 · credential = 凭据 · target host = 目标主机 · connection method = 连接方式 ·
system network = 系统网络 · embedded tailnet node = 内嵌 tailnet 节点 · update source = 更新源 ·
sidecar = 同目录的校验文件 · guardrail = 护栏 · build = 构建 · control plane = 控制面 ·
`tailnet`, `MagicDNS`, `headscale`, `DSH`, `APK`, `sha256` stay as they are.

## Local preview

```bash
node site/build.mjs
npx --yes serve site/dist        # or any static file server
```

Do not open `dist/index.html` over `file://` — relative links and the sticky header behave
differently enough to mislead you.

## Adding a page

1. Create `site/<name>.html`, copying the header and footer from an existing page.
2. Add a nav link to every page (the build does not check nav symmetry — that is on you).
3. Add the page to the `for page in …` list in `.github/workflows/docs-pages.yml` so CI
   asserts it was rendered.
4. Run `node site/build.mjs` and fix anything it reports.

## Base path

Every internal link is **relative**. A project Pages site is served from `/<repo>/`, not from
the root, so a root-absolute link (`/guide.html`) would 404 in production while working
perfectly in local preview. `build.mjs` emits a warning if it sees one, because that class of
bug is invisible until deploy.

## Deploying

`.github/workflows/docs-pages.yml` runs the build and publishes `site/dist` to GitHub Pages on
every push to `main` that touches the site, the docs, or the top-level Markdown.

`sync-personal-site.yml` mirrors the same output to a personally hosted copy. It is manual
only, defaults to a dry run, and renames rather than deletes — the previous release is left on
disk as `<path>-old-<timestamp>`.
