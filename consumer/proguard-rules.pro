# Instrumentation tests access the public SDK API after shrinking.
-keep class org.sihai.apm.** { public *; }
# The separately compiled instrumentation APK shares this runtime. Its Kotlin
# calls are not visible while R8 analyzes the target application's entrypoints.
-keep class kotlin.** { *; }
