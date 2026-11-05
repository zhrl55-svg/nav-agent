# 交接文档 · nav-agent（语音导航智能体）

> 写给「明天新开一个会话」的自己。先读这一页，5 分钟就能接着干，不用重新踩坑。
> 最后更新：2026-11-01 12:04 · 版本 v0.2.0（versionCode 7）

---

## 一句话状态

**主界面是 AI 聊天；聊天可打开导航，导航页与聊天页可以往返切换并保留路线；支持历史目的地选择和中断后继续。Huawei P40 Pro 已构建安装；当前「小度」唤醒仍未通过真人声学命中验证。**

```
说当前配置的唤醒词 → 唤醒 → 「导航去张掖西站」 → 打开导航页开始导航
                → 导航中再说「小高」 → 连续对话，回答以浮层 + TTS 呈现，不切页面
```

---

## 1. 先看这三个文件（按顺序）

| 文件 | 作用 |
|---|---|
| `nav-agent/android/README.md` | **工程主文档**：环境、构建、安装、踩坑、关键设计决策。必读。 |
| `nav-agent/DESIGN.md` | 产品选型、M0–M3 里程碑、高德账号与 Key |
| `nav-agent/HANDOFF.md` | 就是你在读的这个：状态快照 + 下一步 |

代码位置：`D:\workbuddy\2026-10-31-21-31-07\nav-agent\android\`

---

## 2. 架构（v0.2.0 重构后）

```
ChatActivity（LAUNCHER，主界面）
  └ 消息列表 + 输入框 + 语音按钮
  └ 说话命中导航指令 → NavApp 启动 NavActivity

NavActivity（导航页，从聊天打开）
  └ AMapNaviView + 自绘 HUD + 底部聊天浮层 + 「返回对话」

VoiceService（前台服务，麦克风唯一持有者）
  └ VoiceEngine 状态机：ASLEEP / LISTENING / THINKING
      ├ AudioCapture  16k/mono/PCM16，20ms 一帧
      ├ VoskWake      vosk 离线唤醒词
      ├ EnergyVad     能量 VAD 切句（断句 800ms）
      └ MinimaxAsr    整段上传识别

NavApp（Application 单例）
  ├ VoiceGuide   TTS 队列 NAV > USER > CHAT（导航播报可打断闲聊）
  ├ ChatEngine   规则优先 → 未命中走 LLM
  └ MinimaxLlm   minimax-m3.1-flash 流式
```

**为什么语音必须放服务里**：一个麦克风只能有一个 `AudioRecord`，而聊天页和导航页都要用；
且息屏/切后台时唤醒词还得能听见。所以 Activity 只是观众，不持有音频。

**两个"静默"别搞混**（最容易写错的地方）：

| 名字 | 时长 | 在哪 | 作用 |
|---|---|---|---|
| 断句阈值 `MIN_SILENCE_MS` | 800ms | `EnergyVad` | 判断这句话说完了，触发识别 |
| 会话休眠 `IDLE_TIMEOUT_MS` | **20000ms** | `VoiceEngine` | 判断没人再说话，退出连续会话 |

---

## 3. 五分钟跑起来

**本地开发辅助脚本（不入库）**：开发机上有一个 `tools/deploy.sh`，能一条命令完成
编译+安装+启动+截图+日志。但它绑定具体的开发机环境（设备 IP、Gradle 路径），
不随仓库分发，clone 下来的人用不了，需要的话自己写一份。

**为什么不用模拟器**（2026-11-05 结论）：高德只提供 `arm64-v8a` 和
`armeabi-v7a` 的 so（`libs/amap/` 下就这两套，jar 里一个 so 都没有）。
Windows 上的模拟器只能用 x86_64 镜像（有硬件加速），装这个 APK 直接
`INSTALL_FAILED_NO_MATCHING_ABIS`；ARM 镜像能装但全软件转译，高德地图
渲染卡到没法交互。语音链路（麦克风/TTS/声纹）在模拟器上也不可信。
**结论：模拟器只能测设置页、对话页这类纯 UI，导航主链路必须真机。**

手动构建：

```bash
cd "D:/workbuddy/2026-10-31-21-31-07/nav-agent/android"
export JAVA_HOME="/d/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME=/d/Android/Sdk
export GRADLE_OPTS="-Dorg.gradle.internal.http.socketTimeout=20000 -Dorg.gradle.internal.http.connectionTimeout=20000"
/d/Android/gradle/gradle-8.14.6/bin/gradle.bat assembleDebug
```

装到手机（**别用 `adb install`，vivo 上会随机 `Failure [-200]`**）：

```bash
export MSYS_NO_PATHCONV=1   # ★ Git Bash 会把 /data/local/tmp 转成 Windows 路径，必加
APK=app/build/outputs/apk/debug/app-debug.apk
adb push $APK /data/local/tmp/nav.apk
SIZE=$(adb shell "stat -c %s /data/local/tmp/nav.apk" | tr -d '\r')
SID=$(adb shell "pm install-create -t -S $SIZE" | grep -oE "[0-9]+" | head -1)
adb shell "pm install-write -S $SIZE $SID base /data/local/tmp/nav.apk"
adb shell "pm install-commit $SID"
```

调试入口（绕过语音，直接喂一句话给大脑）：

```bash
# 包名是 com.navagent，但 Activity 类在 com.navagent.app 下 —— 别写错
adb shell am start -n com.navagent/com.navagent.app.ChatActivity --es say "导航去张掖西站"
adb shell am start -n com.navagent/com.navagent.app.NavActivity --es dest "张掖站"
adb shell "tail -30 /sdcard/Android/data/com.navagent/files/nav.log"
```

语音模型（44MB）已推到手机，重装不会丢：

```
/sdcard/Android/data/com.navagent/files/models/vosk-cn/
```

丢了也没事，首次启动会自己下（alphacephei → hf-mirror 回退）。

---

## 4. 环境与设备

| 项 | 值 |
|---|---|
| Android SDK | `D:\Android\Sdk`（platforms 34/35，build-tools 34.0.0 + 35.0.0） |
| Gradle | `D:\Android\gradle\gradle-8.14.6` |
| JDK | `D:\Program Files\Android\Android Studio\jbr`（JBR 21） |
| AGP / Kotlin | 8.13.2 / 2.4.21 |
| 高德 Key（Android） | `见 local.properties 的 AMAP_KEY`，绑定包名 `com.navagent` |
| 高德 Key（Web服务） | `见 local.properties 的 AMAP_WEB_KEY`，地理编码 + POI 搜索 |
| MiniMax Key | 在 `local.properties` 的 `MINIMAX_API_KEY`，经 `buildConfigField` 注入，**不进版本库** |
| 聊天模型 | `minimax-m3.1-flash`（实测首字 ~1.0s，思考走独立字段 `reasoning_content`，`content` 干净） |
| ASR 模型 | `asr-1.0` |

| 设备 | serial | 系统 |
|---|---|---|
| Huawei P40 Pro（ELS-AN00） | `MDX0220A21035172` | HarmonyOS 4.0 / API 31 |
| vivo X20 Plus A（PD1710） | `9c80900a` | Android 8.1 / API 27 |

华为是主力测试机。**v0.2.0 只装了华为**，vivo 未验证。

---

## 5. 代码地图

```
core/   纯 Kotlin，不认识任何高德类型
├── ChatEngine.kt   对话编排：全部走 LLM，无规则分支；边流边念（按句切给 TTS）
├── Chat.kt         ChatMessage / Llm 接口
├── LlmTool.kt      工具接口（Function Calling），工具只读、无副作用
├── VoiceIo.kt      Asr / WakeWord / SpeakerGate 接口
├── VoiceGuide.kt   TTS 队列，NAV > USER > CHAT，NAV 可打断
└── NavController.kt 导航引擎抽象

