import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.navagent.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// 高德 search SDK 的重复类处理
//
// 背景：navi-3dmap 与 search 都带 com/amap/apis/utils/core/api/{AMapUtilCoreApi,NetProxy}，
// Gradle 的 exclude 是**模块级**的，剔不了单个类，所以直接把 search 加进依赖会报：
//   Duplicate class com.amap.apis.utils.core.api.AMapUtilCoreApi found in modules search and navi-3dmap
//
// 解法：把 search jar 里的这 2 个类剔掉，保留 navi-3dmap 的那一份。
// 安全性依据（javap 逐方法比对）：
//   navi-3dmap 版 = setNetProxy / getNetProxy / setCollectInfoEnable /
//                   setCollectIPEnable / getCollectIPEnable
//   search 版     = setNetProxy / getNetProxy / setCollectInfoEnable
// search 那份是 navi 那份的**方法子集**，运行时 search 调用的方法 navi 版全都有，
// 行为完全不变；反过来 navi 版多出的两个方法 search 根本不会调。
//
// HiCar 能同时用 PoiSearch 和 navi 也是同样思路（它把整个 com/amap/apis/utils 包剔了）。
// ---------------------------------------------------------------------------

/** 需要从 search jar 里剔除的类（与 navi-3dmap 重叠的那两个） */
val searchDuplicateClasses = setOf(
    "com/amap/apis/utils/core/api/AMapUtilCoreApi.class",
    "com/amap/apis/utils/core/api/NetProxy.class"
)

/**
 * 从 search 的 jar 里剔掉重复类，产出一份「干净」的 jar。
 * 用 build 目录做，不污染 Gradle 缓存（缓存被改会污染后续所有构建）。
 */
val stripSearchDuplicates by tasks.registering {
    description = "从 com.amap.api:search 中剔除与 navi-3dmap 重复的 AMapUtilCoreApi / NetProxy"
    val searchJar = configurations.detachedConfiguration(
        dependencies.create("com.amap.api:search:9.7.1")
    )
    val outDir = layout.buildDirectory.dir("stripped-search")
    inputs.files(searchJar)
    outputs.dir(outDir)
    doLast {
        val jarFile = searchJar.singleFile
        val dest = outDir.get().asFile
        dest.mkdirs()
        val tmp = File(dest, "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        ZipFile(jarFile).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (entry.name in searchDuplicateClasses) {
                    logger.lifecycle("剔除重复类：${entry.name}")
                    return@forEach
                }
                val target = File(tmp, entry.name)
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        }

        val outJar = File(dest, "search-stripped.jar")
        outJar.delete()
        ZipOutputStream(outJar.outputStream().buffered()).use { zos ->
            tmp.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(tmp).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        tmp.deleteRecursively()
        logger.lifecycle("已生成去重 jar：${outJar.absolutePath}（${outJar.length() / 1024}KB）")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // 高德：导航 SDK 与 3D 地图 SDK 的合体包（navi-3dmap）。
    //
    // 用 api 而不是 implementation：app 模块的 MainActivity 直接用 MapView / MapsInitializer，
    // implementation 不会把依赖暴露给下游模块，会报 Unresolved reference 'amap'。
    //
    // 关于重复类（实测结论，别再踩）：
    //  1) navi-3dmap jar 已内置 com.amap.api.maps(270 类) / navi(241 类) / location(23 类)，
    //     而它的 POM 又把 com.amap.api:location 声明为传递依赖 →
    //     Duplicate class com.amap.api.location.* / com.amap.api.fence.*，必须 exclude。
    //  2) search 与 navi-3dmap 只重叠 2 个类，处理方式见上面的 stripSearchDuplicates 任务。
    api("com.amap.api:navi-3dmap:10.0.800_3dmap10.0.800") {
        exclude(group = "com.amap.api", module = "location")
    }

    // 高德搜索 SDK：POI 搜索（place/text、place/around）和地理编码。
    // 有了它就不必再依赖 Web 服务 Key，也不再受主线程禁发 HTTP 的限制。
    // 版本 9.7.1 是官方最后一个版本（2024-03 后停更），已是最新。
    //
    // 用去重后的 jar，而不是直接依赖 search —— 否则会撞 AMapUtilCoreApi。
    //
    // ★ 必须用 api 而不是 compileOnly/runtimeOnly：
    //   compileOnly 不进运行时 classpath（类会 NoClassDefFoundError）；
    //   runtimeOnly 虽然进运行时，但**不传递给下游模块**（app 模块就拿不到，
    //   实测 :app:dependencies 里看不到它，APK 里也没有 poisearch 类）。
    //   app 模块要直接用 PoiSearch / GeocodeSearch，所以必须 api 暴露。
    //
    // ★ 必须用「产物路径」而不是 files(stripSearchDuplicates)：
    //   依赖声明里的 files(TaskProvider) 不会被 Gradle 解析成实际 jar，
    //   实测 :core:dependencies 里根本看不到这一项，APK 里也没有 poisearch 类。
    //   改成指向 build 目录里的实际文件，并让编译/打包任务依赖 stripSearchDuplicates，
    //   保证生成顺序正确。
    val strippedSearchJar = layout.buildDirectory.file("stripped-search/search-stripped.jar")
    api(files(strippedSearchJar))
}

// 让依赖去重任务先于编译和打包执行
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("package") || it.name.startsWith("merge") }
    .configureEach {
        dependsOn("stripSearchDuplicates")
    }
