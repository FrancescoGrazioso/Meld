# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# WEB_REMIX Streaming - NTransformSolver WebView JavaScript interface
-keepclassmembers class com.metrolist.music.utils.cipher.NTransformSolver$SolverWebView {
    @android.webkit.JavascriptInterface public *;
}

# Keep cipher utility classes (SignatureDeobfuscator, NTransformSolver, CipherManager, etc.)
-keep class com.metrolist.music.utils.cipher.** { *; }
-keep class com.metrolist.innertube.utils.PoTokenGenerator { *; }

# Keep SignatureDeobfuscator inner types (Op, OpType) for reflection-safe serialization
-keepclassmembers class com.metrolist.music.utils.cipher.SignatureDeobfuscator {
    *;
}

# Keep coroutine continuation for WebView callbacks
-keepclassmembers class * {
    void resume(...);
    void resumeWithException(...);
}

# Kotlin Serialization
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclasseswithmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

## NewPipeExtractor Rules
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.lang.management.**

## vvv DO NOT REMOVE, WILL BREAK LISTEN TOGETHER
## Listen Together Protobuf
-keep class com.metrolist.music.listentogether.proto.** { *; }
-keepclassmembers class com.metrolist.music.listentogether.proto.** { *; }

## Logging
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

-dontwarn java.beans.**

# vvv DO NOT CHANGE, WILL BREAK JAPANESE TOKENIZATION
## Keep all classes within the kuromoji package
-keep class com.atilika.kuromoji.** { *; }

## Queue Persistence
-keep class com.metrolist.music.models.PersistQueue { *; }
-keep class com.metrolist.music.models.PersistPlayerState { *; }
-keep class com.metrolist.music.models.QueueData { *; }
-keep class com.metrolist.music.models.QueueType { *; }
-keep class com.metrolist.music.playback.queues.** { *; }

-keepclassmembers class * implements java.io.Serializable {
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

## UCrop Rules
-dontwarn com.yalantis.ucrop**
-keep class com.yalantis.ucrop** { *; }
-keep interface com.yalantis.ucrop** { *; }

## Google Cast Rules
-keep class com.metrolist.music.cast.** { *; }
-keep class com.google.android.gms.cast.** { *; }
-keep class androidx.mediarouter.** { *; }

-dontwarn com.google.re2j.**

## Vibra Recognition
-keep class com.metrolist.music.recognition.VibraSignature { *; }
-keepclassmembers class com.metrolist.music.recognition.VibraSignature {
    native <methods>;
}

## Kotlin Reflection
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

## Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

## Shazam Models
-keep class com.metrolist.shazamkit.models.** { *; }
-keepclassmembers class com.metrolist.shazamkit.models.** { *; }

## Kotlinx Serialization
-keepattributes *Annotation*
-keepclassmembers class com.metrolist.shazamkit.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.metrolist.shazamkit.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}

## Protobuf Lite — required for reflection-based field access at runtime
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

## Listen Together Protobuf classes
-keep class com.metrolist.music.listentogether.proto.** { *; }
-keepclassmembers class com.metrolist.music.listentogether.proto.** { *; }

## Size Optimization Flags
-optimizationpasses 5
-mergeinterfacesaggressively
-allowaccessmodification
-repackageclasses 'a'
