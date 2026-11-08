package com.navagent.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.navagent.core.BargeHit
import com.navagent.core.BargeIn
import com.navagent.core.BargeKind
import com.navagent.core.BargeMode
import com.navagent.core.VoiceGuide
import java.io.File
import java.util.concurrent.Executors

/**
 * 语音会话状态机 —— 整套语音能力的唯一入口。
 *
 *   ASLEEP ──唤醒词 / 点按钮──> LISTENING
 *   LISTENING ──VAD 切出一句──> THINKING（识别 + 处理）
 *   THINKING ──处理完──> LISTENING
 *   LISTENING ──静默 5s──> ASLEEP
 *   任何状态 ──TTS 在播──> 暂停拾音（半双工，没有 AEC 只能这么办）
 *
 * ★ 两个"静默"是两件事：
 *   - 800ms 断句阈值（在 EnergyVad 里）：判断这句话说完了
 *   - 5000ms 会话休眠（在这里）：判断没人再说话了
 *
 * 放在前台服务里跑，Activity 只是它的观察者。因为麦克风只能有一个持有者，
 * 而导航页和聊天页都要用。
 */
class VoiceEngine(private val context: Context) {

    enum class State { ASLEEP, LISTENING, THINKING }

    interface Listener {
        fun onState(state: State, hint: String)
        /** ASR 出结果了，交给上层路由 */
        fun onUtterance(text: String)
        /** 一句提示，比如"模型下载中" */
        fun onNotice(text: String)
    }

    @Volatile private var listener: Listener? = null

    @Volatile
    var state: State = State.ASLEEP
        private set

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 由外部注入：TTS 是否真的在播。播报期间只运行打断检测。 */
    @Volatile
    var isSpeakingProvider: () -> Boolean = { false }

    /**
     * 由外部注入：是否处于播报结束后的回声保护期。
     *
     * 必须和 [isSpeakingProvider] 分开：播报中要允许用户打断，
     * 只有真正播完之后才停止普通拾音。
     */
    @Volatile
    var isEchoGuardActiveProvider: () -> Boolean = { false }

    /**
     * 声纹门禁。宿主注入；返回 null 表示模型没准备好或没开 —— 那种情况一律放行。
     */
    @Volatile
    var voiceprintProvider: (() -> VoiceprintGate?)? = null

    /**
     * 当前正在播报的原文。宿主注入（VoiceGuide.currentText）。
     *
     * 播报期间麦克风会把自己的 TTS 收进来。如果播报内容里恰好出现唤醒词
     * （助手回答里带了一句「小猪」），唤醒检测会把自己当成用户，播报自己把自己打断。
     * 有这段原文就能判断：候选词是不是正被播出来。
     */
    @Volatile
    var spokenTextProvider: () -> String? = { null }

    /**
     * 最近播报过的文本（含已播完的），由宿主注入 `voice.recentSpokenText()`。
     *
     * ★ 和 [spokenTextProvider] 分开：后者读的是「当前正在播的那句」，
     *   播完 / 打断就变 null；而回声往往在播完之后才被 VAD 切出来，
     *   必须用带时间窗的历史文本才能认出来。
     */
    @Volatile
    var recentSpokenProvider: () -> String? = { null }

    /**
     * 打断播报时回调。宿主注入 `app.bargeInChat()`（停闲聊 + 让本轮生成静音）。
     *
     * @return 真的停掉了东西才返回 true。导航播报在播时返回 false ——
     *   界面就不该说"已打断播报"，否则用户听着导航还在念、助手却说打断了。
     */
    @Volatile
    var onBargeIn: (() -> Boolean)? = null

    /**
     * 当前在播的是不是导航播报。宿主注入 `voice.speakingPriority == NAV`。
     *
     * ★ 2026-11-07：导航播报**永不打断**。开车时「前方 200 米左转」被一句
     *   「小猪」掐掉是安全问题，所以播报期间按这个标志选 [BargeMode.NONE]。
     */
    @Volatile
    var navSpeakingProvider: () -> Boolean = { false }

    /**
     * 现在是不是「选择列表」窗口（助手刚念过 1、2、3 让用户挑）。
     *
     * 窗口期内才认「第2个」「最后一个」这类序号打断。
     */
    @Volatile
    var choiceModeProvider: () -> Boolean = { false }

    /**
     * 本地命中睡眠口令时回调。宿主注入「播一句『好』然后休眠」。
     *
     * ★ 为什么要在本地拦，不交给模型判断（照 D:/AI 的 _sleep_match 移植）：
     *   原来「你休息吧」是当作普通一句话交给 LLM，由模型决定调 sleep 工具。
     *   代价是用户说完要等 1~2 秒（LLM 首字延迟 + 工具调用往返）才听到回应，
     *   而且模型偶尔不调工具，用户就会觉得"说了没反应"。
     *
     *   睡眠口令是固定短语、语义明确、不需要推理，本地正则判断既快又不会漏。
     *   命中后不经过 LLM，直接播报 + 休眠。
     *
     *   为 null 时不拦截，退回原来的 LLM 工具路径 —— 保证即使宿主没注入也能用。
     */
    @Volatile
    var onSleepCommand: (() -> Unit)? = null

    /**
     * 云端 ASR 真的失败时由宿主播一句提示（「网络不太好，请稍后再试一次」）。
     *
     * ★ 2026-11-07：原来只写一行 Dbg 日志，用户在隧道里喊完一句什么反馈
     *   都没有，只会觉得「坏了」。本地 Vosk 兜底真机实测准确率不够
     *   （「导航去水勘院」被转成「去 是 可以」），已整个删掉，只保留明确提示。
     *   为 null 时保持旧行为（设置页/单测可以不注入）。
     */
    @Volatile
    var onAsrFailure: (() -> Unit)? = null

    private var capture: AudioCapture? = null
    private var wake: com.navagent.core.WakeWord? = null
    /**
     * Vosk Recognizer 不是线程安全的。
     *
     * 真机曾出现 [feed] 在 audio-capture 线程运行，同时主线程调用 [reset]，
     * libvosk.so 内部状态被并发修改后直接 SIGSEGV，进程崩溃并退回桌面。
     * feed / reset / shutdown / ready 检查必须全部经过同一把锁。
     */
    private val wakeLock = Any()
    private var vad: EnergyVad? = null
    /**
     * 识别引擎路由。云端（MiniMax）/ 端侧（sherpa-onnx + SenseVoice）/ 影子三档，
     * 档位在设置页切，默认 cloud 保持旧行为。详见 [AsrRouter]。
     */
    private val asr = AsrRouter(context)

    /** 进程内本地大模型（MNN-LLM 库集成）。单例，和 ChatEngine 共用同一份模型 */
    private val mnnLlm = MnnLocalLlm.get(context)
    private val noiseGate = NoiseGate()
    private val wakeAudio = RecentAudioBuffer(AudioCapture.SAMPLE_RATE * 5 / 2)
    private val huaweiPreprocess = HuaweiAudioPreprocess(context)
    private val sherpaBargeIn = SherpaBargeIn(context)

    /** 播报中要盯的关键词：唤醒词 + 停止口令 + 序号词，全部来自设置 */
    private fun bargeKeywordList(): List<String> =
        (Prefs.wakeWordList() + Prefs.bargeStopWordList() + CHOICE_KEYWORDS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-worker").apply { isDaemon = true }
    }

    @Volatile private var lastActivityMs = 0L
    @Volatile private var busyRecognizing = false
    @Volatile private var speakingNow = false

    /**
     * 实验性全双工：一次播报期间最多放行一次「播报重叠句」上传 ASR。
     * 外放 + 无可靠 AEC 时，TTS 会被 VAD 切成很多句，不封顶会刷爆云端请求。
     */
    @Volatile private var fullDuplexTried = false

    /**
     * 当前 VAD 段里是否混进过「播报期间」的帧。
     *
     * ★ 2026-11-07 22:50：全双工会在播报期间额外喂 VAD，一整段 TTS 被攒成
     *   一句，但**切句往往发生在播报结束后**（真机日志：播完 2 秒切出 10.34s），
     *   只看切句瞬间的 speakingNow 会把它当正常用户语音放行。用这个标志
     *   记录「这段的出身」，切句时一并交给护栏。
     */
    @Volatile private var vadSawPlayback = false

    /** 失败提示的节流：连续几句都失败时别一直念「网络不好」 */
    @Volatile private var lastAsrFailNoticeMs = 0L

