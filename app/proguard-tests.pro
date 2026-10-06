# Only with -Ptune.minify (the instrumented tests against a shrunk app): the
# test APK doesn't bundle what the app already has, and calls into the app's
# own classes by name, so those stay. Everything else (Media3, Coil, Guava, the
# JNI bindings) is shrunk as in a release.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-keep class com.music.tune.** { *; }
