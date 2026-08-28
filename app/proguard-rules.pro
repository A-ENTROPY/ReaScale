# Add project specific ProGuard rules here.
# Compose / Kotlin åºåˆ—åŒ–ä¿ç•™
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ONNX Runtimeï¼ˆé¢„ç•™ï¼ŒM3 æ‰ç”¨åˆ°ï¼‰
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# NCNNï¼ˆé¢„ç•™ï¼‰
-keep class com.tencent.ncnn.** { *; }
-dontwarn com.tencent.ncnn.**
# [FIX 2026-08-26] JNI£ºnative ·½·¨ÃûÓë C++ JNI ·ûºÅ°ó¶¨£¬½ûÖ¹»ìÏı/°şÀë
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class io.reascale.app.core.engine.ReascaleNcnn { *; }
-keep class io.reascale.app.core.engine.ReascaleNcnn { *; }
-keep class io.reascale.app.core.encode.JxlStreamWriter { *; }
-keep class io.reascale.app.core.engine.NcnnEngine { *; }
-keep class io.reascale.app.core.engine.OnnxEngine { *; }
# libjxl / jxl-coder / avif-coder
-keep class com.awxkee.jxlcoder.** { *; }
-keep class com.radzivon.bartoshyk.avif.coder.** { *; }
-keep class androidx.heifwriter.** { *; }
