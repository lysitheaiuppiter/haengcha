import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 판정 엔진. Android·SDK에 의존하지 않아 JVM 테스트와 주행 기록 재생으로 검증한다.
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
    testImplementation(kotlin("test"))
}
