# 交接说明（2026-11-08）

这份文档写给接手的人。讲三件事：**这套东西现在是什么、新机器怎么装起来、哪些坑已经踩过了**。

细节都在各文件的注释里，这里只放跨模块的结论。

---

## 一、架构总览

一条语音从唤醒到播报经过四段，每段都有「端侧」和「云端」两条路：

```
唤醒 ──► 听（识别）──► 想（对话）──► 说（播报）
```

| 环节 | 端侧方案 | 云端方案 | 当前默认 |
|---|---|---|---|
| 唤醒 | Sherpa KWS（3.3MB）+ vosk（44MB） | — | 双引擎，可切仅 KWS |
| 识别 | SenseVoice-Small int8（239MB） | MiniMax `asr-1.0` | 端侧优先，失败降级 |
| 对话 | MNN-Pocket-0.3B（166MB） | MiniMax `m3.1-flash` | **auto**：有网云端、断网本地 |
| 播报 | 华为 hiai / 讯飞系统 TTS | MiniMax `speech-02-hd` | 本地 |

### 术语（很重要，容易混）

- **端侧 = 本地**：模型文件在手机上，用 JNI 在**我们 App 进程内**跑。
- **云端**：MiniMax 的 HTTP API。
- **（已废弃）手机上的 MNN Chat App**：它把模型跑在另一个 App 里，通过 `127.0.0.1:8080` 提供 HTTP。2026-11-08 已全部换成进程内方案，原因是它有俩死穴：App 退到后台端口就没了；请求队列一旦超时卡死，必须杀进程重开。

### 关键代码位置

| 文件 | 职责 |
|---|---|
| `AsrRouter.kt` | 识别三档路由（cloud / local / shadow）+ 延迟统计 |
| `LocalAsr.kt` | sherpa-onnx 端侧识别，模型自动发现与下载 |
| `LlmRouter.kt` | 对话三档路由（auto / cloud / local），自动降级的判定 |
| `NetworkState.kt` | 在线判定（用 `NET_CAPABILITY_VALIDATED`，不是 `isConnected`） |
| `MnnLocalLlm.kt` | MNN-LLM 加载与生成（进程内，单例） |
| `MnnLlm.kt` | 适配成 `core.Llm`，拼 Qwen 模板、裁 system prompt |
| `com/alibaba/mnnllm/android/llm/LlmSession.kt` | 最小 JNI 桥，直接复用官方 `libmnnllmapp.so` |
| `NavApp.kt` `interceptNavigation` | **导航命令规则兜底**（本地档没有工具调用，全靠它） |
| `ModelStore.kt` | 模型下载 / 外部目录导入 / 完整性校验 |

---

## 二、新机器怎么装起来

### 1. 装 APK

模型不进 APK（否则包体 171MB → 410MB），需要单独放。

### 2. 放模型（**关键步骤，不做就等于没有端侧能力**）

两个目录，都放在**外部私有目录**下（用数据线或文件管理器都能碰到，不需要 adb / root）：

```
Android/data/com.navagent/files/models/
├── asr-sensevoice/          ← 端侧识别，2 个文件
│   ├── model.int8.onnx      239MB
│   └── tokens.txt           309KB
└── mnn-llm/                 ← 本地对话，5 个文件
    ├── llm.mnn              931KB
    ├── llm.mnn.weight       164MB
    ├── tokenizer.mtok       1.6MB
    ├── config.json          502B
    └── llm_config.json      3.2KB
```

放好后**重启 App**，`ModelStore.ensureLocalAsr()` 会自动把它们搬进私有目录。

> **下载源现状**：自动下载代码是通的，但源当前不可用 —— `hf-mirror.com` 已把 `resolve/` 请求 308 重定向回 `huggingface.co`，国内 SSL 握手直接断；ModelScope 没有该仓库（404）。所以现阶段靠手动放。
>
> SenseVoice 来自 `huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`（国内需代理）。
> MNN 那套可以从 MNN Chat App 下载后从 `/data/local/tmp/mnn_models/` 拷出。

**模型可选**：`LocalAsr.modelFile()` 会自动发现目录里任何 sherpa-onnx 兼容的 `.onnx`（名字带 int8 的优先），换 SenseVoice 其它精度、paraformer、whisper 都不用改代码。

### 3. 配置

设置页 → 「听：云端识别（备用）」填语音 Key；「对话大脑」填对话 Key。两套独立，改坏一个不影响另一个。

### 4. 权限

安装后必须授予：**定位**（导航必需）、**麦克风**。用 adb 装的话记得补：

```bash
adb shell pm grant com.navagent android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.navagent android.permission.RECORD_AUDIO
```

漏了定位会出现「服务起不来被系统杀」——日志里是 `Bringing down service while still waiting for start foreground`，很难联想到是权限问题。

### 5. 验证

看 `nav.log` 出现这几行就算装好了：

```
本地ASR: 就绪，加载 XXXXms，model.int8.onnx
Sherpa KWS 就绪 keywords=21
本地LLM: 就绪，加载 XXXXms
```

---

## 三、关键设计决策（别轻易改）

### 1. 导航命令走规则，不走模型

本地 0.3B 没有 Function Calling，让它决定"要不要导航、去哪"等于赌运气。规则匹配几十毫秒、行为可预测。

代价是**召回率**：说法超出那几种句式（「我想去趟西站」）就变聊天。要补就往 `interceptNavigation` 的正则里加，不用动模型。

云端档仍走模型的工具调用（工具全可用），两套并存互不干扰。

