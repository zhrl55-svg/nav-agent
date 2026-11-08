# nav-agent · Android 工程

含导航能力的语音智能体。**当前状态：v0.3.0 —— 单界面纯语音。主界面是导航页，全程语音交互，不显示聊天内容；设置入口在导航页。Huawei P40 Pro 已构建安装，唤醒词「小猪」真机命中验证通过。**

## 产品形态（v0.3.0 起，单界面纯语音）

```
NavActivity（主界面 LAUNCHER）
  └ 高德地图 + 高德官方导航界面（转向条、车道线、路况条）
  └ 底部按钮栏：语音 / 开始导航·停止导航 / 历史 / 对话 / 设置
  └ 左上角退出按钮（×）：退出整个应用，不是退出导航
  └ 一行状态提示（不显示聊天内容）
  └ 输入框（旁边有人、识别不准时打字）

说唤醒词 → 唤醒 → 连续对话 → 回答只播报（TTS），不切页面、不显示文字
说「导航去张掖西站」→ 直接在当前页算路，不重建 Activity
```

**为什么改成单界面**（用户拍板）：导航页本来就有地图、语音按钮和输入框，功能是聊天页的超集；
两个页面来回切反而让「返回后导航消失」这类问题变多。

`ChatActivity` **保留代码但不是主界面**（移掉了 LAUNCHER intent-filter）。
保留而不删：纯语音模式万一出问题，没有退路。

语音链路常驻在 `VoiceService`，因为一个麦克风只能有一个 `AudioRecord`，
而导航页和设置页都要用。

## 真机验证结果

| 设备 | 系统 | 结果 |
|---|---|---|
| Huawei P40 Pro（ELS-AN00） | HarmonyOS 4.0，API 31 | ✅ 构建安装和定位/导航链路已有历史验证；当前「小猪」唤醒命中未做真人声学复测 |
| vivo X20 Plus A（PD1710） | Android 8.1，API 27 | 旧版本全链路/TTS 曾验证；本轮导航历史/返回栈及新唤醒逻辑未验证 |

实测数据（张掖市区 → 张掖西站）：定位 `38.945019,100.418638` → 地理编码 `38.923036,100.428341`
→ 算路成功 `2671m / 420s` → 播报「准备出发，全程二点六公里，大约需要七分钟，七十米后左转」。

## 环境

| 组件 | 位置 / 版本 |
|---|---|
| Android SDK | `D:\Android\Sdk`（platforms 34/35，build-tools 34.0.0 + 35.0.0） |
| Gradle | `D:\Android\gradle\gradle-8.14.6`（腾讯镜像下载） |
| JDK | Android Studio 自带 JBR 21（`D:\Program Files\Android\Android Studio\jbr`） |
| AGP / Kotlin | 8.13.2 / 2.4.21 |
| 调试签名 | Android Studio 自动生成的 debug.keystore（请勿提交） |

## 构建 / 安装 / 调试

```bash
cd nav-agent/android
export JAVA_HOME="/d/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME=/d/Android/Sdk
export GRADLE_OPTS="-Dorg.gradle.internal.http.socketTimeout=20000 -Dorg.gradle.internal.http.connectionTimeout=20000"
/d/Android/gradle/gradle-8.14.6/bin/gradle.bat assembleDebug
```

**首次构建**先拉两个不入库的二进制（在仓库根目录执行）：

```bash
bash nav-agent/tools/fetch_amap_sdk.sh   # 高德导航 SDK，187MB
bash nav-agent/tools/fetch_sherpa.sh     # sherpa-onnx AAR，47MB（带 sha256 校验）
```

