plugins {
    id("com.android.library")
}

android {
    namespace = "dev.filterbridge.android.ingest"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":filter-core"))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
}
