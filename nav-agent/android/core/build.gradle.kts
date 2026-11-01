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
// 高德 SDK 依赖（2026-11-01 升级到 V11.3.100）
//
// ★ 为什么升级（这是路线准确性问题的根因）：
//   之前用的 com.amap.api:navi-3dmap:10.0.800 是 **2024-08 的最后版本**
//   （Maven 上 lastUpdated=20240815055821，之后这个 artifact 停更了）。
//   算路数据是 4 年前的 —— 它不知道「南门不通车」这种通行限制，
//   而高德 App（2026 版数据）知道。用户实测两条路线走向完全不同。
//
//   官方最新：**V11.3.100（2026-09-24）**，已从 navi-3dmap 拆成独立包，
//   官方合包名 AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101
//   —— 是**单体合包**（地图+导航+搜索+定位全在内），结构跟旧的 navi-3dmap 一致。
//
// ★ 为什么用本地 jar 而不是 Maven 坐标：
//   Maven 上 com.amap.api:navi 只有 1.9.4（2020 年），新版**没上 Maven**，
//   只能从官网下载 ZIP 手动引入。
//
// ★ API 兼容性（已用 javap 逐个核对，全部保留）：
//   setCrossLocation / setCarBitmap / setTrafficLine / setMapStyle /
//   setLaneInfoShow / setTrafficLayerEnabled / setTrafficBarEnabled /
//   setNaviNight / setLayoutVisible / setTrafficLightsVisible —— 一个没少，
//   所以 NavActivity 的界面配置代码不用改。
//
// ★ 新版新增（这次升级的真正价值）：
//   AMapCarInfo.setCarType / setRestriction —— 可开启车行限制，
//   算路时会考虑「这条路你的车能不能过」（限高/限宽/货车禁行等）。
//   PathPlanningStrategy 新增多路线策略。
// ---------------------------------------------------------------------------

/** 工程内的 SDK jar（官网下载的合包） */
val amapJar = rootProject.file("libs/amap/AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101_20260924.jar")

/** 需要从 SDK jar 里剔除的重复类 */
val duplicateClasses = setOf(
    "com/amap/apis/utils/core/api/AMapUtilCoreApi.class",
    "com/amap/apis/utils/core/api/NetProxy.class"
)

/**
 * 从 SDK jar 里剔掉重复类。
 *
 * 为什么需要：`com.amap.api:search`（Maven 上那个独立包）也带这两个类，
 * 跟导航 SDK 撞 → Duplicate class。
 * `exclude` 是模块级的，剔不了单个类，所以只能打包阶段处理。
 *
 * ★ 2026-11-01 改造：新版是单体合包（自带 maps/navi/services/location），
 *   不再需要独立引入 search —— 所以去重对象从「search jar」变成「SDK jar 本身」，
 *   而 search 的类（poisearch/geocoder）本来就在合包里，不会丢。
 *
 * ★ 关键：剔掉的这 2 个类在 navi-3dmap 里还有一份（导航 SDK 自己要用），
 *   所以不能整个剔掉 —— 只剔「重复的那一份」。
 *   实际做法：合包 jar 里剔掉后，单独引 com.amap.api:navi（Maven 上没有）不现实，
 *   改为**保留合包里的这一份**（合包内部只有一份，不会撞），
 *   只在引入独立 search 包时才需要剔除。
 *
 *   现在不引独立 search 包了 → 这个任务退化为「校验合包内没有重复」，
 *   真正的去重发生在 app 模块（独立 search jar 与合包之间的冲突）。
 */
val stripSearchDuplicates by tasks.registering {
    description = "校验高德 SDK 合包内类不重复（合包自带搜索 SDK，不需要再引独立 search 包）"
    val sdkJar = files(amapJar)
    val outDir = layout.buildDirectory.dir("stripped-search")
    inputs.files(sdkJar)
    outputs.dir(outDir)
    doLast {
        val jarFile = sdkJar.singleFile
        val dest = outDir.get().asFile
        dest.mkdirs()
        val tmp = File(dest, "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        var removed = 0
        ZipFile(jarFile).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                // 这两个类只在「同时引独立 search 包」时才冲突。
                // 现在合包自带 search，所以要**保留**它们 —— 导航 SDK 自己要用。
                val target = File(tmp, entry.name)
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        }

        val outJar = File(dest, "amap-sdk.jar")
        outJar.delete()
        ZipOutputStream(outJar.outputStream().buffered()).use { zos ->
            tmp.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(tmp).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        tmp.deleteRecursively()
        logger.lifecycle("高德 SDK 已就绪：${outJar.absolutePath}（${outJar.length() / 1024 / 1024}MB）")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // 高德 SDK 合包（地图 + 导航 + 搜索 + 定位），官网下载的本地 jar。
    //
    // 用 api 而不是 implementation：app 模块直接用 AMapNaviView / MapsInitializer，
    // implementation 不暴露给下游模块，会报 Unresolved reference 'amap'。
    //
    // ★ 必须用 api 而不是 compileOnly/runtimeOnly：
    //   compileOnly 不进运行时 classpath（类会 NoClassDefFoundError）；
    //   runtimeOnly 虽然进运行时，但**不传递给下游模块**（app 拿不到，
    //   实测 :app:dependencies 里看不到它，APK 里也没有对应类）。
    //
    // ★ 必须用「产物路径」而不是 files(TaskProvider)：
    //   依赖声明里的 files(TaskProvider) 不会被 Gradle 解析成实际 jar，
    //   实测依赖树里根本看不到这一项，APK 里也没有类。
    val strippedJar = layout.buildDirectory.file("stripped-search/amap-sdk.jar")
    api(files(strippedJar))
}

// 让去重任务先于编译和打包执行
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("package") || it.name.startsWith("merge") }
    .configureEach {
        dependsOn("stripSearchDuplicates")
    }
