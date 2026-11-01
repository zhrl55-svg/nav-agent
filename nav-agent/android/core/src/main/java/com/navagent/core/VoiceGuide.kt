package com.navagent.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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

    private data class Utterance(val id: String, val text: String, val priority: Priority)

    private val queue = PriorityBlockingQueue(
        11,
        compareBy<Utterance> { it.priority.ordinal }
    )

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    private var current: Utterance? = null
    private var seq = 0

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

    private fun setSpeaking(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
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

    /** 播报结束后 [ms] 毫秒内都算回声区，这段时间不该拾音 */
    fun inEchoWindow(ms: Long = 600L): Boolean =
        System.currentTimeMillis() - lastSpokeEndMs < ms

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
                finishCurrent()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishCurrent()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "播报出错 code=$errorCode")
                finishCurrent()
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
    private fun finishCurrent() {
        current = null
        setSpeaking(false)
        lastSpokeEndMs = System.currentTimeMillis()
        drain()
    }

    @Synchronized
    private fun drain() {
        if (!ready || current != null) return

        // 云端：请求合成 → 播放 → 播完回调 finishCurrent 继续下一条
        if (enginePackage == CLOUD_ENGINE) {
            val cloud = cloudTts ?: return
            val next = queue.poll() ?: return
            current = next
            setSpeaking(true)
            cloud.speak(
                next.text,
                onDone = { finishCurrent() },
                onError = { msg ->
                    Log.w(TAG, "云端合成失败：$msg")
                    // 失败也要收尾，否则队列卡死、拾音永远被挡
                    finishCurrent()
                }
            )
            return
        }

        val engine = tts ?: return
        if (engine.isSpeaking) return
        val next = queue.poll() ?: return
        current = next
        engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, next.id)
    }

    /**
     * 播报。NAV 优先级会清空待播的闲聊并打断当前播报。
     */
    fun speak(text: String, priority: Priority = Priority.CHAT) {
        if (!ready) return
        if (priority == Priority.NAV) {
            queue.removeAll { it.priority == Priority.CHAT }
            tts?.stop()
            current = null
            setSpeaking(false)
        }
        queue.offer(Utterance("u${++seq}", text, priority))
        drain()
    }

    /** 立即静音，清空队列 */
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

    }
}
