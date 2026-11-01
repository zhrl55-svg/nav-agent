# HiCar App 逻辑分析（华为 P40 Pro 实机逆向）

> 分析对象：设备上安装的 `com.huawei.hicar`（HiCar 手机端），版本 `14.2.0.400`（versionCode 140200400）。
> 分析方式：adb 拉取 base.apk + split_deviceai.apk → jadx 1.5.1 反编译 → 读源码。
> 产物目录：`hicar-analysis/`（jadx_base / jadx_da 源码、APK 原件）。

---

## 0. 一句话结论

HiCar 手机端**不是一个导航 App**，而是一个「车机投屏 + 端侧语音 AI」的中枢：
它把手机上的语音助手能力（唤醒、ASR、NLU、TTS）打包成服务，投影到车机屏幕，
再通过 URL Scheme 把导航指令甩给高德/百度/搜狗地图执行。

**对你的 nav-agent 最有参考价值的三点**：

1. **导航与语音彻底解耦**：HiCar 不自己算路，只解析出「目的地坐标」，然后用 URI 交给地图 App。
   地图 App 是高德、百度、搜狗、腾讯，四套 URL 协议都兼容。
2. **POI 搜索用高德原生 SDK**（`PoiSearch`），不是 Web API —— 正好避开你在 nav-agent 里
   踩过的 `com.amap.api:search` 与 `navi-3dmap` 重复类坑。
3. **语音链路是「唤醒词 → HIAI 引擎 → ASR → 端侧 NLU 模型 → 意图分发 → TTS」**，
   全离线端侧模型（ASR 模型 + NLP 模型分别拷进私有目录），不依赖云端 LLM。

---

## 1. 包结构

| 包名 | 版本 | 作用 |
|---|---|---|
| `com.huawei.hicar` (base) | 14.2.0.400 | 投屏、卡片、设置、路由、HIAI 语音 SDK 封装、地图协议 |
| `com.huawei.hicar` (split `deviceai`) | 14.0.2.002 | **端侧 AI 引擎**（OnDeviceAI）：ASR/NLP/TTS/对话管理/意图处理 |

关键点：端侧 AI 是**独立 split**，按需下发。base 里只是壳和桥接。

### 运行时实际跑的东西

```
com.huawei.hicar/.mobile.notification.DrivingSceneNotifService   前台服务（驾驶场景通知）
com.huawei.hicar/com.amap.api.location.APSService                高德定位服务（HiCar 自己内置）
com.huawei.hicar/.launcher.card.RemoteCardService                车机远程卡片
```

---

## 2. 语音链路（端侧 AI）

### 2.1 入口

```
OnDeviceAiEngine.init()          初始化 ConfigManager / ProcessManager / 车机 Display
      ↓
OnDeviceAiEngine.start()          启动 CoreService（前台服务）
      ↓
CoreService.onCreate()
  ├─ initNlpEngine()   → dialogmanager.a.c  (NlpInvoker)
  ├─ initAsrEngine()   → EngineManager.getAsrEngine()
  ├─ initTts()         → EngineManager.getTtsEngine()  setLanguage(Locale.CHINA)
  └─ ContactListManager  通讯录同步（支持"打电话给XX"）
```

`CoreService` 同时是 `HwSpeechRecognitionInterface.RealtimeSpeechResultListener`，
即 ASR 回调直接进 NLP invoker，中间不经过 Activity。

### 2.2 ASR → NLU → 意图

```java
// CoreService.onResult
STATUS_SUCCESS:
    if (text.isEmpty() || text.length() > 50) return;   // 结果长度上限 50
    handleAsrResult(text, id)
        → mNlpInvoker.a(new c.C0046c(text, id, true))      // true = final
INTERMEDIATE:
    processAsrPartialResultQueue(text, id)
        → mNlpInvoker.a(new c.C0046c(text, id, false))     // false = partial
```

NLP invoker（`dialogmanager.a.c`）是端侧模型调度器，包内 `dialogmanager` 有 358 个类，
含 `nlu/`（NluRegex）、`interperter/`、`action/` 等子模块。

### 2.3 意图执行与播报

NLP 结果通过 `NlpProcessResultListener` 回调到 CoreService：

| 回调 | 行为 |
|---|---|
| `onSuccess(success, text, needTts, listener)` | 显示文字 → TTS 播报 → 1500ms 后检查页面是否切换 |
| `onFail(text, ...)` | 显示文字 → TTS 播报错误原因 |
| `onNeedInfo(text, ...)` | 需要追问时的播报 |
| `onNeedSelect(text, list)` | 需要用户从候选中选 |
| `startAsr()` / `stopAsr()` | 连续对话中 NLP 反向控制 ASR 开关 |
| `onStopTts()` | 打断播报 |
| `onVadDelay(boolean)` | 延长后端 VAD（`extendBackendVad(20)`） |

