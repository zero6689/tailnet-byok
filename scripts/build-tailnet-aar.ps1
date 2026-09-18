# Thin wrapper so that `.\scripts\build-tailnet-aar.ps1` works as documented in
# docs/TSNET.md and in the error message from app/build.gradle.kts.
#
# The real work is in the Node script, which is platform-neutral. This exists
# only because a `.ps1` name is what people type on Windows.
$ErrorActionPreference = 'Stop'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error 'node is required (see docs/BUILD.md)'
    exit 1
}

$script = Join-Path $PSScriptRoot 'build-bridge.mjs'
& node $script @args
exit $LASTEXITCODE
