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
# The debug variant carries an applicationIdSuffix (".debug"), so hardcoding the
# release id here is wrong — it cost one run, with `am start` reporting "Activity
# class ... does not exist" while the install itself had succeeded. The id and the
# launchable activity are asked of the device instead of assumed.
base_id=io.github.zero6689.tailnetbyok

if [ ! -f "$apk" ]; then
  echo "::error::$apk is missing; the assemble step did not produce it"
  exit 1
fi

adb logcat -c || true

echo "== installing $apk"
adb install -r "$apk"

pkg=$(adb shell pm list packages | tr -d '\r' | grep -o "${base_id}[a-z.]*" | head -n1)
if [ -z "$pkg" ]; then
  echo "::error::no installed package matching ${base_id}* — the install did not take"
  adb shell pm list packages | tr -d '\r' | grep 6689 || true
  exit 1
fi
echo "ok: installed as $pkg"

activity=$(adb shell cmd package resolve-activity --brief "$pkg" | tr -d '\r' | tail -n1)
if [ -z "$activity" ]; then
  echo "::error::$pkg declares no launchable activity"
  exit 1
fi
echo "ok: launcher activity is $activity"

# Pre-grant the two runtime permissions. Otherwise the first launch can raise a
# permission dialog, the dialog takes focus, and the check at the bottom fails for a
# reason that has nothing to do with the app.
for perm in android.permission.CAMERA android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant "$pkg" "$perm" || true
done

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

# A live process is not the same as a rendered window: an app stuck on a blank
# screen would pass everything above.
#
# This reads the *last* `mFocusedApp` record rather than grepping the whole dump for
# the package name, because a dump also carries stale records — the first green run
# of this job showed the emulator's fallback home screen and two system ANR dialogs
# in the same output, so a loose grep can pass for the wrong reason. It retries,
# because a swiftshader emulator can take a while to hand focus over.
focused=""
for attempt in 1 2 3 4 5 6; do
  focused=$(adb shell dumpsys window | grep -E 'mFocusedApp' | tail -n1 | tr -d '\r' || true)
  echo "  attempt $attempt: $focused"
  if echo "$focused" | grep -q "$pkg"; then break; fi
  sleep 5
done
if ! echo "$focused" | grep -q "$pkg"; then
  echo "::error::this package does not own the focused window; the UI never rendered"
  adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | tr -d '\r' || true
  exit 1
fi
echo "ok: the focused app is this package"

# What only a device can answer, and only with the bridged build: does the native
# library load? The app says so in its own words on the setup screen, so the check
# reads those words rather than trusting the packaging step. `EXPECT_BRIDGE=yes` is
# set by the job that installs an APK built with `-PwithTsnet=true`.
if [ "${EXPECT_BRIDGE:-no}" = "yes" ]; then
  label='Embedded tailnet node'
  failures='not compiled into this build|native bridge missing|native bridge failed to load'
  dump=""
  for attempt in 1 2 3 4 5; do
    dump=$(adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && adb shell cat /sdcard/window.xml | tr -d '\r' || true)
    if echo "$dump" | grep -q "$label"; then break; fi
    sleep 4
  done
  if ! echo "$dump" | grep -q "$label"; then
    echo "::error::the setup screen never lists the embedded provider"
    echo "$dump" | head -c 2000
    exit 1
  fi
  if echo "$dump" | grep -qE "$failures"; then
    echo "::error::built with the bridge, but the UI reports it unusable:"
    echo "$dump" | grep -oE "$failures" | sort -u
    exit 1
  fi
  echo "ok: the embedded node is offered, and no bridge failure is reported"
fi
