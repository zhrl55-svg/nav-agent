package com.navagent.app

import android.content.Context
import android.content.SharedPreferences
import com.navagent.app.BuildConfig.MINIMAX_ASR_MODEL
import com.navagent.app.BuildConfig.MINIMAX_CHAT_MODEL
import com.navagent.app.BuildConfig.TAVILY_API_KEY
import com.navagent.app.BuildConfig.WAKE_WORDS

/**
 * 运行时可调参数。
 *
 * 全部落 SharedPreferences，读的时候用内存缓存（微秒级），不再套一层本地缓存 ——
 * 设置页改完立刻生效，不用重启。只有**唤醒词**是例外，改完必须重建 vosk Recognizer，
 * 走 [VoiceEngine.reloadWake]。
 *
 * 默认值一律取自 BuildConfig：这样 local.properties / build.gradle.kts 里的配置仍然是
 * "出厂默认"，用户改坏了点恢复默认就能回到编译时的值。
 *
 * ★ init() 必须在 Dbg.init() 之前调用，因为 Dbg 要读 [fileLog] 决定开不开。
 */
object Prefs {

    private const val FILE = "settings"

    private const val K_ASR_MODEL = "asr_model"
    private const val K_CHAT_MODEL = "chat_model"
    private const val K_MAX_TOKENS = "max_tokens"
    private const val K_HISTORY_TURNS = "history_turns"
    private const val K_WAKE_WORDS = "wake_words"
    private const val K_AI_NAME = "ai_name"
    private const val K_WAKE_FOLLOWS_NAME = "wake_follows_name"
    private const val K_NAV_HISTORY = "navigation_history"
    private const val K_IDLE_TIMEOUT = "idle_timeout_ms"
    private const val K_ECHO_GUARD = "echo_guard_ms"
    private const val K_TTS_ENGINE = "tts_engine"
    private const val K_BARGE_IN = "barge_in"
    private const val K_VOICEPRINT = "voiceprint_on"
    private const val K_VOICEPRINT_TH = "voiceprint_threshold"
    private const val K_TTS_VOICE = "tts_voice"
    private const val K_TTS_CLOUD_MODEL = "tts_cloud_model"
    private const val K_TTS_RATE = "tts_rate"
    private const val K_WAKE_BEEP = "wake_beep"
    private const val K_NEARBY_RADIUS = "nearby_radius"
    private const val K_FILE_LOG = "file_log"
    private const val K_TAVILY_KEY = "tavily_key"
    private const val K_BT_TARGET = "bluetooth_target_name"
    private const val K_BT_AUTOSTART = "bluetooth_auto_start"

    /** MiniMax 默认音色：青涩青年 */
    const val DEFAULT_VOICE = "male-qn-qingse"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 设置页「恢复默认」用 */
    fun resetAll() {
        sp.edit().clear().apply()
    }

    // ------------------------------------------------------------ 语音识别

    /** 云端 ASR 模型。MiniMax 目前只有 asr-1.0，留成可编辑是为了以后换版本不用改代码 */
    var asrModel: String
        get() = sp.getString(K_ASR_MODEL, MINIMAX_ASR_MODEL).orEmpty().ifBlank { MINIMAX_ASR_MODEL }
        set(v) = sp.edit().putString(K_ASR_MODEL, v.trim()).apply()

    /**
     * 唤醒词变体，逗号分隔。
     *
     * ★ 改这个必须调 [VoiceEngine.reloadWake]，vosk 的匹配规则在构造时固化。
     */
    var wakeWords: String
        get() = sp.getString(K_WAKE_WORDS, WAKE_WORDS).orEmpty().ifBlank { WAKE_WORDS }
        set(v) = sp.edit().putString(K_WAKE_WORDS, v.trim()).apply()

    /** 拆好的唤醒词列表 */
    fun wakeWordList(): List<String> =
        wakeWords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty {
            WAKE_WORDS.split(",").map { it.trim() }
        }

