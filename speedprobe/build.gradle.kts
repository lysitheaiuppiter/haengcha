plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "haengcha.speedprobe"
    compileSdk = 35
    // AAOS Car API(CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "haengcha.speedprobe"
        // Pleos Playground 배포 요건이 API 28 이상
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pleos SDK 값이 에뮬레이터에서 나오는지 확인하는 용도(PleosProbe)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
}
