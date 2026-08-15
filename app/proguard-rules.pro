# ResumePilot ProGuard Rules

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Keep our data classes (Room 实体 / Gson 模型)
-keep class com.resumepilot.app.data.** { *; }
-keep class com.resumepilot.app.engine.** { *; }

# Gson 反射序列化/反序列化的模型类（PlatformTemplate/WorkflowStep/UIElementInfo 等）
-keep class com.resumepilot.app.adapter.** { *; }
# ResumeData 等被 Gson/JSON 解析的简历模型
-keep class com.resumepilot.app.resume.** { *; }

# kaml YAML 解析（反射创建模型）
-dontwarn com.charleskorn.kaml.**
-keep class com.charleskorn.kaml.** { *; }

# kotlinx.serialization 注解保留（kaml 依赖）
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# 协程与 Flow（反射调用）
-dontwarn kotlinx.coroutines.**
