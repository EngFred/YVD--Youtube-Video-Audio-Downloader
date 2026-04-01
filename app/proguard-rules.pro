# ─── Hilt ────────────────────────────────────────────────────────────────────
-keep class com.engfred.yvd.YVDApplication { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ─── Coroutines ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── NewPipe Extractor ────────────────────────────────────────────────────────
# Keep the extractor and its models so R8 doesn't strip them
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# ─── jsoup (pulled in by NewPipe) ─────────────────────────────────────────────
# jsoup optionally uses re2j for faster regex — not available on Android, safe to ignore
-dontwarn com.google.re2j.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ─── Mozilla Rhino (pulled in by NewPipe for JS extraction) ───────────────────
# Rhino references java.beans.* which is a desktop Java API, not available on Android
-dontwarn java.beans.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ─── javax.script (referenced in META-INF/services, not used on Android) ──────
-dontwarn javax.script.**

# ─── OkHttp (suppress known animal-sniffer & Conscrypt warnings) ──────────────
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# ─── WorkManager ──────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**