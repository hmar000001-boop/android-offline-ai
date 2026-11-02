plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.screenveil.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.screenveil.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    constraints {
        implementation("androidx.core:core:1.17.0") {
            version { strictly("1.17.0") }
            because("ScreenVeil compiles against Android API 36; AndroidX Core 1.18+ raises its compile SDK requirement.")
        }
        implementation("androidx.core:core-ktx:1.17.0") {
            version { strictly("1.17.0") }
            because("ScreenVeil compiles against Android API 36; AndroidX Core 1.18+ raises its compile SDK requirement.")
        }
    }
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    val composeBom = enforcedPlatform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // NOTE: this declaration does NOT put the Kotlin runtime into the instrumentation APK.
    // Android Gradle Plugin leaves libraries the target app already packages out of the
    // androidTest APK (confirmed on API 34 and 36: no kotlin.* classes in its DEX files).
    // The fixture Activity/ContentProvider run in the separate dev.screenveil.app.test process
    // that loads only that APK, so they are written in Java (src/androidTest/java/.../fixture).
    // Do not add Kotlin sources to the fixture package.
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
