pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 国内镜像放在前面：这台机器直连 dl.google.com / repo1.maven.org 很慢甚至超时，
        // 而 aliyun 的镜像有同版本构件。镜像在官方源之前不会改变任何依赖的版本 ——
        // 版本一律由 gradle/libs.versions.toml 钉死，镜像只影响"从哪里下"。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "SoloChat"
include(":app")
include(":core")
