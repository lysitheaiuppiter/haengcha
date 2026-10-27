import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 출시 서명: 키스토어와 비밀번호는 저장소 밖 properties 파일에 둔다(storeFile, storePassword, keyAlias, keyPassword).
// 기본 위치는 ~/.android-keys/haengcha-release.properties이고 -PhaengchaSigning=<파일>로 바꿀 수 있다.
// 파일이 없으면 서명하지 않은 릴리스가 나온다.
val signingProps = Properties().apply {
    val path = findProperty("haengchaSigning") as String?
    val f = path?.let(::File) ?: File(System.getProperty("user.home"), ".android-keys/haengcha-release.properties")
    if (f.isFile) f.inputStream().use(::load)
}

android {
    namespace = "haengcha.app"
    compileSdk = 35

    defaultConfig {
        // 패키지명은 아직 정하지 않아 임시 이름을 쓴다
        applicationId = "haengcha.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (!signingProps.isEmpty) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
        // 디버그 빌드는 심사용 시연판으로도 쓴다(시연 명령 DebugReceiver 포함, 주행 중 화면이 에뮬레이터에서 보임).
        // 출시 키가 있으면 같은 키로 서명해 출시판과 기록을 지우지 않고 서로 바꿔 설치할 수 있게 한다.
        debug {
            versionNameSuffix = "-demo"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":vehicle"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(kotlin("test"))
}