app/
├── NavApp.kt             Application 单例：voice / chat / nav / 观察者注册 / 指令路由 / DNS 预热
├── Prefs.kt              运行时设置（SharedPreferences），所有可调参数集中在这
├── SettingsActivity.kt   设置页（代码构建 UI，不依赖 material）
├── ChatActivity.kt       主界面
├── NavActivity.kt        导航页 + 聊天浮层
├── VoiceService.kt       前台服务（microphone|location）
├── VoiceEngine.kt        语音状态机（含纯唤醒词过滤）
├── AudioCapture.kt       AudioRecord 单一所有者
├── EnergyVad.kt          能量 VAD
├── VoskWake.kt           vosk 离线唤醒 + 模糊匹配
├── ModelStore.kt         模型下载/解压/校验
├── MinimaxAsr.kt         手写 multipart，零依赖
├── MinimaxLlm.kt         SSE 流式 + Function Calling + ThinkFilter
├── NavigationTool.kt     导航控制工具（navigate / stop / open_map）
├── WeatherTool.kt        天气查询（高德 Web API，同一个 Key，零额外配置）
├── NearbyTool.kt         周边搜索（高德 POI，返回 3 个候选）
├── WebSearchTool.kt      通用搜索（Tavily）
├── AmapNavController.kt  算路/导航/HUD/偏航重算
├── AmapWebGeocoder.kt    POI 搜索 + 地理编码（都带城市偏置）
├── NavHud.kt / NavListenerAdapter.kt / Dbg.kt
```

### 设置项（v0.2.0 新增）

入口：聊天页右上角「设置」。全部落 `SharedPreferences`（`Prefs.kt`），出厂默认值取自 `BuildConfig`，
所以「恢复默认」= 回到编译时的值。

| 分组 | 设置项 | 键 | 默认 | 生效方式 |
|---|---|---|---|---|
| 语音识别 | ASR 模型 | `asr_model` | asr-1.0 | 立即（每次请求读 Prefs） |
| | 唤醒词 | `wake_words` | 你好小高,小高,… | **必须重建** `VoiceService.reloadWake()` |
| | 会话休眠 | `idle_timeout_ms` | 20000 | 立即 |
| | 唤醒提示音 | `wake_beep` | true | 立即 |
| 对话 | 对话模型 | `chat_model` | minimax-m3.1-flash | 立即 |
| | 回复长度 | `max_tokens` | 400 | 立即 |
| | 记忆轮数 | `history_turns` | 8 | 立即 |
| 播报 | 语速 | `tts_rate` | 1.0 | `VoiceGuide.setRate()` |
| | 回声保护 | `echo_guard_ms` | 1500 | 立即 |
| 导航 | 周边半径 | `nearby_radius` | 3000 | 立即 |
| 诊断 | 文件日志 | `file_log` | true | → `Dbg.enabled` |

`SettingsActivity` 全用代码构建（没引 material，本工程只有 appcompat）。每次改完 `render()` 重建列表，
重建前记 `scrollY`、之后恢复 —— 不然每改一项都跳回顶部没法用。

**两个布局坑**：
- 右侧值不限宽 → 唤醒词那种长串会把左边 `weight=1` 的标题挤成 0 宽，整行看起来是空的。
  修法：值 TextView 加 `maxWidth(屏宽*0.48)` + `ellipsize=END`。
- EditText 用白字 → 系统 `AlertDialog` 是浅色底，整段内容完全看不见。修法：深色字 + 浅灰输入框背景。

---

## 6. 必须记住的约束（重犯会浪费半天）

1. **`navi-3dmap` 是纯 jar 不是 aar**，manifest 不合并 → `APSService` 必须手写声明。
2. **不能引 `com.amap.api:search`**（`AMapUtilCoreApi` 重复类且 exclude 无效）→ 地理编码/POI 走 Web API。
3. **高德依赖只在 `:core` 声明一次**，用 `api` 不用 `implementation`。
4. **vivo 丢弃第三方应用 Log.i/Log.w** → 只信 `Dbg.log()` 写文件。
5. **Git Bash 会改写 `/data/local/tmp` 路径** → adb 命令前 `export MSYS_NO_PATHCONV=1`。
6. **`am start -n` 的类名是 `com.navagent.app.ChatActivity`**，不是 `com.navagent.ChatActivity`。
7. **改道重算时 `startNavi` 返回 false**（因为导航没停）→ 不能拿返回值当 `navigating`，否则 HUD 消失。
8. **口语地名必须带城市偏置**。实测「高铁西站」不带 city 被 geocode 解析到 529 公里外；
   改成 `place/text` + `city=张掖市` 后命中「高铁西站(公交站)」，2.9 公里。
9. **后台定位要 foregroundServiceType 带 location**。只声明 microphone 时，
   切后台高德定位立刻报 `code=13 后台运行没有后台定位权限`。
10. **POI 搜索的 `citylimit` 要看地名里有没有行政区划**。这是跨城市导航错乱的根因，实测：
    ```
    「景泰县条山路金星家园」
      city=张掖市 & citylimit=true  → 金星灯饰(东部建材市场店)   张掖本地  6.8km   ← 错
      city=张掖市 & citylimit=false → 金星家园  白银市景泰县     420km            ← 对
    ```
    高德 `place/text` 是**语义匹配**，限定城市时会把外地地名硬塞给本市的相似名字。
    规则：关键词含 `[省市县旗镇乡]` → `citylimit=false`；含「区」不算（小区/园区/商圈太常见）。
11. **主线程禁止直接发 HTTP**（`NetworkOnMainThreadException`）。`AmapWebGeocoder` 全部方法都是同步的，
    调用者必须自己开子线程 —— `NavApp.nearby()` 用 `FutureTask` + `get(6,SECONDS)`。
12. **播报必须念地名全称（带城市/区县）**。只念「金星家园」和念「白银市景泰县金星家园」，
    对用户判断"解析对不对"是天壤之别。`AmapNavController.describePlace()` 干这个。
13. **问句不能当目的地**。`导航到哪里？` 会被 `reroute` 正则吃掉变成 `Reroute("哪里")`，
    然后真拿「哪里」去算路。`IntentParser.QUESTION_WORD` 拦这个。
14. **改设置要注意「是否需要重建」**。除唤醒词外全部立即生效（各处每次用时读 `Prefs`，不缓存）。
    唤醒词固化在 vosk `Recognizer` 里，改完必须 `VoiceService.reloadWake()`。
15. **唤醒词是「任一匹配」，不是「全部说完」**。`VoskWake.match()` 用
    `normalized.any { norm.contains(it) }` —— 逗号分隔的每一项都是同义变体，喊任意一个都行。
    副作用：越短的说法越容易误唤醒，嫌吵就只留长的那几个。
16. **思考内容必须边流边剥，不能等收尾**。`m3.1-flash` 的思考走独立字段
    `reasoning_content`（本来就不读，所以干净）；**`MiniMax-M2.7` 是把思考内联在
    `content` 里、用 `<think>…</think>` 包着的**（英文）。而 ChatEngine 是边流边念的，
    等的就是"攒到句号就交给 TTS"，所以整段回来了再 `stripThink` 已经播报完了 ——
    用户实测就听到一串英文 thinking。修法是 `MinimaxLlm.ThinkFilter` 状态机（能处理
    `<thi` + `nk>` 这种把标签拆两帧的情况）。
    M2.7 **不支持**关思考：`thinking.type=disabled` 和 `=off` 都试过，照吐 `<think>`。
17. **只打开地图不导航时，SDK 不会自己跟随定位**。首页点「导航」进来，镜头停在北京市。
    要 `AmapNavController.onFirstFix` 回调 → `naviView.map.moveCamera(newLatLngZoom(15f))`。
    正在导航时别动镜头，会和 SDK 的跟随打架。
18. **界面上的唤醒词提示取最短的那个**。列表第一个是「你好小高」，直接拼会变成
    「唤醒你好小高」；`Prefs.primaryWakeWord()` 取 `minByOrNull { it.length }` → 「唤醒小高」。
19. **MiniMax API 没有内置联网**，别去试 `MiniMax-M2.5-Online` / `M2.5-Search` 这类模型名
    （实测全是 400 `not have model`），`tools:[{"type":"web_search"}]` 也会被拒
    （报 `function is empty`，它要的是 OpenAI 那种完整 function 定义）。
    **但标准 Function Calling 是支持的**，联网只能自己接工具。
20. **工具调用要两轮请求**：第 1 轮带 `tools` 流式跑（模型可能直接吐 `tool_calls` 而不说话），
    执行完把结果作为 `role=tool` 回填，第 2 轮不带 `tools` 再流式。
    实测模型经常第 1 轮 `content` 是空的 —— 用户会静默好几秒，所以代码里补了一句「我查一下。」
    `MAX_TOOL_ROUNDS = 2`，防止模型自己跟自己绕圈烧钱。
21. **SSE 下 `tool_calls` 是分片来的**：首片带 `id` + `function.name`，后续只追加
    `function.arguments` 的字符，按 `index` 累积。当成一次性 JSON 解析会拿到残缺参数。
22. **指令识别已经全部交给模型（2026-11-01 用户拍板），不再有正则规则**。
    `IntentParser.kt` / `Command.kt` 已删除。导航动作也走 Function Calling：
    `navigate`（navigate / stop / open_map 三个 action）+ `get_weather` +
    `search_nearby` + `web_search` + `web_extract`。
    - **为什么推翻"分层"**：规则漏得太厉害 ——「打开导航去X」「拐到前面那家」
      「回刚才那个地方」「不导了停一下」全匹配不上，用户体感是"导航坏了"。
      正则能覆盖的是"说法"，覆盖不了"意图"。
    - **代价（用户已知并接受）**：导航延迟 7ms → 3~4s；离线不可用。
    - **别再加过渡语**：用户明确说不要「我查一下」，宁可静默等结果。
    - `ChatEngine` 构造参数里去掉了 `onCommand`，工具由 `NavApp` 注入。
23. **工具层不做内容过滤**（用户明确要求）。`WebSearchTool` 拿到什么就回什么，
    合规靠 system prompt 里那句「政治军事话题不展开」。之前加过敏感词过滤，被否掉了。
24. **空回复要分两种情况**：`full == null` 是网络挂了，`full == ""` 是模型自己没说话，
    说成"网络不行"会误导用户。
25. **需要用户做选择时列序号**（用户要求）。system prompt 里写死了：
    用 1、2、3 列，每项一句话，最后问「要哪个」。用户回「2」「第二个」就按上一轮列表理解。
    实测「就要第2个」→ `navigate(destination="青豆茶餐厅")` 正确命中第 2 项。
26. **导航页底部有三件东西**（2026-11-01 加）：输入框 + 发送（打字发指令）、
    三个等宽按钮「语音 / 停止导航 / 返回对话」。停止按钮走 `nav.stop()` + 清 `destination`。
27. **TTS 引擎可切，但要过「包可见性」这道坎**。Android 11+ 不声明 `<queries>` 的话
    `packageManager.getPackageInfo("com.iflytek.speechsuite")` 返回 null，
    会把已装的引擎误判成"没装"而回退（实测踩过）。Manifest 里声明了
    `queries > intent > android.intent.action.TTS_SERVICE`。
    另外 **`TextToSpeech.getDefaultEngine()` 返回的是系统默认引擎，不是实例实际用的那个** ——
    切到讯飞之后它还返回 huawei，会让人以为没切成功。要看实例用哪个，只能用自己传的包名。

28. **播报可以被用户打断（barge-in）**。`VoiceEngine.onFrame` 在播报期间仍然跑
    `EnergyVad.feedBargeIn()`：阈值 ×2.5、连续 20 帧（400ms）、**不动 noiseFloor**
    （否则 TTS 一播报就把基线拉高，之后再也检测不到）。命中就 `onBargeIn` →
    `voice.silence()`。设置页有开关，车内音响大导致误触时可以关。

29. **声纹门禁已上线（2026-11-01）**。`SpeakerEncoder`（ONNX）+ `VoiceprintGate`（策略）+ 
    `core/KaldiFbank`（特征）。模型 26MB 走 hf-mirror 下载（huggingface.co 国内直连不通）。
    **实测分得很干净**：主人 0.547~0.747，别人 0.193~0.213，阈值 0.35 卡中间。
    模板存 `voiceprint.bin`（4 + 256×4 = 1028 字节），第一句 ≥1s 自动注册，之后自适应。

30. **Kaldi fbank 移植的致命细节：第 0 个样本的预加重**。
    torchaudio 用 `pad(replicate)` 做边界，所以 `y[0] = x[0] - 0.97·x[0] = 0.03·x[0]`；
    Kaldi 的 C++（feature-window.cc）**不动 x[0]**。两个实现不一致！
    桌面 voiceprint.py 用 torchaudio，所以必须跟 torchaudio。
    这一维错了会让整帧特征偏掉，embedding 完全不可比。实测修正后
    fbank max diff 从 5.5 降到 0.00017，两套特征算出的 embedding 余弦 = 1.000000。

31. **MiniMax 云端 TTS 是可选引擎**（10 个音色 2026-11-01 逐个实测 status=0）：
    `male-qn-qingse` 青涩青年 / `jingying` 精英 / `badao` 霸道 / `daxuesheng` 大学生 /
    `female-shaonv` 少女 / `female-yujie` 御姐 / `female-chengshu` 成熟 / `female-tianmei` 甜美 /
    `presenter_male` 男主播 / `presenter_female` 女主播。
    设置页选完会自动试听一句；**只在「播报引擎 = MiniMax 云端」时才有区别**，本地引擎会忽略。`VoiceGuide.CLOUD_ENGINE = "cloud"` 时走
    `CloudTts` 接口（`MinimaxTts` 实现）。接口 `data.audio` 是 **hex 字符串不是 base64**
    （`494433` = "ID3"）。实测合成 94KB / 1621ms —— 音色好但比本地慢十几倍，
    导航播报这种高频短句还是走本地合适。

32. **system prompt 必须注入当前时间**。模型只知道自己训练到哪天，不知道"今天"是几号 ——
    问日期、问"明天天气"、说"周六去哪"全都会瞎编。`ChatEngine.nowText()` 用
    `yyyy年M月d日 EEEE HH:mm`（Locale.CHINA）拼进 prompt，并且在规则里明确
    「涉及日期星期几号，以上面给的『现在是』为准」。实测「今天是几月几号，星期几」→
    「今天是11月1日，星期日」，和设备时间一致。

33. **Vosk 唤醒匹配现状与验证边界**（2026-11-01）。设备日志确认 AudioCapture 以 16kHz 运行、ASLEEP 心跳正常、Vosk ready；当前配置为「小度小度 / 小度你好 / 你好小度」，日志中常把口令识别成「小多」「多小都」。原因包含小模型解码偏差，以及 ICU 整串多音字转写会插入 `|`，破坏拼音串。实现已改为逐字多音候选、重复词拆分候选、同帧 partial/final 检查；拼音匹配仅检查窗口末尾，至少 4 音素且最多 1 个编辑差异。**构建与安装成功，但尚未由用户/真人声学实测确认唤醒成功。** 不要把“识别器启动正常”记录成“唤醒命中验证通过”。
    > 2026-11-02 更新：已真人实测确认唤醒命中，根因是词首吞音而非阈值，见第 44 条。

34. **按钮圆角用 drawable**。`bg_btn_primary` / `bg_btn_danger` / `bg_btn_ghost` / `bg_btn_listening` 使用圆角 shape；XML 必须配 `android:backgroundTint="@null"` 与 `android:stateListAnimator="@null"`，运行时样式切换用 `setBackgroundResource()`。

35. **聆听状态按钮不缩放**（2026-11-01）。用户反馈缩放呼吸动画不好看。当前按钮固定尺寸，状态文本为「◉ 语音 / 聆听中 / 识别中」，聆听时显示蓝色双层边框并以 alpha 1→0.72、680ms 轻微明暗变化；聊天页和导航页一致。

36. **AI 名字在设置中可修改**（2026-11-01）。`Prefs.aiName` 默认「小高」，用于模型自称、聊天标题、导航页气泡前缀。`wakeFollowsName` 默认关闭；开启后改名同步唤醒词为「名字 / 名字你好 / 你好名字」并重载 Vosk。名字与唤醒词默认独立，避免覆盖已有配置。

37. **跨页面语音状态必须读取实时快照**（2026-11-01）。仅回放 `NavApp.currentVoiceState` 事件缓存会有过期状态，产生「不在听却显示聆听」；`VoiceEngine.stateSnapshot()` 是权威状态，`VoiceService` 提供快照，`NavApp.refreshVoiceUi()` 在 Activity `onResume()` 查询，并在主线程更新 UI。`addVoiceUi()` 的补发也在主线程执行。

38. **聊天与导航页视觉层次**（2026-11-01）。蓝灰主题保持不变；聊天页顶栏/输入栏增加 elevation，消息气泡加大留白，导航 HUD/底栏提高层级，地图回复浮层使用半透明蓝灰圆角卡片。

39. **导航历史持久化**（2026-11-01）。`Prefs.navigationHistory()` 存 SharedPreferences，最多 20 条，最近目的地在前并去重。工具导航、导航页收到目的地、点击继续导航都会记录。历史以真实换行符分隔；不要写成字面反斜线+n。导航页「历史目的地」打开列表，选中后重新规划。

40. **导航页与聊天页返回栈**（2026-11-01）。导航页「返回对话」用 `FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP` 将已有 ChatActivity 提前，保留 NavActivity/地图实例；聊天页「导航」也通过 `REORDER_TO_FRONT` 回到已有 NavActivity。继续导航时从保留的 `app.destination` 调 `navigateTo()`。

41. **聊天页中断导航**（2026-11-01）。导航进行时聊天顶栏显示「中断」按钮；点击调用 `app.nav?.stop()`，只停止当前路线、保留目的地和历史。返回导航页可点「开始导航」继续。

42. **本轮真机验证范围**：`assembleDebug` 成功，并以会话式安装到 Huawei P40 Pro；App 启动确认。聊天截图检查无重叠或裁切。导航历史选择、返回栈往返、中断/继续、唤醒命中尚未做完整人工操作验证；报告时需区分“已构建安装”和“功能真机实测”。
    > 2026-11-02 更新：唤醒命中已真人实测通过（见第 44 条）；睡眠口令本地拦截实测通过（见第 45 条）。导航历史/返回栈仍未逐项复测。

43. **配色是蓝灰，不是绿**。改版前的深绿主题（`#07231D` 那套）已经被用户否掉。现在的色板：底 `#101820` / 栏 `#182430` / 卡 `#1C2A38` / 线 `#25384A` / 文字 `#E6EDF5` / 次要 `#8FA8C0` / 强调 `#4A7BA7` / 高亮 `#7FB2DC`。XML 和 `SettingsActivity.C` 两处要同步改，漏一处就会花。

