/**
 * Language switching for this documentation site.
 *
 * The pages are hand-written and carry BOTH languages as sibling elements —
 * `<span class="i18n-en">…</span><span class="i18n-zh">…</span>` — and
 * `styles.css` hides one of them. That ordering matters: the page reads
 * correctly even if this file never loads, and a translator can see both halves
 * side by side while editing. This script only decides *which* half is shown,
 * and keeps `<html lang>`, the document title and the toggle button in step.
 *
 * Precedence: `?lang=` in the URL, then the saved choice, then the browser's own
 * languages. Nothing is sent anywhere; the choice lives in `localStorage`.
 *
 * A page that draws its own text (provisioning.html) assigns
 * `window.DSHSiteLang.onApply` and re-renders its strings from there.
 *
 * Loaded from `<head>`, after `<title>`, so the choice is applied before the
 * body renders and a Chinese reader never sees the English page flash first.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'tailnet-byok-lang';
  var SUPPORTED = ['en', 'zh'];
  var root = document.documentElement;
  var titleEn = document.title;

  function normalize(value) {
    if (!value) return null;
    var lower = String(value).toLowerCase();
    if (lower.indexOf('zh') === 0) return 'zh';
    if (lower.indexOf('en') === 0) return 'en';
    return null;
  }

  function fromQuery() {
    try {
      return normalize(new URLSearchParams(window.location.search).get('lang'));
    } catch (error) {
      return null;
    }
  }

  function fromStorage() {
    try {
      return normalize(window.localStorage.getItem(STORAGE_KEY));
    } catch (error) {
      return null;
    }
  }

  function fromBrowser() {
    var list = navigator.languages && navigator.languages.length
      ? navigator.languages
      : [navigator.language];
    for (var index = 0; index < list.length; index += 1) {
      var pick = normalize(list[index]);
      if (pick) return pick;
    }
    return null;
  }

  function apply(lang, remember) {
    if (SUPPORTED.indexOf(lang) === -1) lang = 'en';
    var isZh = lang === 'zh';
    root.setAttribute('data-lang', lang);
    root.setAttribute('lang', isZh ? 'zh-CN' : 'en');

    var titleZh = root.getAttribute('data-title-zh');
    document.title = isZh && titleZh ? titleZh : titleEn;

    if (remember) {
      try {
        window.localStorage.setItem(STORAGE_KEY, lang);
      } catch (error) {
        /* private mode: the choice simply does not outlive the page */
      }
    }

    swapAttributes(lang);

    var button = document.getElementById('lang-toggle');
    if (button) {
      // The button names the language it switches *to*, in that language.
      button.textContent = isZh ? 'English' : '中文';
      button.setAttribute('lang', isZh ? 'en' : 'zh-CN');
      button.setAttribute('aria-label', isZh ? 'Switch to English' : '切换到中文');
    }

    if (typeof api.onApply === 'function') api.onApply(lang);
  }

  /**
   * The two-language-siblings rule cannot reach text a browser refuses to parse
   * as markup (`<option>`) or an attribute (`placeholder`). Those carry the
   * Chinese form in `data-zh` / `data-zh-placeholder`, and the English form is
   * read back from the element itself the first time it is swapped — so the
   * markup a translator edits is still the English one.
   */
  function swapAttributes(lang) {
    var nodes = document.querySelectorAll('[data-zh]');
    for (var index = 0; index < nodes.length; index += 1) {
      var element = nodes[index];
      if (!element.hasAttribute('data-en')) {
        element.setAttribute('data-en', element.textContent);
      }
      element.textContent = lang === 'zh'
        ? element.getAttribute('data-zh')
        : element.getAttribute('data-en');
    }

    var fields = document.querySelectorAll('[data-zh-placeholder]');
    for (var field = 0; field < fields.length; field += 1) {
      var input = fields[field];
      if (!input.hasAttribute('data-en-placeholder')) {
        input.setAttribute('data-en-placeholder', input.getAttribute('placeholder') || '');
      }
      input.setAttribute('placeholder', lang === 'zh'
        ? input.getAttribute('data-zh-placeholder')
        : input.getAttribute('data-en-placeholder'));
    }
  }

  var api = {
    /** Set by a page that draws its own text. */
    onApply: null,
    /** The language currently in force. */
    get: function () {
      return root.getAttribute('data-lang') || 'en';
    },
    /** Switch language; `remember` also persists the choice. */
    set: apply,
  };
  window.DSHSiteLang = api;

  apply(fromQuery() || fromStorage() || fromBrowser() || 'en', false);

  document.addEventListener('DOMContentLoaded', function () {
    // The first `apply` ran from `<head>`, before there was a body to reach.
    swapAttributes(api.get());

    var button = document.getElementById('lang-toggle');
    if (!button) return;
    // Only a working toggle is shown: the markup ships it `hidden`, so a reader
    // without JavaScript is never offered a button that does nothing.
    button.hidden = false;
    button.addEventListener('click', function () {
      apply(api.get() === 'zh' ? 'en' : 'zh', true);
    });
  });
})();