Windows 上 `curl` 容易卡住，改用 PowerShell `Invoke-WebRequest` 即可。
sherpa 的 KWS 模型（4.5MB）**已入库**，不用拉。

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
adb shell am start -n com.navagent/com.navagent.app.NavActivity --es dest 张掖西站
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
├── ChatActivity.kt      备用页（已非主界面，保留代码以便查历史）
├── NavActivity.kt       导航页 + 聊天浮层
├── VoiceService.kt      前台服务（microphone|location），麦克风唯一持有者
├── VoiceEngine.kt       语音状态机 ASLEEP/LISTENING/THINKING
├── AudioCapture.kt      AudioRecord 16k/mono/PCM16，20ms 一帧
├── EnergyVad.kt         能量 VAD，自适应噪声底，断句 800ms
├── VoskWake.kt          vosk 离线唤醒 + 模糊匹配（词首丢失/片段/拼音兜底）
├── SherpaBargeIn.kt     播报期间的固定关键词识别（zipformer KWS，只认「小猪」）
├── SherpaKwsTester.kt   KWS 离线自测：拿 wav 跑一遍，验证模型认不认得唤醒词
├── HuaweiAudioPreprocess.kt 华为 ASR_AEC 开关（隐藏 setParameters，失败静默回退）
├── RecentAudioBuffer.kt 唤醒候选的短音频环形缓冲（喂声纹用）
├── VoiceprintGate.kt    声纹门禁（WeSpeaker cnceleb_resnet34_LM）
├── SpeakerEncoder.kt    sherpa-onnx SpeakerEmbeddingExtractor（256 维）
├── ModelStore.kt        模型下载 / 解压 / 校验
├── MinimaxAsr.kt        MiniMax speech_to_text，手写 multipart
├── MinimaxTts.kt        MiniMax 云端 TTS（MP3；audio 字段是 hex，不是 base64）
├── OpenAiLlm.kt         OpenAI 兼容对话（MiniMax / 本地 minis 都走它）
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

## 唤醒与打断（2026-11-04 定稿）

唤醒词当前是「小猪」，两套识别器分工：

| 场景 | 用谁 | 匹配强度 | 目的 |
|---|---|---|---|
| 待命（ASLEEP） | Vosk 自由解码 | 宽松（词首丢失、任意片段、拼音兜底） | 宁可多唤醒，别唤醒不了 |
| 播报中（打断） | Vosk 严格模式 **+** Sherpa zipformer KWS | 严格（必须完整唤醒词） | 不许被 TTS 里的普通词打断 |

播报期间的判定链：

```
麦克风帧 → Vosk(严格) ─┐
             Sherpa KWS ─┴→ 命中唤醒词
                              ↓
                     ① 自打断判定（播报原文里有没有这个词）
                              ↓ 没被自己播出来
                     ② 声纹确认（声纹关着就跳过）
                              ↓ 通过
                     停播报 → 提示「已打断播报」→ 进入聆听
```

**① 声纹是裁判**（`VoiceprintGate`）：主人近场说话和 TTS 回声在 embedding 上差得开，
这一层既能挡回声，也能让主人直接喊话打断。失败一律放行（模型没就绪、没注册 → 退回唤醒词直断），
它防误触，不是身份认证。

> ★ 2026-11-04 之前那条「播报原文里含有唤醒词就不打断」的文本判断是错的，已删：
> AI 的名字就叫「小猪」，回答里带一句「小猪」，用户后面喊的「小猪」全被吞掉，
> 真机表现是「喊了无数遍小猪也没反应」。

**② 两条路都能打断**：

| 触发 | 判定 | 实测 |
|---|---|---|
| 喊唤醒词（Vosk 严格 / Sherpa KWS） | 声纹 ≥ 0.28 才打断 | 回声 0.02~0.11，主人 0.39~0.52 |
| 只说话、不喊唤醒词 | 声纹连续两次 ≥ 0.38 才打断 | 同上，多一次连续确认防误触 |

**实测分数基线**（华为 P40 Pro，2026-11-04，同一份声纹模板）：

| 场景 | 分数 |
|---|---|
| TTS 回声（播报自己在响） | 0.02 ~ 0.11 |
| 主人声音混在播报里 | 0.39 ~ 0.52 |
| 主人安静时说话 | 0.89 |

日常的「匹配阈值」0.45 卡在主人和回声中间，会把主人一起挡掉，
所以打断走独立阈值 `Prefs.voiceprintBargeThreshold`（默认 0.28）。