44. **唤醒词无效的真根因是「词首被吞」，不是阈值不够**（2026-11-02，真机实测确认）。
    唤醒词配「小猪佩奇」时喊十遍不理人。抓 `nav.log` 看到 vosk 稳定只吐后三个字：
    ```
    09:14:44 窗口=「猪配其一」
    09:15:35 听到「猪」→「配」→「七」
    09:16:02 听到「猪皮」
    ```
    「猪」「配」「其/七」「皮」全都出现过，**「小」一次都没有**。所以窗口拼音是
    `zupeiqi`，期望 `xiaozupeiqi`，少的是一整段 `xiao`（4 个字母），编辑距离 4，
    而 `MAX_EDIT_ERRORS` 是 2 —— 无论怎么调阈值都救不回来，因为缺的是开头一整段。

    vosk 中文小模型对词首轻音节、连读首字吞音很常见：喊「小猪佩奇」时「小」和
    「猪」连得太紧，解码器直接并进下一个词。

    **修法：`VoskWake.tailMatch()`** —— 拿 expected 的**后缀**去比 actual 的尾部，
    覆盖率达到 60% 即命中。`zupeiqi`（7/11）能过，`peiqi`（5/11）不能过，
    所以「说佩奇就唤醒」不会发生。对两字词（「小度」）几乎无副作用：去掉词首
    只剩 `du`，长度不够 `MIN_WAKE_PHONEMES`，`sameInitials` 也会挡住。

    同时两处配套放宽：
    - `RESET_EVERY_FRAMES` 750→3000（15 秒→60 秒）。15 秒太密，唤醒词说一半撞上
      重置点，前半截直接被清掉。
    - `MAX_WINDOW_CHARS` 10→14。四字唤醒词加前后噪音，10 个字经常把前半截挤出窗口。

    **验证记录（不要回退成"未验证"）**：09:19:24 日志
    `唤醒词词首丢失兜底命中：「能听的不哦行猪配」→「zupei」` → 进 LISTENING →
    VAD 切句 → 声纹 0.695 放行 → ASR「咦？哎，好了，终于把你唤醒了」。全链路通。

45. **睡眠口令在本地拦，不走 LLM**（2026-11-02，照 `D:/AI/app/s2s_nothink.py` 的
    `_sleep_match` 移植）。原来「你休息吧」当普通一句话交给 LLM，由模型决定调
    `sleep` 工具，代价是 1~2 秒才回应，而且模型偶尔不调工具，用户觉得"说了没反应"。

    现在 `VoiceEngine.isSleepCommand()` 在 ASR 出结果后直接判：**必须句首命中 +
    尾巴只能是语气词**（`啊呀呢吧哦哈嗯啦哟嘛`）。多一个实词
    （「你休息吧那导航怎么办」）就放给 LLM —— 这是在追问，不是命令。
    `SLEEP_PHRASES` **必须按长度降序**，短的排前面会把「你先休息一下吧」切成
    「你先休息吧 + 一下」判否。

    命中后 `onSleepCommand` 回调 → 播一句「好」→ `sleepAfterSpeaking(1500ms)`。
    那个 1500ms 起始守卫不能省：云端 TTS 要几百毫秒才真正出声，没有守卫会
    立刻判定"没在播"，800ms 后就睡了，把「好」截断。

    实测 09:13:25 `本地命中睡眠口令：「你先休息吧。」` → 2.4 秒后休眠。

46. **API Key 可以在设置页换，不用重新编译**（2026-11-02）。`Prefs.minimaxApiKey` /
    `minimaxBaseUrl` 为空时回落到 `BuildConfig`（出厂默认），填了就覆盖。
    LLM / ASR / TTS 三条链路统一走 `Prefs.effectiveApiKey()` / `effectiveBaseUrl()`，
    **每次请求现读**，所以改完立刻生效。设置页只显示脱敏后 4 位（`…N21U`），
    不要把完整 Key 明文摆在屏幕上。`NavApp.prewarm()` 也改成预热生效地址，
    否则用户填了中转，预热的还是官方域名，等于没预热。

