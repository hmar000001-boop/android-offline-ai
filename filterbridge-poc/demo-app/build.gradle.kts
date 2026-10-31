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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":filter-core"))
    implementation(project(":android-ingest"))
}
