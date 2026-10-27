// AAOS Car API(차속·기어·주행 규제·충전 포트·바깥 기온)와 Pleos Vehicle·NaviHelper SDK에서 받아 판정 엔진 형식으로 넘기는 수집 계층
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "haengcha.vehicle"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        minSdk = 28
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
    api(project(":core-judge"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
}
