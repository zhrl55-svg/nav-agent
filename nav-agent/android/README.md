# nav-agent · Android 工程

含导航能力的语音智能体。**当前状态：v0.2.0 —— 主界面是 AI 聊天；导航页与聊天页可往返切换并保留路线，支持导航历史选择和中断后继续。Huawei P40 Pro 构建安装正常；「小度」唤醒仍缺真人声学命中验证。**

## 产品形态（v0.2.0 起）

```
ChatActivity（主界面）          说唤醒词 → 唤醒 → 连续对话
  └ 说「导航去张掖西站」 ──→ NavActivity（导航页）
  └ 顶部「导航」按钮   ──→ 打开/返回既有导航页
                              └ 「返回对话」保留地图页和导航实例
                                 聊天页导航中显示「中断」；可停止后继续
                                 导航页「历史目的地」选择最近 20 个目的地
```

导航不是主界面，是聊天里打开的一个页面。语音链路常驻在 `VoiceService`，
因为一个麦克风只能有一个 `AudioRecord`，而两个页面都要用。

## 真机验证结果

| 设备 | 系统 | 结果 |
|---|---|---|
| Huawei P40 Pro（ELS-AN00 / `MDX0220A21035172`） | HarmonyOS 4.0，API 31 | ✅ 构建安装和定位/导航链路已有历史验证；当前「小度」唤醒命中未做真人声学复测 |
| vivo X20 Plus A（PD1710 / `9c80900a`） | Android 8.1，API 27 | 旧版本全链路/TTS 曾验证；本轮导航历史/返回栈及新唤醒逻辑未验证 |

实测数据（张掖市区 → 张掖西站）：定位 `38.945019,100.418638` → 地理编码 `38.923036,100.428341`
→ 算路成功 `2671m / 420s` → 播报「准备出发，全程二点六公里，大约需要七分钟，七十米后左转」。

## 环境

| 组件 | 位置 / 版本 |
|---|---|
| Android SDK | `D:\Android\Sdk`（platforms 34/35，build-tools 34.0.0 + 35.0.0） |
| Gradle | `D:\Android\gradle\gradle-8.14.6`（腾讯镜像下载） |
| JDK | Android Studio 自带 JBR 21（`D:\Program Files\Android\Android Studio\jbr`） |
| AGP / Kotlin | 8.13.2 / 2.4.21 |
| 调试签名 | `C:\Users\zhrl5\.android\debug.keystore`（alias `androiddebugkey`，密码 `android`） |
| 调试 SHA1 | `26:F6:15:CD:A0:6C:08:F4:7A:4B:BA:70:A3:20:DD:07:47:D7:91:7A` |

## 构建 / 安装 / 调试

```bash
cd nav-agent/android
export JAVA_HOME="/d/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME=/d/Android/Sdk
export GRADLE_OPTS="-Dorg.gradle.internal.http.socketTimeout=20000 -Dorg.gradle.internal.http.connectionTimeout=20000"
/d/Android/gradle/gradle-8.14.6/bin/gradle.bat assembleDebug
```

安装（**不要用 `adb install` / `pm install`，见下方踩坑 7**）：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/nav.apk
SIZE=$(adb shell "stat -c %s /data/local/tmp/nav.apk" | tr -d '\r')
SID=$(adb shell "pm install-create -t -S $SIZE" | grep -oE "[0-9]+" | head -1)
adb shell "pm install-write -S $SIZE $SID base /data/local/tmp/nav.apk"
adb shell "pm install-commit $SID"
```

绕过语音直接验证整条链路（调试入口）：

```bash
adb shell am start -n com.navagent/com.navagent.app.MainActivity --es dest 张掖西站
adb shell cat /sdcard/Android/data/com.navagent/files/nav.log
```

## 模块

```
core/   纯 Kotlin，不认识任何高德类型
├── ChatEngine.kt   对话编排：全部走 LLM（**没有规则分支了**）；边流边念，按句切给 TTS
├── Chat.kt         ChatMessage / Llm 接口
├── LlmTool.kt      工具接口（Function Calling）
├── VoiceIo.kt      Asr / WakeWord / SpeakerGate 接口
├── VoiceGuide.kt   TTS 队列：NAV > USER > CHAT，NAV 可打断并清空闲聊
└── NavController.kt 导航引擎抽象