47. **空闲超时默认 60 秒**（2026-11-02，原 20 秒）。车里正常沉默半分钟很常见，
    20 秒一睡就得重喊唤醒词。注意 `SharedPreferences` 里**已经存过旧值的设备
    不会被新默认值覆盖** —— 老用户要自己去设置页选一次 60 秒。

48. **唤醒敏感度：放宽，但"单字唤醒"必须挡住**（2026-11-02）。
    用户要求「不怕误唤醒，喊得醒就行」，于是加了 `anySegmentMatch()`：把唤醒词拼音
    的**任意片段**拿去比，45% 覆盖即可。配合 `MAX_EDIT_ERRORS` 2→3、
    `MATCH_TAIL_CHARS` 8→12。

    **第一次改完踩坑**：`minLen` 取 45% = 4 个字母，而 `xiao` 正好是 4 个字母，
    真机日志直接出现 `唤醒词片段命中：「小」→「xiao」` —— 单说一个「小」就唤醒，
    车里「小心」「小声点」全会误触。

    三条约束把它挡住：
    - `minLen = max(MIN_WAKE_PHONEMES + 1, 45%)`，最小 5 个字母（约两个音节）
    - 从**词首**开始的片段必须再多一个字（`len > minLen`），否则 `xiaozu` 会退化成 `xiao`
    - `editDistanceWithinLimit` 按串长收紧错误数：4 字母→1、6 字母→2、8 字母以上→3。
      短串给 3 个错误等于"什么都能匹配"

    当前实际行为（唤醒词「小猪佩奇」）：
    ```
    「小猪佩奇」→ 命中   「小猪」→ 命中（xiaozu）
    「猪佩」   → 命中   「佩奇」→ 命中（peiqi）
    「小」     → 不命中  ← 关键
    ```
    `anySegmentMatch` 只对 `expected.length >= LONG_WAKE_PHONEMES`(8) 的词启用。
    两字词不能开：`xiaodu` 的片段 `aodu` 正好等于「角度 jiao-du」的尾部，
    一放开就把 2026-11-01 修掉的「角度」误唤醒重新引回来。

    实测：09:31:43 `唤醒词片段命中：「了小猪」→「xiaozu」`（听到两个字就醒）。

49. **搜索新闻后那句「网络好像不太行」已删除，改由模型自己说**（2026-11-02）。
    原文在 `ChatEngine.process()` 的 `full == null` 分支。用户反馈这句听着像系统故障，
    而且搜索新闻是"第一轮要工具 → 执行搜索 → 第二轮带结果"的两段式，任何一段慢
    都会走到这里，未必真是网络问题，甩锅网络反而误导。

    **中间走过一次弯路**：先把话术换成「没听清，你再说一遍？」—— 用户立刻指出
    这等于把锅甩给用户（明明是他没听见，用户会以为自己没说清楚）。所以最终方案是
    **不再由 App 编任何一句兜底**：

    1. 第一次请求失败且一个字都没出来 → 用**轻量上下文**（只带最近 4 条）+
       `retryPrompt()` 再要一次。`retryPrompt()` 只陈述事实（"上一条没发出去"）
       并划边界（不要编实时信息、不要提技术词），**不给任何现成句子**，措辞由模型决定。
    2. 两次都失败 → `onFailure("这次没连上，稍后再试")`，走 `dispatchNotice`
       显示为系统提示，**不进对话历史、不伪装成 AI 气泡**。
       同时 `history.removeAt(size-1)` 撤回那条没被回应的用户消息，
       否则下一轮模型会看到一句"没人回的问话"。

    只在 `sb.isEmpty() && spoken.isEmpty()` 时重试：已经上屏或已经念出去的内容
    不能重来，否则用户会看到/听到重复的半句话。

50. **从桌面打开 App 一定进导航页，不停在对话页**（2026-11-02）。
    `NavActivity` 加 `android:clearTaskOnLaunch="true"`。

    没有它的话：用户上次停在对话页 → 按 Home → 点桌面图标，Android 恢复的是
    任务栈**顶层**（对话页），看起来就像"App 一打开就是聊天界面"。

    **注意它的生效条件**：只对任务栈的**根 Activity** 起作用。如果任务栈的根
    本身不是 NavActivity（比如用 `adb am start ChatActivity` 冷启动），这个属性
    不生效 —— 自测时必须按真实路径走：桌面启动 → 进对话页 → Home → 点图标。

    实测（2026-11-02）：
    ```
    桌面启动        → NavActivity
    进对话页        → ChatActivity
    Home + 点图标   → NavActivity   ← 栈被清理，且复用原实例
    ```

完整踩坑在 `README.md`。

51. **对话大脑不再绑死 MiniMax，可以接本地 API**（2026-11-02）。
    `MinimaxLlm.kt` → `OpenAiLlm.kt`，地址/Key 从「跟语音共用」拆成独立的一套：

    | | 读哪个 | 默认 |
    |---|---|---|
    | 语音（识别/合成） | `Prefs.voiceBaseUrl()` / `voiceApiKey()` | MiniMax 官方 |
    | 对话大脑 | `Prefs.chatBaseUrl()` / `chatApiKey()` | 跟随语音服务 |

    `chatBaseRaw` 为空时跟随语音（老配置自动兼容）；填了就用填的，**Key 留空就不发
    Authorization 头** —— 本地 llama.cpp / Ollama 大多不校验，发个假 Key 反而被网关拒。

    配套改动：
    - `AndroidManifest.xml` 加 `usesCleartextTraffic="true"`。不开的话本地
      `http://127.0.0.1:8802/v1` 直接抛 `Cleartext HTTP traffic not permitted`，
      现象是"接上本地模型之后它不说话"，日志里只有一行。
    - `NavApp.prewarm()` 的端口从地址里取（原来写死 443，接本地等于握了个空）。
    - 设置页「服务商」预设：MiniMax / OpenAI 兼容 / 本地（预设 `http://127.0.0.1:8802/v1`）。
      切服务商会**同时**改地址和模型名 —— 只改地址的话模型名还留着 `minimax-*`，必然 404。
    - 「连接测试」走 `ChatProbe`，`stream=false` 发一句最短的话，把 HTTP 码和错误原文带回来。
    - 「工具调用」开关（`Prefs.toolsEnabled`）：本地 7B 以下模型大多不会 Function Calling，
      关掉退化成纯聊天，总比得到一堆"我这就帮你导航"的空话强。

52. **接本地接口时不再注入 Tavily**（2026-11-02，用户要求）。
    本地那套服务（`127.0.0.1:8802`，模型名 `minis`）自带完整检索，模型在服务端就查完了。
    再塞一个 `web_search` 等于让它二选一，而 Tavily 那份又慢（5 秒）又碎（中文新闻常是短讯）。

    - `Prefs.webSearchEnabled()` 三态：`auto`（默认，自定义接口→关、云端→开）/ `on` / `off`
    - `OpenAiLlm.activeTools()` 每次请求现算，把 `web_search`、`web_extract` 过滤掉
    - `ChatEngine` 的 `webSearchAvailable` 回调让 **system prompt 跟着一起改口** ——
      工具没了但 prompt 还写着"必须先调 web_search"，模型会调一个不存在的工具，
      轻则空转一轮，重则编造"我查到了"
    - 设置页「联网搜索」新增「搜索工具」一行

    实测（真机 + 用户自己的 `minis` 服务）：
    ```
    10:45:22 LLM 请求 http://127.0.0.1:8802/v1 model=minis tools=3 auth=无
    10:45:24 LLM 首字 1713ms
    10:45:24 LLM 完成 52字 toolCalls=0
    ```
    `tools=3`（navigate / get_weather / search_nearby），不再是 5。

53. **模型用「文本」发工具调用时会被当正文念出来**（2026-11-02，已修）。
    真机历史里抓到的现场：聊天框里一整坨
    `<tool_call>{"name":"web_search","arguments":{...}}</tool_call>`，导航却一点没动。

    原因：原生解析只看 `delta.tool_calls`，而一部分模型（本地小模型尤其）把调用写成
    正文文本。`ThinkFilter` 升级成 `StreamFilter`：同时吞 `<think>`、`<tool_call>`、
    `<|…|>` 这类保留 token；被吞掉的 tool_call 内容攒进 `toolText`，由
    `parseTextToolCalls()` 抠出来当真的调用执行（兼容 `arguments` 是对象 / 是字符串 /
    字段名叫 `parameters` 三种写法）。

    抠 JSON 不能上正则：`arguments` 里还有一层花括号，非贪婪匹配会在内层 `}` 处截断。
    用 `extractJsonObjects()` 做括号配对，跳过字符串里的 `{}` 和转义引号。

54. **失败重试不再重跑整条工具流程**（2026-11-02）。
    `Llm` 接口加 `chatText()`（默认退化成 `chat()`），`OpenAiLlm` 覆写成"只跑最后一段
    无工具请求"。原来 `ChatEngine` 的重试直接再调 `chat()`，等于把刚搜过的那次搜索
    原样重跑一遍：白等 5 秒、白烧一次额度，而且失败原因往往就在搜回来的内容里，
    重跑大概率还是失败。

    两处连带修复：
    - `OpenAiLlm.chat()` 在**最后一轮失败时返回 null**。以前会把前面几轮的半截话
      （「我帮你查一下」）当成功返回，上层既不重试也不提示 —— 用户听到一句话之后
      就是一片死寂，这是"新闻搜索经常失败"体感最直接的来源。
    - 第二轮被 422（内容审核）时，先用 `slimToolMessages()` 把工具结果的正文丢掉、
      只留标题来源，再要一次。
    - 工具结果存进 `lastDigest`，`chatText()` 会把它拼进 system prompt ——
      重试是"把已经查到的东西说出来"，不是重新查一遍。

