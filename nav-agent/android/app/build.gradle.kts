import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 密钥从 local.properties 读，不写死在版本库里。
// 缺 Key 时给空串，编译照过，只是运行时语音/聊天会提示未配置。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, def: String = ""): String = localProps.getProperty(key) ?: def

android {
    namespace = "com.navagent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.navagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.3.11"

        // vosk 的 aar 带 arm64/armv7/x86/x86_64 四套 so，全打进去 APK 涨 40MB。
        // 两台测试机都是 arm64，模拟器用不上，砍掉 x86 两套。
        //
        // ★ 2026-11-01：armeabi-v7a 也砍了（只剩 arm64-v8a）。
        //   理由：新版高德 SDK 的 so 很大 ——
        //     高德 arm64 87MB + armeabi-v7a 64MB
        //     vosk arm64 10MB + armeabi-v7a 9MB
        //   砍掉 v7a 直接省约 73MB，APK 从 ~190MB 降到 ~115MB。
        //
        //   代价：**不再支持 32 位设备**（2015 年前的老机型）。
        //   华为 P40 Pro 的 abilist = arm64-v8a,armeabi-v7a,armeabi，
        //   64 位运行时优先，装 arm64 版完全没问题。
        //   如果以后要支持老车机，把 "armeabi-v7a" 加回这个列表即可。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // ★ 高德 SDK 的 so（官网合包解压出来的，不在 jar 里）。
        //   放在 libs/amap/<abi>/ 下，sourceSets 指定进来。
        //   ★ 必须是 jniLibs.srcDirs 而不是把 so 塞进 app/src/main/jniLibs ——
        //   那样 so 会进版本库（每个 ABI 一份，加起来几十 MB）。
        sourceSets["main"].jniLibs.srcDirs(
            rootProject.file("libs/amap"),
            "src/main/jniLibs"
        )

        // 高德 Key（Android 平台，绑定包名 com.navagent）
        manifestPlaceholders["AMAP_KEY"] = prop("AMAP_KEY")

        // 高德 Web 服务 Key，只用于地名 → 坐标的地理编码。
        //
        // 为什么需要它：AMapNavi.calculateDriveRoute(String start, String end, ...) 这个
        // 「传地址字符串」的重载内部要调 POI 搜索模块，而 search SDK 与 navi-3dmap 存在
        // 无法调和的重复类（com.amap.apis.utils.core.api.AMapUtilCoreApi），只能二选一。
        // 保留 navi-3dmap（导航必需），地理编码改走 Web API，再用坐标算路。
        //
        // 本机开发时写入 local.properties；仓库和示例配置都不应包含真实 Key。
        buildConfigField("String", "AMAP_WEB_KEY", "\"${prop("AMAP_WEB_KEY")}\"")

        // MiniMax 的地址与模型名可作为开发默认值；API Key 只从设置页读取。
        buildConfigField("String", "MINIMAX_BASE_URL", "\"${prop("MINIMAX_BASE_URL", "https://api.minimaxi.com/v1")}\"")
        buildConfigField("String", "MINIMAX_CHAT_MODEL", "\"${prop("MINIMAX_CHAT_MODEL", "minimax-m3.1-flash")}\"")
        buildConfigField("String", "MINIMAX_ASR_MODEL", "\"${prop("MINIMAX_ASR_MODEL", "asr-1.0")}\"")

        // Tavily 搜索 Key（可选）。
        //
        // 不填也能联网：web_search 有一条国内引擎降级链（搜狗/百度/Bing），
        // 实测在这台手机上直连只要 32~41ms，完全不需要 Key。
        // 填了则主搜索走 Tavily（英文更强、正文更全），失败自动降级。
        buildConfigField("String", "TAVILY_API_KEY", "\"${prop("TAVILY_API_KEY", "")}\"")

        // 唤醒词变体，逗号分隔。改这里就能换唤醒词，不用改代码。
        buildConfigField(
            "String",
            "WAKE_WORDS",
            "\"你好小高,小高,小高你好,小高在吗,你好小高在吗,嗨小高\""
        )
    }

    buildTypes {
        release {
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // vosk 的 aar 里带 4 个 ABI 的 so，只保留 arm64 和 armeabi-v7a，省一半体积
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 待命唤醒仍由 Vosk 负责；播报中另起一个只监听「小猪」的 Sherpa KWS。
    // 两个模型职责不同：Vosk 做通用唤醒/同音兜底，Sherpa 在 TTS 混音下做
    // 固定关键词识别，避免自由解码把长播报拼成碎片。
    implementation("com.alphacephei:vosk-android:0.3.75")

    // 完整版 AAR 自带与 Sherpa 完全匹配的 libonnxruntime.so，并同时提供
    // KeywordSpotter 与 SpeakerEmbeddingExtractor。不能再引入 Maven 的
    // onnxruntime-android：两个 AAR 含同名 native 库，最终只能打进一个，
    // 版本不一致时 Sherpa 会在 JNI 初始化阶段直接失败。
    implementation(files(rootProject.file("libs/sherpa/sherpa-onnx.aar")))

    // 高德依赖只在 :core 声明一次，app 通过 project(":core") 传递获得。
    // 若此处再声明 location，会与 navi-3dmap 内置的 location 类冲突（Duplicate class）。
    //
    // search 由 :core 以「去重后的 jar」形式提供（见 core/build.gradle.kts 的
    // stripSearchDuplicates 任务），避免和 navi-3dmap 撞 AMapUtilCoreApi。
}

// core 的去重任务必须先跑完，app 才能把那个 jar 打进 APK。
// 少了这行 Gradle 会报：
//   Task ':app:desugarDebugFileDependencies' uses this output of task
//   ':core:stripSearchDuplicates' without declaring an explicit or implicit dependency
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("package") ||
    it.name.startsWith("merge") || it.name.startsWith("desugar") || it.name.startsWith("dex") }
    .configureEach {
        dependsOn(":core:stripSearchDuplicates")
    }
