# Add project specific ProGuard rules here.

# ──────────────────────────────────────────
# TensorFlow Lite
# ──────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# ──────────────────────────────────────────
# CameraX
# ──────────────────────────────────────────
-keep class androidx.camera.** { *; }

# ──────────────────────────────────────────
# VisionAid ML classes (don't obfuscate model wrappers)
# ──────────────────────────────────────────
-keep class com.visionaid.app.ml.** { *; }
-keep class com.visionaid.app.repository.** { *; }

# ──────────────────────────────────────────
# General Android
# ──────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends androidx.lifecycle.ViewModel
