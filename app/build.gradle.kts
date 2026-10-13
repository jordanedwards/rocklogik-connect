plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
