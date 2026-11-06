# Record Labs (Android release). R8 shrinks and optimises; names are kept as they are
# (nothing here needs hiding, and readable stack traces help with bug reports).
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Libraries that look classes, fields or resources up by name at run time: kept whole.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.atilika.kuromoji.** { *; }
-keep class com.ibm.icu.** { *; }
-keep class org.concentus.** { *; }
-keep class io.github.jaredmdobson.** { *; }
-keep class com.grack.nanojson.** { *; }

# The original Android app's playback code (stream deciphering, PO token web view,
# remote config): reflection, JavaScript bridges and JSON by name. Kept whole.
-keep class com.recordlabs.music.** { *; }
# InnerTube models (JSON by name).
-keep class com.recordlabs.innertube.** { *; }

# org.json is part of Android. A library also brings its own copy, and R8 copied that
# copy's code into its callers (reading a field Android's JSONObject does not have):
# the Discord login crashed with NoSuchFieldError. Kept as is, calls go to Android's.
-keep class org.json.** { *; }

# kotlinx.serialization: generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    static **$* *;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Ktor / OkHttp / Coil service loaders.
-keep class io.ktor.client.engine.** { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }

# JavaScript bridges (web view login, PO token).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Classes referenced by libraries but not on Android (desktop-only or optional parts).
-ignorewarnings
-dontwarn **
