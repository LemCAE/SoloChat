// :core —— 纯 Kotlin JVM 模块，**不允许依赖任何 Android API**。
//
// 为什么单独一个模块：协议解析、节奏算法、防抖规则这些是整个 App 最容易出错、
// 也最需要反复回归的部分。做成纯 JVM 就能用 ./gradlew :core:test 秒级验证，
// 不需要模拟器、不需要真机。另外它也是"日后想把核心搬到电脑端服务"的物理前提。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 刻意**不用** `jvmToolchain(17)`：那会要求本机存在 JDK 17，
    // 而本机只有 JDK 21，构建会直接以 "Cannot find a Java installation ...
    // languageVersion=17" 失败。这里改成"用当前 JDK 编译，字节码目标 17" ——
    // 17 是 Android 侧需要的最低字节码版本，结果完全一致，还少下一套 JDK。
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
