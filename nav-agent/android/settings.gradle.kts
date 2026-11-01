// 仓库顺序：阿里云镜像优先（实测 0.1~0.2s），官方源兜底。
// 2026-10-31：直连 dl.google.com 单次 ~0.9s 且偶发长时间挂死（jstack 见 downloadByCoords 阻塞），故前置镜像。
// 注意：pluginManagement 是独立脚本作用域，不能引用本文件的顶层 val，字符串必须内联。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "nav-agent"
include(":app")
include(":core")
