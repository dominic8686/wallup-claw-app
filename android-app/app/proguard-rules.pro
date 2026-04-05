# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn okhttp3.internal.Util

-dontobfuscate

# ONNX Runtime — keep all JNI-referenced classes
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Gson — keep classes used for JSON deserialization
-keep class com.google.gson.** { *; }

# LiveKit SDK
-keepclassmembers class io.livekit.android.** {
    *** Companion;
}
-keepclasseswithmembers class io.livekit.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# App navigation routes — @Serializable classes used by Compose Navigation
-keep class com.wallupclaw.app.screen.** { *; }
-keepclassmembers class com.wallupclaw.app.screen.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.serialization — keep all serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.wallupclaw.app.**$$serializer { *; }
-keepclassmembers class com.wallupclaw.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.wallupclaw.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# NanoHTTPD (DLNA service)
-keep class fi.iki.elonen.** { *; }
