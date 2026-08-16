pluginManagement {
    repositories {
        if (System.getenv("CI") == "true") {
            // GitHub Actions 等境外 CI：官方仓库（境外访问快且完整）
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 本地/国内环境：阿里云镜像优先（加速 dl.google.com 超时问题），官方兜底
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true") {
            // 境外 CI：官方仓库
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        } else {
            // 本地/国内：阿里云镜像优先，官方兜底
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        }
    }
}

rootProject.name = "ResumePilot"
include(":app")