    /**
     * AI 名字 —— 模型自称、界面标题、导航页气泡前缀都用它。
     *
     * 和唤醒词**解耦**：唤醒词可以是「小度小度」这种叠词，名字只要「小度」。
     * 改名立即生效（ChatEngine 每轮都重新取），不用重载模型。
     */
    var aiName: String
        get() = sp.getString(K_AI_NAME, "小高").orEmpty().ifBlank { "小高" }
        set(v) = sp.edit().putString(K_AI_NAME, v.trim()).apply()

    /** AI 名字和唤醒词是否联动。默认关闭，保留用户已有的独立唤醒配置。 */
    var wakeFollowsName: Boolean
        get() = sp.getBoolean(K_WAKE_FOLLOWS_NAME, false)
        set(v) = sp.edit().putBoolean(K_WAKE_FOLLOWS_NAME, v).apply()

    /** 最近导航目的地，最新在前，最多保留 20 条。SharedPreferences 单字符串避免额外存储依赖。 */
    fun navigationHistory(): List<String> = sp.getString(K_NAV_HISTORY, "").orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun rememberDestination(destination: String) {
        val value = destination.trim()
        if (value.isEmpty()) return
        val updated = (listOf(value) + navigationHistory().filterNot { it.equals(value, ignoreCase = true) })
            .take(MAX_NAV_HISTORY)
        sp.edit().putString(K_NAV_HISTORY, updated.joinToString("\n")).apply()
    }

    private const val MAX_NAV_HISTORY = 20

    /**
     * 界面提示用的主唤醒词 —— 取最短的那个。
     *
     * 列表里第一个通常是「你好小高」，直接拿来拼就成了「唤醒你好小高」，
     * 读起来别扭；取最短的「小高」拼出来是「唤醒小高」，顺口得多。
     */
    fun primaryWakeWord(): String =
        wakeWordList().minByOrNull { it.length } ?: "小高"

    /** 会话休眠：连续这么久没人说话就退出连续会话 */
    var idleTimeoutMs: Long
        get() = sp.getLong(K_IDLE_TIMEOUT, 20_000L)
        set(v) = sp.edit().putLong(K_IDLE_TIMEOUT, v).apply()

    /** 唤醒/命中时是否「嘀」一声 */
    var wakeBeep: Boolean
        get() = sp.getBoolean(K_WAKE_BEEP, true)
        set(v) = sp.edit().putBoolean(K_WAKE_BEEP, v).apply()

    // ------------------------------------------------------------ 对话

    var chatModel: String
        get() = sp.getString(K_CHAT_MODEL, MINIMAX_CHAT_MODEL).orEmpty().ifBlank { MINIMAX_CHAT_MODEL }
        set(v) = sp.edit().putString(K_CHAT_MODEL, v.trim()).apply()

    /** 单条回复的 token 上限 */
    var maxTokens: Int
        get() = sp.getInt(K_MAX_TOKENS, 400)
        set(v) = sp.edit().putInt(K_MAX_TOKENS, v).apply()

    /** 带几轮历史给模型 */
    var historyTurns: Int
        get() = sp.getInt(K_HISTORY_TURNS, 8)
        set(v) = sp.edit().putInt(K_HISTORY_TURNS, v).apply()

    // ------------------------------------------------------------ 播报

    /** TTS 语速。0.8 偏慢（适合嘈杂车内），1.5 偏快 */
    var ttsRate: Float
        get() = sp.getFloat(K_TTS_RATE, 1.0f)
        set(v) = sp.edit().putFloat(K_TTS_RATE, v).apply()

    /** 播报结束后这么久内都不拾音（回声尾巴） */
    var echoGuardMs: Long
        get() = sp.getLong(K_ECHO_GUARD, 1_500L)
        set(v) = sp.edit().putLong(K_ECHO_GUARD, v).apply()

    /**
     * 播报时用户开口就打断播报。
     *
     * 关掉能少误触 —— 车内音响开得很大时，回声可能被当成用户说话。
     */
    var bargeIn: Boolean
        get() = sp.getBoolean(K_BARGE_IN, true)
        set(v) = sp.edit().putBoolean(K_BARGE_IN, v).apply()

    // ------------------------------------------------------------ 声纹

