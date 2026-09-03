plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI 는 매 실행마다 새 VM 이라 안드로이드 기본 디버그 키스토어가 새로 생성된다.
// 그러면 빌드마다 서명이 달라져서 기존 앱 위에 덮어 설치가 안 된다("앱이 설치되지 않음").
// 그래서 고정 키스토어로 서명한다. 파일은 GitHub Actions 시크릿(SIGNING_KEYSTORE_B64)에
// 들어 있고 워크플로가 빌드 직전에 여기로 풀어놓는다. 없으면 기본 디버그 키로 넘어간다.
val signingKeystore = rootProject.file("app/poptube.p12")

android {
    namespace = "com.jklee.poptube"
    compileSdk = 35

    signingConfigs {
        if (signingKeystore.exists()) {
            getByName("debug") {
                storeFile = signingKeystore
                storeType = "PKCS12"
                storePassword = "poptube"
                keyAlias = "poptube"
                keyPassword = "poptube"
            }
        }
    }

    defaultConfig {
        applicationId = "com.jklee.poptube"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        // 앱이 원격 광고차단 규칙을 받아오는 곳. Vercel 배포 후 자기 도메인으로 바꾸면 된다.
        buildConfigField("String", "RULES_URL", "\"https://poptube.vercel.app/api/rules\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
