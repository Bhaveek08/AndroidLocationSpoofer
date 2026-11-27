plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.codex.locationlab"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "dev.codex.locationlab"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A release key is supplied by CI secrets or local Gradle properties. Without it the release
    // build is simply unsigned; no signing material is ever committed to this repository.
    val releaseStore = (findProperty("geoshift.storeFile") as String?) ?: System.getenv("GEOSHIFT_STORE_FILE")
    signingConfigs {
        if (!releaseStore.isNullOrBlank() && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = (findProperty("geoshift.storePassword") as String?) ?: System.getenv("GEOSHIFT_STORE_PASSWORD")
                keyAlias = (findProperty("geoshift.keyAlias") as String?) ?: System.getenv("GEOSHIFT_KEY_ALIAS")
                keyPassword = (findProperty("geoshift.keyPassword") as String?) ?: System.getenv("GEOSHIFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off: MapLibre's style/layer classes are reached reflectively and a wrong
            // keep rule fails only at runtime, on a device, long after the build passed.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in unit tests; the real implementation lets the route parser
    // and the backup codec be covered on the JVM instead of only on a device.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