55. **车载蓝牙播报：播报要标成「导航语音」，不能留默认的音乐类**（2026-11-03）。
    用户反馈「手机导航时声音传不到车机，高德 App 可以」。

    根因：`MediaPlayer` 默认 `USAGE_MEDIA / CONTENT_TYPE_MUSIC` 是**音乐**类，
    只走 A2DP；车机若只接通话通道（HFP）、或媒体音量被静音，播报就完全没声。
    高德能响是因为它标了 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`。

    三处改动：
    - `MinimaxTts`：`MediaPlayer.setAudioAttributes(导航语音 + SPEECH)`，
      必须在 `setDataSource` 之前设。
    - `VoiceGuide.drain()`：本地 TTS 显式传
      `KEY_PARAM_STREAM = STREAM_MUSIC`。不传 Bundle 时用引擎默认流，
      有的引擎是 `STREAM_SYSTEM`，在车机上根本不走蓝牙媒体通道。
    - 新增 `CarAudioRoute`：播报期间申请音频焦点
      （导航播报 GAIN_TRANSIENT、闲聊 MAY_DUCK）+ 切 `MODE_IN_COMMUNICATION`
      并起 SCO；播完立刻还原。

    ★ **必须立刻还原**：SCO 一开麦克风也被锁到 8kHz 窄带，
      晚恢复 200ms，下一句指令就识别不准。所以 `end()` 挂在
      `VoiceGuide.setSpeaking(false)` 同一个调用栈上（`speakWindowListener`）。

    ★ 没有车机可测，只能验证到「音频焦点申请成功、无蓝牙时不切模式」
      （真机日志：`车载音频焦点 request=true` / `当前没有蓝牙音频设备，保持外放`）。
      真实车机出声与否需要用户在车上确认。

56. **地图不跟车、信号不刷新、红绿灯不显示**（2026-11-03，用户一次报了 6 条）。

    - **地图不自动跟随**：`AMapNaviViewOptions.isAutoLockCar` 原来写死 `false`，
      车开几个路口车标就跑到屏幕边缘。改成读 `Prefs.navAutoLock`（默认 true）。
      视角另开 `setNaviMode(CAR_UP / NORTH_UP)`，跟着 `Prefs.navCarUp`。
    - **信号不自动刷新**：SDK 内置的 `navi_gps_view`（id 固定）是静态的，
      进了弱信号就一直挂着不恢复。现在自己做一个胶囊，每秒轮询
      `AmapNavController.signalLevel()`（SDK `onGpsSignalWeak` + 定位时间戳
      双来源，8 秒没定位降为「无信号」），并把 SDK 那个 `navi_gps_view` 隐藏。
      ★ 隐藏要放在每秒的 tick 里做 —— 导航开始时 SDK 会把它重新显示出来。
    - **红绿灯**：只开 `setTrafficLightsVisible` 只画图标，倒计时需要
      `AMapNavi.setTrafficSignalEnable(true)` 打开信号源（V11.x 才有）。
      另外 `NaviInfo.getRouteRemainLightCount()` 能稳定拿到「前方还剩几个灯」，
      自绘 HUD 里显示。倒计时秒数只在有路口数据的路口由 SDK 自己画，
      本地造不出来 —— 这点已经和用户讲明。

57. **导航页顶部工具条**（2026-11-03）。
    退出按钮 + 地址搜索栏 + 信号胶囊排成一条 `top_container`，
    带半透明底板（`bg_top_bar`）。没有底板时地图 POI 名称会从三块之间的缝里
    透出来，实测能看到一个红色的「信」字夹在搜索框和胶囊中间，像 UI 坏了。

    ★ `setCrossLocation` 的 top 必须是 `状态栏 + topBarHeight()`，
    而且**要设两次**：onCreate 时顶栏还没测量（用 dp(64) 估），
    `onResume → applyMapSettings()` 里再按实际高度设一次，否则高德 HUD 会被压住。

    ★ 搜索用输入防抖（800ms）而不是只靠回车：实测中文输入法会吃掉
    `IME_ACTION_SEARCH`（回车只是"确认候选词"），只靠回车的话用户打完字
    什么都没发生。结果最多 4 条（8 条会被键盘压掉大半）。

58. **「地图与导航」独立设置页**（2026-11-03，`MapSettingsActivity`）。
    导航页「设置」按钮改成弹菜单：地图与导航设置 / 应用设置 / 地图回到当前位置。
    不并进 `SettingsActivity` 是因为那页已经十几个分区，而且两类设置的使用
    场景不同（开车调地图、停车调模型）。改完回导航页 `onResume` 立刻生效。

---

## 7. 已验证的实测数据（华为 P40 Pro）

```
语音采集  AudioCapture 16kHz 正常启动；ASLEEP 心跳、Vosk ready 已确认
唤醒命中  当前「小度」配置尚无真人声学命中验证；不要当作已通过
VAD       1.7~3.5s 的句子稳定切出，过短的（<300ms）丢弃
ASR       MiniMax speech_to_text：1.1~1.7s 音频 → 546~1341ms
LLM 首字  预热后 974~1070ms（预热前 2933ms）
算路      张掖西站 2671m/480s；张掖站 12610m/1320s
改道      startNavi=false wasNav=true，HUD 正确更新
```

**历史语音识别实测句子**（此前配置「小高」时）
`「小高，你好，你在听吗？」「打开导航。」「去高铁西站。」「你好，小高。」`

---

## 8. 下一步（按价值排序）

### M2-a-2 声纹门禁（用户已拍板「参考 D:/AI」，**本轮没做**）

设计已定稿，见下方第 10 节。工作量大在 **Kotlin 手写 Kaldi fbank**，
Android 没有 torchaudio，必须和桌面 `D:/AI/app/voiceprint.py` 交叉验证 embedding（余弦 >0.99）。

### M2-c 周边搜索 ✅ 已完成

`Command.AskNearby` 已解析，`AmapWebGeocoder.searchAround()` 走高德 Web API `/v3/place/around`。
开车场景**不弹列表** —— 直接报「最近的中石油加油站 1.8 公里，要导航过去吗」，用户说「去」就导航到该 POI。

**注意**：`nearby()` 中网络请求必须在子线程（`FutureTask`），Android 主线程禁止直接发 HTTP，否则会抛 `NetworkOnMainThreadException`。

### 本轮修复

- **唤醒词滑动窗口**：vosk 把「小高」拆成两个 final 结果，逐帧匹配永远拼不出；改成 4 秒滑动窗口后命中。
- **口语前缀**：「好吧，现在导航去张掖西站」「打开导航去张掖西站」等前缀不再漏掉。
- **休眠时间**：5s → 20s（用户要求静默 20 秒再休眠）。
- **回声保护**：TTS 结束后 1.5 秒内丢弃拾音，避免导航播报被误识别成用户语音。
- **改道重算**：`startNavi=false wasNav=true` 时不再误判成「没在导航」。
- **周边搜索**：`AskNearby` → `AmapWebGeocoder.searchAround()`，子线程执行。
- **跨城市地名解析**（用户实测反馈「导航错了没办法修正」）：`citylimit` 按行政区划动态开关，见约束 10。
- **播报念全称**：从「开始导航，全程6.8公里」改成「注意，目的地不在张掖市：白银市景泰县金星家园，全程420公里，4小时39分钟」。
- **问句过滤**：`导航到哪里？` 不再被当目的地。
- **纠正导航**：「导航错了/纠正导航」→ `Command.RefreshRoute`，重报当前目的地全称并教用户怎么说。
- **周边结果过期**：`lastNearby` 加 2 分钟 TTL，避免导航到早就开过去的点。

### 用户实测反馈过的问题（已修，别改回去）

| 用户原话 | 根因 | 修法 |
|---|---|---|
| 「休眠以后唤醒不起作用」 | vosk 把「小高」拆成两个 final | 4 秒滑动窗口拼接 |
| 「刚才这一版没有打开导航」 | `打开导航去X` 被解析成 `dest="导航去X"` | reroute 改成 `打开(?:导航去\|导航到)` |
| 「导航错了没办法修正过来」 | `citylimit=true` 把外地地名塞给本市同名 POI | 按 `[省市县旗镇乡]` 动态开关 |
| 「到景泰要四百多公里呢，十七分钟能到吗」 | 播报只说距离不说地名，听不出解析歪了 | 播报念城市+区县全称 |
| 「语音回复里面有 thinking」 | M2.7 把英文思考内联进 `content`，而播报是边流边念的 | `ThinkFilter` 流式剥离 |
| 「首页添加导航页按钮」 | — | 顶栏加「导航」，导航中显示「导航中」 |

### 联网搜索（2026-11-01）

- `web_search` 接 Tavily Search API，支持 `general/news`、`day/week/month/year`、最多 5 条来源、发布时间和摘要。
- `web_extract` 接 Tavily Extract API；仅读取搜索结果来源中的 HTTPS URL，每次最多 2 个，正文最多回传 4000 字。
- 设置页可配置/清除 Tavily Key，编辑时密码掩码；Key 未配置、无效、额度不足、请求失败均明确回报“未取得结果”，不伪装成联网成功。
- system prompt 要求实时/易变信息先搜索；摘要不足时才读来源正文；搜索失败须说明未经实时核实。
- `assembleDebug` 已成功，产物 `android/app/build/outputs/apk/debug/app-debug.apk`。真机 API 搜索仍需设备设置有效 Tavily Key 后实测。

### 高德原生 SDK 改造（2026-11-01 下午，实测通过）

**动机**：Web 服务 API 有三个问题——主线程禁发 HTTP（`NetworkOnMainThreadException`）、
多一个 Key 要管、`citylimit` 语义与 SDK 不完全一致。换成原生 SDK 后全部消失。

**重复类问题已解决（这是本轮最关键的技术突破）**：

之前记录「`com.amap.api:search` 与 `navi-3dmap` 重复类且 exclude 无效，只能二选一」——**这个结论是错的**。
实测（`comm -12` 逐类比对两个 jar）两者**只重叠 2 个类**：

```
com/amap/apis/utils/core/api/AMapUtilCoreApi.class
com/amap/apis/utils/core/api/NetProxy.class
```

`exclude` 是**模块级**的，剔不了单个类，所以当时直接放弃了 search。
真正的解法是**打包阶段从 search jar 里剔掉这 2 个类**，保留 navi-3dmap 的。
安全性依据（`javap` 逐方法比对）：

| | 方法 |
|---|---|
| navi-3dmap 版 | setNetProxy / getNetProxy / setCollectInfoEnable / **setCollectIPEnable / getCollectIPEnable** |
| search 版 | setNetProxy / getNetProxy / setCollectInfoEnable |

search 那份是 navi 那份的**方法子集** → 保留 navi 的即可，search 运行时调用的方法一个不缺。
（HiCar 能同时用 PoiSearch 和 navi 也是这个思路，它把整个 `com/amap/apis/utils` 包剔了。）

**踩过的三个坑**（都会静默失败，必须记）：

1. **`files(TaskProvider)` 依赖不会被解析** —— 写 `api(files(stripSearchDuplicates))` 时
   `:core:dependencies` 里看不到，APK 里也没有 poisearch 类。必须写成
   `api(files(layout.buildDirectory.file("stripped-search/search-stripped.jar")))` 指向**实际路径**。
2. **`runtimeOnly` 不传递给下游模块** —— app 模块拿不到，APK 里同样没有。
   要让 app 直接用 `PoiSearch`/`GeocodeSearch`，必须用 **`api`**。
3. **Gradle 要求显式任务依赖** —— 否则报
   `Task ':app:desugarDebugFileDependencies' uses this output of task ':core:stripSearchDuplicates'
   without declaring an explicit or implicit dependency`。
   app 模块要补 `dependsOn(":core:stripSearchDuplicates")`。

**实现**：

- 新增 `app/AmapSdkGeocoder.kt`：`searchPoi` / `searchAroundList` / `geocode` / `locate`，
  全部 SDK 异步回调 + `CountDownLatch` 在工具线程内转同步。
- 删除 `app/AmapWebGeocoder.kt`（Web API 版），`AMAP_WEB_KEY` 不再需要。
- `AmapNavController.navigateTo` 的解析链路改为异步回调链：POI 命中 → 直接算路；
  否则退回 geocode。**不再需要自己开线程**，也不会再抛 `NetworkOnMainThreadException`。
- `REGION_HINT`（`[省市县旗镇乡]` → 放开 `citylimit`）跨城市规则**原样保留**。
- `NavApp.onCreate` 里调 `AmapSdkGeocoder.init()` 做高德隐私合规（必须在任何 SDK 调用之前）。

**真机实测（华为 P40 Pro）**：

```
17:05:47 高德 POI 命中「张掖西站」38.923036,100.428341 city=张掖市
17:05:47 poi 搜索「张掖西站」city=张掖市 → 张掖市甘州区/张掖西站 38.923036,100.428341
17:05:47 calculateDriveRoute 返回=true
17:05:47 播报目的地=张掖市甘州区张掖西站 跨市=false