app/
├── NavApp.kt            Application 单例：TTS / 对话 / 导航 / 工具注入 / DNS 预热
├── Prefs.kt             运行时设置（SharedPreferences），所有可调参数集中在这
├── SettingsActivity.kt  设置页（代码构建 UI，不依赖 material）
├── ChatActivity.kt      主界面（聊天）
├── NavActivity.kt       导航页 + 聊天浮层
├── VoiceService.kt      前台服务（microphone|location），麦克风唯一持有者
├── VoiceEngine.kt       语音状态机 ASLEEP/LISTENING/THINKING
├── AudioCapture.kt      AudioRecord 16k/mono/PCM16，20ms 一帧
├── EnergyVad.kt         能量 VAD，自适应噪声底，断句 800ms
├── VoskWake.kt          vosk 离线唤醒 + 模糊匹配（小.{0,3}高）
├── ModelStore.kt        模型下载 / 解压 / 校验
├── MinimaxAsr.kt        MiniMax speech_to_text，手写 multipart
├── MinimaxLlm.kt        MiniMax 聊天：SSE 流式 + Function Calling + ThinkFilter
├── NavigationTool.kt    导航控制工具（navigate / stop / open_map）
├── WeatherTool.kt       天气查询（高德 Web API，零额外配置）
├── NearbyTool.kt        周边搜索（高德 POI，返回 3 个候选）
├── WebSearchTool.kt     通用搜索（Tavily Search API）
├── WebExtractTool.kt    搜索结果网页正文提取（Tavily Extract API）
├── AmapNavController.kt AMapNavi + AMapLocationClient 的真实实现
├── AmapSdkGeocoder.kt    POI 搜索 + 地理编码 + 一次性定位（高德原生 SDK）
├── BluetoothAutoStart.kt 蓝牙连上车机后自动拉起语音服务
├── NavHud.kt            HUD 数据快照
├── NavListenerAdapter.kt AMapNaviListener 34 个回调全空实现
└── Dbg.kt               文件日志（绕开不可靠的 logcat）
```

**架构纪律**：`core` 只产出 `Command`，不直接调用导航 SDK。换引擎时解析逻辑一行不改。

## 设置

入口在聊天页右上角。全部落 `SharedPreferences`，默认值来自 `BuildConfig`（所以「恢复默认」= 回到编译时的值）。

| 分组 | 设置项 | 默认 | 生效方式 |
|---|---|---|---|
| 语音识别 | ASR 模型 | `asr-1.0` | 立即 |
| | 唤醒词 | 你好小高,小高,… | **需重建** `VoiceService.reloadWake()` |
| | 会话休眠 | 20 秒 | 立即 |
| | 唤醒提示音 | 开 | 立即 |
| 对话 | 对话模型 | `minimax-m3.1-flash` | 立即 |
| | 回复长度 | 400 token | 立即 |
| | 记忆轮数 | 8 轮 | 立即 |
| 播报 | 语速 | 1.0x | `VoiceGuide.setRate()` |
| | 回声保护 | 1.5 秒 | 立即 |
| 导航 | 周边搜索半径 | 3 公里 | 立即 |
| 车载蓝牙 | 蓝牙自动启动 | 关 | 立即（需 BLUETOOTH_CONNECT 运行时权限） |
| | 目标设备名 | 空 | 立即（设备名包含匹配） |
| 联网搜索 | Tavily 搜索 Key | 编译时注入 | 立即 |
| 诊断 | 文件日志 | 开 | → `Dbg.enabled` |

**唤醒词语义**：逗号分隔的每一项是**同义变体，任一命中即可**（`VoskWake.match()` 里的
`normalized.any { norm.contains(it) }`）。不是"必须全部说完"。
代价是越短的说法越容易误唤醒，嫌吵就只留长的那几个。

`SettingsActivity` 全用代码构建 —— 本工程只有 appcompat，为这一个页引 material 要多 1MB 不值得。

## 联网能力

**MiniMax 的 API 没有内置联网**。实测 `MiniMax-M2.5-Online` / `M2.5-Search` 这类模型名全是
400 `not have model`，`tools:[{"type":"web_search"}]` 也被拒（报 `function is empty`）。
但**标准 OpenAI 格式的 Function Calling 是支持的**，所以联网得自己接。

框架已经在 `core/LlmTool.kt`：模型判断该不该查 → 我们执行工具 → 结果回填 → 模型总结。

```
第 1 轮（带 tools，流式）：模型可能直接吐 tool_calls，content 常常是空的
  ↓ 执行工具
