# ---------------------------------------------------------------------------
# R8 / ProGuard rules for tailnet-byok
# ---------------------------------------------------------------------------

# --- Kotlinx serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.zero6689.tailnetbyok.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.zero6689.tailnetbyok.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- gomobile-bound Go bridge ---------------------------------------------
# gomobile generates JNI glue named after the Go package. Every class that the
# reflection-based JNI lookup touches must survive shrinking.
-keep class go.** { *; }
-keep class mobile.** { *; }
-keep class tailnet.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# --- The reflectively-loaded provider --------------------------------------
# `ProviderRegistry` resolves this installer with Class.forName, so R8 cannot
# see the reference and would strip it. Without these rules the failure is
# release-only and presents as "the embedded node feature silently disappeared",
# which is the worst kind of bug to chase.
#
# `…tailnetbyok.mobile.**` is the gomobile-generated facade. Note the package:
# `-javapkg` is a *prefix*, and gomobile appends the Go package name, so binding
# the Go package `mobile` under `-javapkg=io.github.zero6689.tailnetbyok` lands
# at `io.github.zero6689.tailnetbyok.mobile.Mobile`.
-keep class io.github.zero6689.tailnetbyok.mobile.** { *; }
-keep class io.github.zero6689.tailnetbyok.net.tsnet.** { *; }
-keepclassmembers class io.github.zero6689.tailnetbyok.net.tsnet.** {
    public static void install(...);
}

# --- Compose ---------------------------------------------------------------
-dontwarn androidx.compose.**

# --- Stripping the crash surface ------------------------------------------
# Strip Log calls in release. Combined with SafeLog's compile-time redaction
# this makes "secrets never reach logcat" defensible in release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
