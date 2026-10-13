import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One debug key for every machine and CI, so Google sign-in works on any debug build
// (keystore/README.md). The certificate's SHA-1 must match keystore/debug.sha1.
val debugKeystore = rootProject.file("keystore/debug.keystore")
val expectedDebugSha1 = rootProject.file("keystore/debug.sha1").readText().trim().uppercase()

android {
    namespace = "ca.rocklogik.connect"
    compileSdk = 37

    defaultConfig {
        applicationId = "ca.rocklogik.connect"
        // API 25: the test tablet is a Galaxy Tab A on Android 7.1.1 (decided 13 October 2026).
        minSdk = 25
        targetSdk = 36 // compile against 37 for AndroidX; target 36 until 37 is tested
        versionCode = 1
        versionName = "0.1.0"
        // rocklogik-cloud base URL; the Google web client id arrives with ROCKLOGIK-82.
        buildConfigField("String", "CLOUD_BASE_URL", "\"https://cloud.rocklogik.ca\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // BLE: Nordic's Android BLE Library and scanner compat, BSD-3-Clause, used as dependencies.
    implementation("no.nordicsemi.android:ble-ktx:2.11.0")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303") // the real org.json for JVM tests
}

// Fails the build when the project debug key is missing or is not the one registered with Google.
val verifyDebugKey by tasks.registering {
    inputs.files(rootProject.file("keystore/debug.sha1"))
    doLast {
        if (!debugKeystore.exists()) {
            throw GradleException("keystore/debug.keystore is missing: see keystore/README.md")
        }
        val store = listOf("PKCS12", "JKS").firstNotNullOfOrNull { type ->
            runCatching {
                KeyStore.getInstance(type).apply {
                    debugKeystore.inputStream().use { load(it, "android".toCharArray()) }
                }
            }.getOrNull()
        } ?: throw GradleException("keystore/debug.keystore cannot be opened with the debug password")
        val cert = store.getCertificate("androiddebugkey")
            ?: throw GradleException("keystore/debug.keystore has no androiddebugkey alias")
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        if (sha1 != expectedDebugSha1) {
            throw GradleException("keystore/debug.keystore SHA-1 is $sha1, but keystore/debug.sha1 says $expectedDebugSha1")
        }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(verifyDebugKey) }