第 2 轮（不带 tools，流式）：基于工具结果作答
```

| 工具 | 数据源 | 配置 | 状态 |
|---|---|---|---|
| `get_weather` | 高德天气 Web API | **零配置**（复用 AMAP_WEB_KEY） | ✅ 已上线 |
| `web_search` | Tavily Search API | 设置页配置 Tavily Key | ✅ 已接入：新闻/常规检索、时间范围、发布时间和来源摘要 |
| `web_extract` | Tavily Extract API | 复用 Tavily Key | ✅ 已接入：从搜索结果读取最多 2 个 HTTPS 网页正文 |

**为什么天气能零配置**：高德那个 Web Key 本来就是为了地理编码打进包里的，
`/v3/weather/weatherInfo` 是同一个 Key 下的接口，不额外花钱。
实况（base）和预报（all）是两个接口，并发跑 265ms。

实测：说「明天张掖要带伞吗」→ 模型调 `get_weather({"city":"张掖市"})` → 工具回 4 天预报
→ 模型答「不用带，张掖明天白天和夜间都是晴天，没有降雨」。整条 4.4 秒（两轮 LLM 各约 2 秒）。

**要加通用搜索**（新闻、价格、百科之外的事实），接一个搜索 API 即可，
按 `LlmTool` 实现三个成员（`name` / `description` / `parametersJson` / `execute`）。
候选：博查 BoCha（国内，明确支持 Function Call，`open.bochaai.com`）、智谱 Web Search。

## 关键设计决策

1. **导航界面用高德官方内置 UI（可切回自绘 HUD）。**
   2026-11-01 改的。原来是「SDK 自带 UI 全关、HUD 自绘」—— 因为内置底栏
   （退出 / 继续导航 / 设置）和我们的语音按钮完全重叠。

   现在的解法是 **`setCrossLocation(rect, rect)`**：告诉高德「你的 HUD 只能画在这个矩形里」，
   上边留状态栏、下边留我们的底部面板，两套 UI 就能共存了。官方内置界面有车道级导航、
   路况条、限速牌这些自己画不出来的东西，维护成本低得多。

   必须同时开 `isAutoNaviViewNightMode` + `isNaviNight` 跟随系统，
   否则高德浅色地图配我们的深色底栏，交界非常割裂。

   自绘 HUD 保留为备选（设置页可切）：`NaviInfo.getIconBitmap()` + `nextRoadName` +
   `curStepRetainDistance` + `pathRetainDistance/Time`。
2. **接管 SDK 播报。**
   `setUseInnerVoice(false)` 关掉高德 TTS，改由 `onGetNavigationText(String)` 把文本灌进自己的
   `VoiceGuide` 队列。这样「导航播报」和「用户对话」共用一条 TTS，靠 Priority 抢占，不会两套声音打架。
3. **地名解析走高德原生 SDK（PoiSearch + GeocodeSearch），不用 Web API。**
   2026-11-01 已从 Web API 切换到 SDK：不再受主线程禁发 HTTP 的限制，也不用多管一个 Web Key。
   坐标结果与 Web API 版本逐位相同（真机实测）。

   ★ **曾经记的「search 与 navi-3dmap 有无法排除的重复类，只能二选一」是错的，已推翻。**
   实测两个 jar 只重叠 2 个类（`AMapUtilCoreApi`、`NetProxy`），`exclude` 是模块级的剔不了单个类，
   解法是打包阶段从 search jar 里剔掉这 2 个类、保留 navi-3dmap 的。
   search 那份是 navi 那份的**方法子集**，运行时行为不变。详见 `core/build.gradle.kts`
   的 `stripSearchDuplicates` 任务。
4. **半双工兜底。** 播报期间点按钮是「打断播报」而不是「开始拾音」，避免把自己的声音收进去。
5. **`NaviLatLng` 用无参构造 + setter**，避开 `NaviLatLng(double, double)` 参数顺序（lat/lon 还是 lon/lat）的歧义。

## 踩过的坑（全部实测，别重犯）

### 构建

1. `services.gradle.org` 拉 Gradle 会超时（6 分钟只下 11MB/130MB）。用 `mirrors.cloud.tencent.com/gradle/`，4.5 秒。
2. **依赖下载会无声挂死**：`jstack` 显示线程卡在 `downloadByCoords` → `SSLSocketImpl.readApplicationRecord`，26 分钟零进展零输出。
   必须设 `GRADLE_OPTS="-Dorg.gradle.internal.http.socketTimeout=20000 -Dorg.gradle.internal.http.connectionTimeout=20000"`。
3. 仓库前置阿里云镜像（`/repository/google`、`/repository/central`，单次 0.1~0.2s，比 `dl.google.com` 快一个数量级）。
   注意 `pluginManagement {}` 是**独立脚本作用域**，不能引用 settings.gradle.kts 顶层 `val`，镜像地址必须内联。
4. **重复类两个来源**：
   - `navi-3dmap` jar 已内置 `maps`(270 类) / `navi`(241 类) / `location`(23 类)，而 POM 又把 `location` 声明为传递依赖 → 必须 `exclude(group="com.amap.api", module="location")`。
   - `search` 与 `navi-3dmap` 只重叠 `com/amap/apis/utils/core/api/{AMapUtilCoreApi,NetProxy}` 两个类，
     **exclude 剔不了单个类**（exclude 是模块级的）→ 在打包阶段从 search jar 里剔掉这 2 个类，
     保留 navi-3dmap 的（search 那份是方法子集，navi 那份是超集，行为不变）。
     三个坑：`files(TaskProvider)` 不会被解析（要写实际路径）、`runtimeOnly` 不传递给下游模块
     （要用 `api`）、app 侧必须补 `dependsOn(":core:stripSearchDuplicates")`。
5. 高德依赖只在 `:core` 声明一次，且必须用 `api` 而非 `implementation`，否则 app 模块 `Unresolved reference 'amap'`。

### 运行

6. **`navi-3dmap` 是纯 jar 不是 aar，manifest 不合并。**
   它内置的 `AMapLocationClient` 会拉起 `com.amap.api.location.APSService`，
   不手动在 manifest 里声明就会 `Unable to start service ... not found`，定位退化。
7. **vivo 上 `adb install` / `pm install` 会随机返回 `Failure [-200]`。**
   改用 `pm install-create` → `install-write` → `install-commit` 三步会话式安装，稳定成功。
8. **vivo 会丢弃第三方应用的 `Log.i` / `Log.w`**，logcat 里只剩 SDK 自己的 `System.err`，排查时完全瞎。
   → 关键路径统一写 `/sdcard/Android/data/com.navagent/files/nav.log`（`Dbg`），`adb shell cat` 直接看。
9. **`launchMode=singleTop` 下 `am start --es` 不重建 Activity**，只回调 `onNewIntent`。
   不实现 `onNewIntent` 的话 extra 被静默丢弃（实测 `onCreate` 里拿到 null）。
10. **系统定位开关可能是关的**：`settings get secure location_mode` 为 null/false 时定位永远拿不到。
    `settings put secure location_mode 3` + `settings put secure location_providers_allowed +network`
    （注意 `+network` 的加号语法，直接 `put ... network,gps` 在部分 ROM 上不生效）。
11. **`AMapNaviView` 的 `getViewOptions()` 不保证返回视图正在用的那份**，按官方写法「取出 → 改 → `setViewOptions` 放回」。
12. **高德把没有正式名称的路返回成「无名道路」**，直接显示很丑，代码里退回 `currentRoadName`，再不行给「沿当前道路」。
13. **Git Bash 会把 `/data/local/tmp` 改写成 Windows 路径**，`adb push` 直接报 `remote secure_mkdirs failed`。
    所有 adb 命令前 `export MSYS_NO_PATHCONV=1`。
14. **`am start -n` 的类名是 `com.navagent.app.XxxActivity`**（namespace 是 `com.navagent.app`，
    applicationId 才是 `com.navagent`）。写成 `com.navagent.ChatActivity` 会报 `does not exist`。
15. **改道重算时 `startNavi()` 返回 false**（导航没停，不需要重新 start）。
    不能拿返回值当 `navigating` —— 否则 HUD 消失、问「还有多久」答「现在没在导航」。用 `started || wasNavigating`。
16. **口语地名要带城市偏置，但 `citylimit` 必须能关掉**。这是两个方向相反的坑：
    - 不带 city：「高铁西站」被 geocode 解析到 529 公里外 → 加 `city=张掖市` 后命中 2.9 公里。
    - `citylimit=true` 走到底：「景泰县条山路金星家园」被硬塞成本市的「金星灯饰」6.8 公里，
      而真实目的地是白银市景泰县，420 公里。
    最终规则：关键词含 `[省市县旗镇乡]` → `citylimit=false`，否则 `citylimit=true`。
    「区」不算（小区/园区/商圈太常见）。城市从 `AMapLocation.getCity()` 拿（要 `setNeedAddress(true)`）。
17. **后台定位要 foregroundServiceType 带 location**。只声明 `microphone` 时，
    切后台高德立刻报 `code=13 后台运行没有后台定位权限`，定位永久失败。
18. **vosk-android 是 aar，带 4 套 ABI 的 so**，全打进去 APK 从 90MB 涨到 129MB。
    用 `ndk { abiFilters }` 只留 arm64-v8a / armeabi-v7a，回到 109MB。
19. **第一次聊天要 2.9s 才出首字，之后 1.0s** —— 差的 1.5 秒全是 DNS + TLS。
    `NavApp.prewarm()` 启动时先握一次手，把这段成本挪到用户看不见的时候。
20. **唤醒词会被 ASR 再认一遍**。vosk 命中「小高」进会话后，同一句话又被 VAD 切出来送 ASR，
    结果"你好小高"被当成用户的话发给模型。`VoiceEngine.isWakeOnly()` 整句匹配挡掉
    （必须整句匹配，否则「小高，导航去西站」会被误杀）。
21. **vosk 会把「小高」拆成两个 `final` 结果**（先出「小」再出「高」，各带一次端点），
    逐帧匹配永远拼不出来。`VoskWake` 用 4 秒滑动窗口把碎片拼起来再匹配。
22. **主线程禁止直接发 HTTP。**（2026-11-01 已不适用：高德搜索换成原生 SDK，SDK 自己异步回调。）
    天气工具仍走 Web API，仍需注意 —— 但它跑在工具线程上。
    `NavApp.nearby()` 用 `FutureTask` + `get(6,SECONDS)`，否则抛 `NetworkOnMainThreadException`，
    而且会被 catch 成「解析异常 null」，看不出是线程问题。
23. **播报必须念地名全称**（城市 + 区县 + 名字）。只说「开始导航，全程6.8公里」时，
    用户完全不知道自己被导到了本市的同名地点。`AmapNavController.describePlace()` 拼全称，
    跨市时再加一句「注意，目的地不在${当前城市}」。
24. **问句不能当目的地**。`导航到哪里？` 会被 `reroute` 正则吃成 `Reroute("哪里")`，
    然后真拿「哪里」去算路。`IntentParser.QUESTION_WORD` 拦掉纯疑问词。
25. **周边搜索结果要过期**。`lastNearby` 加 2 分钟 TTL —— 车在开，十分钟前搜的加油站
    已经开过去三五公里，再说「去」等于往回开。
26. **思考内容必须边流边剥**。`m3.1-flash` 走独立的 `reasoning_content`（不读就没事），
    但 **`MiniMax-M2.7` 把英文思考内联在 `content` 里、用 `<think>…</think>` 包着**。
    `ChatEngine` 是边流边念的，等整段回来再 `stripThink` 早就朗读完了。
    `MinimaxLlm.ThinkFilter` 是状态机，能处理标签被拆到两帧（`<thi` + `nk>`）的情况。
    M2.7 不支持关思考：`thinking.type=disabled` / `=off` 都试过，照吐。
27. **只打开地图不导航时 SDK 不跟随定位**，镜头停在北京市。
    要 `AmapNavController.onFirstFix` → `naviView.map.moveCamera(newLatLngZoom(15f))`。
    正在导航时别动镜头，会和 SDK 跟随打架。
28. **界面上的唤醒词提示取最短的那个**。列表第一个是「你好小高」，直接拼成
    「唤醒你好小高」很别扭；`Prefs.primaryWakeWord()` 用 `minByOrNull { it.length }`。
29. **指令识别全部交给模型，不再有正则规则**（2026-11-01 用户拍板，`IntentParser.kt` /
    `Command.kt` 已删）。导航动作也走 Function Calling：`navigate`（navigate / stop / open_map）
    + `get_weather` + `search_nearby` + `web_search`。

    为什么推翻之前的「分层」方案：正则漏得太厉害 ——「打开导航去X」「拐到前面那家」
    「回刚才那个地方」「不导了停一下」全匹配不上，用户体感是"导航坏了"。
    **正则能覆盖的是"说法"，覆盖不了"意图"。**

    已知代价（用户接受）：导航延迟 7ms → 3~4s；离线不可用。
30. **不要在工具调用间隙补过渡语**。加过「我查一下。」「好的。」，用户明确否掉 ——
    宁可静默 2~3 秒等结果，也不要多余的垫话。
31. **工具层不做内容过滤**（用户要求）。`WebSearchTool` 拿到什么回什么，
    合规靠 system prompt 里那句「政治军事话题不展开」。
32. **空回复要分两种**：`full == null` 是网络挂了，`full == ""` 是模型自己没说话
    （常见于触发模型自身的安全判断），说成"网络不行"会误导用户。

## 授权与合规

- 个人开发者，**未获得技术服务使用许可**（需企业认证）。Key 仅供短期少量测试，**不能上架分发**。
- 包内有两个 Key：Android Key（绑定包名 `com.navagent`）与 Web 服务 Key（地理编码）。
  Web Key 随包分发是自用妥协，若要做产品必须挪到自建服务端中转。
- `MapsInitializer.updatePrivacyShow/updatePrivacyAgree/initialize` 必须在任何 SDK 调用之前。

## 未完成

- [ ] 声纹门禁（只认主人声音）—— 设计定稿在 `HANDOFF.md` 第 10 节，本轮未实现
- [x] **通用 Web 搜索**（Tavily `web_search` + `web_extract`）——设置页配置 Key；实时/易变信息优先检索，摘要不足时读取来源网页正文。天气已通
- [x] 周边搜索（`AmapSdkGeocoder.searchAroundList()`，SDK 异步回调 + latch 转同步，不报列表只问「最近的中石化 1.8 公里，要去吗」）
- [ ] 回声消除 —— 现在是「播报时暂停拾音」的半双工兜底，
      副作用是**导航播报期间喊「小高」听不见**
- [ ] 聊天历史持久化（杀进程就丢）
- [ ] `Dbg` 在 release 关闭
- [ ] vivo X20 Plus A 上跑一遍 v0.2.0（只验了华为）

## 查高德 SDK 签名的方法

这个包是纯 jar 不是 aar，**不要凭记忆写 API**：

```bash
JAVAP="/d/Program Files/Android/Android Studio/jbr/bin/javap.exe"
NAVI=$(ls ~/.gradle/caches/modules-2/files-2.1/com.amap.api/navi-3dmap/*/*/navi-3dmap-*.jar | head -1)
"$JAVAP" -classpath "$NAVI" com.amap.api.navi.AMapNavi
"$JAVAP" -classpath "$NAVI" -constants com.amap.api.navi.enums.PathPlanningStrategy
"$JAVAP" -classpath "$NAVI" com.amap.api.navi.AMapNaviListener
```
