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

    /** 由外部注入：TTS 是否在播。播报期间不拾音（半双工） */
    @Volatile
    var isSpeakingProvider: () -> Boolean = { false }

    /**
     * 声纹门禁。宿主注入；返回 null 表示模型没准备好或没开 —— 那种情况一律放行。
     */
    @Volatile
    var voiceprintProvider: (() -> VoiceprintGate?)? = null

    /**
     * 播报被"打断"时回调。宿主注入 `voice.silence()`。
     *
     * 为什么需要：用户在播报中间喊一句「不对」「停一下」，如果不打断，得等整段
     * 念完才轮到他说 —— 开车时这几秒很要命。
     */
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
    private var vad: EnergyVad? = null
    private val asr = MinimaxAsr()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-worker").apply { isDaemon = true }
    }

    @Volatile private var lastActivityMs = 0L
    @Volatile private var busyRecognizing = false
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
            ready = true

            if (vad == null) {
                val v = EnergyVad(
                    onSpeechStart = { lastActivityMs = System.currentTimeMillis() },
                    onSegment = { pcm -> onSegment(pcm) }
                )
                // ★ 声纹前置到打断判定：只有主人的声音能打断 AI 的播报。
                //   麦克风把导航播报、收音机、乘客说话收进来，都不该打断。
                v.bargeInCheck = { pcm -> isOwnerVoice(pcm) }
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
            wake?.shutdown()
            wake = null
            ensureWake()
            notify(if (wake?.ready == true) "唤醒词已更新：${Prefs.wakeWords}" else "唤醒模型加载失败")
        }
    }

    /** 模型就在本地就加载，不在就下一个。阻塞，只在 worker 线程调用 */
    private fun ensureWake() {
        if (wake?.ready == true) return
        // 模型放外部私有目录：adb 能直接 push 进去，省得每次重装都重新下 44MB
        val modelRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = ModelStore.voskDir(modelRoot)
        val words = Prefs.wakeWordList()
        if (dir != null) {
            wake = VoskWake(dir, words)
        } else {
            notify("首次使用，正在准备语音模型…")
            val d = ModelStore.ensureVosk(modelRoot) { msg -> notify(msg) }
            if (d != null) {
                wake = VoskWake(d, words)
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
        wake?.shutdown()
        wake = null
        ready = false
        setState(State.ASLEEP)
    }

    /** 点按钮 / 点悬浮球：直接进连续会话 */
    fun wakeManually() {
        lastActivityMs = System.currentTimeMillis()
        beep()
        setState(State.LISTENING)
        wake?.reset()
        vad?.reset()
    }

    fun sleep() {
        vad?.reset()
        wake?.reset()
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
     *   所以这里等播报彻底结束（isSpeakingProvider() 变 false 且过了回声保护期），
     *   再真正进入 ASLEEP。
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
        // 半双工：自己在播报时不拾音，否则会把 TTS 的声音当用户说话。
        // ★ 但仍在检测「用户是不是开口了」—— 开口就打断播报，别让人干等。
        if (isSpeakingProvider()) {
            if (state != State.ASLEEP) {
                lastActivityMs = System.currentTimeMillis()
                if (Prefs.bargeIn && vad?.feedBargeIn(frame, len) == true) {
                    Dbg.log("播报中检测到说话，打断")
                    vad?.reset()
                    vad?.resetBargeIn()
                    main.post {
                        onBargeIn?.invoke()
                        setState(State.LISTENING)
                        listener?.onNotice("我在听…")
                    }
                }
            }
            return
        }
        if (busyRecognizing) return

        when (state) {
            State.ASLEEP -> {
                // 心跳：确认待命时帧还在流、唤醒器还活着。
                // 排查"唤醒没反应"时先看这条 —— 有它说明麦克风正常，问题在识别；
                // 没它说明采集线程已经死了。
                asleepFrames++
                if (asleepFrames % HEARTBEAT_FRAMES == 0L) {
                    Dbg.log("ASLEEP 心跳 ${asleepFrames * AudioCapture.FRAME_MS / 1000}s wakeReady=${wake?.ready}")
                }
                if (wake?.feed(frame, len) == true) {
                    Dbg.log("唤醒词命中")
                    main.post {
                        beep()
                        lastActivityMs = System.currentTimeMillis()
                        setState(State.LISTENING)
                        vad?.reset()
                    }
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
     * 这段音频是不是主人说的。
     *
     * 用在**打断播报**的判定上：只有主人的声音才打断 AI 的播报，
     * 麦克风收进来的导航播报、收音机、乘客说话都不打断。
     *
     * ★ 失败一律放行（跟 [passVoiceprint] 同一个原则）：
     *   声纹没注册、算不出、超时 —— 全部当"是主人"。
     *   它是防误打断，不是身份认证，别把打断功能整个废掉。
     */
    private fun isOwnerVoice(pcm: ShortArray): Boolean {
        if (!Prefs.voiceprint) return true
        val gate = voiceprintProvider?.invoke() ?: return true
        // 没注册模板时无法判定 —— 放行（第一句往往是主人自己说的）
        if (!gate.enrolled) return true
        return runCatching { gate.allow(pcm) }.getOrDefault(true)
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
            wake?.reset()
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
