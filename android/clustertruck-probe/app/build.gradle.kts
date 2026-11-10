plugins {
    id("com.android.application")
}

android {
    namespace = "com.clustertruck.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clustertruck.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
