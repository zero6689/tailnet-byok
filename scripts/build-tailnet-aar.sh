#!/usr/bin/env bash
# Thin wrapper so that `./scripts/build-tailnet-aar.sh` works as documented in
# docs/TSNET.md and in the error message from app/build.gradle.kts.
#
# The real work is in the Node script, which is platform-neutral. This exists
# only because a `.sh` name is what people type.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "error: node is required (see docs/BUILD.md)" >&2
  exit 1
fi

exec node "$here/build-bridge.mjs" "$@"
