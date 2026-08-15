# ResumePilot ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Keep our data classes
-keep class com.resumepilot.app.data.** { *; }
-keep class com.resumepilot.app.engine.** { *; }