17:06:09 poi 搜索「景泰县条山路金星家园」city=张掖市 → 白银市景泰县/金星家园 37.188254,104.06645
17:06:09 播报目的地=白银市景泰县金星家园 跨市=true
```

坐标与历史 Web API 版本**逐位相同**，跨城市行为一致（当年那个 6.8km 错 → 420km 对的坑没复发）。

### 蓝牙自动启动（2026-11-01 下午，已实现，真机未验证）

- 新增 `app/BluetoothAutoStart.kt`：按**设备名包含匹配**（车机名常带后缀，如「Ford Sync」，
  所以用 contains 而不是 equals）。
- 连上后：① 启动 `VoiceService` 前台服务（唤醒词常驻监听，用户直接说话）；
  ② 发常驻通知，点通知进聊天页。
- **为什么不直接弹 Activity**：Android 10+ 后台启动 Activity 的官方豁免清单里
  **没有「已连接蓝牙设备」这一条**，前台服务本身也不算豁免。
  只有「系统发的 PendingIntent（如通知点击）」和 `SYSTEM_ALERT_WINDOW` 两条正路。
  选了通知点击这条稳的。
- 权限：Android 12+ 的 `BLUETOOTH_CONNECT` 是运行时权限，不给就读不到设备名、收不到广播。
  旧权限 `BLUETOOTH`/`BLUETOOTH_ADMIN` 加了 `maxSdkVersion=30`。
- 断开时清触发标记，否则重连不再触发。
- 注册在 `NavApp.onCreate`（不是只在设置页），否则杀进程重启后功能失效。
- 设置页新增「车载蓝牙」区：开关 / 目标设备名 / 状态 / 查看已配对设备。
- **状态：代码与编译均通过，但设备上没有配对的蓝牙设备，真机行为未实测。**

### 高德官方导航界面（2026-11-01 傍晚，已实现并真机验证）

**起因**：用户发现 HiCar 导航时显示的是高德 App 的界面。逆向确认 HiCar 的做法是
**手机只做语音大脑，导航界面甩给高德**（`amapurihicar://hicarmap/navi?...`），
车机屏上跑高德。但 HiCar 依赖车机环境，纯手机做不到投屏。

**实测验证的高德协议**（`dumpsys package com.autonavi.minimap`）：

```
amapurihicar → com.autonavi.minimap/com.autonavi.map.activity.HicarSplashActivity
amapuri      → com.autonavi.minimap/com.autonavi.map.activity.SchemeHandleActivity
```

发一条 `amapurihicar://hicarmap/navi?src=...&coord_type=gcj02&location=lat,lng`
即可拉起高德自带导航界面（实测冷启动也正常，转向条/ETA/限速牌/路况全有）。

**但 nav-agent 没走跳转路线**，而是**开高德 SDK 的内置界面** —— 因为跳转高德 App 会
丢掉语音聊天、导航历史、历史目的地选单。保留自建页面，把渲染交给高德。

**实现**（`NavActivity`）：

- `options.isLayoutVisible = Prefs.amapBuiltinUi`（默认开）
- **`setCrossLocation(rect, rect)` 解决重叠**：告诉高德「HUD 只能画在这个矩形里」。
  上边留出状态栏高度（不给 0，否则右上角路名标签会被状态栏压掉一半），
  下边留出底部面板高度（实测测量，量不到时兜底 220dp）。
- `isAutoNaviViewNightMode = true` + `isNaviNight = 跟随系统`：
  **这个必须有**，否则高德默认浅色地图配我们深色底栏，交界非常割裂。
- `isLaneInfoShow = true`（车道级导航）、`isTrafficBarEnabled = true`（路况条）
- `isRouteListButtonShow = false`（底部空间让给我们的按钮栏）
- 自绘顶部 HUD 卡片在开内置界面时隐藏（`renderHud` 里判断），否则两套转向提示叠一起。
- 设置页新增「高德官方导航界面」开关，可切回自绘模式。

**语音、聊天浮层、导航历史、返回对话全部保留** —— 只有地图渲染和转向提示换成了高德的。

**真机实测**：深色模式下高德地图自动变深色，与蓝灰主题统一；
转向条「74米 进入无名道路」完整不被遮挡；底部四个按钮和输入框完整可读。

### 单界面重构 + 唤醒修复（2026-11-01 傍晚，已实现并真机验证）

用户提了五个问题，一并解决：

**① 首次进入唤醒词无效（根因是「解码器没热身」，不是唤醒逻辑错）**

日志实锤：
```
17:28:57 vosk 听到：「有不对啊哦你不」     ← 首次进入时听到的全是碎片
17:31:49 vosk 听到：「角度」→ 唤醒词命中    ← 误唤醒！
```
- 首次进入只调 `VoiceService.start()`，vosk 解码器刚 new 出来，前几秒解码质量极差；
  点语音按钮会走 `wakeManually()` → `wake.reset()`，顺手清了解码状态，所以「点一下就好了」。
- **修法**：`VoskWake` 建好后先喂 50 帧（1 秒）静音热身，让解码器进入稳定态。
- **同时修误唤醒**：`approximateMatch` 原本只允许 1 个编辑误差，
  「角度 jiaodu」和「小度 xiaodu」只差首字母 j/x 就命中了 —— 「角度」「加油」「街道」都会误触发。
  加了**声母检查**：首音节声母必须相同或在近似对列表里
  （`zh/z`、`ch/c`、`sh/s`、`n/l`、`f/h`、`r/l`；**j/x 不在列表**）。
  离线复现验证：「角度」「加油」「街道」全部拒绝，「小度/小杜/小肚/小都/晓度/小豆」全部命中。
- **真机实测**：`vosk 听到：「小」→「都」→ 唤醒词命中，窗口=「…小都」`（vosk 拆成两段，滑动窗口拼出来了）。

**② 导航页不显示聊天内容**
`NavActivity` 的 `onUser`/`onAssistantDelta`/`onAssistantDone` 改成空实现，
布局里的 `chat_overlay` 气泡浮层删除。回答只走 TTS 播报。

**③ 所有按钮底色统一为输入框底色**
布局里 5 个按钮全改 `@drawable/bg_input`（`#16222E`）；
**文字颜色必须同时改成浅色**（原来是 `#0E1620` 深色字配深底，看不清）。
`updateNavButton` 原来会运行时换 `bg_btn_primary`/`bg_btn_danger`，改成统一 `bg_input`，
状态区分靠**文字颜色**（停止导航时红字 `#F09595`）。
`bg_btn_listening` 也重写了：原来是实心蓝底 `#5B8FC9`，改成「同底色 + 蓝色描边 + 外圈」。

**④ 从对话页返回后导航消失**
两个原因，都修了：
- `AmapNavController` 新增 `pauseNavigation()`/`resumeNavigation()`，
  对应高德 SDK 的 `pauseNavi()`/`resumeNavi()`。**只暂停不停路线** ——
  `stopNavi()` 会把路线和目的地全清掉。
  `NavActivity.onPause()` 调 pause，`onResume()` 调 resume。
- `NavApp.toolNavigate` 以前未导航时走 `openNavigation()` 起新 Intent，
  导航页是 singleTop 会走 `onNewIntent` 重建整页 → 路线丢失。
  改成**直接用已存在的控制器** `controller.navigateTo(dest)`。

**⑤ 设置入口搬到导航页**
`ChatActivity` 不再是主界面，设置入口移到导航页按钮栏（新增 `nav_settings` 按钮）。

### 单界面架构（2026-11-01，用户拍板）

- **`NavActivity` 变成 LAUNCHER 主界面**，`ChatActivity` 保留代码但移除 intent-filter（不可见备用页）。
  保留而不删：纯语音模式万一出问题，没有退路。
- 导航页自己启动 `VoiceService`（`startVoiceService()`），权限回调里也补启动。
- 按钮栏五个：**语音 / 开始导航·停止导航 / 历史 / 对话 / 设置**，底色统一。
- 输入框保留（旁边有人、识别不准时能打字）。
- `toolNavigate` 改成优先用现有控制器，避免 Activity 重建。

