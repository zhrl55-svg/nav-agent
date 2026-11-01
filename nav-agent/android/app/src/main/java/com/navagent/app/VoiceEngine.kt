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

    private var capture: AudioCapture? = null
    private var wake: VoskWake? = null
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
                vad = EnergyVad(
                    onSpeechStart = { lastActivityMs = System.currentTimeMillis() },
                    onSegment = { pcm -> onSegment(pcm) }
                )
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
        if (dir != null) {
            wake = VoskWake(dir, Prefs.wakeWordList())
        } else {
            notify("首次使用，正在准备语音模型…")
            val d = ModelStore.ensureVosk(modelRoot) { msg -> notify(msg) }
            if (d != null) {
                wake = VoskWake(d, Prefs.wakeWordList())
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

    private fun beep() {
        if (!Prefs.wakeBeep) return
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
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
    }
}
