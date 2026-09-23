#!/usr/bin/env bash
# Smoke-test the debug APK on the emulator the caller has already booted.
#
# This lives in a file rather than inline in the workflow for a reason that cost two
# CI runs to learn. android-emulator-runner hands each *line* of its `script:` input
# to a separate `/usr/bin/sh -c`, so neither variables nor `set` options survive from
# one line to the next: `apk=...` on line one and `adb install "$apk"` on line three
# installed an empty filename (`adb: filename doesn't end .apk or .apex:`). A file
# invoked once with bash is one shell, and it can be read by a human later.
#
# What it proves is deliberately narrow: the app installs, launches, stays up, does
# not crash, and owns the focused window. The last check matters — a live process
# with a blank screen would satisfy the first four.
set -euo pipefail

apk=app/build/outputs/apk/debug/app-debug.apk
pkg=io.github.zero6689.tailnetbyok
activity="$pkg/.MainActivity"

if [ ! -f "$apk" ]; then
  echo "::error::$apk is missing; the assemble step did not produce it"
  exit 1
fi

adb logcat -c || true

echo "== installing $apk"
adb install -r "$apk"

echo "== launching $activity"
adb shell am start -W -n "$activity"

sleep 10

pid=$(adb shell pidof "$pkg" | tr -d '\r' || true)
if [ -z "$pid" ]; then
  echo "::error::the app is not running ten seconds after launch"
  adb logcat -d | tail -100
  exit 1
fi
echo "ok: the app is alive as pid $pid"

if adb logcat -d -b crash | grep -q 'FATAL EXCEPTION'; then
  echo "::error::the app crashed on launch"
  adb logcat -d -b crash | tail -60
  exit 1
fi
echo "ok: nothing in the crash buffer"

# `mCurrentFocus` is the field on API 30; `mFocusedApp` is the fallback, because a
# rename in a future image must not read as a product failure.
focused=$(adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true)
echo "$focused"
if ! echo "$focused" | grep -q "$pkg"; then
  echo "::error::this package does not own the focused window; the UI never rendered"
  exit 1
fi
echo "ok: a window from this package is focused"
