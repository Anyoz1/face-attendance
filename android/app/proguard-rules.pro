-keep class ai.onnxruntime.** { *; }
# Keep the small app API stable so the same physical-device verification can
# exercise the optimized APK. R8 still shrinks/optimizes the AndroidX libraries.
-keep class com.anyoz.faceattendance.** { *; }
# AndroidJUnitRunner calls this shared API while verifying the release APK.
-keep class androidx.tracing.** { *; }
# These public runtimes are shared with the separately built test APK. Keep
# their ABI intact; inference work remains in optimized native ONNX kernels.
-keep class kotlin.** { *; }
-keep class androidx.lifecycle.** { *; }