**★ 声纹模板会过期**：环境变了（车里、麦克风挪了位置、自适应漂了）分数会掉到 0.1 以下，
这时候再准的判定也认不出主人。重新注册一次比调阈值靠谱 ——
设置页「重新注册声纹」，或调试入口 `--es vp_enroll 1`（拿最近 2.5 秒音频覆盖模板）。

**回声消除**目前是三层叠加，都不完美：

- `AudioCapture` 显式挂 `AcousticEchoCanceler`（系统 AEC），音源保持 `VOICE_RECOGNITION`
  —— 换 `VOICE_COMMUNICATION` 会连主人近场语音一起压低，真机上喊「小猪」只听得到「小」。
- `HuaweiAudioPreprocess` 在播报期间下发华为的 `ASR_AEC=ON`（隐藏 `setParameters`），失败静默回退。
- 播报结束后 600ms 回声保护期（`Prefs.echoGuardMs`）内不拾音。

**调试入口**（都需要 `-s MDX0220A21035172`，两台设备同时连着）：

```bash
# KWS 离线自测：模型认不认得唤醒词
adb -s MDX0220A21035172 shell am start -n com.navagent/com.navagent.app.NavActivity \
  --es kws_test /sdcard/Android/data/com.navagent/files/kws_test/1.wav

# 绕过语音，直接喂一句话
adb -s MDX0220A21035172 shell am start -n com.navagent/com.navagent.app.NavActivity \
  --es say "请在回答里说出唤醒词小猪"

# 直接播一段长文本（测打断必须用这个：走对话模型可能返回工具调用，根本不播）
adb -s MDX0220A21035172 shell am start -n com.navagent/com.navagent.app.NavActivity \
  --es tts "这是一段很长的播报内容..."

# 声纹自测：打印最近两秒麦克风音频的声纹分数
adb -s MDX0220A21035172 shell am start -n com.navagent/com.navagent.app.NavActivity --es vp_test 1

# 声纹重新注册：拿最近 2.5 秒音频覆盖旧模板（换环境了就得重来一遍）
adb -s MDX0220A21035172 shell am start -n com.navagent/com.navagent.app.NavActivity --es vp_enroll 1

adb -s MDX0220A21035172 pull /sdcard/Android/data/com.navagent/files/nav.log .
```

`kws_test` 用的是模型包自带的 `test_wavs/` + `test_keywords.txt`
（`android/libs/sherpa/kws.tar.bz2`），已验证能命中「周望军」「朱丽楠」等官方样例，
所以「模型不工作」和「现场音频激不活解码器」可以分开定位。
注意 wav 必须放在 app 自己的外部目录里，放在 `/sdcard/kws_test` 会 EACCES。

## 设置

入口在导航页底部「设置」按钮。全部落 `SharedPreferences`，默认值来自 `BuildConfig`（所以「恢复默认」= 回到编译时的值）。

| 分组 | 设置项 | 默认 | 生效方式 |
|---|---|---|---|
| 语音识别 | ASR 模型 | `asr-1.0` | 立即 |
| | 唤醒词 | 小猪,小猪你好,你好小猪 | **需重建** `VoiceService.reloadWake()`（播报中另走 KWS，无需重建） |
| | 会话休眠 | 60 秒 | 立即 |
| | 唤醒提示音 | 开 | 立即 |
| 对话 | 对话模型 | `minimax-m3.1-flash` | 立即 |
| | 回复长度 | 400 token | 立即 |
| | 记忆轮数 | 8 轮 | 立即 |
| 播报 | 语速 | 1.0x | `VoiceGuide.setRate()` |
| | 回声保护 | 600 毫秒 | 立即 |
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
4. **唤醒词 + 声纹打断。** 播报期间暂停普通 VAD / ASR，只运行 Vosk 高召回唤醒检测；命中候选后用最近 1.5 秒音频做声纹确认。两项都通过才停播并开始拾音，既接得住被 Vosk 拆错的唤醒词，也能拦住 TTS 和旁人声音。点语音按钮也会先停播再拾音。
5. **`NaviLatLng` 用无参构造 + setter**，避开 `NaviLatLng(double, double)` 参数顺序（lat/lon 还是 lon/lat）的歧义。
6. **左上角 × 是「退出整个应用」，不是退出导航。**
   高德内置界面的底栏（退出 / 继续导航 / 设置）被 `setCrossLocation` 裁到屏幕外，
   官方那个「退出」跟着没了，只能自己补一个。四步缺一不可，真机逐项验证过：
   `nav.stop()` → `VoiceService.stop()` → `finishAffinity()` → 400ms 后杀进程。
   最后一步必须做：高德的 `APSService`（定位辅助）不跟 Activity 走，光 finish 的话 `pidof` 还有值。
   **不要为了给按钮让位去动 `crossLocation`**：往下推会露出顶部深色带；
   `left` 给 56dp 会让高德切成窄条模式，转向箭头直接消失。按钮压住箭头一点，功能优先。
