import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 판정 엔진(haengcha.judge)과 주행 엔진(haengcha.drive). Android·SDK에 의존하지 않아 JVM 테스트와 주행 기록 재생으로 검증한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 주행 엔진의 화면 상태(StateFlow)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}
