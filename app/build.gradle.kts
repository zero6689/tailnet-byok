import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Kotlin is compiled by AGP itself (built-in Kotlin, AGP 9). Applying
    // `org.jetbrains.kotlin.android` here would fail: it is incompatible with
    // AGP 9's new DSL. The two compiler plugins below are still required —
    // built-in Kotlin supplies the compiler, not Compose's or kotlinx's.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Switches and overridable SDK levels
//
// The names deliberately do NOT match the Android extension's own properties.
// When this was written, AGP still carried a deprecated
// `compileSdkVersion: String?` (the old "android-35" form) alongside the modern
// `compileSdk: Int?`, and a local variable with the same name lost to that
// receiver member — which surfaced as the baffling "inferred type is String?
// but Int? was expected" on the line below. AGP 9 has removed the deprecated
// property, so the collision can no longer happen; `resolved*` is kept because
// it also reads better next to the `-P` overrides.
// ---------------------------------------------------------------------------
val withTsnet: Boolean =
    (providers.gradleProperty("withTsnet").orNull ?: "false").toBooleanStrict()

val resolvedCompileSdk: Int =
    (providers.gradleProperty("compileSdk").orNull ?: libs.versions.compileSdk.get()).toInt()

val resolvedTargetSdk: Int =
    (providers.gradleProperty("targetSdk").orNull ?: libs.versions.targetSdk.get()).toInt()

val resolvedMinSdk: Int = libs.versions.minSdk.get().toInt()

val resolvedBuildTools: String =
    providers.gradleProperty("buildTools").orNull ?: libs.versions.buildTools.get()