    /**
     * 最近一次「确认正在播报」的时刻。
     *
     * 用来判断一段识别音频有没有和播报重叠：重叠的音频**不能**拿去
     * 更新声纹模板（会把 TTS 的声音揉进主人模板，见 VoiceprintGate.allow）。
     */
    @Volatile private var lastPlaybackAtMs = 0L
    @Volatile private var wakeInterruptPending = false
    @Volatile private var wakeConfirmPending = false
    @Volatile private var skipEchoGuard = false
    @Volatile private var ownerPollPending = false
    private var ownerPollFrames = 0
    private var ownerPollLogCount = 0
    private val ownerStreak = java.util.concurrent.atomic.AtomicInteger(0)
    private var asleepFrames = 0L

    // ------------------------------------------------------------ 唤醒引擎对照实验
    //
    // 2026-11-08：SenseVoice 接进识别后，有人会问「vosk 还需要吗」。
    // 事实是每帧音频同时喂给 vosk 和 Sherpa KWS 两套模型，功能重叠。
    // 这里加一个开关和计数，用真机数据回答，而不是靠猜。

    /** 只跑 KWS，不加载 vosk */
    private fun kwsOnly(): Boolean = Prefs.wakeEngine == "kws-only"

    /**
     * KWS 是否需要常开。
     *
     * ★ 注意：kws-only 模式下 KWS 承担唤醒，跟「打断」开关无关，必须强制开。
     *   否则用户把「播报中可打断」关掉，就彻底叫不醒了 —— 这是个静默的坑。
     */
    private fun kwsActive(): Boolean = Prefs.bargeIn || kwsOnly()

    private var kwsWakeHits = 0
    private var voskWakeHits = 0

    /**
     * 导航播报期间命中的打断词，等播完再执行（用户选择的方案 1）。
     *
     * 为什么不能在播报中直接执行：导航播报被打断本身就是这次要修的问题。
     * 为什么不能直接丢掉：用户喊了「先停下」却毫无反应，比打断更糟。
     */
    @Volatile private var deferredHit: BargeHit? = null
    @Volatile private var deferredAtMs = 0L

    /** 上一次命中的签名，用来给同一句话去抖（窗口会保留 4 秒，否则连续几十帧都命中） */
    @Volatile private var lastHitKey: String = ""
    @Volatile private var lastHitAtMs: Long = 0L


    /**
     * 本次播报期间的唤醒词表。
     *
     * `Prefs.wakeWordList()` 每次都要读 prefs + 切分字符串，而打断判定是
     * 每 20ms 一帧跑的，全放在采集线程上不合适。播报开始时取一次就够 ——
     * 正在播报这一小段时间里改唤醒词的概率约等于零。
     */
    @Volatile private var bargeWakeWords: List<String> = emptyList()

    /** 本次播报期间的停止口令（来自设置，播报开始时取一次） */
    @Volatile private var bargeStopWords: List<String> = emptyList()

