# Add project specific ProGuard rules here.
# Compose / Kotlin 序列化保留
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ONNX Runtime（预留，M3 才用到）
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# NCNN（预留）
-keep class com.tencent.ncnn.** { *; }
-dontwarn com.tencent.ncnn.**