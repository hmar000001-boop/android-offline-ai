plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // CI instrumentation needs POST_NOTIFICATIONS granted before AndroidJUnitRunner starts.
    // AGP passes these options to the install operations it performs for connected tests.
    // This is enabled only in the disposable emulator job; it is NOT packaged into the APK.
    if (System.getenv("SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS") == "1") {
        installation {
            installOptions.add("-g")
        }
        println("SCREENVEIL_GRADLE_INSTALL_OPTIONS=-g")
    }

    namespace = "dev.screenveil.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.screenveil.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 20
        versionName = "1.6.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is read from the environment so no secret is committed. Without these
    // variables `assembleRelease` still builds (R8 verified) but produces an UNSIGNED apk.
    val releaseKeystore = System.getenv("SCREENVEIL_KEYSTORE_FILE")
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SCREENVEIL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCREENVEIL_KEY_ALIAS")
                keyPassword = System.getenv("SCREENVEIL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
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
    // Activity Result APIs are used by MainActivity. Keep Fragment explicitly current because
    // some transitive AndroidX dependencies can otherwise select a pre-1.3 FragmentActivity
    // implementation, which is incompatible with Activity Result permission callbacks.
    implementation("androidx.fragment:fragment:1.9.1") {
        version { strictly("1.9.1") }
        because("Activity Result APIs require Fragment 1.3.0 or newer when FragmentActivity is present")
    }
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
    // Bundled, offline LiteRT runtime. Do not use MediaPipe Tasks here: this app permits
    // network access only for explicit model downloads, not task-usage telemetry.
    implementation("com.google.ai.edge.litert:litert:2.3.2")

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