### 2. 对话后端默认 auto，但切换必须让用户看见

早先不做自动切换，是因为切到本地就等于失去工具调用，用户会莫名其妙发现"导航不好使了"。导航挪到规则层之后这个代价才消失 —— **这是 auto 档能存在的前提**。

真的换了后端会弹一次 Toast，设置页那行显示当前实际走哪条。**不要改成静默切换**。

### 3. 唤醒声纹默认宽松

`requestWakeOwnerConfirmation` 里：声纹没注册 / 模型没就绪 / 音频不足 0.6 秒 → **一律放行**。

原则：宁可让旁人喊醒一次（他下不了命令，识别阶段还有声纹拦截），也不能让主人喊不醒把设备变成砖。

### 4. 半双工（播报期间不拾音）

没有可靠的 AEC，播报时 VAD/ASR 全部暂停，只留关键词检测。所以**用户不能用"嗯？""等等"打断**，必须喊唤醒词或停止口令。

「全双工」开关是实验性的，实测会把自己的 TTS 录进去造成自问自答，默认关。

### 5. 唤醒应答只在"单纯唤醒"时说

打断场景（喊「果果」让它闭嘴）只嘀一声，不出人声 —— 否则等于又插一句，还占住播报通道把紧接着的指令挡在门外。

---

## 四、踩过的坑（按排查代价排序）

### 1. `OfflineRecognizer` 的 `assetManager` 必须传 null ★★★

```kotlin
OfflineRecognizer(assetManager = null, config = config)   // 必须 null
```

传 `context.assets` 会让 sherpa 走 AssetManager 读文件，和 filesDir 绝对路径对不上，然后 **native abort**。Kotlin 的 try/catch 抓不到，进程静默消失，logcat 里连 FATAL EXCEPTION 都没有，只有 `appDiedLocked ... reason=null`。

### 2. 播报队列卡死 90 秒 ★★★

`VoiceGuide.speak(NAV)` 先 `tts.stop()` 再 `drain()`，而 stop 是异步的 —— 那一刻 `isSpeaking` 仍为 true，`drain()` 直接 return 且**没有重试**。队列一直压着，直到下次唤醒才顺带播出来。

修复：`drain()` 里撞上 `isSpeaking` 时自旋重试 12 次 × 250ms。

### 3. 待命唤醒没有检测（kws-only 那次） ★★

KWS 原来只在「播报中」分支喂音频（它是为打断服务的），待命唤醒一直由 vosk 负责。把 vosk 关掉后**待命时没有任何模型在听**，表现为"喊破嗓子叫不醒"且无报错。

教训：**切任何一路检测前，先确认目标状态下真的有人在听**。

### 4. TTS 对「」直角引号处理不一致 ★★

播报文案里带「」会被吞字 / 夹怪停顿（用户反馈"点…后面少几个字"）。**所有要念出来的字符串都不要用直角引号**。

（给模型看的文本里可以用，不影响。）

### 5. `setShadowLayer` 在硬件加速下模糊被裁剪 ★★

想做文字光晕，必须切软件层：`setLayerType(View.LAYER_TYPE_SOFTWARE, null)`，否则只有一圈硬边。

### 6. 高德 POI 的 `cityName` 是查询偏置城市 ★★

带城市偏置搜索时，返回的 `cityName` 是**你查的那个城市**，不是 POI 真实所在地。真实地址只在 `snippet` / `formatted` 里。外地同名判定必须从地址全文抽省市区。

### 7. 高德 `snippet` 本身就是完整地址 ★

再拼一遍「市+区」会得到「张掖市甘州区甘肃省张掖市甘州区…」。拼接前先 `contains` 判断。

### 8. 模型文件残缺会导致 native abort ★

下到一半的模型拿去 init 会直接崩。`ModelStore` 用体积阈值判断完整性（onnx > 200MB、tokens > 100KB）。

---

## 五、已知限制

| 限制 | 说明 |
|---|---|
| 半双工 | 播报中不能用自然语言打断，必须喊唤醒词/停止口令 |
| 0.3B 能力上限 | 短输入会复读（已用 repetition_penalty 1.15 缓解），长句可能答非所问 |
| 导航规则召回有限 | 句式外的说法会变成聊天 |
| 停止导航后的"继续" | 依赖 `lastStoppedDestination` 或导航历史首条；重启 App 后只剩历史 |
| 模型自动下载 | 代码就绪但源不通，当前靠手动放 |
| 端侧对话无工具 | 本地档不能搜索/天气，只有规则导航 |
| 冷启动 | 识别模型加载约 2–5 秒，这段时间识别走云端 |

---

## 六、调试

日志在 `/sdcard/Android/data/com.navagent/files/nav.log`（设置页 → 诊断 → 查看日志）。

常用关键字：

```bash
# 识别
grep -E "本地ASR|ASR 无结果" nav.log
# 对话后端走了哪条
grep -E "对话后端=" nav.log
# 导航规则有没有命中
grep -E "导航兜底|离线候选|停止导航（规则|继续导航" nav.log
# 唤醒
grep -E "KWS 命中|声纹确认" nav.log
# 播报卡顿
grep -E "播报入队|开始播报|播报引擎还在收尾" nav.log
# 崩溃排查（进程静默消失时看这个）
adb logcat -d | grep -E "sherpa-onnx|appDiedLocked|FATAL"
```

注意：`Dbg.log` 写文件是异步的，崩溃瞬间的日志可能没落盘，`logcat` 更可靠。
