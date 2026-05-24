plugins {
    id("com.android.library")
}

group = "com.github.topjohnwu.libsu"

android {
    namespace = "com.topjohnwu.superuser"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.6.0")
}