### 2.4 TTS 细节

```java
private void textToSpeech(String str, boolean needTts, listener, boolean interrupt) {
    if (interrupt) { cancelTts(); textToSpeechInternal(...); }
    else if (mIsSpeaking || mTtsEngine.isSpeaking()) {
        waitTtsFunction = () -> textToSpeechInternal(...);   // 排队，等当前这句念完
    } else textToSpeechInternal(...);
}
```

**TTS 队列 + 可打断**：`cancelTts()` 会先 `recordLatestStatusTts()` 记住被打断时的状态，
播报结束后 `transferToLatestStatus()` 恢复。这正是你 nav-agent 里 `VoiceGuide` 的
`NAV > USER > CHAT` 优先级 + barge-in 的同款设计，华为叫它「状态机」。

### 2.5 唤醒

唤醒不在 HiCar 自己实现，而是委托给系统：

```
com.huawei.wakeup.services.WakeupService      （系统级，独立包）
com.huawei.hiai.wakeup.permission.USE_WAKEUP
com.huawei.ziri.service.IWakeupInterfacePool  （紫研/ziri 语音框架）
com.huawei.hiai.platform.framework.wakeup.WakeupMgr
```

HiCar 只做**模式编排**：

```java
enum WakeupMode { CAR_MODE, PHONE_MODE }

enum BdVoiceConstant$VoiceWakeUpMode {
    VOICE_PHONE_WAKE_UP(0),   CLICK_WAKE_UP(1),     VOICE_CAR_WAKE_UP(2),
    PUSH_WAKE_UP(3),          CLICK_BLANK_WAKE_UP(4), CLICK_APP_WAKE_UP(5),
    QUIT_NATURAL_WAKE_UP(6),  QUIT_VOICE_WAKE_UP(7),   QUIT_RADIO_WAKE_UP(8)
}
```

还有「免唤醒」（free wakeup）机制：
`HiaiAsrFreeWakeUpAbilityProxy` / `HiaiNluFreeWakeUpAbilityProxy` / `CoordinationFreeWakeupAbilityProxy`，
支持自定义唤醒词上传云端（`uploadWakeupWords`）和本地录音注册。

关键日志印证了双通道设计：

```
freewakeup_coordination_recognize_decision_strategy
device forbidden wakeup for integration mode
device is in whitelist, ignore wakeup words.
none of recognizing, fullDuplex and free wakeup, writeAudio should not be called
```

注意最后一条：**fullDuplex（全双工）** 是 HiCar 声明的能力之一，
这正是你 nav-agent 尚未解决的问题（半双工导致播报期间喊唤醒词听不见）。

---

## 3. 导航逻辑（最值得抄的部分）

### 3.1 意图分发

`NavigationIntentProcessor.handleIntent()`：

```java
switch (str) {
    case "POISearch":        → launchSearch(bean)          POI 搜索
    case "POISelect":        → handleSelectCommand(bean)    列表翻页/选择
    case "StartNavigation":  → doNavigation(bean)          开始导航
    case "MapZoom":          ┐
    case "TrafficStatus":    ├→ doControlNavigation(str, bean)  地图控制
    case "NaviExit":         │
    case "MapOverview":      │
    case "NavigationVoice":  ┘
    case "GoBack":           → doGoBackNavigation()
    case "PreferenceUpdate": → doRouteNavigation(bean)     路线偏好
    case "MapNavigation":    → searchMap(bean)             只搜图不导航
    case "CancelPoiSearch":  → cancelPoiSearch(bean)
}
```

进入前有**两道前置检查**：

```java
if (!isGpsProviderEnabled()) return 1004;      // 系统定位开关
if (!checkNavigatingPermission()) return 1001; // ACCESS_COARSE_LOCATION
```

### 3.2 导航指令怎么发给地图 App

**HiCar 自己不算路**，只发 URI：

```java
// MapConstant
CAR_AMAP_BASE_URL  = "amapurihicar://hicarmap"
CAR_BAIDU_BASE_URL = "baidumap://hicarmap"
CAR_SOGOU_BASE_URL = "sogoumap://hicarmap"
CAR_TENCENT_BASE_URL = "tencentmap://hicarmap"

// NavigationProcessHelper.doNaving
sb = String.format("%s/navi?src=com.huawei.hicar&coord_type=", baseUrl)
sb.append("gcj02")
sb.append("&location=")
sb.append(lat).append(",").append(lng)     // 目的地
// 途经点：&viaPoints={"viaPoints":[{"name":..,"lng":..,"lat":..}]}
intent.setData(Uri.parse(sb.toString()))
intent.setFlags(335544320)                  // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
IntentExEx.addHwFlags(intent, 16)          // 华为私有 flag：跨屏启动
startActivityForResultSafely(carDisplayCtx, intent)   // 投到车机屏
```