**真机回归**（华为 P40 Pro）：
```
18:03:59 startNavi=true pathLen=2671 pathTime=480
18:04:29 导航已暂停（路线保留）    ← 切到对话页
18:05:01 导航已恢复                 ← 返回导航页
```
截图确认：返回后转向提示「74米 进入无名道路」、转向箭头、绿线路线、当前位置箭头**全部保留**。

### 对话页聆听配色 + 主动休眠（2026-11-01 傍晚，已实现并真机验证）

**① 对话页「聆听中」显示不明显**

根因：上一轮把 `bg_btn_listening` 改成了「深底 + 描边」（为了跟导航页统一后的输入框底色配套），
但对话页的按钮平时是**实心蓝底**（`bg_btn_primary` `#4A7BA7`）——
深底样式套到对话页的深色背景上几乎看不见。

修法：**给对话页单独做一个明亮样式** `bg_mic_listening.xml`（亮蓝 `#6FA8DC` 实底 +
白色描边外圈 + 深色字）。导航页继续用 `bg_btn_listening`（深底描边）。
两个页面的按钮底色体系本来就不同，不该共用一个 drawable。

**② 主动休眠（用户说「你休息吧」等就闭嘴）**

- `NavigationTool` 加了第四个 action `sleep`。
  工具描述里列了触发说法：「你休息吧」「你先休息吧」「不要打扰我」「你先不要打扰我」「安静点」。
- `ChatEngine` 的 system prompt 加了第 2b 条，明确要求这类话**必须调 sleep**，
  并强调「不要把休眠说成『好的我知道了』却继续聊天」。
- ★ **时序是这道题的关键**：不能工具一执行就睡。
  模型调用工具后还要生成回复（「好，有事叫我」）并用 TTS 播出来；
  这时若已休眠，TTS 的声音会被麦克风收进去 —— 半双工下自己的播报会被当成用户说话，
  可能又触发一轮识别。
  修法：`VoiceEngine.sleepAfterSpeaking()` 轮询 `isSpeakingProvider()`，
  等播报彻底结束（再等 800ms 让回声尾巴散掉）才进 ASLEEP；
  最多等 15 秒，超时强制睡。
- `NavApp.voiceEngineProvider` 暴露引擎引用（原来只有 `voiceStateSnapshotProvider`），
  工具层不用绕 `ACTION_SLEEP` 的 Intent 通路。

**真机实测**：

```
18:22:24.902 consumeSayExtra: 你休息吧
18:22:26.522 工具调用 navigate({"action":"sleep"})      ← 模型正确识别
18:22:26.524 用户要求休息，进入休眠
18:22:27.338 播报结束，进入休眠                      ← 播完才睡
18:23:10.642 唤醒词命中                                ← 休眠后唤醒仍然有效
```

三种说法全部通过：「你休息吧」「你先不要打扰我」「别烦我了」。

### 声纹前置 + 播报状态 + 蓝牙列表 + 识别提示 + 改名（2026-11-01 晚，已实现并真机验证）

**① 声纹前置到打断判定（barge-in）**

原来 barge-in 只用能量阈值 —— 麦克风把导航播报、收音机、乘客说话收进来都能打断。
现在：`EnergyVad` 检测到打断倾向时**持续攒音频**，攒够 [BARGE_IN_CHECK_MS] 600ms 后
交声纹门禁判定（`EnergyVad.bargeInCheck` 回调），只有主人的声音才真正打断。

- `VoiceEngine.isOwnerVoice()`：声纹没注册/算不出 → **一律放行**
  （它是防误打断，不是身份认证，别把打断功能整个废掉）
- 代价：打断响应延迟 0.6 秒（声纹判定需要 ≥0.5 秒音频）。
  换来「麦克风串音不打断」，开车场景这笔交易划算。
- 注意：`bargeFrames`（能量连续帧计数）现在只用于辅助，**真正的判定权交给声纹**。

**② 播报时按钮显示「播报中」**

- `VoiceGuide` 加 `speakingListeners`（`CopyOnWriteArrayList` —— 回调在 TTS 线程触发，
  界面在主线程注册注销，并发改会炸）。
- `isSpeaking` 的赋值统一走 `setSpeaking()`，它才触发通知。
- 两个页面的语音按钮在播报时显示「播报中」，**优先于**「聆听中/识别中」
  （此时是半双工，麦克风根本不拾音，显示「聆听中」是误导）。

**③ 蓝牙设置列出手机中的设备**

`BluetoothAutoStart.listDevices()`：已连接 + 已配对未连，已连接排前。

**真机实测读到 11 个已配对设备**：
```
小度音箱灵动版 2e37e   MINISO99      Mifa_A10+
My Volvo Car           NOME-BS19     VOLVO XC60-1EAA
BD-H1583               HUAWEI WATCH GT 2-3F9
CT09                   Baseus Bowie WM02
vivo Wireless Sport 2
```
设置页点「蓝牙设备列表」弹列表，**点设备直接填进「目标设备名」**。
权限缺失 / 蓝牙未开 / 无设备，三种场景分别给对应提示，不再是笼统的「没找到设备」。

**④ 输入框上方显示识别到的文字**

导航页新增 `recognized` TextView（状态行和输入框之间）：
识别成功显示 → 发送后或模型开始回答时自动消失。

为什么需要：纯语音界面没有消息列表，用户**看不到机器听成了什么**。
识别错了（「西站」听成「西战」）根本无从发现，只能眼睁睁看它导错地方。
现在能立刻确认，并用输入框改掉。

**⑤ 应用改名「AI导航」+ 语音按钮图标**

- `app_name`：小高 → **AI导航**
- 「◉ 语音」的圆点字符换成矢量图标：
  `ic_mic.xml`（喇叭，待命/播报中）+ `ic_mic_active.xml`（麦克风，聆听中）。
  **为什么不用 Unicode 符号**：不同 ROM/字体下长相差别很大，尺寸基线也不可控。

### 休眠与导航播报的关系（2026-11-01 晚，实测确认）

**结论：休眠完全不影响导航播报，两者是独立通道。**

原因：导航播报走 `AmapNavController.onGetNavigationText` → `voice.speak(text, Priority.NAV)`，
**根本不经过 `VoiceEngine` 的状态机**。`VoiceEngine` 管的是「唤醒 → 聆听 → 识别 → 交给模型」，
导航播报是另一个入口直接进 TTS 队列。

休眠只是把 `VoiceEngine` 置为 `ASLEEP`（停止拾音、不再响应语音），TTS 队列照常工作。

**实测证据**（华为 P40 Pro）：

```
19:53:49.021  播报: 即将左转,随后在红绿灯右转
19:53:55.374  播报结束，进入休眠            ← 休眠
19:54:37.349  NaviInfo step=74 ...          ← 休眠后导航引擎仍在推送
19:55:40.634  startNavi=true pathLen=2671  ← 休眠期间改道，导航照常执行
19:55:40.745  播报: 准备出发,...七十米后左转  ← 休眠后照常播报
```

**为什么休眠后一段时间看不到新播报**：车静止时高德不推进导航进度
（`remain=2671/499` 一直不变），也就没有新的转向指令 —— 这是正常行为，不是故障。
真机开起来就会持续播报。

**顺带确认的副作用**（这是设计意图，不是 bug）：
导航播报时麦克风会收到 TTS 的声音 → 攒够 0.6 秒 → 声纹判定分数极低
（实测 0.015 / 0.080 / 0.062 / -0.052）→ 丢弃 → **不打断播报**。
这正是「麦克风的声音不打断 AI 播报」要的效果。
同时确认导航播报内容**没有被送进 ASR/模型**（`submit` 日志里只有真实用户语音）。

### 应用图标（2026-11-01 晚，两轮修正）

原来用系统图标 `@android:drawable/ic_menu_mylocation`（一个裸定位针），没有底色方框，
在深色壁纸上很难辨认。

**第一版**做了「折角地图」造型 + 深色渐变底，用户反馈两个问题：
1. 「图标是立方体不是箭头」
2. 「底色也太深了」

**根因（第一版的问题）**：
- 「折角」造型本身就有立体方块的感觉，再叠加 EMUI 对自适应图标前景的立体化处理，
  整个图标就变成立方体 —— 这是设计问题，不是 bug。
- 底色直接用了应用界面的深色（`#1B3040` → `#0E1620`），
  桌面壁纸通常也是深色的，图标跟壁纸糊在一起，就是一块黑。

**第二版（当前）**：
- 前景改成**朝上的定位箭头**（白色水滴箭头 + 蓝色圆孔）。
  形状简单、剪影明确，立体化后仍然是箭头 —— 这是最高度可辨识的导航符号。
- 背景改成**亮蓝灰渐变**（`#6BAEE0` → `#2F6FA8`）。
  **图标跟应用界面不必完全同色** —— 图标要的是「在桌面上能一眼找到」。
- 箭头缩小并上移，让它完整落在自适应图标安全区内
  （第一版底部被 EMUI 立体化压平了一点）。

**记住的教训**：
自适应图标的前景会被系统做立体化处理（EMUI 风格），
所以前景用矢量、传统 PNG 另外生成，**两者形状必须保持一致**。

**另一个坑**：自己写 PNG 编码（本机没 Pillow）。
PNG 就是 zlib 压缩的扫描线，纯标准库（zlib + struct）就能写；
多边形要用**射线法**判断点在多边形内（箭头是折角造型时不是纯三角形）。

### 高德 SDK 升级到 V11.3.100 + 路线规划修复（2026-11-01 晚）

**起因**：用户对比 HiCar 发现两件事 —— ① 界面不一样；② **「这个南门不通车，导航的准确性才是最重要的」**。

#### 根因：SDK 版本太老

之前用的 `com.amap.api:navi-3dmap:10.0.800` 是 **2024-08 的最后版本**
（Maven 上 `lastUpdated=20240815055821`，之后这个 artifact 停更）。
**算路数据是 4 年前的** —— 它不知道「南门不通车」这种通行限制，
而高德 App（2026 版数据）知道，所以两边路线走向完全不同：

```
SDK 版：2671 米 / 420~480 秒，走「无名道路」直接向北
App 版：2.8 公里 / 9 分钟，走「临泽北路」往东绕开
```

