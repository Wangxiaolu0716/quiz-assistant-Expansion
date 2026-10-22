import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    buildDir = rootProject.file(".app-build")
    namespace = "com.quizassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wangxiaolu.quizassist"
        minSdk = 26
        targetSdk = 35
        versionCode = 20511
        versionName = "2.5.11"

        // ---- 远程配置端点 ----
        //
        // 仓库里**不写死任何服务器地址**：地址从 local.properties（已 gitignore）或
        // Gradle 属性读，读不到就是空字符串 —— 客户端进入完全离线模式，
        // 不拉配置、不弹公告、不检查更新、不上报。
        //
        //   local.properties:
        //     configUrl=https://你的域名/api/client/config
        //     reportUrl=https://你的域名/api/client/report
        //
        // 或者命令行：
        //   ./gradlew assembleRelease -PconfigUrl=... -PreportUrl=...
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun remoteValue(key: String): String =
            (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: ""

        buildConfigField("String", "CONFIG_PRIMARY_URL", "\"${remoteValue("configUrl")}\"")
        // 备源已下线，只保留「主源 → 本地缓存 → 内置默认值」三层
        buildConfigField("String", "CONFIG_FALLBACK_URL", "\"\"")
        buildConfigField("String", "CONFIG_REPORT_URL", "\"${remoteValue("reportUrl")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    signingConfigs {
        create("release") {
            val storePath = signingProperties.getProperty("storeFile")
            if (!storePath.isNullOrBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    if (providers.gradleProperty("skipLint").orNull == "true") {
        lint {
            checkReleaseBuilds = false
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("io.coil-kt:coil:2.7.0")
}