    /**
     * 声纹门禁开关。开了之后只有注册过的声音能被响应。
     *
     * ★ 它是防误触发（乘客说话、导航串音），不是身份认证 ——
     * 算不出 embedding 时会放行，别当锁用。
     */
    var voiceprint: Boolean
        get() = sp.getBoolean(K_VOICEPRINT, true)
        set(v) = sp.edit().putBoolean(K_VOICEPRINT, v).apply()

    /** 余弦阈值。0.35 是桌面实测值（同人 0.56~0.77，异人 -0.09~0.28） */
    var voiceprintThreshold: Float
        get() = sp.getFloat(K_VOICEPRINT_TH, 0.35f)
        set(v) = sp.edit().putFloat(K_VOICEPRINT_TH, v.coerceIn(0.1f, 0.9f)).apply()

    /**
     * TTS 引擎包名。空串 = 系统默认。
     *
     * 这台华为上默认是 `com.huawei.hiai`，另一个可用的是 `com.iflytek.speechsuite`。
     * 改完要 `VoiceGuide.setEngine()` 重建实例才生效。
     */
    var ttsEngine: String
        get() = sp.getString(K_TTS_ENGINE, "").orEmpty()
        set(v) = sp.edit().putString(K_TTS_ENGINE, v.trim()).apply()

    /** 云端合成的音色 id（MiniMax voice_id） */
    var ttsVoiceId: String
        get() = sp.getString(K_TTS_VOICE, DEFAULT_VOICE).orEmpty().ifBlank { DEFAULT_VOICE }
        set(v) = sp.edit().putString(K_TTS_VOICE, v.trim()).apply()

    /** 云端合成用的模型 */
    var ttsCloudModel: String
        get() = sp.getString(K_TTS_CLOUD_MODEL, "speech-02-hd").orEmpty().ifBlank { "speech-02-hd" }
        set(v) = sp.edit().putString(K_TTS_CLOUD_MODEL, v.trim()).apply()

    // ------------------------------------------------------------ 导航

    /** 周边搜索半径（米） */
    var nearbyRadius: Int
        get() = sp.getInt(K_NEARBY_RADIUS, 3_000)
        set(v) = sp.edit().putInt(K_NEARBY_RADIUS, v).apply()

    // ------------------------------------------------------------ 联网

    /**
     * Tavily 搜索 Key。默认用编译时注入的，设置页可以覆盖（换 Key 不用重新打包）。
     *
     * 为什么是 Tavily：MiniMax API 没有内置联网，通用搜索只能接第三方；
     * 实测手机能直连 api.tavily.com（10ms），不像 Serper 那样要挂代理。
     */
    var tavilyKey: String
        get() = sp.getString(K_TAVILY_KEY, TAVILY_API_KEY).orEmpty().ifBlank { TAVILY_API_KEY }
        set(v) = sp.edit().putString(K_TAVILY_KEY, v.trim()).apply()

    fun clearTavilyKeyOverride() = sp.edit().remove(K_TAVILY_KEY).apply()

    // ------------------------------------------------------------ 蓝牙

    /**
     * 蓝牙自动启动的目标设备名关键词。
     *
     * 车机蓝牙名一般带品牌（"Ford""迈巴赫""车机"），用**包含**匹配而不是全等，
     * 因为车机名常有后缀（"Ford Sync""我的车机"）。
     *
     * 空串 = 关闭这个功能。
     */
    var bluetoothTargetName: String
        get() = sp.getString(K_BT_TARGET, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_BT_TARGET, v.trim()).apply()

    /** 蓝牙自动启动总开关 */
    var bluetoothAutoStart: Boolean
        get() = sp.getBoolean(K_BT_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(K_BT_AUTOSTART, v).apply()

    // ------------------------------------------------------------ 诊断

    /** 是否写 /sdcard/.../nav.log。关掉能省一点 IO，但出问题就没日志看了 */
    var fileLog: Boolean
        get() = sp.getBoolean(K_FILE_LOG, true)
        set(v) = sp.edit().putBoolean(K_FILE_LOG, v).apply()
}
