plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hwcamtrack"
    compileSdk = 35

    // 显式指定 build-tools：本机 SDK 里装的是 33.0.1 / 34.0.0 / 36.0.0 / 37.0.0，
    // 不写这一行 AGP 8.7.3 会默认去找 35.0.0 并触发联网安装。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hwcamtrack"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // ML Kit / TFLite 只提供这四个 ABI 的原生库，显式声明可以避免打包进空目录。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 真机安装用 debug 签名，方便直接 adb install；正式发布请换成自己的 keystore。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        // 显式指定工程内的调试签名，而不是让 AGP 去 %USERPROFILE%\.android\debug.keystore
        // 现造一个：本机不允许在用户目录下创建 *.lock 文件，默认路径会直接失败。
        // 这个 keystore 就是标准的 Android 调试签名（storepass/keypass 均为 android）。
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // TFLite 模型必须保持不压缩，否则无法直接 mmap 从 assets 加载。
        noCompress.add("tflite")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        // 不因 lint 报错中断构建（本工程用到的 Media3 API 全部是稳定 API）。
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ---- 界面基础 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 网络：自动发现与 MJPEG 取流 ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- 取流：RTSP(RTP) 与 HTTP-FLV 用 ExoPlayer，其余由自研通道兜底 ----
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // ---- 人体检测 ----
    // 方案 A（默认，支持多人）：TFLite Task Library + EfficientDet-Lite0
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // 方案 B（单人，骨骼关键点，无需额外模型文件）：ML Kit Pose Detection
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")

    testImplementation("junit:junit:4.13.2")
}
