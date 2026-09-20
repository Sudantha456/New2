# ═══════════════════════════════════════════════════════════════════════════════
#  You-Tube — R8 / ProGuard rules
#
#  R8 full mode is enabled globally (gradle.properties: android.enableR8.fullMode=true).
#  These rules are deliberately *narrow*: every blanket `-keep` costs APK size and
#  defeats the class-merging/vertical-inlining passes that make startup fast.
# ═══════════════════════════════════════════════════════════════════════════════

# ── Attributes ────────────────────────────────────────────────────────────────
-keepattributes *Annotation*,AnnotationDefault,InnerClasses,EnclosingMethod,Signature,Exceptions
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ── Unactionable warnings from optional/absent dependencies ───────────────────
-dontwarn javax.annotation.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.misc.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.**
-dontwarn com.google.common.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**
-dontwarn coil.**
-dontwarn androidx.media3.**

# ═══════════════════════════════════════════════════════════════════════════════
#  1. NewPipeExtractor
#
#  The extractor resolves almost everything statically, with two exceptions that
#  must be kept (mirrors NewPipe's own shipping configuration):
#    • timeago patterns  — instantiated by name from a bundled pattern table
#    • YouTube player JS — executed through Rhino / javax.script
# ═══════════════════════════════════════════════════════════════════════════════
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keepnames class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (signature deciphering, n-param / throttling parameter evaluation)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# jsoup (HTML fallback extraction) + nanojson (InnerTube JSON)
-keep class org.jsoup.nodes.Document { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.grack.nanojson.**

# protobuf-javalite — generated InnerTube player responses
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# The extractor models are Serializable (cross-process / cache hand-off).
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# ═══════════════════════════════════════════════════════════════════════════════
#  2. AndroidX Media3 / ExoPlayer
#     Media3 ships consumer rules inside each AAR; only names are preserved here so
#     that release-mode crash reports stay readable.
# ═══════════════════════════════════════════════════════════════════════════════
-keepnames class androidx.media3.** { *; }
-keepnames class androidx.media3.exoplayer.mediacodec.MediaCodecSelector
# Custom codec selectors / adapter factories are passed to builders by interface.
-keep class * implements androidx.media3.exoplayer.mediacodec.MediaCodecSelector { *; }
-keep class * implements androidx.media3.exoplayer.MediaCodecAdapter$Factory { *; }
# MediaSession keeps command buttons & extras across the Binder boundary.
-keepclassmembers class androidx.media3.session.** implements android.os.Parcelable {
    static ** CREATOR;
}

# ═══════════════════════════════════════════════════════════════════════════════
#  3. kotlinx.serialization  (canonical rules + generated serializers)
# ═══════════════════════════════════════════════════════════════════════════════
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.app.youtube.lite.**$$serializer { *; }
-keepclassmembers class com.app.youtube.lite.** {
    *** Companion;
}
-keepclasseswithmembers class com.app.youtube.lite.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ═══════════════════════════════════════════════════════════════════════════════
#  4. Kotlin runtime
# ═══════════════════════════════════════════════════════════════════════════════
-keep class kotlin.Metadata { *; }          # Compose stability inference reads it
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlin.**

# ═══════════════════════════════════════════════════════════════════════════════
#  5. Platform entry points (instantiated by name from the manifest)
# ═══════════════════════════════════════════════════════════════════════════════
-keep class com.app.youtube.lite.LiteApp { *; }
-keep class com.app.youtube.lite.ui.MainActivity { *; }
-keep class com.app.youtube.lite.player.PlaybackService { *; }

# ═══════════════════════════════════════════════════════════════════════════════
#  6. Debug aids — uncomment a block while chasing a release-only crash
# ═══════════════════════════════════════════════════════════════════════════════
# -dontobfuscate
# -dontoptimize
# -keep class com.app.youtube.lite.** { *; }