7. **算路会被「小区内部道路」坑，SDK 侧改不了。**
   起点在小区里时，SDK 把它连到最近的小区内部道路、从最近的出口穿出去。
   实测（张掖中央公园）：SDK 从南门穿，那条路实际不通车；高德 App 走北门。
   原因是 App 有**出入口通行属性数据**（哪个门能出车），开放平台（Web API / 导航 SDK）没有。

   以下全部试过，**都没用**（固定起点 `38.945004,100.418646`，逐项对比日志）：
   - `setIntelligentSorting(true)` + `AMapNaviCalRouteOptions` 重载 → 路线一模一样（2671m）
   - `setMultipleRouteNaviMode(true)` → 返回 3 条候选、路径完全相同，**而且导航中会来回切路线**
     （SDK 按实时路况在所有候选间自动切换，地图上路线反复跳）。**已撤，别再开。**
     配套的 `selectRouteId` 遍历也不要写：遍历会把当前路线切到最后一条，和自动切换叠在一起更乱。
   - 策略 0 / 10 / 12、Web API 的 `isindoor` 0/1 → 都是南门那条
   - 起点换成 POI 的 `entr_location` → 中央公园只返回东北角那个门，绕得更远

   唯一有效的是**让起点不在小区内**：同一起点偏西 30m，路线立刻从南门变成白塔街（2966m）。
   真要做只能做「起点吸附到小区外道路」，代价是起点偏移约 100m、开头可能报一次偏航。
   2026-11-01 讨论后决定**先不做**（用户拍板：不动了）。

   ★ 调试技巧：`am start ... --es slat 38.945004 --es slon 100.418646` 可固定算路起点。
   室内 GPS 在小区里能漂 70m，不钉住起点，任何「改配置有没有用」的对比都是假的 —— 踩过。

### 高德 SDK 版本（★ 路线准确性的根因）

**当前用 V11.3.100（2026-09-24），不是 Maven 上的 `navi-3dmap`。**

```
原因：com.amap.api:navi-3dmap 最新只到 10.0.800（2024-08 停更）。
      它的算路数据是 4 年前的 —— 不认识「南门不通车」这类通行限制，
      而高德 App（2026 版数据）认识，两边路线走向完全不同（实测）。
      SDK 版：2671 米走「无名道路」直接向北
      App 版：2.8 公里走「临泽北路」往东绕开
官方新版：导航SDK V11.3.100，已拆成独立包 com.amap.api.navi。
      ★ Maven 上没有（只有 1.9.4 / 2020 年），只能官网下载 ZIP 手动引入。

获取：bash tools/fetch_amap_sdk.sh
      下载到 android/libs/amap/（187MB，**不入 Git**，.gitignore 已排除）。
```

**车行限制**（官方文档给的正解，`AmapNavController.applyCarInfo()`）：

```kotlin
val carInfo = AMapCarInfo().apply {
    setCarType("0")        // 0 = 轿车
    setRestriction(true)   // 规避限行路段
    Prefs.carNumber.takeIf { it.isNotBlank() }?.let { setCarNumber(it) }
}
navi.setCarInfo(carInfo)
```

