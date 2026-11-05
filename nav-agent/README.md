# nav-agent

> 含高德地图导航能力的 Android 语音智能体。
> 开车时不用看屏幕 —— 说一句话就能算路、查天气、问新闻，回答直接念给你听。

---

## 它能做什么

| 能力 | 怎么用 | 依赖 |
|---|---|---|
| **语音唤醒** | 喊「小猪」唤醒，全程离线识别，不联网 | sherpa-onnx 关键词检测 |
| **语音导航** | 「导航去张掖西站」→ 当前页直接算路开始导航 | 高德 SDK |
| **连续对话** | 唤醒后可连续追问，回答只用 TTS 播报，不切页面 | MiniMax（ASR + LLM + TTS） |
| **打断播报** | 导航播报中喊「小猪」可以打断 | sherpa-onnx |
| **实时天气** | 「明天张掖要带伞吗」 | 高德天气 API，**零额外配置** |
| **新闻与任意网页** | 「今天张掖有什么新闻」→ 检索 → 读原文 → 总结 | Tavily Search / Extract |
| **周边搜索** | 「附近有什么加油站」 | 高德 POI |
| **长期记忆** | 住处、公司、饮食偏好等，跨会话保留 | 本地存储 |

主界面就是导航页：地图、转向条、语音按钮，没有聊天记录列表。**回答只播报，不显示文字** —— 这是产品有意做的选择（v0.3.0 起）。

---

## 快速开始

### 1. 拉两个不入库的二进制

高德 SDK 和唤醒词模型都不在版本库里（体积大且有授权限制），首次构建前先拉：

```bash
bash nav-agent/tools/fetch_amap_sdk.sh   # 高德导航 SDK，约 187MB
bash nav-agent/tools/fetch_sherpa.sh     # sherpa-onnx AAR，47MB（带 sha256 校验）
```

### 2. 填 `local.properties`

这个文件已被 `.gitignore` 排除，**不要提交**。

```properties
sdk.dir=D\:\\Android\\Sdk

# 高德 Key（Android 端，绑定包名 com.navagent）
AMAP_KEY=<你的高德 Android Key>
# 高德 Web 服务 Key（地理编码 / POI / 天气，不绑定包名）
AMAP_WEB_KEY=<你的高德 Web Key>

# MiniMax：语音识别 + 语音合成 + 对话模型共用
MINIMAX_API_KEY=<你的 MiniMax Key>
MINIMAX_BASE_URL=https://api.minimaxi.com/v1
MINIMAX_CHAT_MODEL=minimax-m3.1-flash
MINIMAX_ASR_MODEL=asr-1.0

# Tavily：通用搜索 + 网页正文提取
TAVILY_API_KEY=<你的 Tavily Key>
```

### 3. 编译

工程**没有 gradle wrapper**，需要本机装好 Gradle：

```bash
cd nav-agent/android
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME=/path/to/Android/Sdk
export GRADLE_OPTS="-Dorg.gradle.internal.http.socketTimeout=20000 -Dorg.gradle.internal.http.connectionTimeout=20000"
/path/to/gradle-8.14.6/bin/gradle.bat assembleDebug
```

> `GRADLE_OPTS` 里的超时**不能省**：`services.gradle.org` 在国内会卡死
> （实测 6 分钟只下 11MB/130MB），换腾讯镜像才正常。

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 架构

```
core/    纯 Kotlin，不认识任何高德类型 —— 换导航引擎时解析逻辑一行不改
├── ChatEngine.kt    对话编排：全部走 LLM，边流边念，按句切给 TTS
├── Chat.kt          ChatMessage / Llm 接口
├── LlmTool.kt       工具接口（Function Calling）
├── VoiceIo.kt       Asr / WakeWord / SpeakerGate 接口
├── VoiceGuide.kt    TTS 队列：NAV > USER > CHAT，导航播报可打断闲聊
└── NavController.kt 导航引擎抽象

app/     Android 实现
├── NavApp.kt            Application 单例：TTS / 对话 / 导航 / 工具注入
├── NavActivity.kt       主界面（LAUNCHER）：导航页 + 底部语音按钮
├── VoiceService.kt      前台服务，麦克风唯一持有者
├── VoiceEngine.kt       语音状态机 ASLEEP / LISTENING / THINKING
├── AudioCapture.kt      AudioRecord 16k/mono/PCM16，20ms 一帧
├── EnergyVad.kt         能量 VAD，自适应噪声底，断句 800ms
├── VoskWake.kt          离线唤醒 + 模糊匹配（词首丢失 / 片段 / 拼音兜底）
├── SherpaBargeIn.kt     播报期间的固定关键词识别
├── MinimaxAsr.kt        MiniMax 语音识别（手写 multipart）
├── MinimaxTts.kt        MiniMax 云端 TTS
├── OpenAiLlm.kt         OpenAI 兼容对话 + 多轮工具循环
├── NavigationTool.kt    导航控制
├── WeatherTool.kt       天气（高德 Web API）
├── NearbyTool.kt        周边搜索（高德 POI）
├── WebSearchTool.kt     通用搜索（Tavily）
├── WebExtractTool.kt    网页正文提取（Tavily + 本机 WebView 兜底）
└── AmapNavController.kt AMapNavi + AMapLocationClient 实现
```