控制指令统一走：

```java
MAP_INSTRUCTION_URL_FORMAT = "%s/navi/instruction?qt=%s&version=1.1&src=com.huawei.hicar"
qt 取值：rg_zoom_in / rg_zoom_out / rg_open_navitrafficstatus / rg_close_navitrafficstatus
         rg_open_navivoice / rg_close_navivoice / rg_change_navipreference
         quit_navi / rg_open_lineoverview（百度）/ rg_overview_in（搜狗）
```

**地图切换策略**：百度有高低德两套 URI（`baidumap://hicarmap` vs `baidumap://hdhicarmap`），
并对百度地图有版本黑名单（`BAIDU_MAP_BLACKLIST_MINI_VERSION = "15.4.0"`）。

### 3.3 POI 搜索

`OnDeviceAiLocationListener`：

```java
// 先定位
AMapLocationClientOption: Battery_Saving + OnceLocation + HttpTimeOut 10s
→ 拿到 city / lat / lng

// 再搜 POI
PoiSearch.Query query = new PoiSearch.Query(placeName, "", city);  // city 作城市限定
query.setPageSize(10);
query.setPageNum(1);

// 附近搜索才加 SearchBound 半径限制
if (placeType 在 NEARBY_ADDRESS_SET: 美食城/加油站/停车场/超市/厕所/服务区/咖啡厅)
    poiSearch.setBound(new SearchBound(new LatLonPoint(lat, lng), 0));  // 半径 0 = 不限
```

**这里没有 citylimit 开关** —— HiCar 靠的是
「placeName 里包含 city 字符串时，改用 city="" 的全城搜索」的启发式：

```java
if (!isEmpty(city) && !isEmpty(placeName) && placeName.contains(city))
    query = new PoiSearch.Query(placeName, "", null);   // 不限定城市
else
    query = new PoiSearch.Query(placeName, "", city);   // 限定城市
```

这和 nav-agent 里你踩过的「外地地名被硬塞给本市同名 POI」是同一个坑，
HiCar 的解法比你的 `[省市县旗镇乡]` 正则更简单但也更粗糙。

### 3.4 候选列表

```java
// 搜索成功
saveSearchedAddress(list);                       // 存到 sSearchedAddress（按 detailAddress 索引）
if (list.size() > poiCountCondition) showPlacesCard(list);   // 超过阈值才弹卡片
notifyQueryAddress(list, 1020);                  // 回给 NLP 层
```

列表项包含：名称、snippet（地址）、**距离**（`AMapUtils.calculateLineDistance`）、
经纬度。「去这家」通过 `sSearchedAddress` 反查 —— 这和你 nav-agent 的 `lastNearby` TTL 机制同源。

---

## 4. 投屏与卡片架构

HiCar 用 **SoftBus** 做手机↔车机通道：

```
com.huawei.hicar.carhop.softbus.AbstractSessionConnect
com.huawei.hicar.carhop.manager.CarHopSessionManager
com.huawei.hicar.carhop.manager.DeviceProfileMgr
com.huawei.hicar.ICarHopSessionCallback
com.huawei.hicar.services.HiCarHopService
```

指令下发通道（`cmd`）：
`com.huawei.hicar.carhop.bean.CmdCode` / `CmdRequest` / `SessionDataBean`

卡片体系：
```
com.huawei.hicar.launcher.card.RemoteCardService
com.huawei.hicar.externalapps.media / gallery / travel / weather
com.huawei.hicar.client.control.nss.NssWebViewActivity
```

还有 **无障碍服务** 做跨 App 自动化：

```
com.huawei.hicar.mobile.accessibilityservice.HiCarAccessibilityService
com.huawei.perception.capbility.supplier.accessbility.PerceptionAccessibilityService
```

这让 HiCar 能读屏幕并模拟点击 —— 「打开音乐 App 播放某歌」这类跨 App 操作就是这么做的。

---

## 5. 其他值得注意的点

### 5.1 集成了滴滴

```
com.didi.drouter.store.LoadProvider    （Didi Router，跨 App 路由）
com.huawei.hicar.carhop.seekcar.CarParkingProvider   （找车/停车）
com.huawei.hicar.client.control.park.ParkService
```

