# ffmpeg-kit uses JNI + reflection
-keep class com.arthenica.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.**

# Keep Kotlin metadata for coroutines
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlin.Unit
-dontwarn kotlinx.coroutines.flow.**
