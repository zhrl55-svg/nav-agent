package com.navagent.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.PriorityBlockingQueue

/**
 * 播报队列。
 *
 * 开车场景的硬约束：
 * 1. 导航播报优先级最高，必须能打断 Agent 的闲聊
 * 2. 同一时刻只有一条在播，避免叠音
 * 3. 播报期间外部应暂停拾音（半双工），见 [isSpeaking]
 *
 * 关于 TTS 引擎（踩过的坑）：
 * 华为 P40 Pro（HarmonyOS 4 / API 31）的 `tts_default_synth` 是 com.svox.pico，
 * 也就是 AOSP 自带的英文引擎，**不支持中文**。直接 new TextToSpeech(context) 拿到的
 * 就是它，setLanguage(zh) 会返回 LANG_NOT_SUPPORTED，结果就是全程静音但没有任何报错。
 * 所以这里主动挑一个已知支持中文的引擎，并在语言不支持时回退到系统默认再试一次。
 */
class VoiceGuide(
    context: Context,
    /**
     * 指定 TTS 引擎包名，空串 = 系统默认。
     *
     * 实测这台华为 P40 Pro 上有两个引擎：
     *   com.huawei.hiai            华为智慧引擎（系统默认，本地合成）
     *   com.iflytek.speechsuite    讯飞（音色更"人味"一些）
     * `settings get secure tts_default_synth` 返回的 com.svox.pico 是**假的** —— 那个包
     * 根本不存在（dumpsys 查不到），华为在 TextToSpeechManager 层重定向到了 hiai。
     * 所以别信那个值，用 `activeEngine` 看实际连上的是谁。
     */
    enginePackage: String = ""
) : TextToSpeech.OnInitListener {

    /** 要求的引擎包名。空串 = 系统默认 */
    @Volatile
    private var enginePackage: String = enginePackage

    enum class Priority { NAV, USER, CHAT }

    private data class Utterance(
        val id: String,
        val text: String,
        val priority: Priority,
        val order: Long
    )

    private val queue = PriorityBlockingQueue(
        11,
        compareBy<Utterance> { it.priority.ordinal }
            .thenBy { it.order }
    )

    private val appContext = context.applicationContext

    /** 打断后重试 drain 用（[bargeInChat]） */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var current: Utterance? = null
    private var seq = 0L

    /**
     * ★ 必须放在所有属性声明之后。
     * 放在前面的话 appContext 还没初始化，startEngine() 里直接 NPE
     * （实测崩在 `VoiceGuide.<init>` → `startEngine`）。
     */
    init {
        startEngine()
    }

    /** 是否真的能出声。false 时界面应提示用户装中文 TTS 引擎。 */
    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var isSpeaking: Boolean = false
        private set

    /**
     * 播报状态变化回调。
     *
     * 界面用它把语音按钮切成「播报中」——
     * 用户能一眼看出 AI 正在说话，这时喊话是要打断的。
     *
     * 用 `CopyOnWriteArrayList` 而不是裸 List：回调在 TTS 线程触发，
     * 而界面在主线程注册/注销，并发改会炸。
     */
    private val speakingListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 注册播报状态监听。返回的 lambda 用于注销。 */
    fun addSpeakingListener(l: (Boolean) -> Unit): () -> Unit {
        speakingListeners.add(l)
        return { speakingListeners.remove(l) }
    }

    private fun notifySpeaking(speaking: Boolean) {
        speakingListeners.forEach { runCatching { it(speaking) } }
    }

    /**
     * 播报窗口回调：(是否开始播报, 是否导航级)。
     *
     * ★ 2026-11-03 加。宿主用它切车载蓝牙通道（见 app 层 CarAudioRoute）：
     *   播报期间申请音频焦点；播放流固定使用 STREAM_MUSIC，由系统送到蓝牙 A2DP。
     *   不切通信模式，也不启动 SCO。
     */
    @Volatile
    var speakWindowListener: ((Boolean, Boolean) -> Unit)? = null

    /** 当前正在播/刚播完那条的优先级，给 speakWindowListener 判断用 */
    @Volatile
    private var currentPriority: Priority = Priority.CHAT

    /**
     * 现在在播的是哪一档；没在播就是 null。
     *
     * ★ 2026-11-07 加。打断策略要按档位分开：导航播报不能被用户打断，
     *   闲聊播报可以。宿主（VoiceService）把它注入 VoiceEngine，由引擎判断
     *   「这次命中的打断词该不该执行」。
     */
    val speakingPriority: Priority?
        get() = if (current != null) currentPriority else null

    private fun setSpeaking(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        runCatching {
            speakWindowListener?.invoke(speaking, currentPriority == Priority.NAV)
        }
        notifySpeaking(speaking)
    }

    /**
     * 上一次播报结束的时刻。
     *
     * 用来做「回声保护期」：外放的声音有混响尾巴，播报停了麦克风还会收到 200~400ms
     * 的残响，VAD 会把这段当成用户说话切出去送 ASR。
     * 实测表现是：导航播报「即将左转」之后，识别结果出现「即将左转」的碎片。
     */
    @Volatile
    var lastSpokeEndMs: Long = 0L
        private set

    /**
     * 打断后的「硬静音窗口」截止时刻。
     *
     * ★ 2026-11-07 修自激循环：bargeInChat() 同步把 isSpeaking 置 false，
     *   但云端 TTS 的 stop()/MediaPlayer.release() 是异步 post 到主线程的。
     *   这中间 VAD 已经开始喂帧，会把还没停干净的 TTS 尾巴整段录进去。
     *   宿主把 [inHardMuteWindow] 并入 isSpeakingProvider，窗口内继续按
     *   「正在播报」处理：不喂 VAD、保持 AEC，等播放器真正落地再恢复拾音。
     */
    @Volatile
    private var hardMuteUntilMs: Long = 0L

    fun inHardMuteWindow(): Boolean = System.currentTimeMillis() < hardMuteUntilMs

    /** 播报结束后 [ms] 毫秒内都算回声区，这段时间不该拾音 */
    fun inEchoWindow(ms: Long = 600L): Boolean =
        System.currentTimeMillis() - lastSpokeEndMs < ms

    /**
     * 当前正在播的那句话的原文（没有在播就是 null）。
     *
     * 用途：播报期间用它判断「刚听到的唤醒词是不是我们自己播出来的」。
     * 真机踩过的坑 —— 助手回答里带了一句「小猪」，麦克风把这段 TTS 收了进去，
     * 唤醒检测误判成用户喊话，直接把播报打断，用户人还在那儿根本没出声。
     */
    val currentText: String?
        get() = current?.text

    /**
     * 最近播报过的文本（**含已播完的**），用来在 ASR 结果里认出
     * 「把自己的播报回声听成用户说话」。
     *
     * ★ 不能复用 [currentText]：bargeInChat() 和正常播完都会把 current 置 null，
     *   而回声恰恰是在播完之后才被 VAD 切出来送识别的，那时原文已经没了
     *   （真机日志：播完 2 秒切出 10.34s 音频，ASR 结果就是刚播的那两句）。
     */
    private val recentSpoken = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    private fun rememberSpoken(text: String) {
        if (text.isBlank()) return
        recentSpoken.addLast(System.currentTimeMillis() to text)
        while (recentSpoken.size > 6) recentSpoken.removeFirst()
    }

    /** 最近 [windowMs] 毫秒内播过的文本拼起来；没有就 null */
    @Synchronized
    fun recentSpokenText(windowMs: Long = 8_000L): String? {
        val now = System.currentTimeMillis()
        while (recentSpoken.isNotEmpty() && now - recentSpoken.first().first > windowMs) {
            recentSpoken.removeFirst()
        }
        if (recentSpoken.isEmpty()) return null
        return recentSpoken.joinToString("") { it.second }.takeIf { it.isNotBlank() }
    }

    /**
     * 语速。由宿主在启动时注入（core 不认识 SharedPreferences）。
     *
     * 车内场景实测：1.0 在安静环境够用，但发动机噪音 + 高速风噪下容易听漏，
     * 1.2 更稳；导航播报本身信息密度高，开太快反而记不住。所以做成可调。
     */
    @Volatile
    var rate: Float = 1.0f
        private set

    /** 改语速，立即生效（不用重建引擎） */
    fun setRate(r: Float) {
        rate = r.coerceIn(0.5f, 2.0f)
        runCatching { tts?.setSpeechRate(rate) }
    }

    /** 实际连上的引擎包名（回退后会和 [enginePackage] 不同）。给设置页显示用 */
    @Volatile
    var activeEngine: String = ""
        private set

    /**
     * 云端合成器。`enginePackage == [CLOUD_ENGINE]` 时走它，否则走系统 TTS。
     */
    @Volatile
    var cloudTts: CloudTts? = null

    /**
     * 外部注入的日志出口。core 模块不认识 app 层的 Dbg，
     * 而 TTS 引擎的问题只靠 Log.i 在华为机上排查不到（日志会被吞），
     * 所以这里留个口子让宿主写进文件。
     */
    @Volatile
    var logger: ((String) -> Unit)? = null

    private fun startEngine() {
        val pkg = enginePackage

        // 云端合成：不需要本地引擎，直接算就绪
        if (pkg == CLOUD_ENGINE) {
            Log.i(TAG, "TTS 使用云端合成")
            logger?.invoke("使用云端合成")
            activeEngine = CLOUD_ENGINE
            ready = true
            isAvailable = true
            drain()
            return
        }

        if (pkg.isNotBlank()) {
            // 不预先判断"包是否装了" —— Android 11+ 的包可见性会把它误判成没装。
            // 直接尝试构造，起不来在 onInit 里回退。
            Log.i(TAG, "创建 TTS 引擎（指定）：$pkg")
            logger?.invoke("创建引擎（指定）：$pkg")
            val t = runCatching { TextToSpeech(appContext, this, pkg) }.getOrNull()
            if (t != null) {
                tts = t
                return
            }
            Log.w(TAG, "指定引擎 $pkg 构造失败，回退系统默认")
            logger?.invoke("指定引擎 $pkg 构造失败，回退")
        }
        Log.i(TAG, "创建 TTS 引擎（系统默认）")
        logger?.invoke("创建引擎（系统默认）")
        tts = TextToSpeech(appContext, this)
    }

    /**
     * 运行时切换引擎。会重建 TTS 实例 —— 正在播的那句会被打断，队列清空。
     */
    fun setEngine(pkg: String) {
        if (pkg == enginePackage && ready) return
        enginePackage = pkg
        // 清空表示"正在初始化"，设置页会显示「初始化中…」
        activeEngine = ""
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        setSpeaking(false)
        current = null
        queue.clear()
        startEngine()
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 初始化失败 status=$status")
            logger?.invoke("初始化失败 status=$status engine=$enginePackage")
            // ★ 指定引擎失败必须回退，否则整个播报就哑了 —— 用户点了讯飞会以为坏了
            if (enginePackage.isNotBlank()) {
                logger?.invoke("指定引擎 ${enginePackage} 起不来，回退系统默认")
                runCatching { tts?.shutdown() }
                tts = null
                ready = false
                enginePackage = ""
                startEngine()
                return
            }
            ready = false
            isAvailable = false
            return
        }

        val result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            isAvailable = false
            Log.e(TAG, "TTS 引擎不支持中文 result=$result，播报将静音；需安装中文语音引擎")
            logger?.invoke("引擎 ${engine.defaultEngine} 不支持中文 result=$result")
            // 指定的引擎不支持中文（比如某些只装英文包的），也回退
            if (enginePackage.isNotBlank()) {
                runCatching { tts?.shutdown() }
                tts = null
                ready = false
                enginePackage = ""
                startEngine()
                return
            }
            return
        }

        isAvailable = true
        engine.setSpeechRate(rate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                setSpeaking(true)
            }

            override fun onDone(utteranceId: String?) {
                // ★ 传 id —— 打断产生的回调不能误伤后来入队的那条
                finishCurrent(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishCurrent(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "播报出错 code=$errorCode")
                finishCurrent(utteranceId)
            }
        })
        ready = true
        // ★ 不能用 engine.defaultEngine —— 它返回的是**系统设置里的默认引擎**，
        //   不是这个实例实际连的那个。实测切到讯飞之后它还返回 com.huawei.hiai，
        //   会让人误以为没切过去。
        //   正确的是：指定引擎没被回退就用指定的，回退了才去问系统默认。
        activeEngine = enginePackage.ifBlank {
            runCatching { engine.defaultEngine }.getOrNull().orEmpty()
        }
        Log.i(TAG, "TTS 就绪，中文可用，引擎=$activeEngine")
        logger?.invoke("就绪，引擎=$activeEngine")
        drain()
    }

    @Synchronized
    /**
     * 播完一条，继续下一条。
     *
     * ★ ★ 必须按 utteranceId 匹配（2026-11-04）：
     *   打断当前播报时，TTS 引擎会回调 `onError(被打断那条的 id)`。
     *   如果不检查 id，这个「打断产生的回调」会把**后来加入的**那条
     *   也当成播完了 —— 结果是新语音刚要播就被丢掉，
     *   用户听到的是「说了一半就没声了」（用户反馈：
     *   「停止的时候又触发了没有播报完的语音」）。
     *
     *   id 不匹配就只更新状态，不动 current、不 drain。
     */
    private fun finishCurrent(utteranceId: String? = null) {
        if (utteranceId != null) {
            val cur = current
            if (cur == null || cur.id != utteranceId) {
                // 这是**别的** utterance 的回调（多半是被打断的那条）
                Log.w(TAG, "忽略不匹配的播报回调 id=$utteranceId（当前=${current?.id}）")
                return
            }
        }
        current = null
        setSpeaking(false)
        lastSpokeEndMs = System.currentTimeMillis()
        logger?.invoke("播报完成 id=${curLogId(utteranceId)} 队列=${queue.size}")
        drain()
    }

    private fun curLogId(utteranceId: String?): String = utteranceId ?: "-"

    @Synchronized
    private fun drain() {
        if (!ready || current != null) return

        // 云端：请求合成 → 播放 → 播完回调 finishCurrent 继续下一条
        if (enginePackage == CLOUD_ENGINE) {
            val cloud = cloudTts ?: return
            val next = queue.poll() ?: return
            current = next
            currentPriority = next.priority
            setSpeaking(true)
            logger?.invoke("开始播报 id=${next.id} priority=${next.priority} chars=${next.text.length}")
            // ★ 必须带 id（2026-11-07 真机 bug）：
            //   以前这里传 null，finishCurrent 会跳过 id 校验 ——
            //   上一条（比如闲聊）延迟到达的播完回调，会把**当前在播的导航播报**
            //   的 current 直接清掉，随后 drain 播下一条，
            //   而 MinimaxTts.play() 开头会 stopInternal()，
            //   于是导航播报当场被掐断（用户反馈"导航播报也被打断了"）。
            cloud.speak(
                next.text,
                onDone = { finishCurrent(next.id) },
                onError = { msg ->
                    Log.w(TAG, "云端合成失败：$msg")
                    // 失败也要收尾，否则队列卡死、拾音永远被挡
                    finishCurrent(next.id)
                }
            )
            return
        }

        val engine = tts ?: return
        if (engine.isSpeaking) return
        val next = queue.poll() ?: return
        current = next
        currentPriority = next.priority
        // ★ 显式指定媒体流。
        //   不传 Bundle 时用的是引擎默认流，各家 TTS 引擎不一样（有的是 STREAM_SYSTEM），
        //   而 STREAM_SYSTEM 在车机上根本不走蓝牙媒体通道 —— 这也是一类"没声音"的来源。
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        logger?.invoke("开始播报 id=${next.id} priority=${next.priority} chars=${next.text.length}")
        engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, params, next.id)
    }

    /**
     * 播报。NAV 优先级会清空待播的闲聊并打断当前播报。
     *
     * ★ 顺序很关键（2026-11-04）：
     *   ① 先把 current 清掉
     *   ② **再** tts.stop()
     *   反过来的话，stop() 触发的 onError 回调（异步，在下一个消息循环）
     *   回来时 current 已经被 null 掉了、新的那���也 drain 出来了，
     *   回调就会把**新播报的那条**误判成「播完了」——
     *   用户听到「说了一半就没声了」（用户反馈：
     *   「停止的时候又触发了没有播报完的语音」）。
     *
     *   现在 finishCurrent(utteranceId) 会按 id 匹配，
     *   老回调找不到对应的 current 就直接忽略，不会误伤新语音。
     */
    @Synchronized
    fun speak(text: String, priority: Priority = Priority.CHAT) {
        if (!ready) return
        // 真有新播报要开始了，硬静音让位给 isSpeaking（否则会双重遮挡）
        hardMuteUntilMs = 0L
        if (priority == Priority.NAV) {
            queue.removeAll { it.priority == Priority.CHAT }
            // 导航播报可以打断用户闲聊，但**不能打断另一条导航播报**。
            // 高速上高德会连续下发「前方测速」「保持左侧」「进入服务区」等提示，
            // 如果每条 NAV 都 stop()，前一条就会只播半句。
            if (current?.priority != Priority.NAV) {
                // ① 先清 current（让 stop() 的回调找不到它，只能被忽略）
                current = null
                setSpeaking(false)
                // ② 再打断引擎
                tts?.stop()
                cloudTts?.stop()
            }
        }
        val order = ++seq
        queue.offer(Utterance("u$order", text, priority, order))
        rememberSpoken(text)
        logger?.invoke(
            "播报入队 id=u$order priority=$priority chars=${text.length} " +
                "current=${current?.id ?: "-"} 队列=${queue.size}"
        )
        drain()
    }

    /**
     * 用户主动打断——**只停闲聊，不动导航**。
     *
     * ★ 2026-11-07 加。原来的打断入口是 [silence]，它把队列连导航播报一起清掉：
     *   用户喊一句「小猪」，正在播的「前方 200 米左转」当场没了。开车时这是安全问题。
     *
     * 现在的语义：
     *   - 当前在播导航（[Priority.NAV]）→ 一个字都不停，返回 false，由调用方去延迟执行；
     *   - 当前在播闲聊/回答 → 停掉，并把队列里待播的闲聊一起扔掉；
     *   - 队列里还排着的导航播报保留（理论上不会出现：导航入队时本来就会抢占闲聊）。
     *
     * @return 是否真的打断了什么
     */
    @Synchronized
    fun bargeInChat(): Boolean {
        val cur = current
        if (cur != null && cur.priority == Priority.NAV) {
            logger?.invoke("打断请求被忽略：正在播导航播报")
            return false
        }
        val had = cur != null || queue.isNotEmpty()
        queue.removeAll { it.priority != Priority.NAV }
        // ★ 先立硬静音窗口，再置 isSpeaking=false。宿主把 inHardMuteWindow()
        //   并进 isSpeakingProvider，所以这 300ms 内麦克风不会被放开。
        hardMuteUntilMs = System.currentTimeMillis() + HARD_MUTE_MS
        // 顺序同 speak()：先清 current，stop() 的回调才会被 finishCurrent 按 id 忽略
        current = null
        setSpeaking(false)
        tts?.stop()
        cloudTts?.stop()
        lastSpokeEndMs = System.currentTimeMillis()
        logger?.invoke("打断闲聊播报 had=$had 剩余队列=${queue.size} 硬静音=${HARD_MUTE_MS}ms")
        if (queue.isNotEmpty()) {
            // 有导航播报排在后面：stop() 是异步的，立刻 drain 可能被
            // `engine.isSpeaking` 挡住，隔几拍再试一次
            retryDrain(3)
        }
        return had
    }

    /** 打断后重试 drain：引擎刚从 stop() 恢复时 isSpeaking 可能还是 true */
    private fun retryDrain(attempt: Int) {
        if (attempt <= 0) return
        main.postDelayed({
            drain()
            if (current == null && queue.isNotEmpty()) retryDrain(attempt - 1)
        }, DRAIN_RETRY_MS)
    }

    /** 立即静音，清空队列（含导航播报）。只给睡眠/退出/切引擎用。 */
    fun silence() {
        queue.clear()
        current = null
        setSpeaking(false)
        tts?.stop()
        cloudTts?.stop()
        lastSpokeEndMs = System.currentTimeMillis()
    }

    fun shutdown() {
        queue.clear()
        tts?.stop()
        tts?.shutdown()
        cloudTts?.stop()
        tts = null
        ready = false
        isAvailable = false
    }

    companion object {
        const val TAG = "VoiceGuide"

        /** 特殊「引擎包名」：走 [cloudTts] 云端合成，不是真的 TTS 引擎 */
        const val CLOUD_ENGINE = "cloud"

        /** 打断后重试 drain 的间隔 */
        const val DRAIN_RETRY_MS = 250L

        /** 打断后的硬静音时长：等 MediaPlayer 真正 stop + 混响衰减 */
        const val HARD_MUTE_MS = 300L

    }
}