### 5.2 隐私与权限

- 自定义权限体系：`HICAR_PERMISSION` / `HICAR_PERMISSION_SYSTEM` / `HICAR_PERMISSION_CONNECT`
- 华为签名级权限：`huawei.android.permission.HW_SIGNATURE_OR_SYSTEM`
- 独立权限组：`com.huawei.hicar.HICAR_NOTIFICATION_PERMISSION`
- 内容传感器：`com.huawei.ondeviceai.contentsensor`（60 个类），用于感知当前页面上下文

### 5.3 调试与数据

```java
RuntimeDataManager.getInstance().uploadToCloud("[engine init] " + getStamp() + ", uuid:" + uuid + ", hwRelease, release");
```

**引擎初始化/销毁事件带 uuid 上报云端**。另外 `RuntimeDataManager.save()` 会把
执行结果（SUCCESS/FAIL + 文本）落到本地。

---

## 6. 与 nav-agent 的对照

| 维度 | HiCar | 你的 nav-agent |
|---|---|---|
| 语音引擎 | 系统级 HIAI + ziri 唤醒（离线模型） | vosk 小模型 + MiniMax 云端 ASR |
| 理解 | 端侧 NLP 模型（358 类 dialogmanager） | MiniMax LLM + Function Calling |
| 联网 | 弱（主要靠云端 HIAI） | Tavily web_search / web_extract |
| 导航计算 | **不做**，URI 甩给高德/百度/搜狗 | 高德 navi-3dmap SDK 自算 |
| POI 搜索 | 高德原生 `PoiSearch` SDK | 高德 Web API `/v3/place/around` |
| 城市限定 | 启发式：地名含城市名就放开 | `[省市县旗镇乡]` 正则动态开关 citylimit |
| 全双工 | 声明支持 fullDuplex | 半双工（播报期间停拾音） |
| TTS | 队列 + 打断 + 状态恢复 | `VoiceGuide` NAV>USER>CHAT + barge-in |
| 候选选择 | 卡片列表 + `sSearchedAddress` 反查 | 编号列表 + `lastNearby`（2 分钟 TTL） |
| 结果反馈 | 错误码分级（1001 权限 / 1004 GPS / 1008 无地图 / 1010 失败） | 统一字符串回给模型 |

### 可以直接借鉴的三点

1. **URI 协议层**：如果 nav-agent 想把导航交给高德 App（而非自绘 HUD），
   `amapurihicar://hicarmap/navi?src=...&coord_type=gcj02&location=lat,lng` 是现成的协议。
2. **POI 距离计算**：`AMapUtils.calculateLineDistance` 拿直线距离，
   比 nav-agent 现在让高德返回距离更省事。
3. **错误码分级**：HiCar 的 1001/1004/1008/1010 让上层能区分
   「没权限 / 没 GPS / 没装地图 / 执行失败」，nav-agent 现在只有一句自然语言。

---

## 7. 文件索引

```
hicar-analysis/
├── hicar-base.apk          base 包原件（35MB）
├── hicar-deviceai.apk      端侧 AI split 原件（119MB）
├── base-manifest.txt       aapt2 导出的 manifest 树
├── jadx_base/sources/      base 反编译源码（14081 个 .java）
├── jadx_da/sources/        deviceai 反编译源码（2615 个 .java）
└── jadx/                   jadx 1.5.1 工具
```

### 重点源码路径

```
jadx_da/sources/com/huawei/ondeviceai/
├── CoreService.java                       ASR/TTS/NLP 编排中枢
├── EngineManager.java                     ASR/TTS 引擎工厂
├── hicar/OnDeviceAiEngine.java            端侧 AI 生命周期
├── hicar/HicarEngine.java                 与 HiCar 主体的桥
├── hicar/intent/navigation/
│   ├── NavigationIntentProcessor.java     导航意图分发
│   ├── NavigationProcessHelper.java       导航执行 + 地图 URI 拼装
│   └── OnDeviceAiLocationListener.java    高德定位 + POI 搜索
├── hicar/common/constant/MapConstant.java  四家地图的 URL 协议
└── dialogmanager/                         端侧 NLP 模型调度（358 类）

jadx_base/sources/com/huawei/hicar/
├── base/entity/WakeupMode.java            CAR_MODE / PHONE_MODE
├── base/constant/BdVoiceConstant$VoiceWakeUpMode.java   9 种唤醒来源
├── deviceai/state/DeviceAiStateManager.java 语音状态机
├── carhop/softbus/                        SoftBus 投屏通道
└── mobile/accessibilityservice/           无障碍自动化
```
