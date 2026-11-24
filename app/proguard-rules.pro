-keep class io.github.system32crowdstrikewin11.openaircursor.NativeBridge { *; }
-keep class io.github.system32crowdstrikewin11.openaircursor.AirCursorAccessibilityService { *; }

# MediaPipe Tasks & JNI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Google Flogger
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# Protobuf Lite
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-dontwarn com.google.protobuf.**

-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