**为什么语音放在 Service 里**：一个麦克风只能有一个 `AudioRecord`，而导航页和设置页都要用；
息屏 / 切后台时唤醒词还得能听见。所以 Activity 只是观众，不持有音频。

---

## 联网是自己接的

**MiniMax 的 API 没有内置联网**。实测 `MiniMax-M2.5-Online` / `M2.5-Search` 这类模型名全是
400 `not have model`，`tools:[{"type":"web_search"}]` 也被拒（报 `function is empty`）。
但**标准 OpenAI 格式的 Function Calling 是支持的**，所以联网得自己接。

框架在 `core/LlmTool.kt`：模型判断该不该查 → App 执行工具 → 结果回填 → 模型总结。

| 工具 | 数据源 | 配置 |
|---|---|---|
| `navigate` | 高德 SDK | 零配置 |
| `get_weather` | 高德天气 Web API | 零配置（复用 `AMAP_WEB_KEY`） |
| `search_nearby` | 高德 POI | 零配置 |
| `web_search` | Tavily Search | 设置页配 Tavily Key |
| `web_extract` | Tavily Extract，本机 WebView 兜底 | 复用 Tavily Key |

### 检索不是「搜一次就完事」

对话主循环允许**最多 3 轮工具调用**：模型可以「搜 → 读原文 → 发现不对 → 换个关键词再搜」。
中间轮静音执行，只有最终答案播报 —— 否则模型每轮都说一句「我再查一下」，TTS 会重复念。

`web_extract` 有两条取正文的路：Tavily Extract 走服务端（快）；
**抓不到时用本机的 WebView 把页面渲染出来再取 `innerText`** —— 公众号、知乎、各类
单页应用只有这条路能读。

---

## 真机验证

| 设备 | 系统 | 结果 |
|---|---|---|
| Huawei P40 Pro（ELS-AN00） | HarmonyOS 4.0 / API 31 | ✅ 构建安装、定位与导航链路已验证；唤醒命中未做真人声学复测 |
| vivo X20 Plus A（PD1710） | Android 8.1 / API 27 | 旧版本全链路与 TTS 曾验证；本轮改动未复测 |

实测数据（张掖市区 → 张掖西站）：定位 `38.945019,100.418638` → 地理编码 `38.923036,100.428341`
→ 算路成功 `2671m / 420s` → 播报「准备出发，全程二点六公里，大约需要七分钟，七十米后左转」。

### 兼容性硬约束

**必须 arm64 设备。** 高德 SDK 只提供 `arm64-v8a` 和 `armeabi-v7a` 的 so：
- x86_64 模拟器直接 `INSTALL_FAILED_NO_MATCHING_ABIS`
- ARM 模拟器能装但全软件转译，高德地图渲染卡到没法交互

**模拟器只能测设置页、对话页这类纯 UI，导航主链路必须真机。**
语音链路（麦克风 / TTS / 声纹）在模拟器上也不可信。

---

## 文档

| 文件 | 内容 |
|---|---|
| [`android/README.md`](android/README.md) | **工程主文档**：环境、构建、模块、关键设计决策、全部踩过的坑 |
| [`DESIGN.md`](DESIGN.md) | 产品选型与里程碑 |
| [`HANDOFF.md`](HANDOFF.md) | 状态快照与下一步 |

---

## 已知限制

- [ ] 声纹门禁（只认主人声音）—— 设计已定稿，未实现
- [ ] 回声消除 —— 播报中用唤醒词打断已可，播报结束后另有可调回声保护期
- [ ] 聊天历史持久化 —— 杀进程就丢
- [ ] `Dbg` 文件日志在 release 版关闭
- [ ] vivo X20 Plus A 上未跑过当前版本

---

## 授权与合规

- 高德 SDK 需自行申请 Key，并遵守高德开放平台的服务条款
- 语音唤醒与声纹模型用 sherpa-onnx / WeSpeaker，遵循各自许可
- `local.properties` 里的 Key **不要提交到版本库**