官方最新是 **V11.3.100（2026-09-24）**，已从 `navi-3dmap` 拆成独立包，
官方合包名 `AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101`。

**★ Maven 上没有新版**（`com.amap.api:navi` 只到 1.9.4 / 2020 年），
只能官网下载 ZIP 手动引入 → `tools/fetch_amap_sdk.sh` 自动下载。
二进制 187MB，**不入 Git**（`.gitignore` 排除 `libs/amap/`）。

#### 车行限制（官方文档给的正解）

`AMapNavi.setCarInfo` 官方原文：

> 设置车辆信息(包括车型，车牌，车高，车重等)，**路径规划时会躲避车辆限行区域和路线**
> 目前小客车算路只考虑车牌因素，货车算路会考虑车高、车重等信息
> 其中 `AMapCarInfo.setRestriction(boolean)` 用于控制是否规避限行路段

- `applyCarInfo()`：`setCarType("0")`（轿车）+ `setRestriction(true)` + 车牌
- ★ **每次算路前都要再设一遍**（官方说它影响的是「路径规划时」的行为，
  不能只在初始化时设一次）
- 设置页新增「车牌号」——官方说小客车限行**只认车牌**（尾号限行），
  不填车牌只能规避结构性禁行（限高/限宽/货车道）

#### 界面：按官方 API Doc 改

官方文档（`AMapNaviViewOptions`）明确几个接口**已过时**：

| 过时接口 | 替代 |
|---|---|
| `setNaviNight` / `setAutoNaviViewNightMode` | `setMapStyle(MapStyle, path)` |
| `setCustomMapStylePath` | 同上 |
| `setCrossLocation` | `setEyrieCrossLocation`（但那个只管路口大图，HUD 位置仍用旧的，实测有效） |

- 昼夜样式：`setMapStyle(MapStyle.AUTO, "")`（AUTO = 昼夜自动切换，跟着系统走）
- ★ **地图样式必须在 `setOnMapLoadedListener` 回调里设** ——
  官方给这个回调就是「地图的加载完成事件监听」。
  在 `onCreate` 里直接设会被吃掉（实测地图一直是浅色，就是这个原因）。
- 关掉被底部面板遮住的缩放按钮：`map.uiSettings.setZoomControlsEnabled(false)`（保留手势缩放）
- 路况线 `setTrafficLine(true)` + 路况图层 + 车道信息 + 红绿灯倒计时气泡
- 车辆模型：`setCarBitmap(俯视汽车矢量图)`，替代 SDK 默认的蓝色三角箭头

#### 顺带修的时序 bug：城市偏置

定位回调里**坐标先到、城市名后到**（城市要靠地址反查）。
`hasFix` 一变 true 就去搜索 → `currentCity` 还是 null → **城市偏置失效**，
跨城市地名会解析错。

修法：`navigateTo` 时若城市未就绪，等最多 3 秒（`waitForCityThenResolve`）。

实测：
```
21:15:54.278 首次定位 ... city=null
21:15:55.214 poi 搜索「景泰县条山路金星家园」city=张掖市 → 白银市景泰县/金星家园 37.188254,104.06645
```

#### 版本号

`versionCode 8` / `versionName 0.3.0`

#### ★ 路线差异的真正根因：算路策略过时（不是数据、不是起点）

这一条查得最深，值得完整记下来 —— 前面两次判断都不对：

**排查过程**：
1. 先怀疑 SDK 数据旧（2024 版）→ 升级到 V11.3.100，**路线没变**（还是 2671 米）
2. 再怀疑起点不同 → 用高德 **Web API** 拿**完全相同的起点坐标**去算：
   ```
   Web API：2736 米 / 488 秒 → 段0 无名道路74m → 段1 无名道路111m
                      → 段2 临泽北路→临泽南路 2192m → 段3 无名道路210m → 段4 84m
   ```
3. 对比 SDK 算出来的路径段，**逐段完全一致**（除了总长 2671 vs 2736 的取整差）
4. 结论：**起点一样、数据一样，差别只在算路策略**

**根因**：官方文档（V11.3.100 API Doc · PathPlanningStrategy）明确写着：

> `DRIVING_DEFAULT` —— **已过时**。推荐使用 `PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT`
> `DRIVING_MULTIPLE_ROUTES_DEFAULT`（策略值 10）—— 多路径-速度优先+躲避拥堵+距离较短，**建议使用该策略**

我们一直在用**已过时**的 `DRIVING_DEFAULT`（单路径、纯速度优先），
而高德 App 用的是新版多路径策略，所以绕开了南门那条路。

**改法**：`calculateDriveRoute(..., PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT)`

**实测验证**（日志里能看到策略值和每段路名）：
```
算路策略 = 10 (DRIVING_MULTIPLE_ROUTES_DEFAULT=10)
段0 (74m):   无名道路
段1 (111m):  无名道路
段2 (2192m): 临泽北路 → 临泽南路
段3 (210m):  无名道路
段4 (84m):   无名道路
```
与高德 Web API 逐段吻合。

**★ 顺带加的路线诊断日志**（`onCalculateRouteSuccess`）：
打出每段走的路名和长度。以后路线不对，看这段日志就知道走的哪条路。
（路名在 `AMapNaviStep.links[].roadName` —— `AMapNaviStep` 自己没有 roadName 字段，
写成 `step.roadName` 编译不过。）

#### 体积优化：砍掉 armeabi-v7a

```
高德 SDK：arm64 87MB + armeabi-v7a 64MB
vosk：   arm64 10MB + armeabi-v7a 9MB
砍掉 v7a 省约 73MB → APK 190MB → 155MB
```
`abiFilters += listOf("arm64-v8a")`。
代价：不再支持 32 位设备（2015 年前的老机型）。
华为 P40 Pro 的 abilist = `arm64-v8a,armeabi-v7a,armeabi`，64 位优先，装 arm64 版没问题。
要支持老设备就把 `"armeabi-v7a"` 加回 `abiFilters`（`fetch_amap_sdk.sh` 里也有对应注释）。

APK 剩余体积构成（155MB）：
```
libAMapSDK_NAVI_v11_3_100.so  68.1 MB   ← 高德导航引擎（大头，去不掉）
libonnxruntime.so              16.8 MB   ← 声纹门禁
libvosk.so                     9.6 MB   ← 离线唤醒
libneonui_shared.so             7.2 MB
libnui.so                       6.4 MB
```

### 收尾

- [ ] `Dbg` 在 release 构建里关掉（现在 debug/release 都写文件）

- [ ] vivo 上跑一遍 v0.2.0
- [ ] 导航播报与唤醒的冲突：现在播报期间完全停止拾音（半双工），
      意味着**导航正在说话时喊「小高」听不见**。要么加 AEC，要么播报时仍喂唤醒词。
- [ ] 聊天历史持久化（现在杀进程就没了）

---

## 9. 已知限制（不是 bug，是授权限制）

- 高德个人开发者，**未获得技术服务使用许可**（需企业认证）。Key 仅供短期少量测试，**不能上架分发**。
- Web 服务 Key 打包在 APK 里（`BuildConfig.AMAP_WEB_KEY`）。自用可接受，做产品必须挪到服务端中转。
- MiniMax Key 已从 `local.properties` 注入，不裸露在源码里，但**仍在 APK 里**（自用可接受）。

---

## 10. 声纹门禁 · 详细设计（2026-11-01 定稿，待实现）

> 依赖可用性、模型体积、接口地址都是实测过的，不要重新调研。

### 10.1 参考实现

`D:/AI`（真源在 WSL `~/s2s`）—— **只读参考，不要改**。

| 文件 | 里面有什么 |
|---|---|
| `app/voiceprint.py` | 声纹门禁全部算法，主要抄这个 |
| `app/asr_minimax.py` | MiniMax ASR 适配层（已移植到 `MinimaxAsr.kt`） |
| `app/s2s_nothink.py` | 补丁挂载顺序、唤醒词匹配（已移植到 `VoskWake.kt`） |

### 10.2 算法（照抄，参数一个别改）

**模型**：WeSpeaker `cnceleb_resnet34_LM`，26MB ONNX，输出 256 维 embedding，CPU 跑。

**特征**：Kaldi fbank，**必须和 torchaudio `kaldi.fbank` 对齐**：

```
num_mel_bins 80 / frame_length 25ms / frame_shift 10ms
window_type hamming / dither 0.0 / use_energy false / sample_frequency 16000
snip_edges = true
随后 CMN：feat = feat - feat.mean(axis=0)
输入 (1, T, 80) → 输出 (256,) → L2 归一化
```

**判定**：余弦阈值 **0.35**（同人实测 0.56~0.77，异人 -0.09~0.28）。

**注册**：一次会话里第一句有效语音（≥1.0s）自动注册；<0.4s 不算，>15s 只取前 15s。

**自适应**：只在分数 ≥0.50 时更新模板，EMA 0.15。

**★ 失败一律放行**：超时、取不到音频、embedding 失败 → 全部 `allow=true`。
它是防误触发（乘客说话、导航串音），**不是身份认证**，别当锁用。

### 10.3 依赖

| 项 | 结论 |
|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | ✅ Maven Central 有，最新 1.31.1 |
| 模型 `Wespeaker/wespeaker-cnceleb-resnet34-LM` | ✅ hf-mirror 可达，含 26MB onnx |

模型同样不打包，走 `ModelStore` 下载到 `models/wespeaker/`。

### 10.4 落地顺序

1. 写 `KaldiFbank.kt`（预加重 0.97、去直流、hamming 窗、mel 滤波器组、log）
2. 用桌面 `voiceprint.py` 对同一段 wav 出 embedding，和 Android 端比余弦（要 >0.99）
3. 接 `OnnxSpeakerGate`，进 `VoiceEngine` 的 RECOGNIZING 分支
4. 只在 `busyRecognizing` 后、`listener.onUtterance` 前插一道门

---

## 11. 明天开工的第一句话

> 接着做 nav-agent，先读 `nav-agent/HANDOFF.md` 和 `nav-agent/android/README.md`。
> 现在 v0.2.0 已经能「聊天 → 导航 → 导航中唤醒聊天」，下一步做第 10 节的声纹门禁。

或者想先看效果：

> 接着做 nav-agent，装到华为上跑一遍，把 nav.log 贴给我看
