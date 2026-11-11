# Keep JNI entry points and the progress callback interface.
-keep class com.wm.starcam.engine.NativeEngine { *; }
-keep interface com.wm.starcam.engine.NativeEngine$ProgressCallback { *; }
# finalizeStack looks up String onStars(String) by name on its callback's class (jni_bridge.cpp):
# nothing in Kotlin calls it, so R8 would remove or rename it.
-keepclassmembers class * implements com.wm.starcam.engine.NativeEngine$ProgressCallback {
    public java.lang.String onStars(java.lang.String);
}
