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
        versionCode = 2
        versionName = "0.1.1-launcher-fix"
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