官方原文：「路径规划时会躲避车辆限行区域和路线」「小客车算路**只考虑车牌因素**」
→ 设置页要填车牌才能规避尾号限行。**每次算路前都要再设一遍**（它影响的是算路时的行为）。

**界面相关（按官方 API Doc，别用已过时的接口）**：

| 过时接口 | 替代 |
|---|---|
| `setNaviNight` / `setAutoNaviViewNightMode` | `setMapStyle(MapStyle.AUTO, "")` |
| `setCustomMapStylePath` | 同上 |

★ 地图样式**必须在 `setOnMapLoadedListener` 回调里设**（官方给这个回调就是「地图加载完成」）。
在 `onCreate` 里直接设会被吃掉 —— 实测地图一直浅色就是这个原因。

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
33. **vosk 识别器建好后必须热身**（2026-11-01）。解码器刚 new 出来时内部状态是空的，
    前几秒解码质量极差 —— 实测首次进入应用后头几秒喊「小度」，日志里听到的是「和」「之」「有不对」
    这种完全不相干的碎片；而点一下语音按钮就好了。
    **原因不是唤醒逻辑有问题**：点按钮会走 `wakeManually()` → `wake.reset()`，
    reset 顺手清了解码状态，之后就正常了。
    修法：`VoskWake` 建好后先喂 50 帧（1 秒）静音热身。
34. **拼音近似匹配必须加声母检查**（2026-11-01）。只允许 1 个编辑误差太松：
    「角度 jiaodu」和「小度 xiaodu」只差首字母 j/x 就命中了 ——
    「角度」「加油」「街道」全都会误唤醒。
    修法：首音节声母必须相同或在近似对列表里
    （`zh/z`、`ch/c`、`sh/s`、`n/l`、`f/h`、`r/l`；**j/x 不在列表**）。
35. **页面切走要用 `pauseNavi()` 不能用 `stopNavi()`**（2026-11-01）。
    `stopNavi()` 会把路线和目的地全清掉 —— 用户反馈的「返回后导航消失」就是这个问题。
    配对的是 `resumeNavi()`，两边都要调。
36. **给 Activity 传目的地别用 Intent 重建**（2026-11-01）。导航页是 singleTop，
    `am start` 会走 `onNewIntent` 重建整页，路线和地图状态全丢。
    单界面架构下直接用已存在的控制器 `controller.navigateTo(dest)` 就行。
37. **vosk 的方法是 `getResult()`/`getPartialResult()`**，Kotlin 属性语法写 `r.result` / `r.partialResult`。
    写成 `r.result()` 会报「Cannot infer type for type parameter 'T'」——
    因为 `result()` 会被解析成 Kotlin 的 `kotlin.Result`。

## 授权与合规

- 个人开发者，**未获得技术服务使用许可**（需企业认证）。Key 仅供短期少量测试，**不能上架分发**。
- 包内有两个 Key：Android Key（绑定包名 `com.navagent`）与 Web 服务 Key（地理编码）。
  Web Key 随包分发是自用妥协，若要做产品必须挪到自建服务端中转。
- `MapsInitializer.updatePrivacyShow/updatePrivacyAgree/initialize` 必须在任何 SDK 调用之前。

## 未完成

- [ ] 声纹门禁（只认主人声音）—— 设计定稿在 `HANDOFF.md` 第 10 节，本轮未实现
- [x] **通用 Web 搜索**（Tavily `web_search` + `web_extract`）——设置页配置 Key；实时/易变信息优先检索，摘要不足时读取来源网页正文。天气已通
- [x] 周边搜索（`AmapSdkGeocoder.searchAroundList()`，SDK 异步回调 + latch 转同步，不报列表只问「最近的中石化 1.8 公里，要去吗」）
- [ ] 回声消除 —— 播报中用唤醒词打断，播报结束后另有可调回声保护期
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

## 端侧语音识别（2026-11-08）

原来的识别只有云端一条路（MiniMax `asr-1.0`），隧道/地库里必哑。现在加了端侧，
用**同一个** `com.navagent.core.Asr` 接口，三档可切：