    private val tone by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
    }

    fun setListener(l: Listener?) {
        listener = l
        l?.onState(state, hintFor(state))
    }

    /** 启动采集并加载唤醒模型。阻塞在后台线程，重复调用安全。 */
    fun start() {
        if (capture?.isRunning == true) return
        lastActivityMs = System.currentTimeMillis()

        worker.execute {
            ensureWake()
            // 临时自检：设置里打开 mnn_llm_selftest 时跑一句，验证库集成链路通不通。
            // 验证通过后删掉这段，改成正常的对话后端。
            if (Prefs.mnnLlmSelfTest) mnnLlm.selfTest()
            // 播报中的关键词词表：唤醒词 + 停止口令 + 序号词，全部来自设置
            if (kwsActive()) sherpaBargeIn.load(bargeKeywordList())
            ready = true

            if (vad == null) {
                val v = EnergyVad(
                    onSpeechStart = { lastActivityMs = System.currentTimeMillis() },
                    onSegment = { pcm -> onSegment(pcm) },
                    // 每句现取：设置页改「断句灵敏度」下一句就生效
                    minSilenceMs = { Prefs.vadSilenceMs }
                )
                vad = v
            }

            val cap = AudioCapture { frame, len -> onFrame(frame, len) }
            if (cap.start()) {
                capture = cap
                notify("语音已就绪")
            } else {
                lastError = cap.lastError
                notify(cap.lastError ?: "麦克风启动失败")
            }
            main.post { listener?.onState(state, hintFor(state)) }
        }
    }

    /**
     * 唤醒词改了 / 模型重新下载了之后调用。
     *
     * 为什么必须重建：vosk 的匹配规则（归一化后的词表、同音兜底正则）在
     * `VoskWake` 构造时固化，改 Prefs 不会影响已经建好的实例。
     *
     * 只重建唤醒器，不动 AudioCapture —— 重启采集会闪一下麦克风，而且没必要。
     */
    fun reloadWake() {
        worker.execute {
            shutdownWake()
            ensureWake()
            // 唤醒词变了，KWS 词表也要跟着重建（否则又是"改了没生效"）
            if (kwsActive()) sherpaBargeIn.load(bargeKeywordList())
            notify(
                when {
                    kwsOnly() -> "唤醒词已更新（仅 KWS）：${Prefs.wakeWords}"
                    wakeReady() -> "唤醒词已更新：${Prefs.wakeWords}"
                    else -> "唤醒模型加载失败"
                }
            )
        }
    }

    /** 模型就在本地就加载，不在就下一个。阻塞，只在 worker 线程调用 */
    private fun ensureWake() {
        if (wakeReady()) return
        // ★ kws-only：完全不加载 vosk（44MB 模型 + 一路持续解码）。
        //   唤醒交给 Sherpa KWS —— 它的关键词表里本来就有唤醒词。
        //   注意 wakeReady() 会一直是 false，心跳日志里能看到，这是预期行为。
        if (kwsOnly()) return
        // 模型放外部私有目录：adb 能直接 push 进去，省得每次重装都重新下 44MB
        val modelRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = ModelStore.voskDir(modelRoot)
        val words = Prefs.wakeWordList()
        if (dir != null) {
            replaceWake(VoskWake(dir, words))
        } else {
            notify("首次使用，正在准备语音模型…")
            val d = ModelStore.ensureVosk(modelRoot) { msg -> notify(msg) }
            if (d != null) {
                replaceWake(VoskWake(d, words))
            } else {
                lastError = "语音模型下载失败，唤醒词不可用（按钮仍可用）"
                notify(lastError!!)
            }
        }
    }

    fun stop() {
        capture?.stop()
        capture = null
        sherpaBargeIn.close()
        resetVad()
        shutdownWake()
        huaweiPreprocess.setActive(false)
        ready = false
        setState(State.ASLEEP)
    }

    /** 点按钮 / 点悬浮球：直接进连续会话 */
    fun wakeManually() {
        lastActivityMs = System.currentTimeMillis()
        skipEchoGuard = false
        wakeAudio.clear()
        beep()
        setState(State.LISTENING)
        resetWake()
        resetVad()
    }

    fun sleep() {
        resetVad()
        resetWake()
        setState(State.ASLEEP)
    }

    /**
     * 播报完再休眠。
     *
     * ★ 为什么不能直接 sleep()：
     *   用户说「你休息吧」，模型会回一句「好的，我休息了」并用 TTS 播出来。
     *   如果工具执行时立刻 sleep，TTS 还在播，声音会被麦克风收进去 ——
     *   半双工下这会导致刚播完又被自己的声音触发一轮识别，
     *   甚至被 VAD 切成新的一句话送去 ASR。
     *
     *   所以这里等 TTS 播报结束，再留一段回声尾巴，最后真正进入 ASLEEP。
     *
     * @param startGuardMs 开始轮询前的等待。云端 TTS 是"请求 → 合成 → 播放"，
     *   调用 speak() 之后 isSpeaking 要等几百毫秒才变 true。本地拦截睡眠口令时
     *   紧接着就调这个方法，如果不给守卫会立刻判定"没在播"→ 800ms 后就睡了，
     *   把刚合成好的「好」截断。LLM 工具路径不需要（那时 TTS 已经在播），传 0。
     */
    fun sleepAfterSpeaking(startGuardMs: Long = 0L) {
        main.post {
            if (startGuardMs <= 0L) {
                waitSpeakingThenSleep(0)
            } else {
                main.postDelayed({ waitSpeakingThenSleep(0) }, startGuardMs)
            }
        }
    }

    /**
     * 轮询等播报结束。
     *
     * @param attempt 已经等了多少次（每次 150ms，最多约 15 秒）
     */
    private fun waitSpeakingThenSleep(attempt: Int) {
        val speaking = isSpeakingProvider()
        if (speaking) {
            if (attempt >= MAX_WAIT_SPEAKING) {
                // 等太久了，直接睡，别让用户干等
                Dbg.log("等待播报结束超时，强制休眠")
                doSleep()
                return
            }
            main.postDelayed({ waitSpeakingThenSleep(attempt + 1) }, WAIT_SPEAKING_STEP_MS)
            return
        }
        // 播报刚结束，回声尾巴还在，等一小会儿再睡，避免收进自己的声音
        main.postDelayed({ doSleep() }, POST_SPEAK_GUARD_MS)
    }

    private fun doSleep() {
        Dbg.log("播报结束，进入休眠")
        sleep()
    }

    // ---------------------------------------------------------------- 内部

    private fun onFrame(frame: ShortArray, len: Int) {
        val speaking = isSpeakingProvider()
        if (speaking) lastPlaybackAtMs = System.currentTimeMillis()
        if (speaking && !speakingNow) {
            speakingNow = true
            skipEchoGuard = false
            wakeAudio.clear()
            ownerPollFrames = 0
            ownerStreak.set(0)
            fullDuplexTried = false
            huaweiPreprocess.setActive(true)
            bargeWakeWords = Prefs.wakeWordList()
            bargeStopWords = Prefs.bargeStopWordList()
            sherpaBargeIn.reset()
            // 播报期间只保留唤醒检测。重置解码器，避免上一轮连续会话
            // 或刚结束的 TTS 残留文本影响这一轮唤醒判定。
            resetWake()
        }
        // 环形缓冲一直在滚。不管什么状态都留最近 2.5 秒麦克风音频：
        // 声纹判定只能靠真实音频分出「主人」和「TTS 回声」，
        // 缓冲空着就没法判（之前只在待命和播报时写，听话过程中是空的）。
        wakeAudio.add(frame, len)
        if (speaking) {
            // 普通 VAD、ASR 和声纹判定在播报期间全部暂停（没有 AEC，只能半双工）。
            // 只剩「打断判定」在跑：唤醒词 / 停止口令 / 选择列表序号。
            if (!Prefs.bargeIn) return

            // 判定用哪一档规则。导航播报期间是 NONE —— 只记录，不执行。
            val navSpeaking = navSpeakingProvider()
            val choiceActive = choiceModeProvider()
            val mode = BargeIn.mode(
                speaking = true,
                navSpeaking = navSpeaking,
                choiceActive = choiceActive
            )
            // ★ 导航播报期间照样要认词，只是不执行（记下来播完再说）。
            //   所以匹配档位按「如果在播闲聊」算，不能因为 mode=NONE 就跳过识别。
            val matchMode = if (mode == BargeMode.NONE) {
                BargeIn.mode(speaking = true, navSpeaking = false, choiceActive = choiceActive)
            } else {
                mode
            }
            // ★ 2026-11-07：Sherpa KWS 回归，但词表是**运行时生成**的。
            //
            //   为什么不是"只用 Vosk"：真机实测 Vosk 在 TTS 混音里只会输出碎片
            //   （「足了啊与其了的啊的的 事了呢」），长文本播报中一句都匹配不上。
            //   Sherpa 把解码约束在关键词路径上，同样条件下能命中。
            //   词表由 KwsKeywords 现场转拼音，改设置即生效，不再是写死的「小猪」。
            val sherpaLabel = if (kwsActive()) sherpaBargeIn.feed(frame, len) else null
            val sherpaHit = sherpaLabel?.let {
                BargeIn.match(it, matchMode, bargeWakeWords, bargeStopWords)
            }
            // ★ kws-only：vosk 不再吃音频，唤醒完全靠上面那条关键词路径。
            //   注意 feedWake 有副作用（维护解码状态），跳过时后续 wakeHeardText()
            //   也要一起跳过，否则会读到上一轮的残留文本造成误判。
            val voskOn = !kwsOnly()
            val wakeFed = if (voskOn) feedWake(frame, len, strict = true) else false
            val heard = if (voskOn) wakeHeardText() else ""
            val textHit = BargeIn.match(heard, matchMode, bargeWakeWords, bargeStopWords)
            val hit = sherpaHit ?: textHit ?: if (wakeFed) {
                BargeHit(BargeKind.WAKE, "", wakeMatchText().orEmpty(), 0)
            } else {
                null
            }

            if (hit == null) {
                // 词表没中，还有第三条腿：声纹。
                pollOwnerVoice()
                // ★ 实验性全双工（2026-11-07，默认关）：播报期间继续喂 VAD，
                //   用户可以直接插话，不必先喊唤醒词。护栏全在 onSegment 里
                //   —— 声纹、播报重叠、单次限额、播报原文回声，四道都要过。
                //   依赖「打断检测」开着（上面 !Prefs.bargeIn 会提前 return），
                //   因为 AEC 不可靠时半双工仍是默认且更安全的选择。
                if (Prefs.fullDuplex && !busyRecognizing && state == State.LISTENING) {
                    vadSawPlayback = true
                    vad?.feed(frame, len)
                }
                return
            }

            val source = when {
                sherpaHit != null -> "KWS「${sherpaLabel}」"
                textHit != null -> "Vosk 关键词"
                else -> "播报中唤醒词"
            }

            // 导航播报：记下来，等它说完再执行
            if (mode == BargeMode.NONE) {
                stageDeferredBarge(hit)
                return
            }
            if (debounced(hit)) return
            // 停止口令/序号如果正是我们自己念出来的，不算（回声保护）
            if (hit.kind != BargeKind.WAKE && isSpokenEcho(hit.matched)) {
                Dbg.log("$source 命中「${hit.matched}」来自播报内容，忽略")
                pollOwnerVoice()
                return
            }
            // ★ 2026-11-07 用户定的策略：声纹 / 关键词 / 唤醒词，
            //   任意一样命中就打断（或关系）。
            //   以前关键词还要再过一次声纹确认（与关系），那是"召回杀手"：
            //   主人喊了关键词，声纹因为混音打分偏低就被否掉，等于没喊。
            // 累计命中来源。跑一周就能回答「vosk 到底还需不需要」：
            // 如果 kws 一路涨、vosk 始终是 0，那 vosk 就是纯成本。
            when {
                sherpaHit != null -> kwsWakeHits++
                textHit != null -> voskWakeHits++
            }
            val standbySec = asleepFrames * AudioCapture.FRAME_MS / 1000
            Dbg.log(
                "$source 命中「${hit.matched}」kind=${hit.kind} mode=$mode，打断" +
                    "｜累计 kws=$kwsWakeHits vosk=$voskWakeHits 待命=${standbySec}s"
            )
            handleWakeDetected(interruptedSpeech = true, hit = hit)
            return
        }
        if (speakingNow) {
            // 播报自然结束或被打断后，Vosk 不能带着上一段解码状态进入下一轮。
            speakingNow = false
            sherpaBargeIn.reset()
            ownerPollFrames = 0
            ownerStreak.set(0)
            // ★ 打断后 skipEchoGuard 还开着，麦克风马上要恢复拾音。此刻关掉
            //   华为 ASR_AEC 等于把 TTS 残响原样收进去，延后到放行窗口结束再关。
            //   回调里再查一次状态：期间若又开始播报就别关。
            if (skipEchoGuard) {
                main.postDelayed({
                    if (!isSpeakingProvider() && !skipEchoGuard) {
                        huaweiPreprocess.setActive(false)
                    }
                }, ECHO_SKIP_MS + 100L)
            } else {
                huaweiPreprocess.setActive(false)
            }
            resetWake()
            // 导航播报期间用户喊的打断词，现在播完了，立刻执行
            runDeferredBarge()
        }
        // 只有播报真正结束后才屏蔽回声尾巴。这里不能包含播报中的阶段，
        // 否则用户会在整段导航提示期间都无法打断。
        if (!skipEchoGuard && isEchoGuardActiveProvider()) return
        if (busyRecognizing) return

        when (state) {
            State.ASLEEP -> {
                // 心跳：确认待命时帧还在流、唤醒器还活着。
                // 排查"唤醒没反应"时先看这条 —— 有它说明麦克风正常，问题在识别；
                // 没它说明采集线程已经死了。
                asleepFrames++
                if (asleepFrames % HEARTBEAT_FRAMES == 0L) {
                    // 带上引擎档位：kws-only 下 wakeReady 恒为 false（没加载 vosk），
                    // 不标出来的话，下次排查会以为唤醒器坏了。
                    Dbg.log(
                        "ASLEEP 心跳 ${asleepFrames * AudioCapture.FRAME_MS / 1000}s " +
                            "engine=${Prefs.wakeEngine} wakeReady=${wakeReady()}"
                    )
                }
                // ★ 2026-11-08 修复（真机事故）：kws-only 下待命必须自己喂 KWS。
                //
                //   原来 KWS 只在「播报中」那条分支里喂 —— 它是为打断服务的。
                //   待命唤醒一直由 vosk 负责。把 vosk 关掉之后，**待命时没有任何
                //   模型在听**，表现就是「喊破嗓子也叫不醒」，而且没有任何报错。
                //
                //   教训：切换任何一路检测前，先确认目标状态下真的有人在听。
                var kwsWake = false
                if (kwsOnly()) {
                    sherpaBargeIn.feed(frame, len)?.let { label ->
                        // 待命只认唤醒词：停止口令/序号在这里没有意义，传空词表
                        val h = BargeIn.match(
                            label, BargeMode.CHAT, Prefs.wakeWordList(), emptyList()
                        )
                        if (h != null && h.kind == BargeKind.WAKE) {
                            kwsWake = true
                            kwsWakeHits++
                            Dbg.log(
                                "待命 KWS 命中「${h.matched}」raw=$label｜累计 kws=$kwsWakeHits"
                            )
                        }
                    }
                }
                val voskFed = if (kwsOnly()) false else feedWake(frame, len)
                if (kwsWake || voskFed) {
                    Dbg.log("待命中唤醒候选，等待声纹确认")
                    requestWakeOwnerConfirmation("待命中", interruptedSpeech = false)
                }
            }

            State.LISTENING -> {
                vad?.feed(frame, len)
                val idle = Prefs.idleTimeoutMs
                if (System.currentTimeMillis() - lastActivityMs >= idle) {
                    Dbg.log("静默 ${idle}ms，会话休眠")
                    main.post {
                        setState(State.ASLEEP)
                        listener?.onNotice("已休眠，说「${Prefs.primaryWakeWord()}」再唤醒")
                    }
                }
            }

            State.THINKING -> Unit
        }
    }

    /**
     * 用 Vosk 命中前后的最近一段音频确认是不是主人。
     *
     * Vosk 负责“像不像唤醒词”，声纹负责“是不是主人在说”。两级的职责分开后，
     * 播报中可以使用高召回匹配，不会因为 TTS 正文里的普通词直接打断；
     * 待命唤醒也能拦住屏幕外的旁人声音。
     *
     * 失败一律放行，保持声纹系统原有的防误触定位：模型未就绪或未注册时，
     * 不能把整个唤醒功能锁死。
     */
    private fun requestWakeOwnerConfirmation(
        source: String,
        interruptedSpeech: Boolean,
        heardText: String? = null,
        hit: BargeHit? = null
    ) {
        // ★ 停止口令和序号不走唤醒词那套回声判断：判的是「这个词是不是我们自己
        //   正在念的内容」。助手刚说过「别说了」，用户紧接着也说「别说了」，
        //   这两句在识别文本里长得一样，只能靠时间窗口区分，所以宁严勿宽。
        if (hit != null && hit.kind != BargeKind.WAKE && isSpokenEcho(hit.matched)) {
            Dbg.log("$source 命中「${hit.matched}」来自播报内容，忽略")
            return
        }
        // ★ 声纹是打断播报的裁判。
        //
        // 取舍很明确：
        //   - 声纹可用 → 必须确认是主人才打断。主人近场说话和 TTS 回声在
        //     embedding 上差得很开，这一层既能挡回声，也能让主人直接喊话打断。
        //   - 声纹不可用（没注册 / 模型没就绪 / 用户关掉了）→ 退回唤醒词直断，
        //     宁可多打断一次，也不能让用户喊了没反应。
        //
        // ★ 之前那个「播报原文里含有唤醒词就不打断」的文本判断已经删掉：
        //   真机 21:50 证明它是错的 —— 助手回答里带一句「小猪」，用户后面喊的
        //   「小猪」全被吞掉，播报再也打不断。
        // 只有「打断播报」这条路径才强制声纹。
        // 待命唤醒保持宽松：万一模板过期或没注册，主人至少还能把机器叫醒，
        // 不至于整台设备变成砖。
        // ★ 2026-11-08 用户反馈「唤醒好像没有声纹验证」—— 确实没有。
        //   原来只有「打断播报」这条路径强制声纹，待命唤醒故意放得很宽松
        //   （怕声纹模板异常把设备锁死）。代价是旁人喊一声「果果」也会唤醒、
        //   还会听到应答，虽然接着下的命令会被识别阶段的声纹拦住。
        //
        //   现在改成：开着「只认我的声音」且声纹已注册时，待命唤醒同样要验证。
        //   没注册/模型没就绪时仍然放行 —— 宁可让旁人喊醒一次，
        //   也不能让主人喊不醒。
        val gate = when {
            interruptedSpeech && Prefs.voiceprintBargeIn -> voiceprintProvider?.invoke()
            !interruptedSpeech && Prefs.voiceprint -> voiceprintProvider?.invoke()
            else -> null
        }
        val voiceprintUsable = gate != null && gate.enrolled
        if (!voiceprintUsable) {
            if (interruptedSpeech && isBroadcastEcho(heardText)) {
                Dbg.log("$source 唤醒词来自播报内容，忽略（不打断，声纹不可用）")
                return
            }
            handleWakeDetected(interruptedSpeech, hit)
            return
        }
        if (wakeConfirmPending) return
        val pcm = wakeAudio.snapshot()
        // Vosk 可能在 TTS 刚开始、缓冲还很短时就命中一个误候选。
        // VoiceprintGate 对 <0.5s 的音频采用“太短则放行”，这对普通 ASR
        // 是合理的兜底，但对“停止正在播放的语音”过于宽松：
        // 真机日志里 0.24s / 0.48s 的 TTS 误候选就是因此打断了播报。
        // 唤醒打断必须等到足够长的声纹证据；不足时宁可继续播报。
        if (pcm.size < AudioCapture.SAMPLE_RATE * 3 / 5) {
            val secs = "%.2f".format(pcm.size.toDouble() / AudioCapture.SAMPLE_RATE)
            if (interruptedSpeech) {
                // 打断：证据不足就不动手，避免被 TTS 的短误候选骗到
                Dbg.log("$source 唤醒候选音频仅 ${secs}s，不足 0.60s，暂不打断")
                return
            }
            // 唤醒：缓冲短说明刚清理过，不能因为验证不了就让主人喊不醒
            Dbg.log("$source 音频仅 ${secs}s，不足以验证声纹，按放行处理")
            handleWakeDetected(interruptedSpeech, hit)
            return
        }

        wakeConfirmPending = true
        // 两个阈值分开取，因为它们要挡的东西不一样：
        //   打断 → voiceprintBargeThreshold：要和 TTS 回声抢，宁松勿严
        //   唤醒 → voiceprintThreshold：待命时没有回声干扰，按常规匹配标准来
        val threshold = if (interruptedSpeech) {
            Prefs.voiceprintBargeThreshold
        } else {
            Prefs.voiceprintThreshold
        }
        worker.execute {
            try {
                val evidence = strongestOwnerWindow(gate, pcm)
                if (evidence == null) {
                    Dbg.log("$source 声纹无法计算，按失败放行策略继续唤醒")
                    handleWakeDetected(interruptedSpeech, hit)
                } else if (evidence.score >= threshold) {
                    Dbg.log(
                        "$source 声纹确认通过 ${"%.3f".format(evidence.score)} " +
                            "vs 阈值 ${"%.2f".format(threshold)}（${evidence.label}）"
                    )
                    handleWakeDetected(interruptedSpeech, hit)
                } else {
                    Dbg.log(
                        "$source 声纹确认拒绝 ${"%.3f".format(evidence.score)} " +
                            "vs 阈值 ${"%.2f".format(threshold)}（${evidence.label}），继续播报/待命"
                    )
                }
            } finally {
                wakeConfirmPending = false
            }
        }
    }

    /**
     * 同时比较尾窗和完整缓冲，取主人声纹最高的一段。
     *
     * 完整缓冲可能混入较多 TTS；尾窗则可能刚好落在“小猪”两个字上。
     * 只取一个固定长度会让这两种情况二选一，真机上表现为“有时能打断、
     * 有时喊不醒”。这里两次推理在 voice-worker 上执行，不阻塞采集线程。
     */
    private fun strongestOwnerWindow(gate: VoiceprintGate, pcm: ShortArray): OwnerEvidence? {
        fun tail(seconds: Float): ShortArray {
            val samples = (AudioCapture.SAMPLE_RATE * seconds).toInt().coerceAtMost(pcm.size)
            return pcm.copyOfRange(pcm.size - samples, pcm.size)
        }
        val windows = buildList {
            // 唤醒词本身通常只有 0.5~0.8 秒。TTS 混音时，短窗往往能留下
            // 更多主人语音，长窗则留给 Vosk 延迟吐字或用户拖长音的情况。
            add("短窗 0.8s" to tail(0.8f))
            add("尾窗 1.2s" to tail(1.2f))
            add("完整窗口" to tail(2.0f))
        }
        return windows
            .mapNotNull { (label, samples) ->
                gate.scoreForCheck(samples, AudioCapture.SAMPLE_RATE)
                    ?.let { OwnerEvidence(it, label) }
            }
            .maxByOrNull { it.score }
    }

    private data class OwnerEvidence(val score: Float, val label: String)

    /**
     * 声纹自测：把最近两秒麦克风音频算一遍分，打印出来。
     *
     * 用途：测出「主人自己的声音能拿多少分」。
     * 打断播报要靠这个分数定阈值 —— 安静环境是基线，播报混音时会掉一点，
     * 掉多少全靠这个入口测出来，日志里写得很清楚。
     */
    fun debugVoiceprintScore() {
        val gate = voiceprintProvider?.invoke()
        if (gate == null || !gate.enrolled) {
            Dbg.log("声纹自测：没注册声纹，跑不了")
            return
        }
        val pcm = wakeAudio.snapshot()
        if (pcm.size < AudioCapture.SAMPLE_RATE) {
            Dbg.log("声纹自测：缓冲不够 1s，稍后再试")
            return
        }
        worker.execute {
            val evidence = strongestOwnerWindow(gate, pcm)
            val score = evidence?.score ?: -1f
            val label = evidence?.label ?: "算不出"
            val threshold = Prefs.voiceprintBargeThreshold
            Dbg.log("声纹自测：分数 " + fmt(score) + "（" + label + "） 阈值 " + fmt(threshold))
        }
    }

    /**
     * 声纹重新注册：拿最近这段音频当成主人样本，覆盖旧模板。
     *
     * 为什么需要：模板是一次性的。环境变了（车里、麦克风位置换了、
     * 中途自适应漂了），分数会掉到 0.1 以下，这时候再准的判定也认不出主人。
     * 重新录一遍比调阈值靠谱。
     */
    fun debugEnrollVoiceprint() {
        val gate = voiceprintProvider?.invoke()
        if (gate == null) {
            Dbg.log("声纹注册：模型没就绪")
            return
        }
        val pcm = wakeAudio.snapshot()
        worker.execute {
            val seconds = pcm.size.toDouble() / AudioCapture.SAMPLE_RATE
            if (seconds < 1.0) {
                Dbg.log("声纹注册：只有 " + fmt(seconds.toFloat()) + "s，不够 1s")
                return@execute
            }
            val ok = gate.enroll(pcm, AudioCapture.SAMPLE_RATE)
            val tail = if (ok) "成功" else "失败"
            Dbg.log("声纹注册：" + tail + "（" + fmt(seconds.toFloat()) + "s）")
        }
    }

    /**
     * 播报期间的声纹轮询：主人真的在说话时打断。
     *
     * ★ 2026-11-07 起默认**不**打断，只记录。播报中的打断统一由 Vosk 认出来的
     *   关键词决定（先停下 / 别说了 / 第N个…）。这条路留着兜底：
     *   真机上如果发现关键词召回还是不够，把设置里的 bargeInOnOwnerVoice 打开，
     *   就退回"主人一开口就停"的老行为。
     *
     * 为什么要求连续两次过线：单次窗口可能正好压在 TTS 的换气处，分数偶然抬高。
     *
     * 跑在 worker 线程上（ONNX 推理约 0.1s，不能压在采集线程上）。
     */
    private fun pollOwnerVoice() {
        if (!Prefs.voiceprintBargeIn) return
        val gate = voiceprintProvider?.invoke() ?: return
        if (!gate.enrolled) return
        if (ownerPollPending) return

        ownerPollFrames++
        if (ownerPollFrames < OWNER_POLL_FRAMES) return
        ownerPollFrames = 0

        val pcm = wakeAudio.snapshot()
        if (pcm.size < AudioCapture.SAMPLE_RATE * 3 / 5) return

        ownerPollPending = true
        worker.execute {
            try {
                val window = tailWindow(pcm, OWNER_POLL_SECONDS)
                val score = gate.scoreForCheck(window, AudioCapture.SAMPLE_RATE)
                if (score == null) {
                    ownerStreak.set(0)
                    return@execute
                }
                val threshold = Prefs.voiceprintBargeThreshold +
                    OWNER_ONLY_MARGIN
                if (score >= threshold) {
                    val streak = ownerStreak.incrementAndGet()
                    Dbg.log("声纹轮询 " + fmt(score) + " ≥ " + fmt(threshold) + "（连续 $streak 次）")
                    if (streak >= OWNER_STREAK) {
                        ownerStreak.set(0)
                        // ★ 导航播报期间一律不打断（真机 17:49:05 踩过）：
                        //   声纹过线后照样 beep + 切聆听 + 弹"已打断播报"，
                        //   而导航音频其实还在念 —— 用户听到的就是"导航被打断"。
                        if (navSpeakingProvider()) {
                            Dbg.log("声纹确认主人在说话，但导航播报中 → 播完再听")
                            stageDeferredBarge(BargeHit(BargeKind.WAKE, "", "声纹", 0))
                        } else {
                            Dbg.log("声纹确认主人在说话，打断播报")
                            handleWakeDetected(interruptedSpeech = true)
                        }
                    }
                } else {
                    if (ownerStreak.getAndSet(0) > 0) {
                        Dbg.log("声纹轮询 " + fmt(score) + " 低于阈值，连续计数清零")
                    }
                    ownerPollLogCount++
                    if (ownerPollLogCount % OWNER_POLL_LOG_EVERY == 0) {
                        Dbg.log("声纹轮询基线 " + fmt(score) + "（阈值 " + fmt(threshold) + "）")
                    }
                }
            } finally {
                ownerPollPending = false
            }
        }
    }

    private fun tailWindow(pcm: ShortArray, seconds: Float): ShortArray {
        val samples = (AudioCapture.SAMPLE_RATE * seconds).toInt().coerceAtMost(pcm.size)
        return pcm.copyOfRange(pcm.size - samples, pcm.size)
    }

    private fun fmt(value: Float): String = String.format("%.3f", value)

    /**
     * 刚听到的唤醒词，是不是当前正在播的内容本身。
     *
     * 只在播报期间判断。比对时去掉空白和标点，避免「小 猪」「小猪，」这种
     * TTS 实际输出和配置写法不一致导致漏判。
     *
     * ★ 次数比对是关键：播报文本里出现一次「小猪」，而识别窗口里出现两次，
     * 说明多出来那一次是用户自己喊的 —— 这时候必须放行，不能当回声压掉。
     * 否则用户会发现「只要 AI 提到自己的名字，他喊打断就没反应」。
     */
    private fun isBroadcastEcho(heardText: String?): Boolean {
        val spoken = spokenTextProvider() ?: return false
        val norm = spoken.filter { it.isLetterOrDigit() }
        if (norm.isEmpty()) return false
        val words = Prefs.wakeWordList()
            .map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (words.none { norm.contains(it) }) return false

        // 没有识别文本（Sherpa KWS 命中）：只能按「播报里确实念了这个词」保守处理
        val heard = heardText?.filter { it.isLetterOrDigit() }
        if (heard.isNullOrEmpty()) return true

        // 播报里没有这个词，但窗口里有 → 是用户喊的（播报中途插进来的）
        val spokenHas = words.any { norm.contains(it) }
        val heardHas = words.any { heard.contains(it) }
        if (!spokenHas && heardHas) return false

        // 播报里出现过：比次数，多出来的才算用户的
        val spokenCount = words.maxOf { countOccurrences(norm, it) }
        val heardCount = words.maxOf { countOccurrences(heard, it) }
        return heardCount <= spokenCount
    }

    /**
     * 刚命中的停止口令/序号，是不是当前正在播的内容本身。
     *
     * 唤醒词那套判断（[isBroadcastEcho]）比的是"次数"，因为播报里出现一次
     * 「小猪」不代表用户喊了；但「别说了」这种口令没有次数可数 ——
     * 播报里正在念它，同时也可能是用户在说，只能保守处理：**播报里含这个词就不认**。
     * 代价是助手正好在说「别说了」时用户喊同一句无效，这个窗口很短，可以接受。
     */
    private fun isSpokenEcho(matched: String): Boolean {
        val spoken = spokenTextProvider() ?: return false
        val norm = spoken.filter { it.isLetterOrDigit() }
        val m = matched.filter { it.isLetterOrDigit() }
        return m.isNotEmpty() && norm.contains(m)
    }

    /** 命中时的识别文本（Vosk 窗口）。拿不到就 null。 */
    private fun wakeMatchText(): String? = synchronized(wakeLock) {
        wake?.lastMatchText
    }

    /** 当前识别窗口文本（打断词匹配用）。只在采集线程调用。 */
    private fun wakeHeardText(): String = synchronized(wakeLock) {
        wake?.heardText.orEmpty()
    }

    private fun countOccurrences(text: String, word: String): Int {
        if (word.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(word)
        while (index >= 0) {
            count++
            index = text.indexOf(word, index + word.length)
        }
        return count
    }

    /**
     * 命中打断词后统一切到完整聆听。
     *
     * audio 线程可能连续命中多帧，用 [wakeInterruptPending] 合并成一个主线程任务。
     * 播报中命中时先停播（只停闲聊，导航播报由 [onBargeIn] 那边的策略挡掉），
     * 再进入 LISTENING。
     *
     * @param hit 命中的是哪一类。序号（[BargeKind.INDEX]）命中时不用等用户再说一遍：
     *   直接把它规范化成「第2个」提交给大脑 —— 用户说的就是这句，让他重复一遍很蠢。
     */
    private fun handleWakeDetected(
        interruptedSpeech: Boolean,
        hit: BargeHit? = null
    ) {
        if (wakeInterruptPending) return
        wakeInterruptPending = true
        lastActivityMs = System.currentTimeMillis()
        resetVad()

        // 序号命中时把规范化说法直接提交：用户说的就是这句，
        // 让他等列表念完再说一遍等于这个功能没做。
        val indexHit = hit?.takeIf { it.kind == BargeKind.INDEX }

        main.postAtFrontOfQueue {
            // 不论是不是"打断"，都让宿主把这一轮对话闭嘴：
            // 导航播报期间攒下的打断等到播完才执行，那时模型可能刚好又开始念下一句，
            // 这时候还是要静音，否则用户刚喊完又被塞一句进来。
            val interrupted = onBargeIn?.invoke() == true
            // ★ 2026-11-08 用户要求：**打断时不要应答语**。
            //   用户喊「果果」是为了让它闭嘴，再回一句「嗨」等于又插一句，
            //   而且会重新占用播报通道（半双工），把紧接着的指令挡在门外。
            //   打断场景只嘀一声，表示「听到了，已经停了」。
            if (interrupted || interruptedSpeech || indexHit != null) beep() else wakeAck()
            setState(State.LISTENING)
            // ★ 打断之后马上要听用户接下来的话，不能被「播报结束回声保护期」挡住。
            //   打断时 lastSpokeEndMs 刚被刷新，正常会锁 1.5 秒 —— 用户紧跟着说的
            //   半句话会被整段吞掉（这是之前「打断后第一句老丢」的原因之一）。
            skipEchoGuard = true
            Dbg.log("打断：硬静音窗口结束前不放拾音，回声放行 ${ECHO_SKIP_MS}ms 后恢复")
            main.postDelayed({ skipEchoGuard = false }, ECHO_SKIP_MS)
            when {
                indexHit != null -> {
                    Dbg.log("序号打断：提交「${indexHit.canonical}」")
                    listener?.onNotice("好，${indexHit.canonical}")
                    listener?.onUtterance(indexHit.canonical)
                }
                interrupted -> listener?.onNotice("已打断播报，我在听…")
                interruptedSpeech -> listener?.onNotice("我在听…")
            }
            wakeInterruptPending = false
        }
    }

    /**
     * 导航播报期间命中的打断词先存着。
     *
     * 反复命中同一句只刷新时间，不重复记日志 —— 窗口会保留 4 秒，
     * 一句话能触发几十次。
     */
    private fun stageDeferredBarge(hit: BargeHit) {
        val first = deferredHit == null
        deferredHit = hit
        deferredAtMs = System.currentTimeMillis()
        if (first) {
            Dbg.log("导航播报中命中「${hit.matched}」kind=${hit.kind}，播完再执行")
        }
    }

    /** 播报结束：把导航期间攒下的打断执行掉 */
    private fun runDeferredBarge() {
        val hit = deferredHit ?: return
        deferredHit = null
        val age = System.currentTimeMillis() - deferredAtMs
        if (age > DEFER_MAX_AGE_MS) {
            Dbg.log("延迟打断已过期 ${age}ms，丢弃 kind=${hit.kind}")
            return
        }
        // interruptedSpeech=false：播报已经结束，没有东西要停了，
        // 只需要「响一下 + 进入聆听」（序号则顺带把选择提交掉）
        Dbg.log("导航播报结束，执行延迟打断 kind=${hit.kind} age=${age}ms")
        handleWakeDetected(interruptedSpeech = false, hit = hit)
    }

    /**
     * 同一句命中去抖。
     *
     * 声纹拒绝后播报继续，滑动窗口里那几个字还在 —— 不去抖的话每 20ms 就再发一次
     * 声纹请求，把 worker 线程打满，真正的主人喊话反而排不进去。
     */
    private fun debounced(hit: BargeHit): Boolean {
        val now = System.currentTimeMillis()
        val key = "${hit.kind}:${hit.matched}"
        if (key == lastHitKey && now - lastHitAtMs < HIT_DEBOUNCE_MS) return true
        lastHitKey = key
        lastHitAtMs = now
        return false
    }

    /** 重置 VAD 时一并清掉「这段混进过播报帧」的标记，别让它串到下一句 */
    private fun resetVad() {
        vad?.reset()
        vadSawPlayback = false
    }

    private fun onSegment(pcm: ShortArray) {
        if (busyRecognizing) return
        busyRecognizing = true
        // ★ 必须在采集线程（切句这一刻）捕获，不能等 worker 里再问：
        //   ASR 是网络请求，worker 跑起来时播报往往已经结束，spokenTextProvider
        //   也清空了，护栏会全部失效；反过来用「播报结束后 2 秒」这种时间窗
        //   又会把用户紧接着说的第一句话误判成重叠句丢掉。
        val cutDuringPlayback = Prefs.fullDuplex && speakingNow
        // 这段 VAD 音频里有没有混进播报期间的帧（切句可能晚于播报结束）
        val sawPlayback = vadSawPlayback
        vadSawPlayback = false
        val spokenAtCut = spokenTextProvider()
        setState(State.THINKING)
        worker.execute {
            try {
                // ★ 实验性全双工的护栏（默认关）：播报期间 VAD 切出来的句子
                //   绝大多数是我们自己的 TTS。四道关都要过才送去识别：
                //   本轮只试一次 / 必须已注册声纹且不自适应 / 更高阈值 /
                //   识别结果不能是播报原文。过不了就当没听见。
                val fromPlayback = cutDuringPlayback || sawPlayback
                if (fromPlayback) {
                    val gate = voiceprintProvider?.invoke()
                    val allowed = !fullDuplexTried && Prefs.voiceprint &&
                        gate != null && gate.enrolled &&
                        gate.allow(
                            pcm, AudioCapture.SAMPLE_RATE,
                            margin = FULL_DUPLEX_MARGIN, adaptAllowed = false
                        )
                    fullDuplexTried = true
                    if (!allowed) {
                        Dbg.log("全双工：播报重叠句未过护栏，丢弃")
                        if (state != State.ASLEEP) setState(State.LISTENING)
                        return@execute
                    }
                }

                // ★ 声纹门禁：不是主人说的就当没听见（不识别、不上屏、不进历史）
                if (!passVoiceprint(pcm)) {
                    if (state != State.ASLEEP) setState(State.LISTENING)
                    return@execute
                }

                // 噪声门只处理「上传 ASR 的副本」：VAD / 唤醒 / 声纹继续用原始
                // PCM，免得把噪声底压成 0 之后影响 VAD 的自适应阈值。
                val uploadPcm = if (Prefs.noiseGate) {
                    noiseGate.apply(pcm, Prefs.noiseGateThresholdDb)
                } else {
                    pcm
                }

                val text = asr.transcribe(uploadPcm, AudioCapture.SAMPLE_RATE)
                // null = 云端真的失败；"" = 识别成功但确实没说话。两者反馈不同
                val cloudFailed = text == null
                when {
                    text.isNullOrBlank() -> {
                        Dbg.log("ASR 无结果，回到聆听")
                        if (state != State.ASLEEP) setState(State.LISTENING)
                        // 节流：隧道里连着几句都失败，也只提示一次
                        if (cloudFailed &&
                            System.currentTimeMillis() - lastAsrFailNoticeMs > FAIL_NOTICE_INTERVAL_MS
                        ) {
                            lastAsrFailNoticeMs = System.currentTimeMillis()
                            main.post { onAsrFailure?.invoke() }
                        }
                    }

                    // ★ 通用回声护栏：不管是不是全双工、也不管切句时还在不在播，
                    //   只要识别结果和最近播过的文本重合，就是自己的声音被收了回去。
                    //   真机日志：播完 2 秒切出 10.34s 音频，ASR 逐字复述刚播的两句。
                    looksLikeEcho(text, recentSpokenProvider()) -> {
                        Dbg.log("丢弃播报回声：「$text」")
                        main.post { setState(State.LISTENING) }
                    }

                    // 全双工下识别结果和正在播的原文重合 → 是自己念的，别自问自答
                    fromPlayback && looksLikeBroadcast(text, spokenAtCut) -> {
                        Dbg.log("全双工：识别结果来自播报原文，丢弃「$text」")
                        main.post { setState(State.LISTENING) }
                    }

                    // 唤醒词本身又被 ASR 认了一遍，别当成用户的话送去聊天
                    isWakeOnly(text) -> {
                        Dbg.log("丢弃纯唤醒词：「$text」")
                        lastActivityMs = System.currentTimeMillis()
                        main.post { setState(State.LISTENING) }
                    }

                    // ★ 睡眠口令本地拦截：不走 LLM，省掉 1~2 秒往返，也不会漏。
                    //   必须放在 isWakeOnly 之后、else 之前 —— 顺序错了「小度你休息吧」
                    //   会被当成纯唤醒词丢掉。
                    onSleepCommand != null && isSleepCommand(text) -> {
                        Dbg.log("本地命中睡眠口令：「$text」")
                        lastActivityMs = System.currentTimeMillis()
                        main.post { onSleepCommand?.invoke() }
                    }

                    else -> {
                        lastActivityMs = System.currentTimeMillis()
                        main.post {
                            listener?.onUtterance(text)
                            setState(State.LISTENING)
                        }
                    }
                }
            } finally {
                busyRecognizing = false
            }
        }
    }

    /**
     * 识别出来的整句是不是播报内容的一部分（全双工自激护栏）。
     * @param spoken 切句那一刻的播报原文，不能用处理时的（那时可能已清空）
     */
    private fun looksLikeBroadcast(text: String, spoken: String?): Boolean {
        val norm = spoken?.filter { it.isLetterOrDigit() } ?: return false
        val heard = text.filter { it.isLetterOrDigit() }
        return heard.length >= 4 && norm.contains(heard)
    }

    /**
     * 通用回声判断：识别文本里任意连续 6 字命中最近播过的文本，就算回声。
     *
     * 用「6 字窗口」而不是整句包含，是因为 ASR 对 TTS 回声会有个别错字
     * （「您说」→「你说」、「山丹」→「三单」），整句比对一错就漏；
     * 但连续 6 字完全相同，基本不可能是用户自己刚好复述出来的。
     * 短句（「好」「对」「继续」）长度不够 6，直接放行，不会误伤。
     */
    private fun looksLikeEcho(heard: String, recent: String?): Boolean {
        if (recent.isNullOrBlank()) return false
        val h = heard.filter { it.isLetterOrDigit() }
        val r = recent.filter { it.isLetterOrDigit() }
        if (h.length < 6) return false
        return h.windowed(6).any { r.contains(it) }
    }

    /**
     * 声纹判定。
     *
     * 还没注册模板时，拿这一句去注册（≥1s 才够），同时**照常放行** ——
     * 主人说的第一句不能被自己拦掉。
     */
    private fun passVoiceprint(pcm: ShortArray): Boolean {
        if (!Prefs.voiceprint) return true
        val gate = voiceprintProvider?.invoke() ?: return true
        // 这段音频是不是和播报重叠（含刚播完的尾巴）
        val overlap = System.currentTimeMillis() - lastPlaybackAtMs < OVERLAP_GUARD_MS
        if (!gate.enrolled) {
            // ★ 模板只允许用"干净"的音频注册。播报混音里注册出来的模板是
            //   主人 + TTS 的混合，之后会认不出主人也拦不住 TTS（用户实测）。
            if (overlap) {
                Dbg.log("声纹注册跳过：这段音频和播报重叠，等一句干净的")
                return true
            }
            gate.enroll(pcm)
            return true
        }
        // 和播报重叠：不自适应模板 + 阈值再抬 0.10
        if (gate.allow(
                pcm,
                AudioCapture.SAMPLE_RATE,
                margin = if (overlap) OVERLAP_MARGIN else 0f,
                adaptAllowed = !overlap
            )
        ) return true
        // ★ 2026-11-07：选择列表窗口里不卡声纹。
        //   真机日志 16:52:11：用户回答列表，声纹 0.424 < 0.45 被丢掉 ——
        //   界面上什么都没发生，用户只会说「列表没反应」。
        //   这个窗口只有 120 秒、而且是我们主动问"要哪个"，短时间内放宽是值得的；
        //   非窗口期仍然按阈值卡，乘客说话照样挡。
        if (choiceModeProvider()) {
            Dbg.log("选择窗口内声纹未过，仍然放行（正在等用户作答）")
            return true
        }
        return false
    }

    /**
     * 只有唤醒词、没有实际内容的那一句。
     *
     * 场景：vosk 命中「小高」→ 进连续会话 → 同一句话被 VAD 切出来再送 ASR，
     * 结果就是"你好小高"被当成用户的话发给了模型。这里挡掉。
     * 但"小高，导航去西站"这种**带内容的**必须放行，所以是整句匹配不是包含匹配。
     *
     * ★ 正则必须跟着唤醒词动态生成。写死「小高」的话，用户把唤醒词改成「你好小明」之后
     *   这个过滤器就彻底失效了，"你好小明"会被当成闲聊发给模型。
     */
    private fun isWakeOnly(text: String): Boolean {
        val norm = text.filter { it.isLetterOrDigit() }
        if (norm.isEmpty() || norm.length > 12) return false
        return wakeOnlyRegex().matches(norm)
    }

    @Volatile
    private var wakeOnlyCache: Pair<String, Regex>? = null

    private fun wakeOnlyRegex(): Regex {
        val src = Prefs.wakeWords
        wakeOnlyCache?.let { if (it.first == src) return it.second }
        val words = Prefs.wakeWordList()
            .map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        val re = if (words.isEmpty()) {
            NEVER_MATCH
        } else {
            Regex("^(你好|嗨|喂)?(${words.joinToString("|") { Regex.escape(it) }})(你好|在吗|在不在|在么|呀|啊)?$")
        }
        wakeOnlyCache = src to re
        return re
    }

    /**
     * 是不是睡眠口令（照 D:/AI `_sleep_match` 的规则移植）。
     *
     * 两条约束缺一不可：
     *   1. **必须出现在句首**。纯包含匹配太激进 —— D:/AI 那边踩过
     *      「你休息吧起了怪了是不是必须要等多长时间」被当成睡眠命令吃掉。
     *   2. **尾巴只能是语气词**。哪怕多一个实词（「你休息吧那导航怎么办」）
     *      也说明用户是在追问，不是在命令。
     *
     * 命中即返回，不再看更短的短语：短语表按长度降序排，
     * 「你先休息一下吧」优先于「你先休息吧」，避免长句被短词切碎。
     */
    private fun isSleepCommand(text: String): Boolean {
        val norm = text.filter { it.isLetterOrDigit() }
        if (norm.isEmpty()) return false
        for (phrase in SLEEP_PHRASES) {
            if (!norm.startsWith(phrase)) continue
            val tail = norm.substring(phrase.length)
            return tail.all { it in TRAILING_PARTICLES }
        }
        return false
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        // 每次进出待命都把唤醒器清干净：休眠时残留的解码状态会让下一句
        // 接在上一句后面，唤醒词永远拼不出来（实测踩过）
        if (s == State.ASLEEP) {
            skipEchoGuard = false
            wakeAudio.clear()
            resetWake()
            resetVad()
        } else {
            asleepFrames = 0
        }
        listener?.onState(s, hintFor(s))
    }

    private fun notify(msg: String) {
        Dbg.log("VoiceEngine: $msg")
        main.post { listener?.onNotice(msg) }
    }

    private fun replaceWake(next: com.navagent.core.WakeWord) {
        synchronized(wakeLock) {
            wake = next
        }
    }

    private fun feedWake(
        frame: ShortArray,
        len: Int,
        strict: Boolean = false
    ): Boolean = synchronized(wakeLock) {
        wake?.feed(frame, len, strict) == true
    }

    private fun resetWake() {
        synchronized(wakeLock) {
            wake?.reset()
        }
    }

    private fun shutdownWake() {
        synchronized(wakeLock) {
            wake?.shutdown()
            wake = null
        }
    }

    private fun wakeReady(): Boolean = synchronized(wakeLock) {
        wake?.ready == true
    }

    /**
     * 唤醒提示音：**两声**短促的「嘀」。
     *
     * ★ 为什么从一声改成两声：单声 120ms 在车内太容易被路噪、导航播报盖掉，
     *   用户喊完不知道有没有醒，往往再喊一遍。两声间隔 140ms 有明显节奏感，
     *   比加长单声更容易从环境噪音里分辨出来，又不至于像 D:/AI 那样
     *   播一句「我在呢」—— 车里正在导航，TTS 会打断播报。
     */
    /**
     * 唤醒应答：「嗨，我在呢」。
     *
     * ★ 2026-11-08 用户要求：把唤醒后的提示音换成人声。
     *   必须延迟再播 —— 用户经常连着说「果果 导航去西站」，
     *   唤醒瞬间就播报的话，后半句会被半双工挡在门外（播报期间不拾音），
     *   表现就是「唤醒词之后那句指令老是丢」。
     *   所以等 700ms：这期间如果已经进了识别（busyRecognizing），就不吭声。
     */
    private fun wakeAck() {
        if (!Prefs.wakeAckVoice) {
            beep()
            return
        }
        main.postDelayed({
            if (state != State.LISTENING || busyRecognizing) return@postDelayed
            if (isSpeakingProvider() || navSpeakingProvider()) return@postDelayed
            val ok = runCatching {
                val v = (context.applicationContext as? NavApp)?.voice ?: return@runCatching false
                v.speak(WAKE_ACK_TEXT, VoiceGuide.Priority.USER)
                true
            }.getOrDefault(false)
            // TTS 起不来就退回嘀声，别让唤醒没反馈
            if (!ok) beep()
        }, WAKE_ACK_DELAY_MS)
    }

    private fun beep() {
        if (!Prefs.wakeBeep) return
        runCatching {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, WAKE_BEEP_MS)
            main.postDelayed({
                runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, WAKE_BEEP_MS) }
            }, WAKE_BEEP_GAP_MS)
        }
    }

    /** 设置页展示云端 ASR 的延迟分位数（连接复用效果看这个） */
    fun asrStats(): String = asr.statsText()

    /** 端侧识别模型是否加载成功。设置页「识别引擎」那一行用它显示状态 */
    val localAsrReady: Boolean get() = asr.localReady

    fun stateSnapshot(): Pair<State, String> = state to hintFor(state)

    private fun hintFor(s: State): String = when (s) {
        State.ASLEEP -> "待命 · 说「${Prefs.primaryWakeWord()}」唤醒"
        State.LISTENING -> "在听…"
        State.THINKING -> "识别中…"
    }

    private companion object {
        /** 唤醒词列表为空时的兜底：永远不匹配 */
        val NEVER_MATCH = Regex("^$^")

        /** 20ms 一帧，1500 帧 = 30 秒 */
        const val HEARTBEAT_FRAMES = 1500L

        /** 等待播报结束的轮询间隔 */
        const val WAIT_SPEAKING_STEP_MS = 150L

        /** 最多等这么久（15 秒）还没播完就强制休眠 */
        const val MAX_WAIT_SPEAKING = 100

        /** 播报结束后的额外等待，等回声尾巴散掉 */
        const val POST_SPEAK_GUARD_MS = 800L

        /**
         * 播报打断的最低主人声纹分数。
         *
         * ★ 2026-11-04 实测（P40 Pro）：
         *   TTS 回声本身          0.02 ~ 0.11
         *   主人声音混在播报里    0.39 ~ 0.52
         *   主人安静时            0.89
         *
         *   之前的 0.45 卡在主人和回声中间，主人喊了也过不去 ——
         *   真机表现是「喊了无数遍小猪都没反应」。
         *   现在走 [Prefs.voiceprintBargeThreshold]（默认 0.28），
         *   回声最高 0.11、主人最低 0.39，分得很开。
         */
        const val BARGE_IN_MIN_SCORE = 0.28f

        /**
         * 声纹轮询（播报期间）的参数。
         *
         * 每 [OWNER_POLL_FRAMES] 帧（20ms）查一次，取最近 [OWNER_POLL_SECONDS] 秒音频。
         * [OWNER_STREAK] 次连续过线才算主人开口 —— 单次可能压在 TTS 的换气处。
         * [OWNER_ONLY_MARGIN] 是「不用喊唤醒词、只靠声纹」这条路的额外余量：
         * 没有唤醒词当证据，只剩声纹一个，所以要求更严一点。
         */
        const val OWNER_POLL_FRAMES = 20
        const val OWNER_POLL_SECONDS = 1.0f
        const val OWNER_STREAK = 2

        /**
         * 「只靠声纹、不用唤醒词」这条路的额外余量。
         *
         * ★ 2026-11-07：从 0.10 降到 0.05。意义变了 —— 现在声纹过线不再直接打断，
         *   只是"送云端 ASR 判词"的触发条件。误触的代价从"播报被莫名掐断"
         *   变成"白跑一次 ASR"，所以宁可放宽一点，别再出现"喊了没反应"。
         */
        const val OWNER_ONLY_MARGIN = 0.05f

        /** 每 5 次轮询打一条基线分数（0.4s 一次 → 2s 一条），别把日志淹掉 */
        const val OWNER_POLL_LOG_EVERY = 5

        /**
         * 打断之后临时跳过回声保护期的时长。
         *
         * 打断的一瞬间 TTS 刚停，`lastSpokeEndMs` 被刷新，正常会锁住拾音
         * [Prefs.echoGuardMs]（默认 1.5s）—— 用户紧跟着说的那句话就整段丢了。
         *
         * ★ 2026-11-07 从 2 秒缩到 800ms：2 秒无保护拾音太长，足以把还在响的
         *   TTS 尾巴整段录进去，再经 ASR → LLM → TTS 形成自激（用户反馈
         *   「打断后只停一下、又把录音里 AI 的回音播了一遍」）。
         *   现在前 300ms 由 VoiceGuide 硬静音挡住，剩下约 500ms 只用来接住
         *   用户紧接着说的第一句，之后回声保护恢复。
         */
        const val ECHO_SKIP_MS = 800L

        /**
         * 导航播报期间攒下的打断，最多认这么久。
         *
         * 太长会把「上一段播报时随口说的词」当成现在要说的话；
         * 太短则用户喊完之后导航还在念，等于白喊。4 秒覆盖大多数导航提示。
         */
        const val DEFER_MAX_AGE_MS = 4_000L

        /**
         * 序号词。选择列表里用户会说「第一个…第六个」「最后一个」，候选一般不超过 6 条。
         *
         * ★ 必须写**中文数字**：ICU 把「第1个」转成 `dì1gè`（数字和「个」黏在一起），
         *   拆出来的 `1gè` 不是模型 token，整行会被丢弃（真机日志验证过）。
         *   「第一个」转成 `dì yī gè`，三个音节干干净净。
         */
        val CHOICE_KEYWORDS: List<String> =
            listOf("我选择", "最后一个") +
                listOf("第一个", "第二个", "第三个", "第四个", "第五个", "第六个")

        /** 播报结束后这么久内的音频仍算"重叠"，不拿去做声纹自适应 */
        const val OVERLAP_GUARD_MS = 2_000L

        /** 重叠音频的额外阈值（防止 TTS 回声蹭过线） */
        const val OVERLAP_MARGIN = 0.10f

        /** 实验性全双工：播报重叠句要过的额外声纹阈值，比普通重叠更严 */
        const val FULL_DUPLEX_MARGIN = 0.15f

        /** ASR 失败提示的最短间隔 */
        const val FAIL_NOTICE_INTERVAL_MS = 15_000L

        /** 同一句命中的去抖间隔（声纹拒绝时窗口里的字还会留一会儿） */
        const val HIT_DEBOUNCE_MS = 1_500L

        /** 唤醒提示音：单声时长 / 两声间隔 */
        const val WAKE_BEEP_MS = 90

        /** 唤醒应答的台词。改这句只影响「嘀声被替换掉」的那条反馈 */
        const val WAKE_ACK_TEXT = "嗨"

        /**
         * 唤醒后等多久再应答。
         * 太短会盖住用户紧接着说的指令（半双工，播报期间不拾音）；
         * 太长又显得反应迟钝。700ms 是实测比较稳的折中。
         */
        const val WAKE_ACK_DELAY_MS = 700L
        const val WAKE_BEEP_GAP_MS = 140L

        /**
         * 睡眠口令表。**必须按长度降序** —— [isSleepCommand] 命中第一个前缀就定论，
         * 短的排前面会把「你先休息一下吧」切成「你先休息吧 + 一下」，尾巴不是
         * 语气词，直接判否，反而漏掉。
         *
         * 这份表要和 NavigationTool / ChatEngine 提示词里告诉模型的那几句对齐，
         * 否则会出现「说了但本地不认、模型也不认」的灰区。
         */
        val SLEEP_PHRASES = listOf(
            "你先不要打扰我啊",
            "你先休息一下吧",
            "你不要打扰我啊",
            "你先不要打扰我",
            "你先休息一下",
            "你不要打扰我",
            "我先忙一会啊",
            "你先休息吧",
            "我先忙一会",
            "你休息吧",
            "你退下吧",
            "先这样吧",
            "先到这吧",
            "安静点",
            "别说了",
            "闭嘴"
        )

        /**
         * 句尾允许出现的语气词。
         *
         * 只有这些字跟在口令后面才算"在命令"；多一个实词（「你休息吧那导航怎么办」）
         * 就说明用户在追问，必须放给 LLM。这条是从 D:/AI 那边实测踩坑抄来的。
         */
        val TRAILING_PARTICLES = "啊呀呢吧哦哈嗯啦哟嘛".toSet()
    }
}
