plugins {
    id("com.android.application")
}

android {
    namespace = "dev.filterbridge.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.filterbridge.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.2-dual-local-image-classifier"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":filter-core"))
    implementation(project(":android-ingest"))
    implementation(project(":ai-runtime-onnx"))
    implementation("androidx.activity:activity:1.13.0")
}
