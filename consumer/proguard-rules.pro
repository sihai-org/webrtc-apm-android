# Instrumentation tests access the public SDK API after shrinking.
-keep class org.sihai.apm.** { public *; }