| 档位 | 返回什么 | 端侧做什么 | 用途 |
|---|---|---|---|
| `cloud` | 云端结果 | 不加载 | 默认，回滚用 |
| `local` | 端侧优先，失败降级云端 | 参与决策 | 正式切主链路 |
| `shadow` | **云端结果** | 后台跑，只记差异 | 灰度收数据 |

设置页 → 应用设置 → 语音 → 「识别引擎」。默认 cloud，新装机和回滚都是旧行为。

### 涉及文件

- `LocalAsr.kt`（新）—— sherpa-onnx `OfflineRecognizer` + SenseVoice-Small int8
- `AsrRouter.kt`（新）—— 三档路由 + 影子对比日志
- `Prefs.asrEngine`（新）—— `asr_engine`，默认 `cloud`
- `VoiceEngine.asr` —— 由 `MinimaxAsr()` 换成 `AsrRouter(context)`

### 模型放哪

`filesDir/models/asr-sensevoice/`，两个文件：

```
model.int8.onnx   239MB
tokens.txt        309KB
```

**没有打包进 APK**（会让包体从 115MB 涨到 345MB）。当前是手动 adb push 进去的，
`ModelStore` 还没接入下载逻辑——后续要补一个首次启动下载。

### ★ 一个必须记住的坑

`OfflineRecognizer(assetManager = ..., config = ...)` 的 **assetManager 必须传 null**，
模型文件在 filesDir 时必须这样。传 `context.assets` 会让 sherpa 走 AssetManager
读文件，和绝对路径对不上，然后：

```
W sherpa-onnx: You are using an absolute path '...tokens.txt', but assetManager is NOT set to null.
F sherpa-onnx: Read binary file: Load '...tokens.txt' failed
```

`F` 是 native abort，**Kotlin 的 try/catch 抓不到**，进程直接静默消失，
logcat 里连 FATAL EXCEPTION 都没有，只有 `appDiedLocked ... reason=null`。
2026-11-08 在 P40 上踩过一次，排查了半小时。

### 实测（华为 P40 / 麒麟 990）

- 模型加载 **2666ms**（在 `asr-warmup` 线程，不阻塞启动）
- 与 Sherpa KWS、vosk 唤醒、声纹并存正常
- 真机听音识别待验（需要人说话）

## 本地大模型库集成（2026-11-08）

MNN-LLM 直接跑在导航 App 进程内，不经过 HTTP、不依赖别的 App。
今天实测 MNN Chat 的 API 有两个死穴（后台不监听、队列卡死后要重启），
库集成把这两个问题从根上消掉了。

### 涉及文件

- `com/alibaba/mnnllm/android/llm/LlmSession.kt`（新）—— 最小 JNI 桥，180 行
- `com/alibaba/mnnllm/android/llm/GenerateProgressListener.kt`（新）—— 回调接口
- `MnnLocalLlm.kt`（新）—— 加载/生成封装，带自检
- `app/src/main/jniLibs/arm64-v8a/` —— `libmnnllmapp.so` + `libMNN.so`（9MB）

**不用写一行 C++。** 官方 MnnLlmChat 的 LlmSession.kt 有 472 行、牵出十几个类；
但 JNI 只认「包名+类名+方法名+参数表」，所以声明同样名字的 native 方法，
就能直接复用官方编译好的 `libmnnllmapp.so`。

### ★ 包名和类名不能改

JNI 符号是 `Java_com_alibaba_mnnllm_android_llm_LlmSession_initNative`，
所以必须放在 `com.alibaba.mnnllm.android.llm` 包里、类名必须是 `LlmSession`。

### 模型

`filesDir/models/mnn-llm/`，5 个文件（直接拷 MNN Chat 下载的那份，不用转换）：

```
config.json        502B    运行参数
llm_config.json    3.2KB   结构 + jinja 模板
llm.mnn            931KB   计算图
llm.mnn.weight     164MB   int4 权重
tokenizer.mtok     1.6MB   词表
```

### 踩过的四个坑（按排查顺序）

