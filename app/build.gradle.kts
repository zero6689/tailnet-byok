import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Switches and overridable SDK levels
//
// The names deliberately do NOT match the Android extension's own properties.
// AGP still carries a deprecated `compileSdkVersion: String?` (the old
// "android-35" form) alongside the modern `compileSdk: Int?`, and a local
// variable with the same name loses to that receiver member — which surfaces as
// the baffling "inferred type is String? but Int? was expected" on the line
// below. `resolved*` names cannot collide with anything.
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
        versionCode = 7
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
        versionName = "0.2.7"

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
        // defaults below are empty on purpose, because the public build promises
        // that this app has no server of its own and no address baked in. A
        // downstream build (a company's own shell, a household's own phone) can
        // pass -PdefaultTarget=host:port -PdefaultMode=system and ship an app whose
        // users have nothing to type. The mechanism is upstream; the value is not.
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
            isMinifyEnabled = true
            isShrinkResources = true
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
    sourceSets["main"].apply {
        if (withTsnet) {
            kotlin.srcDir("src/tsnet/kotlin")
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
        disable += setOf("GradleDependency", "OldTargetApi")
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
