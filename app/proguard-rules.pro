# JNI entry points are resolved by name, so never rename or strip them.
-keep class com.trustmarktool.nativebridge.TrustMarkNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep WorkManager worker constructors used by reflection.
-keep class com.trustmarktool.worker.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ONNX Runtime uses JNI internally.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