1. **configPath 必须是 `config.json` 的文件路径，不是模型目录**
   传目录会报 `Module load failed for config: <dir>`。native 拿它去 readText，
   再从路径反推模型目录。

2. **文本从 `onProgress` 累积，不是从返回的 map 取**
   map 里只有 `prefill_time / decode_len / decode_time` 这些指标，没有正文。
   官方 `GenerateResultProcessor` 也是累积 progress。

3. **回调返回值语义是「要不要停」，不是「要不要继续」**（最坑的一个）
   `onProgress` 返回 `true` = 中止。按直觉写成 `true` 的后果：
   每轮只生成 1 个 token（`decode_len=1`，输出停在「我」），
   而且日志看上去一切正常，很难联想到是这个。

4. **`max_new_tokens` 要写进 init 的配置 JSON**
   模型自带 config.json 没这个字段，用 `JSONObject(...).put("max_new_tokens", 256)`。
   （这条和第 3 条是同一次改的，未单独验证其必要性，但加上无害。）

### 实测（华为 P40 / 麒麟 990）

```
加载       1852ms
首字       161ms
prefill    207 t/s   (33 token / 159ms)
decode     78 t/s    (59 token / 753ms)
整句耗时   950ms
```

和 MNN Chat 那条路的 58–77 t/s 持平。**冷启动第一次只有 22 t/s**，
因为同时在加载 ASR/KWS/声纹三套模型抢 CPU；稳态就是 78 t/s。

### 当前状态

只做到「能加载、能生成、日志可验证」（`mnn_llm_selftest=true` 触发自检）。
**还没接进对话链路**——ChatEngine 现在仍然走 OpenAI 兼容的 HTTP。
下一步要做的：把 `MnnLocalLlm.generate()` 接到 ChatEngine 的 LLM 接口上，
替代 `OpenAiLlm`，并且注意它不支持 Function Calling（导航工具会退化成纯聊天）。

## 对话后端两档路由（2026-11-08）

`cloud`（默认）走 MiniMax，工具全可用；`local` 走进程内 MNN-0.3B，纯聊天。
设置页 → 对话大脑 → 「对话后端」。

### 涉及文件

- `LlmRouter.kt`（新）—— 按 `Prefs.chatEngine` 分发，实现 `core.Llm`
- `MnnLlm.kt`（新）—— 把 `MnnLocalLlm` 适配成 `Llm`，自己拼 Qwen 模板
- `NavApp.kt` —— `llm = LlmRouter(cloud = OpenAiLlm(...), local = MnnLlm(...))`
- `MnnLocalLlm` —— 改成进程内单例（`MnnLocalLlm.get(ctx)`），避免加载两遍

### 为什么不做「有网走云端、没网走本地」的自动切换

两者能力不对等：同一个「导航去国贸」，云端会真的开始导航，本地只能回一句
「好的」。自动切换会让用户不知道自己这句话到底会不会被执行。做成显式开关，
用户自己知道当前是什么模式。

### ★ 本地模式的系统提示词必须砍

云端那份 system prompt 有几千字（工具协议、搜索规则、候选确认流程），
本地一条都用不上，但每一条都要 prefill。实测：

| system 字数 | prompt_len | 首字延迟 |
|---|---|---|
| 700 字 | 462 token | **3.5 秒** |
| 260 字 | 221 token | **1.4 秒** |

3.5 秒的首字延迟，用户会以为死机。所以 `MnnLlm` 只截取开头 260 字
（人设 + 当前时间 + 「回答要简短」），并在末尾追加一句「当前是离线本地模式，
没有联网和导航工具」。历史也只带最近 1 轮。

### 实测（P40，端到端走 ChatEngine）

```
对话后端=local 1597ms 结果=13字
  本地LLM 生成 1597ms（首字 1373ms）
  prefill 221 token / 1367ms = 161 t/s
  decode    9 token /  215ms =  42 t/s
  →「已确认路线，交通信息更新中」
```

冷启动第一句约 2.1 秒（模型加载 1.8 秒 + 首字 1.9 秒），之后稳定在 1.4–1.6 秒。
