# app/libs

Where `gomobile bind` writes `tailnet.aar`.

This directory is a staging area, not a source directory. The AAR is **not** committed:

- it is a multi-megabyte binary that nobody can review in a diff;
- it embeds a Go runtime plus the whole `tsnet` dependency graph, so it changes shape with
  every dependency bump;
- it is reproducible from a version number, which is what `scripts/build-bridge.mjs` and the
  `tailnet-bridge` CI workflow exist to do.

`.gitignore` excludes `*.aar` here and re-includes this file, so the directory survives a
clone without its contents.

## Producing it

```bash
node scripts/fetch-toolchain.mjs go ndk
node scripts/build-bridge.mjs
```

Or download the artifact from a run of the **Tailnet bridge** workflow and drop it in here.

## Using it

```bash
./gradlew assembleDebug -PwithTsnet=true
```

Without that flag, `app/src/tsnet/kotlin` is not added to the source set and the AAR is not on
the classpath — which is why the default build works on a machine with no Go toolchain.

If you pass `-PwithTsnet=true` and this file is missing, the build fails immediately with the
command to produce it, rather than with ten thousand unresolved references.

## A note on the ABI

`gomobile bind` targets `arm64-v8a, armeabi-v7a, x86, x86_64` by default. Most people trim that
to `arm64-v8a` to keep the APK small, which is a normal and supported configuration — but the
resulting AAR installs happily on an x86_64 emulator and then throws `UnsatisfiedLinkError` on
first use. `TsnetProviderInstaller` checks `Build.SUPPORTED_ABIS` up front so that failure
becomes a readable message in the UI instead.

If you trim ABIs, say so in your pull request description. It is the kind of change that is
invisible in a diff and surprising at runtime.
