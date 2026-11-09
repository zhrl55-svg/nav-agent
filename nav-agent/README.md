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
├── VoskWake.kt          离线唤醒 + 模糊匹配（词首丢失 / 片段 / ICU 拼音；无手写同音字表）
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

## 实验选项（2026-11-07，默认关）

借鉴桌面项目 `D:/AI` 的语音链路，设置页新增「实验室 · 语音链路」一栏。
**不改任何设置时行为与之前完全一致**，这些开关是留给你真机 A/B 验证的：

| 设置 | 作用 | 需要注意 |
|---|---|---|
| 断句灵敏度 | VAD 静默 600 / 800 / 1000 / 1200 ms | 调小更快但容易抢话 |
| 上传噪声门 | 按 attack 5ms / hold 250ms / release 80ms 包络压掉底噪再送 ASR | 只作用于上传副本，不影响 VAD、唤醒、声纹 |
| 全双工 | 播报期间继续切句，用户可直接插话 | 外放自激风险高；需开打断检测且已注册声纹，另有三道护栏 |
| ASR 延迟 | 只读：p50 / p90 / 成功失败次数 | 用来看连接复用的效果 |

设置页「语音播报」另加了**「播报后回声保护」总开关**（默认开，保持原行为）：
关掉后播报一结束就立刻恢复拾音，可以紧跟插话，代价是车内混响大时容易自激。
时长仍由「回声保护时长」控制。

**另外两条是默认生效的行为改动**：

- **ASR 连接复用**：成功响应不再 `disconnect()`，连续对话复用 HTTPS 连接，
  省掉每句一次的 TCP+TLS 握手；启动时同时预热对话和语音两个 host。
- **ASR 失败反馈**：云端真的失败（不是「没说话」）时播一句「网络不太好，
  请稍后再试一次」，15 秒节流。原来只写日志，用户在隧道里会以为程序坏了。

**真机实测结论（2026-11-07，P40 Pro，18 句）**：ASR `fail=0`、p50=738ms、
p90=1123ms，无崩溃。但两项实验被删掉了：

- 「断网本地识别兜底」「ASR 双跑对比」：Vosk 小模型约 2/3 句子有错字，
  长句基本碎，「往下读。」→「下毒」、「导航去水勘院」→「去 是 可以」。
  断网时拿它兜底会把错的目的地送进导航，代码已整个移除。
- 全双工开关保留，但 15 次播报重叠判定全部被声纹护栏拦下（0 次放行）：
  混音里声纹只有 0.24~0.33，过不了 0.50 阈值。真正的插话靠既有声纹轮询，
  导航播报期间「播完再听」也已验证。**当前不建议开启全双工**。

---

## 端侧搜索（2026-11-09 重做）

端侧模型不会调工具（0.6B 实测一次都没按 `<tool_call>` 或裸 JSON 输出），所以搜索是
**宿主自己的确定性流程**，模型不参与判断，也不参与改写念稿：

```
规则判断要不要搜 → 规则拼查询词 → 备用引擎 / Tavily 并行取 → 直接念筛选过的来源
```

| 环节 | 位置 | 说明 |
|---|---|---|
| 触发 | `MnnLlm.planSearch()` | 四类：新闻、实时事实（限行/门票/营业/油价/路况/评分/活动…）、明确检索（查一下/搜一下）、详情追问 |
| 拼词 | `MnnLlm.buildQuery()` | **只删祈使语和语气词**，内容词不动（旧版会把「新闻联播几点开始」删成「联播几点开始」） |
| 取数 | `MnnLlm.fastSearch()` | 中文查询同时起备用引擎（搜狗/百度/Bing）与 Tavily；备用引擎够用就直接用，否则等 Tavily（新闻 12s / 通用 8s） |
| 念稿 | `MnnLlm.digest()` | 新闻 3 条 × 48 字；有正文时用 `bestSentence()` 按查询词挑最相关的一句，不再取开头 110 字 |

**搜索前一定先出一句「我查一下」** —— 车上一声不吭等 12 秒，用户会以为死机。

「读出来 / 详细说说」这类追问复用上一轮结果（3 分钟缓存），不会再跑一次网络。

### 新闻垂类源：中新网滚动列表（2026-11-09 加）

