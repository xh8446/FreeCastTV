plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freecast.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.freecast.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 244
        versionName = "2.44"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/freecast.jks")
            storePassword = "freecast123"
            keyAlias = "freecast"
            keyPassword = "freecast123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            // 恢复混淆 + 资源压缩（体积减半）；TV 崩溃已通过主题修复解决
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