// ---------------------------------------------------------------------------
// Single-ABI builds, without AGP's IDE-injection path.
//
// `-Pandroid.injected.build.abi=arm64-v8a` also slims the tsnet AAR down to one
// ABI, but AGP reads ANY `android.injected.*` switch as "an IDE is deploying
// this" and then silently injects `android:testOnly="true"` into the merged
// manifest. Proof, not a guess: in
// app/build/intermediates/manifest_merge_blame_file/debug/.../manifest-merger-blame-debug-report.txt
// that attribute is the ONLY one without an `--> <source file:line>` line.
//
// Why that matters: a testOnly APK cannot be installed from the package
// installer at all on Huawei/HarmonyOS devices - it is refused with
// "应用是非正式发布版本，当前设备不支持安装" - and it needs `adb install -t`
// everywhere else. The 58 MB arm64 build of 2026-09-12 was therefore
// uninstallable by hand (see _ops/HANDOFF.md §7).
//
// Filtering through the NDK DSL gives the same slim output and produces an
// ordinary debug APK. Usage: -PabiFilters=arm64-v8a (comma-separate for more).
// ---------------------------------------------------------------------------
val resolvedAbiFilters: List<String> =
    (providers.gradleProperty("abiFilters").orNull ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val tsnetAar = layout.projectDirectory.file("libs/tailnet.aar").asFile

if (withTsnet && !tsnetAar.exists()) {
    throw GradleException(
        """
        withTsnet=true but ${tsnetAar.relativeTo(rootDir)} is missing.

        Build it first (requires Go, gomobile and the Android NDK):
            ./scripts/build-tailnet-aar.sh          # macOS / Linux
            ./scripts/build-tailnet-aar.ps1         # Windows

        ...or grab the artifact from a CI run of the "tailnet-bridge" workflow.
        Full details: docs/TSNET.md
        """.trimIndent(),
    )
}

// ---------------------------------------------------------------------------
// Release signing.
//
// Resolution order:
//   1. keystore.properties in the repo root (git-ignored; local release builds)
//   2. environment variables        (CI: KEYSTORE_FILE / KEYSTORE_PASSWORD /
//                                     KEY_ALIAS / KEY_PASSWORD)
//   3. no signing config            (assembleRelease still works, unsigned)
//
// No secret ever lives in a committed file.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = secret("storeFile", "KEYSTORE_FILE")
val releaseStorePassword: String? = secret("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = secret("keyAlias", "KEY_ALIAS")
val releaseKeyPassword: String? = secret("keyPassword", "KEY_PASSWORD")

val hasReleaseSigning: Boolean =
    releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null &&
        rootProject.file(releaseStoreFile).exists()

android {
    namespace = "io.github.zero6689.tailnetbyok"
    compileSdk = resolvedCompileSdk

    // Explicit, because AGP's own default still points at build-tools 34.0.0
    // even when compiling against SDK 35. On a machine that has only 35.0.0
    // installed — which is exactly what scripts/fetch-toolchain.mjs produces —
    // leaving this implicit fails with a license error naming a build-tools
    // version nothing in this project asked for.
    buildToolsVersion = resolvedBuildTools

    defaultConfig {
        applicationId = "io.github.zero6689.tailnetbyok"
        minSdk = resolvedMinSdk
        targetSdk = resolvedTargetSdk
        versionCode = 20
        // Bumped from 0.1.0 on 2026-09-13. The delivery filename and the app's
        // own version had drifted apart (a file called v0.2.1 installed an app
        // reporting 0.1.0-debug), which left no way to tell from the phone which
        // build was actually installed. Keep this in step with the published
        // APK name from now on.
        //
        // 0.2.4: the embedded tsnet node grows a cookie jar and a sane redirect
        // cap (see tailnet/tailnet.go Fetch). Before this, choosing the embedded
        // node as the connection method could not complete the auth proxy's
        // 303 + Set-Cookie handshake at all.
        //
        // 0.2.5: the DSH screen. The bridge gains a loopback reverse proxy
        // (tailnet/proxy.go) so the app can render the target's own UI in a
        // WebView on a phone that has no Tailscale client installed -- the
        // socket into the tailnet is created in Go, so Android's HTTP stack has
        // no route to it and a WebView cannot dial it directly. Also folds in
        // the trailing-dot MagicDNS false warning fixed after 0.2.4.
        //
        // 0.2.6: in-app updates. The app reads <update source>/dsh.apk.version,
        // downloads /dsh.apk when it advertises something newer, and refuses to
        // install anything whose bytes do not match /dsh.apk.sha256 -- a missing
        // or mismatched sidecar is a failure, never a silent pass. The update
        // source defaults to the target's own origin and is user-configurable.
        //
        // 0.2.7: provisioning. A deployment can hand the app its configuration as
        // a dshbyok://setup link (or a QR code containing one), and the app shows
        // what it would change and waits for a tap. A link may carry configuration
        // and never a credential -- a credential-shaped field refuses the whole
        // link -- and a target the address policy rejects cannot be applied. A
        // private build can pre-fill a target with -PdefaultTarget; the public
        // build's default stays empty, and CI checks that it does.
        //
        // 0.2.8: the keyboard stops covering the composer. The DSH screen left
        // the IME inset to the page, and this engine (Chromium 116 WebView on
        // Android 14) sometimes never tells the page the keyboard is up -- both
        // `innerHeight` and `visualViewport.height` keep the keyboard-closed
        // value -- so no page-side patch can notice. The screen now shrinks the
        // WebView by the IME inset itself (`imePadding`), which leaves the page
        // with a viewport that is already correct. See WebScreen.kt.
        //
        // 0.2.9: the four things that make it usable without a laptop open.
        //   * uploads can choose several files and can come from the camera;
        //   * the app checks the update source by itself at start-up (version
        //     file only) and offers what it finds in a row, not a dialog;
        //   * the DSH screen has a way back to settings, the failure card offers
        //     it too, and a first run with no target gets the configuration link
        //     path (paste it, or open the provisioning page);
        //   * a session that stops running posts a notification — but only when
        //     the app is not already in front of the user.
        // 0.3.0: the configuration hand-off became a two-way, on-device feature.
        //   * the settings screen draws this device's configuration as a QR code
        //     (and as text, with a Copy button) for another phone to scan, and the
        //     writer parses its own output back before drawing it;
        //   * the app scans a code itself -- camera on the scanner screen, or a
        //     picture the user picks, which needs no permission -- and what it
        //     reads goes through the same parse-show-confirm path as a pasted link;
        //   * CAMERA is requested at runtime by that screen alone and is optional:
        //     everything above works with it permanently denied.
        //
        // 0.3.1: the app stops making a configured user walk through onboarding.
        //   * the ways-in card is shown while there is no target, instead of until
        //     the security note is acknowledged — it had become a permanent header
        //     above the button back to the screen the user was using;
        //   * a launch with a working configuration goes straight to the DSH
        //     screen (once per process, and never over a configuration link that
        //     is waiting to be confirmed);
        //   * the settings top bar carries the same "open the DSH UI" action as
        //     the section at the bottom, because that section is below every field
        //     and every diagnostic line.
        //
        // 0.3.2: the deployment's page is offered by the code section.
        //   The first-run card now appears exactly while there is no target, so a
        //   deployment build (pre-filled target) had lost its only link to the page
        //   that generates codes. It sits with the code section, which is the same
        //   subject.
        //
        // 0.3.3: "the app never told me my task finished" became answerable.
        //   Every reason the notice was declined used to be a silent `return`, so the
        //   four causes (on screen / notifications off / permission never granted /
        //   refused) were indistinguishable from outside. The outcome is now returned
        //   and reported, and the diagnostics panel carries three lines: whether
        //   Android would let this app post, what the task watch is doing right now,
        //   and what happened to the last finish notice.
        //
        // 0.3.4: the DSH screen gets the page-level touches the sibling shell has.
        //   Same page, same server, so the shell's tunePage() was the only remaining
        //   difference between what the two apps render: a viewport meta with
        //   viewport-fit=cover when the page has none, `referrer: no-referrer`, and
        //   16px form fields below 700px (the size at which mobile engines stop
        //   zooming a focused field). The shell's IME mirror and drag-drop helpers are
        //   deliberately not ported — they exist for the shell's own chrome.
        //
        // 0.3.5: the DSH screen goes immersive, and the watch survives the background.
        //   * no app bar: the page keeps the row, the content starts under the status
        //     bar, and the two exits float over it (a slim auto-hiding arrow in the
        //     corner, and this app's settings moved down into the left-hand column the
        //     page's own navigation lives in, one-handed);
        //   * a foreground service (dataSync, silent low-importance row) is raised
        //     with the route and lowered with it, so the task watch keeps polling
        //     while the app is off screen instead of being frozen with the process.
        //
        // 0.3.6: the bottom of the DSH screen stops running under the system bars.
        //   * the page is padded by the safe drawing insets (status bar, navigation
        //     bar, display cutout, IME) instead of the status bar and the keyboard
        //     alone. On a 3-button phone the composer's tool row, the sidebar's own
        //     settings row and the cost panel above it were drawn under the
        //     navigation bar: visible, and untappable, because the system bar takes
        //     the touches;
        //   * this app's own settings button is pinned to the bottom of the *safe
        //     area* rather than to a fixed 104dp above the bottom edge — which is
        //     where the page's cost panel sits, so the button used to be drawn on top
        //     of the panel it was trying to avoid;
        //   * the page's sidebar footer is told, measured, to stop one gap above that
        //     button, so the sidebar's bottom is one block inside the safe area: the
        //     cost panel, the page's settings row, and this app's way back.
        //
        // 0.3.7: the finish notice pops up instead of only reaching the shade.
        //   * the notice moved to a channel created with IMPORTANCE_HIGH. On Android 8
        //     and later the *channel's* importance decides whether a notification is a
        //     heads-up banner, and an app may lower a channel's importance but never
        //     raise it — so the 0.3.5 channel, created as DEFAULT, could never banner
        //     on any phone that already had it. A new channel id is the only fix; the
        //     old channel is deleted, and the pre-channel priority is HIGH as well;
        //   * the diagnostics panel gained a line for the channel's *effective*
        //     importance, because "it arrived silently" and "it never arrived" looked
        //     identical from the outside — and they have different fixes.
        //
        // 0.3.8: one settings entry at the bottom of the DSH screen, not two.
        //   * the floating settings button that 0.3.5 put in the sidebar column is gone,
        //     and with it the strip the page used to keep free for it. It was a
        //     duplicate — the same callback as the arrow at the top — and it put a
        //     second "settings" in the corner the page's own settings row occupies,
        //     which is what the user asked to merge;
        //   * the page's row is the one that stays: DSH renders its settings trigger in
        //     exactly one place, so removing that row would leave no way into DSH's
        //     settings at all. Back to this app's settings is the Back gesture and the
        //     arrow above, both of which were already there.
        // 0.4.0: the public build gets its "open the provisioning page" button back.
        //   * `DEFAULT_PROVISIONING_URL` was empty unless a deployment passed
        //     -PdefaultProvisioningUrl, and the first-run card draws that button only
        //     when the value is an http(s) URL. The maintainer's own build passed it;
        //     a public install did not — so the page was reachable on one device and
        //     simply absent on another, with no way for the user to tell why. The
        //     default is now the public page and a deployment still overrides it.
        //   * nothing about it is private (it is the same page the README links), which
        //     is why it may have a default at all, unlike DEFAULT_TARGET.
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // See resolvedAbiFilters above. Empty by default, so a plain
        // assembleDebug still bundles every ABI the tsnet AAR carries.
        if (resolvedAbiFilters.isNotEmpty()) {
            ndk {
                abiFilters.addAll(resolvedAbiFilters)
            }
        }

        // Non-secret defaults only. Secrets are entered by the user at runtime
        // and stored through SecretVault (Android Keystore). Nothing private is
        // ever baked into the APK.
        buildConfigField(
            "String",
            "DEFAULT_CONTROL_URL",
            "\"${providers.gradleProperty("defaultControlUrl").orNull ?: "https://controlplane.tailscale.com"}\"",
        )
        buildConfigField("boolean", "TSNET_ENABLED", withTsnet.toString())

        // ---------------------------------------------------------------------
        // Build-time configuration defaults, for a branded or private build.
        //
        // The values are NEVER committed: they come from -P properties, and the
        // address-shaped defaults below are empty on purpose, because the public build
        // promises that this app has no server of its own and no address baked in. (The
        // one exception is DEFAULT_PROVISIONING_URL, whose fallback is the project's own
        // public page — see its own note further down.) A downstream build (a company's
        // own shell, a household's own phone) can pass -PdefaultTarget=host:port
        // -PdefaultMode=system and ship an app whose users have nothing to type. The
        // mechanism is upstream; the value is not.
        //
        //   ./gradlew assembleDebug -PdefaultTarget=100.64.0.1:3080 -PdefaultMode=system
        //
        // Values go through the same parser a dshbyok:// link does (see
        // SetupLinkParser.parseDefaults), so a malformed property degrades to "no
        // default" instead of producing an app whose first screen is broken — and a
        // credential-shaped value is refused there too.
        // ---------------------------------------------------------------------
        fun gradlePropertyOrEmpty(name: String): String =
            providers.gradleProperty(name).orNull?.trim().orEmpty()

        buildConfigField("String", "DEFAULT_TARGET", "\"${gradlePropertyOrEmpty("defaultTarget")}\"")
        buildConfigField("String", "DEFAULT_MODE", "\"${gradlePropertyOrEmpty("defaultMode")}\"")
        buildConfigField("String", "DEFAULT_UPDATE_URL", "\"${gradlePropertyOrEmpty("defaultUpdateUrl")}\"")
        // The docs page that draws a configuration link and its QR code.
        //
        // Defaulted to the *public* page (0.4.0), because the alternative shipped a
        // first-run card with no way to reach it: `SetupScreen` renders the button
        // only for an http(s) value, so an empty default reads to a user as "the
        // configuration page is gone" rather than as "this build has no default".
        // A deployment that hosts its own page still wins by passing
        // -PdefaultProvisioningUrl. Nothing private can leak through this one — it is
        // the page the README already links — so unlike DEFAULT_TARGET it is allowed
        // to have a value in a public build. `ci.yml` asserts both halves of that.
        val publicProvisioningUrl = "https://zero6689.github.io/tailnet-byok/provisioning.html"
        buildConfigField(
            "String",
            "DEFAULT_PROVISIONING_URL",
            "\"${gradlePropertyOrEmpty("defaultProvisioningUrl").ifEmpty { publicProvisioningUrl }}\"",
        )

        // NOTE (2026-09-12 23:0x): a `resourceConfigurations += setOf("en", "zh")`
        // style filter was tried here to strip the dependencies' translated
        // resources, and was REMOVED again: with it in place
        // `:app:processDebugResources` hung with zero file writes for 18+ minutes
        // (Gradle 8.11.1 + AGP, same build otherwise succeeding), twice in a row.
        // The app's own copy lives in `values/` and `values-zh/`, which is what
        // shipping Chinese actually requires; the other locales in the APK are a
        // size nicety, not worth a build that never finishes. Revisit only with a
        // way to observe aapt2.
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // `-PnoMinify=true` turns R8 and resource shrinking off for a release
            // build. It exists for one purpose: a release has to be *runnable*
            // before it is published, and the only builds this project has ever
            // run on a real device are the ones it could install. Shipping a
            // minified artifact nobody has executed is a bet on the keep rules;
            // this is the escape hatch that removes the bet for a first public
            // release. The default stays minified. See docs/RELEASING.md.
            val noMinify = (providers.gradleProperty("noMinify").orNull ?: "false").toBooleanStrict()
            isMinifyEnabled = !noMinify
            isShrinkResources = !noMinify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.create("release") {
                    storeFile = rootProject.file(releaseStoreFile!!)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                    enableV1Signing = false
                    enableV2Signing = true
                }
            } else {
                logger.lifecycle(
                    "tailnet-byok: no release keystore found — the release APK will be UNSIGNED.",
                )
                null
            }
        }
    }

    // The tsnet bridge lives in its own source set so that the default build
    // stays compilable on a machine without the prebuilt .aar.
    //
    // `directories` rather than `srcDir`: Gradle 9.6 deprecates `srcDir`/`srcDirs`
    // on a source directory set in favour of the mutable set. Same effect, one less
    // Gradle-10 blocker.
    sourceSets["main"].apply {
        if (withTsnet) {
            kotlin.directories.add("src/tsnet/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // The gomobile AAR ships Go licence files that collide with AGP's.
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        // Secrets must never be logged; this check is the machine half of that rule.
        //
        // The three version checks are off for one reason: every version here is a
        // deliberate pin (`gradle/libs.versions.toml`, `fetch-toolchain.mjs`,
        // `make-wrapper.mjs`), and moving one is a reviewed change with a build
        // behind it — not a lint suggestion. Gradle itself is the clearest case:
        // AGP 9.4.1's tested pairing is Gradle 9.6.0, so the "9.8.0 is available"
        // that this check raises is an invitation to leave the tested pairing.
        // Dependabot already proposes those bumps, where they can be evaluated.
        disable += setOf("GradleDependency", "OldTargetApi", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // QR codes, both directions. `core` is pure Java: the encoder and the
    // luminance-source decoder are the two halves used here, and neither touches
    // AWT (`MatrixToImageWriter` and `BufferedImageLuminanceSource` do, and are
    // deliberately not used). Everything security-relevant about a scan is
    // therefore testable on the JVM, next to `SetupLinkTest`.
    implementation(libs.zxing.core)

    // The viewfinder. CameraX binds to the activity lifecycle, which is what
    // stops the camera from outliving the screen; the deprecated
    // `android.hardware.Camera` API would put that bookkeeping here instead.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // `PreviewView`: the view that turns a `SurfaceRequest` into a correctly
    // rotated, correctly cropped preview. Hand-rolling that against a
    // `TextureView` is the part of a scanner that fails on exactly one device
    // family and never on the emulator.
    implementation(libs.androidx.camera.view)

    // Native tailnet node. Absent unless -PwithTsnet=true.
    if (withTsnet) {
        implementation(files(tsnetAar))
    }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

// ---------------------------------------------------------------------------
// P2-11: the Android half of the licence inventory.
//
// `scripts/third-party-licenses.mjs` resolves the Go graph and writes
// `THIRD-PARTY-NOTICES.md`. This is the same idea for the Gradle graph: androidx,
// CameraX, ZXing, Kotlin and OkHttp ship inside the APK without being linked into
// `libgojni.so`, and Apache-2.0 section 4 constrains distributing them in binary
// form exactly as much as it constrains the Go modules — the repository said those
// dependencies were "inventoried separately" and no such inventory existed.
//
// Gradle owns the graph and the POMs, so Gradle resolves them; the result is
// committed, `scripts/generate-license-screen.mjs` is the consumer that puts the
// texts inside the APK, and CI regenerates and compares. A module whose POM
// declares no licence fails this task rather than shipping silently.
//
// Deliberately not the `com.jaredsburrows.license` plugin: one more third-party
// build plugin, pinned and upgraded forever, to write a table this repository can
// write with the API it already has.
// `tasks.register("name") { … }` rather than `val x by tasks.registering { … }`:
// Gradle 9.6 deprecates the delegated-property form (and the property name was the
// only thing naming the task, which is the sort of implicit coupling that makes a
// rename break CI silently).
val androidLicenceInventory = tasks.register("androidLicenceInventory") {
    group = "verification"
    description = "Writes docs/ANDROID-DEPENDENCIES.md from the release runtime classpath."

    val runtimeClasspath = configurations.named("releaseRuntimeClasspath")
    val reportFile = rootProject.layout.projectDirectory.file("docs/ANDROID-DEPENDENCIES.md")

    doLast {
        val root = runtimeClasspath.get().incoming.resolutionResult.rootComponent.get()
        val ids = collectModuleIds(root)

        val rows = mutableListOf<AndroidLicenceRow>()
        val unlicensed = mutableListOf<String>()
        for (id in ids) {
            val module = "${id.group}:${id.module}"
            val declared = declaredLicences(configurations, dependencies, id)
            val licences = declared.ifEmpty { POM_LICENCE_EXCEPTIONS[module].orEmpty() }
            if (licences.isEmpty()) {
                unlicensed += "$module ${id.version}"
            } else {
                rows += AndroidLicenceRow(module, id.version, licences)
            }
        }
        logger.lifecycle("androidLicenceInventory: read a licence for ${rows.size} of ${ids.size} modules")
        if (unlicensed.isNotEmpty()) {
            throw GradleException(
                "no licence declared in the POM for: ${unlicensed.sorted().joinToString(", ")}. " +
                    "Add it to the inventory deliberately rather than shipping it unnamed.",
            )
        }

        val sorted = rows.sortedBy { it.module }
        val counts = sorted.flatMap { row -> row.licences.map { it to row.module } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.size }
            .toSortedMap()

        val text = buildString {
            appendLine("# Android and Kotlin dependencies")
            appendLine()
            appendLine("Every module the release build takes from the Gradle graph, with the licence its")
            appendLine("own POM declares. Generated by `./gradlew :app:androidLicenceInventory` from the")
            appendLine("`releaseRuntimeClasspath` resolution — not written by hand, and regenerated and")
            appendLine("compared in CI the way `THIRD-PARTY-NOTICES.md` is.")
            appendLine()
            appendLine("The Go modules linked into `libgojni.so` are inventoried separately in")
            appendLine("[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). Together the two files are what")
            appendLine("describes the contents of the APK.")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Licence | Modules |")
            appendLine("| --- | ---: |")
            for ((licence, count) in counts) appendLine("| $licence | $count |")
            appendLine("| **total** | **${sorted.size}** |")
            appendLine()
            appendLine("## Modules")
            appendLine()
            appendLine("| Module | Version | Licence (as its POM declares it) |")
            appendLine("| --- | --- | --- |")
            for (row in sorted) appendLine("| `${row.module}` | ${row.version} | ${row.licences.joinToString("; ")} |")
        }

        val target = reportFile.asFile
        target.parentFile.mkdirs()
        target.writeText(text)
        logger.lifecycle("wrote docs/ANDROID-DEPENDENCIES.md: ${sorted.size} modules, ${counts.size} licences")
    }
}

/** One resolved Gradle module and whatever its POM says the licence is. */
data class AndroidLicenceRow(val module: String, val version: String, val licences: List<String>)

/**
 * The modules whose POM declares no `<licenses>` block at all.
 *
 * Three of the 129 modules in the release graph are like this. Each entry is a claim
 * about an upstream project, so each one names where it comes from: the licence is
 * the one stated by the project's own repository, not by this file. A module that is
 * neither in a POM nor in this table fails the task, which is the point — adding a
 * dependency must not be able to slip an unnamed licence into the APK.
 */
val POM_LICENCE_EXCEPTIONS: Map<String, List<String>> = mapOf(
    // Apache-2.0, per https://github.com/google/auto/blob/main/LICENSE
    "com.google.auto.value:auto-value-annotations" to listOf("The Apache Software License, Version 2.0"),
    // Apache-2.0, per https://github.com/google/guava/blob/master/LICENSE (the POM is a stub)
    "com.google.guava:listenablefuture" to listOf("The Apache Software License, Version 2.0"),
    // Apache-2.0, per https://github.com/zxing/zxing/blob/master/LICENSE (the POM's licence prose is in <description>)
    "com.google.zxing:core" to listOf("The Apache Software License, Version 2.0"),
)

/** Reads `<licenses><license><name>` out of a POM. Empty means the POM declares none. */
fun readPomLicences(pom: java.io.File): List<String> {    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(pom)
    val nodes = document.getElementsByTagName("license")
    val names = mutableListOf<String>()
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as org.w3c.dom.Element
        val name = element.getElementsByTagName("name").item(0)?.textContent?.trim()
        if (!name.isNullOrEmpty()) names += name
    }
    return names.distinct()
}

/**
 * The licences a module declares, read from its POM.
 *
 * `ArtifactResolutionQuery` with `MavenPomArtifact` — the documented way to ask for a
 * POM — returned no artifact at all for any module on Gradle 8.11, which made every
 * module look unlicensed. This asks for the POM as an artifact in its own right
 * instead: `@pom`, non-transitive, which Gradle serves from its cache.
 */
fun declaredLicences(
    configurations: org.gradle.api.artifacts.ConfigurationContainer,
    dependencyHandler: org.gradle.api.artifacts.dsl.DependencyHandler,
    id: org.gradle.api.artifacts.component.ModuleComponentIdentifier,
): List<String> {
    val detached = configurations.detachedConfiguration(
        dependencyHandler.create("${id.group}:${id.module}:${id.version}@pom"),
    )
    detached.isTransitive = false
    val pom = runCatching { detached.resolve().singleOrNull() }.getOrNull() ?: return emptyList()
    return readPomLicences(pom)
}

/** Walks the resolution result iteratively: a recursive local function here makes the Kotlin DSL compiler fail with a bare "IR lowering" error. */
fun collectModuleIds(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<org.gradle.api.artifacts.component.ModuleComponentIdentifier> {
    val found = LinkedHashSet<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
    // `visited` is not an optimisation. Without it every path to a shared dependency
    // re-expands its subtree, and a dependency graph full of diamonds grows
    // exponentially: the first version of this walk died with "Java heap space"
    // nineteen seconds in, before resolving a single POM.
    val visited = HashSet<org.gradle.api.artifacts.component.ComponentIdentifier>()
    val pending = ArrayDeque<org.gradle.api.artifacts.result.ResolvedComponentResult>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val component = pending.removeLast()
        if (!visited.add(component.id)) continue
        (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { found += it }
        for (dependency in component.dependencies) {
            if (dependency is org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                pending.addLast(dependency.selected)
            }
        }
    }
    return found.toList()
}