泛新闻（「今天有什么新闻」「国际新闻」「财经方面呢」）不再走搜索引擎 ——
搜「国际新闻」回来全是频道首页，Tavily 那边是乱码。改用
`https://www.chinanews.com.cn/scroll-news/news1.html`：一次 GET、100 条、
UTF-8 干净、每条带栏目和精确到分钟的时间。

| 能力 | 实现 |
|---|---|
| 国内 / 国际 / 全部 | `NewsFeed.scopeOf()` 按问句切范围，再用栏目过滤 |
| 主题追问「财经方面的」 | 命中栏目词（财经/体育/社会/文化/教育/时政）直接取该栏目 |
| 具体主题「张掖马拉松」 | 标题关键词匹配，凑不够 2 条就返回 null → 交给搜索引擎 |
| 缓存 | 60 秒。一轮对话里「国际的呢」不必再拉一次 |

实测：`今天有什么新闻` 656ms 三条；`国际新闻` 750ms 三条；`财经方面的` 13ms（命中缓存）。

> 被淘汰的源，别再试了：新浪 RSS 停更在 2018-09、人民网 RSS 停更在 2025-06、
> Bing 新闻页是 JS 壳（静态 HTML 里没有任何条目）。

### 可信源排序（2026-11-09 加）

`FallbackWebSearch` 不再「谁先给够数就用谁」：

- 官方/央媒（`gov.cn`、`people.com.cn`、`xinhuanet.com`、`cctv.com`、`chinanews.com.cn`…）= 2 分
- 本地信息站（`bendibao.com` 等）= 1 分
- 普通网页 = 0 分

拿到 2 条 2 分结果才提前收工，否则继续问下一家；最终结果按分数排序。
政策类问题（限行/社保/公积金/摇号…）如果一条权威源都没有，第一条正文前会加
「网上非官方说法，仅供参考」—— 直出模式会把这句一起念出来。

实测「兰州市的限行规则是什么」：Bing 只给「兰州旅游十大景点」，搜狗给本地宝
（2026 每日更新）。排序后取本地宝，读正文 1.5s，念的是「2026兰州限行规定（时间+区域+规则）…」。

### 真机上暴露过的两个坑（已处理）

| 现象 | 原因 | 处理 |
|---|---|---|
| 念出「第一条《һ߽ڣ¡ĸ֡-》：ʱ」 | Tavily 的中文新闻标题是 GBK 站被错误解码的乱码 | `parseItems` 里 `looksGarbled()` 整条丢弃（U+0080–U+08FF / 替换符 / 私用区占比过高） |
| 问油价，念的是「甘肃今日92号汽油…最新价格查询。甘肃油价多少钱一升？」 | 来源只给了页面导语，没有数字，直出把导语当答案 | 问数字（多少钱/几点/多少）而来源无数字时，改说「搜到了相关页面，但没给出具体数字」 |

> ⚠️ 直出模式有个固有风险：**内容农场的页面会被原样念出来**。
> 实测「张掖限行」——张掖本地没有限行规定，但搜索结果里全是「XX年限行时间规定」的农场页。
> 改词表救不了这类问题，要解决得走「官方源优先」或「加一句『网上信息』」，属于产品决策。

> 这些词表都在 `MnnLlm` 的 companion 里（`REALTIME_WORDS` / `DETAIL_WORDS` / `HANDLED_ELSEWHERE`…），
> 真机上发现漏搜或误搜，直接加词/删词即可，不用改逻辑。

**搜索不再受「工具调用」开关控制**：那个开关只管云端 Function Calling。
端侧搜索只看「联网搜索」三态（自动/开/关）。

---

## 已知限制

- [ ] 声纹门禁真机阈值校准 —— 功能已实现（`VoiceprintGate.kt`：首句自动注册、
      EMA 自适应、播报重叠不更新模板），0.35 是通用默认值，需按个人声音调
- [ ] 回声消除 —— 播报中用唤醒词打断已可，播报结束后另有可调回声保护期
- [ ] 聊天历史持久化 —— 杀进程就丢
- [ ] `Dbg` 文件日志在 release 版关闭
- [ ] vivo X20 Plus A 上未跑过当前版本

---

## 授权与合规

- 高德 SDK 需自行申请 Key，并遵守高德开放平台的服务条款
- 语音唤醒与声纹模型用 sherpa-onnx / WeSpeaker，遵循各自许可
- `local.properties` 里的 Key **不要提交到版本库**
