import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.solochat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.solochat"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("SOLOCHAT_VERSION_CODE")?.toInt() ?: 1
        versionName = (System.getenv("SOLOCHAT_VERSION_NAME") ?: "0.1.0").removePrefix("v")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        val releaseKeyPath = System.getenv("SOLOCHAT_KEYSTORE_PATH")
        if (!releaseKeyPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeyPath)
                storePassword = requireNotNull(System.getenv("SOLOCHAT_KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("SOLOCHAT_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("SOLOCHAT_KEY_PASSWORD"))
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    // Room schema 导出：不导的话每次改表结构都无法安全迁移，只能清库
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // 日志 web 服务。CIO 引擎是纯 Kotlin 实现、依赖最少（不带 Netty 那一堆），
    // 而这里只需要"把日志用 HTML 和 JSON 吐出去"这一件事。
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // kotlinx-coroutines-test：SessionController 的测试要在虚拟时间里跑防抖与播出，
    // 否则一条用例要真等几秒，而且并发时序不可复现。
    testImplementation(libs.kotlinx.coroutines.test)
    // 刻意**不引入 Robolectric**：它在测试运行时才去下载 android-all 构件，
    // 离线/受限网络下必然以 MavenDependencyResolver 的 FileNotFoundException 失败
    // （这个坑实测踩过）。所以需要 Android Context 的逻辑一律抽成纯函数再测
    // （见 data/pricing/ModelPricing.kt 的 PricingRules）。

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
