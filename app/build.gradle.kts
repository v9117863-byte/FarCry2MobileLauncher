plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farcry2.mobile"
    compileSdk = 35
    ndkVersion = "24.0.8215888"

    defaultConfig {
        applicationId = "com.farcry2.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0-runtime-host"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    if (file("src/main/cpp/CMakeLists.txt").isFile) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

val verifyBundledRuntime by tasks.registering {
    doLast {
        val runtimeRoot = file("src/main/assets/runtime")
        val manifestFile = file("src/main/assets/runtime/runtime-manifest.json")
        if (!manifestFile.isFile) {
            throw GradleException("No embedded runtime found. Stage a licensed runtime before making a one-APK release.")
        }
        val json = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        listOf("entrypoint", "wine", "box86", "box64", "rootfs", "graphicsBackend").forEach { key ->
            val relative = json[key] as? String ?: throw GradleException("Runtime manifest missing $key")
            val candidate = file("src/main/assets/runtime/$relative").canonicalFile
            if (!candidate.toPath().startsWith(runtimeRoot.canonicalFile.toPath()) || !candidate.exists()) {
                throw GradleException("Runtime component $key is missing or outside runtime directory: $relative")
            }
        }
    }
}
if (providers.gradleProperty("requireBundledRuntime").orNull == "true") {
    tasks.named("preBuild").configure { dependsOn(verifyBundledRuntime) }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.12")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    implementation("androidx.core:core:1.13.1")
}
