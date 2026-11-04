package com.navagent.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
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

    /** 播报被唤醒词打断时回调。宿主注入 `voice.silence()`。 */
    @Volatile
    var onBargeIn: (() -> Unit)? = null

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
    private val asr = MinimaxAsr()
    private val wakeAudio = RecentAudioBuffer(AudioCapture.SAMPLE_RATE * 5 / 2)
    private val huaweiPreprocess = HuaweiAudioPreprocess(context)
    private val sherpaBargeIn = SherpaBargeIn(context)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-worker").apply { isDaemon = true }
    }

    @Volatile private var lastActivityMs = 0L
    @Volatile private var busyRecognizing = false
    @Volatile private var speakingNow = false
    @Volatile private var wakeInterruptPending = false
    @Volatile private var wakeConfirmPending = false
    @Volatile private var skipEchoGuard = false
    @Volatile private var ownerPollPending = false
    private var ownerPollFrames = 0
    private var ownerPollLogCount = 0
    private val ownerStreak = java.util.concurrent.atomic.AtomicInteger(0)
    private var asleepFrames = 0L

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
            if (Prefs.bargeIn) sherpaBargeIn.load()
            ready = true

            if (vad == null) {
                val v = EnergyVad(
                    onSpeechStart = { lastActivityMs = System.currentTimeMillis() },
                    onSegment = { pcm -> onSegment(pcm) }
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
            notify(if (wakeReady()) "唤醒词已更新：${Prefs.wakeWords}" else "唤醒模型加载失败")
        }
    }

    /** 模型就在本地就加载，不在就下一个。阻塞，只在 worker 线程调用 */
    private fun ensureWake() {
        if (wakeReady()) return
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
        vad?.reset()
        shutdownWake()
        sherpaBargeIn.close()
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
        vad?.reset()
    }

    fun sleep() {
        vad?.reset()
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
        if (speaking && !speakingNow) {
            speakingNow = true
            skipEchoGuard = false
            wakeAudio.clear()
            ownerPollFrames = 0
            ownerStreak.set(0)
            huaweiPreprocess.setActive(true)
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
            // 普通 VAD、ASR 和声纹判定在播报期间全部暂停。
            // 只有唤醒词或主人声纹能触发打断，避免导航播报、收音机和
            // 乘客说话被当成用户指令。
            if (Prefs.bargeIn && sherpaBargeIn.feed(frame, len)) {
                Dbg.log("播报中 Sherpa 命中「小猪」，等待声纹确认 state=$state")
                requestWakeOwnerConfirmation(
                    "Sherpa 播报中",
                    interruptedSpeech = true,
                    heardText = null
                )
            }
            // 播报中必须等完整唤醒词，不能用待命状态的词首丢失、任意片段
            // 和近似拼音兜底。宽匹配会把 TTS 连续文本拼成“小猪”，也会在
            // 真主人只说出“小”时提前触发；完整拼音匹配仍能兜住“晓猪”等同音词。
            if (Prefs.bargeIn && feedWake(frame, len, strict = true)) {
                Dbg.log("播报中命中唤醒候选，等待声纹确认 state=$state")
                requestWakeOwnerConfirmation(
                    "播报中",
                    interruptedSpeech = true,
                    heardText = wakeMatchText()
                )
            }
            // 声纹轮询：主人开口就能打断，不要求一定喊了唤醒词。
            //
            // 真机 21:50 那次的问题：唤醒词其实命中了（Sherpa 命中两次），
            // 但被「播报原文里含有小猪」这条文本判断挡了回去，用户喊了也没反应。
            // 文本判断本来就不可靠 —— 回答里出现一次「小猪」就把整段播报变成哑巴。
            // 现在改成用声纹当唯一裁判：主人的声音（近场、不同于 TTS）才打断。
            pollOwnerVoice()
            return
        }
        if (speakingNow) {
            // 播报自然结束或被打断后，Vosk 不能带着上一段解码状态进入下一轮。
            speakingNow = false
            ownerPollFrames = 0
            ownerStreak.set(0)
            huaweiPreprocess.setActive(false)
            sherpaBargeIn.reset()
            resetWake()
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
                    Dbg.log("ASLEEP 心跳 ${asleepFrames * AudioCapture.FRAME_MS / 1000}s wakeReady=${wakeReady()}")
                }
                if (feedWake(frame, len)) {
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
        heardText: String? = null
    ) {
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
        val gate = if (interruptedSpeech && Prefs.voiceprintBargeIn) {
            voiceprintProvider?.invoke()
        } else {
            null
        }
        val voiceprintUsable = gate != null && gate.enrolled
        if (!voiceprintUsable) {
            if (interruptedSpeech && isBroadcastEcho(heardText)) {
                Dbg.log("$source 唤醒词来自播报内容，忽略（不打断，声纹不可用）")
                return
            }
            handleWakeDetected(interruptedSpeech)
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
            Dbg.log(
                "$source 唤醒候选音频仅 ${"%.2f".format(pcm.size.toDouble() / AudioCapture.SAMPLE_RATE)}s，" +
                    "不足 0.60s，暂不打断"
            )
            return
        }

        wakeConfirmPending = true
        val threshold = Prefs.voiceprintBargeThreshold
        worker.execute {
            try {
                val evidence = strongestOwnerWindow(gate, pcm)
                if (evidence == null) {
                    Dbg.log("$source 声纹无法计算，按失败放行策略继续唤醒")
                    handleWakeDetected(interruptedSpeech)
                } else if (evidence.score >= threshold) {
                    Dbg.log(
                        "$source 声纹确认通过 ${"%.3f".format(evidence.score)} " +
                            "vs 阈值 ${"%.2f".format(threshold)}（${evidence.label}）"
                    )
                    handleWakeDetected(interruptedSpeech)
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
     * 播报期间的声纹轮询：主人在说话就打断，不要求一定喊了唤醒词。
     *
     * 为什么需要它：
     *   唤醒词检测在 TTS 混音里本来就不稳 —— 真机 21:50 那次 Vosk 一个字都没匹上，
     *   只有 Sherpa KWS 命中。所以光靠唤醒词，主人的打断请求有概率整段丢掉。
     *   声纹是另一条独立证据：主人的近场人声和 TTS 回声在 embedding 上差得开，
     *   连续两次过线就打断。
     *
     * 为什么要求连续两次：单次窗口可能正好压在 TTS 的换气处，分数偶然抬高。
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
                        Dbg.log("声纹确认是主人在说话，打断播报")
                        handleWakeDetected(true)
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

    /** 命中时的识别文本（Vosk 窗口）。拿不到就 null。 */
    private fun wakeMatchText(): String? = synchronized(wakeLock) {
        wake?.lastMatchText
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
     * 命中唤醒词后统一切到完整聆听。
     *
     * audio 线程可能连续命中多帧，用 [wakeInterruptPending] 合并成一个主线程任务。
     * 播报中命中时先停播，再进入 LISTENING；已经确认是用户主动唤醒，因此本次
     * 跳过播报结束时间戳带来的回声窗口，避免唤醒词之后的第一句话又被挡住。
     */
    private fun handleWakeDetected(interruptedSpeech: Boolean) {
        if (wakeInterruptPending) return
        wakeInterruptPending = true
        lastActivityMs = System.currentTimeMillis()
        vad?.reset()
        main.postAtFrontOfQueue {
            if (interruptedSpeech) onBargeIn?.invoke()
            beep()
            setState(State.LISTENING)
            if (interruptedSpeech) {
                listener?.onNotice("已打断播报，我在听…")
            }
            wakeInterruptPending = false
        }
    }

    private fun onSegment(pcm: ShortArray) {
        if (busyRecognizing) return
        busyRecognizing = true
        setState(State.THINKING)
        worker.execute {
            try {
                // ★ 声纹门禁：不是主人说的就当没听见（不识别、不上屏、不进历史）
                if (!passVoiceprint(pcm)) {
                    if (state != State.ASLEEP) setState(State.LISTENING)
                    return@execute
                }
                val text = asr.transcribe(pcm)
                when {
                    text.isNullOrBlank() -> {
                        Dbg.log("ASR 无结果，回到聆听")
                        if (state != State.ASLEEP) setState(State.LISTENING)
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
     * 声纹判定。
     *
     * 还没注册模板时，拿这一句去注册（≥1s 才够），同时**照常放行** ——
     * 主人说的第一句不能被自己拦掉。
     */
    private fun passVoiceprint(pcm: ShortArray): Boolean {
        if (!Prefs.voiceprint) return true
        val gate = voiceprintProvider?.invoke() ?: return true
        if (!gate.enrolled) {
            gate.enroll(pcm)
            return true
        }
        return gate.allow(pcm)
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
            vad?.reset()
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
    private fun beep() {
        if (!Prefs.wakeBeep) return
        runCatching {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, WAKE_BEEP_MS)
            main.postDelayed({
                runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, WAKE_BEEP_MS) }
            }, WAKE_BEEP_GAP_MS)
        }
    }

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
        const val OWNER_ONLY_MARGIN = 0.10f

        /** 每 5 次轮询打一条基线分数（0.4s 一次 → 2s 一条），别把日志淹掉 */
        const val OWNER_POLL_LOG_EVERY = 5

        /** 唤醒提示音：单声时长 / 两声间隔 */
        const val WAKE_BEEP_MS = 90
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
