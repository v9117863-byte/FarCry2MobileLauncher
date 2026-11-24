plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farcry2.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.farcry2.mobile"
        minSdk = 26
        targetSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
        versionCode = 9
        versionName = "0.9.0-runtime-host"
    }
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
    implementation("androidx.documentfile:documentfile:1.0.1")
}
