plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.sihai.apm"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native/jni"))
    sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("native/resources"))
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
val verifyNative by tasks.registering {
    doLast {
        listOf("armeabi-v7a", "arm64-v8a", "x86_64").forEach { abi ->
            check(layout.buildDirectory.file("native/jni/$abi/libwebrtc_apm_jni.so").get().asFile.isFile) {
                "Missing native ABI $abi: build/import native artifacts first (docs/build.md)."
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNative) }
