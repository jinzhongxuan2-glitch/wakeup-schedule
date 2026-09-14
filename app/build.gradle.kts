import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wakeup.schedule"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wakeup.schedule"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.5"
    }

    signingConfigs {
        create("release") {
            // 固定的 release 签名：后续版本同签名才能覆盖安装（应用内更新的前提）
            // 凭据在根目录 keystore.properties（不入库）；缺失时退回内置学习用默认值
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            storeFile = rootProject.file(props.getProperty("storeFile", "wakeup-release.keystore").removePrefix("../"))
            storePassword = props.getProperty("storePassword", "wakeup2026")
            keyAlias = props.getProperty("keyAlias", "wakeup")
            keyPassword = props.getProperty("keyPassword", "wakeup2026")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    lint {
        // 个人项目打包不跑 release lint（其依赖下载在国内易断流）
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.jsoup)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
