package com.navagent.app

import android.content.Context
import android.content.SharedPreferences
import com.navagent.app.BuildConfig.MINIMAX_ASR_MODEL
import com.navagent.app.BuildConfig.MINIMAX_BASE_URL
import com.navagent.app.BuildConfig.MINIMAX_CHAT_MODEL
import com.navagent.app.BuildConfig.WAKE_WORDS

/**
 * 运行时可调参数。
 *
 * 全部落 SharedPreferences，读的时候用内存缓存（微秒级），不再套一层本地缓存 ——
 * 设置页改完立刻生效，不用重启。只有**唤醒词**是例外，改完必须重建 vosk Recognizer，
 * 走 [VoiceEngine.reloadWake]。
 *
 * 地址、模型名等非敏感默认值可取 BuildConfig；API Key 只保存在设置中，
 * 不进入 APK。
 *
 * ★ init() 必须在 Dbg.init() 之前调用，因为 Dbg 要读 [fileLog] 决定开不开。
 */
object Prefs {

    private const val FILE = "settings"

    private const val K_ASR_MODEL = "asr_model"

    /** 语音服务（识别/合成）的 Key 与地址。存储名保留 minimax_*，老版本的设置不会丢 */
    private const val K_API_KEY = "minimax_api_key"
    private const val K_API_BASE = "minimax_api_base"

    /** 对话大脑可以完全独立于语音服务：接本地模型时不能跟语音共用地址 */
    private const val K_CHAT_PROVIDER = "chat_provider"
    private const val K_CHAT_BASE = "chat_api_base"
    private const val K_CHAT_KEY = "chat_api_key"
    private const val K_TOOLS = "chat_tools_enabled"

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
private const val K_VOICEPRINT_BARGE = "voiceprint_barge_in"
private const val K_VOICEPRINT_BARGE_TH = "voiceprint_barge_threshold"
    private const val K_TTS_VOICE = "tts_voice"
    private const val K_TTS_CLOUD_MODEL = "tts_cloud_model"
    private const val K_TTS_RATE = "tts_rate"
    private const val K_NAV_VOICE_MODE = "nav_voice_mode"
    private const val K_WAKE_BEEP = "wake_beep"
    private const val K_NEARBY_RADIUS = "nearby_radius"
    private const val K_FILE_LOG = "file_log"
    private const val K_TAVILY_KEY = "tavily_key"
    private const val K_WEB_SEARCH_MODE = "web_search_mode"
    private const val K_BT_TARGET = "bluetooth_target_name"
    private const val K_BT_AUTOSTART = "bluetooth_auto_start"
    private const val K_AMAP_BUILTIN_UI = "amap_builtin_ui"
    private const val K_NAV_CAR_UP = "nav_car_up"
    private const val K_NAV_AUTO_LOCK = "nav_auto_lock"
    private const val K_NAV_AUTO_ZOOM = "nav_auto_zoom"
    private const val K_NAV_TRAFFIC_LINE = "nav_traffic_line"
    private const val K_NAV_TRAFFIC_LIGHT = "nav_traffic_light"
    private const val K_NAV_SEARCH_BAR = "nav_search_bar"
    private const val K_NAV_ZOOM = "nav_zoom"
    private const val K_CAR_BT_AUDIO = "car_bt_audio"
    private const val K_CAR_NUMBER = "car_number"
    private const val K_MEMORY_USER_ID = "memory_user_id"
    private const val K_MINIS_PROVIDER_MIGRATED = "minis_provider_migrated"

    /** MiniMax 默认音色：青涩青年 */
    const val DEFAULT_VOICE = "male-qn-qingse"

    /** 对话大脑服务商标识 */
    const val PROVIDER_MINIS = "minis"
    const val PROVIDER_MINIMAX = "minimax"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_LOCAL = "local"

    /** P40 上运行的 Minis 本地网关。 */
    const val MINIS_BASE_URL = "http://127.0.0.1:8802/v1"
    const val MINIS_CHAT_MODEL = "minis"

    /** Tavily 搜索工具的三态 */
    const val WEB_SEARCH_AUTO = "auto"
    const val WEB_SEARCH_ON = "on"
    const val WEB_SEARCH_OFF = "off"

    /** 导航播报内容与频次。 */
    const val NAV_VOICE_DETAILED = "detailed"
    const val NAV_VOICE_STANDARD = "standard"
    const val NAV_VOICE_CONCISE = "concise"
    const val NAV_VOICE_OFF = "off"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrateLegacyMinisProvider()
    }

    /**
     * 旧版本把 P40 的 8802 网关归在“本地服务”里，模型名已改成 minis。
     * 精确匹配这组旧预设时升级为独立 Minis 服务商；迁移只执行一次，之后用户
     * 主动选择“其他本地服务”不会被改回。
     */
    private fun migrateLegacyMinisProvider() {
        if (sp.getBoolean(K_MINIS_PROVIDER_MIGRATED, false)) return
        val provider = sp.getString(K_CHAT_PROVIDER, "").orEmpty()
        val base = sp.getString(K_CHAT_BASE, "").orEmpty().trimEnd('/')
        val model = sp.getString(K_CHAT_MODEL, "").orEmpty()
        val editor = sp.edit().putBoolean(K_MINIS_PROVIDER_MIGRATED, true)
        if (provider == PROVIDER_LOCAL && base == MINIS_BASE_URL && model == MINIS_CHAT_MODEL) {
            editor.putString(K_CHAT_PROVIDER, PROVIDER_MINIS)
        }
        editor.apply()
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

    /**
     * 会话休眠：连续这么久没人说话就退出连续会话。
     *
     * ★ 默认从 20 秒提到 60 秒（2026-11-02）。20 秒在车里太激进：
     *   导航途中正常沉默半分钟很常见（听路况、等红灯、跟人说话），
     *   一睡就得重新喊唤醒词。参考 D:/AI 那边默认 300 秒 —— 移动端不用那么长，
     *   但 60 秒能让"问一句、想一下、再问一句"这种节奏不被切断。
     */
    var idleTimeoutMs: Long
        get() = sp.getLong(K_IDLE_TIMEOUT, 60_000L)
        set(v) = sp.edit().putLong(K_IDLE_TIMEOUT, v).apply()

    /** 唤醒/命中时是否「嘀」一声 */
    var wakeBeep: Boolean
        get() = sp.getBoolean(K_WAKE_BEEP, true)
        set(v) = sp.edit().putBoolean(K_WAKE_BEEP, v).apply()

    // ------------------------------------------------------------ 对话

    /**
     * 用户在设置里填的语音服务 Key（识别 + 合成）。空字符串 = 用编译期注入的那个。
     *
     * 为什么要这一层：BuildConfig 里的 Key 是编译时烧进 APK 的，换 Key 就得重新
     * 构建一次；装到别人手机上也没法用他自己的额度。有了这个字段，
     * 装好的 APK 直接在设置页填自己的 Key 就能跑；清空后需要 Key 的服务会停用。
     *
     * ★ 2026-11-02 起它只管**语音**。对话大脑有独立的 Key/地址（见下面的 chat*），
     *   因为本地模型接的是 `http://192.168.x.x:1234/v1`，跟语音服务根本不是一个服务。
     *   混用一个字段的话，改对话大脑的地址会把语音识别也一起改坏。
     */
    var voiceKeyRaw: String
        get() = sp.getString(K_API_KEY, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_API_KEY, v.trim()).apply()

    /** 语音服务地址。空 = 用编译期的。换中转 / 私有部署时填 */
    var voiceBaseRaw: String
        get() = sp.getString(K_API_BASE, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_API_BASE, v.trim()).apply()

    /** 语音服务实际生效的 Key。Key 只来自设置页。 */
    fun voiceApiKey(): String = voiceKeyRaw

    /** 语音服务实际生效的地址：用户填了用用户的，没填用出厂的 */
    fun voiceBaseUrl(): String = voiceBaseRaw.ifEmpty { MINIMAX_BASE_URL }

    /** 是否已配置语音服务 Key（设置页显示状态用） */
    fun voiceKeyConfigured(): Boolean = voiceKeyRaw.isNotEmpty()

    // ------------------------------------------------------------ 对话大脑

    /** 对话大脑服务商。只用于设置页的预设和文案，运行时真正生效的是地址 + 模型名 */
    var chatProvider: String
        get() = sp.getString(K_CHAT_PROVIDER, PROVIDER_MINIS).orEmpty().ifBlank { PROVIDER_MINIS }
        set(v) = sp.edit().putString(K_CHAT_PROVIDER, v.trim()).apply()

    /**
     * 对话大脑的接口地址（OpenAI 兼容，`{地址}/chat/completions`）。
     *
     * 空 = 跟随语音服务（也就是 MiniMax 官方）。填了就用填的 ——
     * 本地跑 Ollama / LM Studio / vLLM 时这里填 `http://电脑IP:端口/v1`。
     */
    var chatBaseRaw: String
        get() = sp.getString(K_CHAT_BASE, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_CHAT_BASE, v.trim()).apply()

    /** 对话大脑的 Key。本地模型通常不需要，允许留空 */
    var chatKeyRaw: String
        get() = sp.getString(K_CHAT_KEY, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_CHAT_KEY, v.trim()).apply()

    fun chatBaseUrl(): String = chatBaseRaw.ifEmpty {
        if (chatProvider == PROVIDER_MINIS) MINIS_BASE_URL else MINIMAX_BASE_URL
    }

    /**
     * 对话大脑实际使用的 Key。
     *
     * - 没填自定义地址 → 跟随语音服务（同一家 MiniMax，一把 Key 通吃，老配置自动兼容）
     * - 填了自定义地址 → **只**用自己填的；留空就不发 Authorization 头
     *   （本地 llama.cpp / Ollama 大多不校验，发个假 Key 反而被某些网关拒）
     */
    fun chatApiKey(): String =
        if (chatBaseRaw.isEmpty() && chatProvider == PROVIDER_MINIMAX) voiceApiKey() else chatKeyRaw

    /** 是否接了自定义 / 本地接口 */
    fun chatEndpointIsCustom(): Boolean =
        chatBaseRaw.isNotEmpty() || chatProvider != PROVIDER_MINIMAX

    /** 地址是不是本机或局域网。设置页文案和「免 Key」提示用 */
    fun chatEndpointIsLocal(): Boolean {
        val host = runCatching { java.net.URI(chatBaseUrl()).host }.getOrNull()
            ?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true
        val p = host.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /**
     * 是否允许模型调用工具（导航 / 搜索 / 天气 / 周边）。
     *
     * ★ 必须能关：本地跑的小模型（7B 以下）大多不会 Function Calling，
     *   开着它只会得到一段"我这就帮你导航"的空话。关掉至少还能当聊天用。
     */
    var toolsEnabled: Boolean
        get() = sp.getBoolean(K_TOOLS, true)
        set(v) = sp.edit().putBoolean(K_TOOLS, v).apply()

    /** Key 的脱敏展示：只留后 4 位 */
    fun maskedVoiceKey(): String = mask(voiceApiKey())

    fun maskedChatKey(): String = mask(chatApiKey())

    private fun mask(k: String): String = when {
        k.isBlank() -> "未配置"
        k.length <= 8 -> "已配置"
        else -> "…" + k.takeLast(4)
    }

    /**
     * 对话模型名。留空时按服务商给一个合理的默认值 ——
     * 接了本地模型却还默认 `minimax-m3.1-flash` 的话，请求必然 404，
     * 用户会以为"本地接口不通"。
     */
    var chatModel: String
        get() = sp.getString(K_CHAT_MODEL, "").orEmpty().ifBlank { defaultChatModel() }
        set(v) = sp.edit().putString(K_CHAT_MODEL, v.trim()).apply()

    private fun defaultChatModel(): String = when (chatProvider) {
        PROVIDER_MINIS -> MINIS_CHAT_MODEL
        PROVIDER_LOCAL -> "qwen2.5:7b"
        PROVIDER_OPENAI -> "gpt-4o-mini"
        else -> MINIMAX_CHAT_MODEL
    }

    /**
     * 设备本地身份。Minis 等支持记忆的网关可以据此区分用户，其他接口会忽略。
     * 该值不是密钥，也不会上传到本项目自己的服务器。
     */
    fun memoryUserId(): String {
        val existing = sp.getString(K_MEMORY_USER_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        return synchronized(this) {
            sp.getString(K_MEMORY_USER_ID, "").orEmpty().ifBlank {
                java.util.UUID.randomUUID().toString().also {
                    sp.edit().putString(K_MEMORY_USER_ID, it).apply()
                }
            }
        }
    }

    fun conversationId(): String = "${memoryUserId()}-default"

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

    /**
     * 导航播报模式。
     *
     * 详细：完整播报高德下发的文本。
     * 标准：过滤明显低价值提示和短时间重复文本。
     * 简洁：只播转向、出口、并道、限速、拥堵改道等关键提示。
     * 关闭：不播导航提示，但用户主动对话的语音回复不受影响。
     */
    var navVoiceMode: String
        get() = sp.getString(K_NAV_VOICE_MODE, NAV_VOICE_DETAILED)
            .orEmpty()
            .takeIf { it in NAV_VOICE_MODES }
            ?: NAV_VOICE_DETAILED
        set(v) = sp.edit().putString(
            K_NAV_VOICE_MODE,
            v.takeIf { it in NAV_VOICE_MODES } ?: NAV_VOICE_DETAILED
        ).apply()

    fun navVoiceModeLabel(): String = when (navVoiceMode) {
        NAV_VOICE_DETAILED -> "详细"
        NAV_VOICE_CONCISE -> "简洁"
        NAV_VOICE_OFF -> "关闭"
        else -> "标准"
    }

    private val NAV_VOICE_MODES = setOf(
        NAV_VOICE_DETAILED,
        NAV_VOICE_STANDARD,
        NAV_VOICE_CONCISE,
        NAV_VOICE_OFF
    )

    /** 播报结束后这么久内都不拾音（回声尾巴） */
    var echoGuardMs: Long
        get() = sp.getLong(K_ECHO_GUARD, 1_500L)
        set(v) = sp.edit().putLong(K_ECHO_GUARD, v).apply()

    /** 播报期间允许唤醒词立即停播并开始拾音。 */
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
     * 声纹参与「打断播报」判定。
     *
     * ★ 和 [voiceprint] 分开，是两个不同的开关：
     *   [voiceprint] 管的是**平时听你说话**要不要放行（关掉是为了识别更宽松）；
     *   这个管的是**播报中谁有资格打断**。
     *
     * 为什么分开：用户为了「随时能打断」把 [voiceprint] 关了（关了之后声纹一开就
     * 打断不了，命令都识别不出来），结果播报这边也没有第二道防线，
     * 助手回答里带一句「小猪」就能把播报掐断，用户喊了也没反应。
     *
     * 打开后：播报中命中唤醒词必须再过声纹确认，主人的近场人声连续两次过线就直接打断。
     * 声纹没注册 / 模型没就绪时自动退回「唤醒词直断」，不会把设备锁死。
     */
    var voiceprintBargeIn: Boolean
        get() = sp.getBoolean(K_VOICEPRINT_BARGE, true)
        set(v) = sp.edit().putBoolean(K_VOICEPRINT_BARGE, v).apply()

    /**
     * 打断播报用的声纹阈值（和 [voiceprintThreshold] 分开）。
     *
     * ★ 实测数据（华为 P40 Pro，2026-11-04）：
     *   播报回声（TTS 自己）  0.02 ~ 0.11
     *   主人声音混在播报里    0.39 ~ 0.52
     *   主人安静时（刚注册）  0.89
     *
     *   0.45（平时的匹配阈值）正好卡在中间 —— 回声挡得住，主人也被挡住，
     *   真机上表现就是「喊了无数遍小猪都没反应」。
     *   打断这条路径取 0.28：回声最高 0.11，主人最低 0.39，分得很开。
     */
    var voiceprintBargeThreshold: Float
        get() = sp.getFloat(K_VOICEPRINT_BARGE_TH, 0.28f)
        set(v) = sp.edit().putFloat(K_VOICEPRINT_BARGE_TH, v.coerceIn(0.15f, 0.6f)).apply()

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

    /**
     * 导航页用高德官方内置界面，还是自己画的 HUD。
     *
     * true  = 高德内置界面（转向图、车道线、全览按钮等官方样式）
     * false = 自绘 HUD（原来的做法，只显示转向箭头+距离+路名+剩余）
     *
     * 默认开启高德内置界面：官方界面信息更全（车道级导航、路况条、限速牌），
     * 自己维护这套 UI 成本高且更新慢。
     */
    var amapBuiltinUi: Boolean
        get() = sp.getBoolean(K_AMAP_BUILTIN_UI, true)
        set(v) = sp.edit().putBoolean(K_AMAP_BUILTIN_UI, v).apply()

    /**
     * 导航视角：true = 车头朝上（跟随转向），false = 正北朝上。
     *
     * ★ 2026-11-03：用户反馈「地图有时候不能自动调整方向和中心」。
     *   原来 `isAutoLockCar` 写死 false，地图就不会跟着车走 ——
     *   车开出几个路口，车标已经跑到屏幕边缘甚至屏幕外，看着像「地图卡住了」。
     *   改成默认 true（锁车跟随），并且把视角做成可选：跑长途看全局用北朝上。
     */
    var navCarUp: Boolean
        get() = sp.getBoolean(K_NAV_CAR_UP, true)
        set(v) = sp.edit().putBoolean(K_NAV_CAR_UP, v).apply()

    /** 自动锁车：车标固定在地图中心，地图随车移动 */
    var navAutoLock: Boolean
        get() = sp.getBoolean(K_NAV_AUTO_LOCK, true)
        set(v) = sp.edit().putBoolean(K_NAV_AUTO_LOCK, v).apply()

    /** 自动缩放：按车速自动拉近/拉远 */
    var navAutoZoom: Boolean
        get() = sp.getBoolean(K_NAV_AUTO_ZOOM, true)
        set(v) = sp.edit().putBoolean(K_NAV_AUTO_ZOOM, v).apply()

    /** 路况线：路线按拥堵着色。关掉是纯蓝线 */
    var navTrafficLine: Boolean
        get() = sp.getBoolean(K_NAV_TRAFFIC_LINE, true)
        set(v) = sp.edit().putBoolean(K_NAV_TRAFFIC_LINE, v).apply()

    /** 红绿灯：地图图标 + 提示视图 + SDK 信号数据 */
    var navTrafficLight: Boolean
        get() = sp.getBoolean(K_NAV_TRAFFIC_LIGHT, true)
        set(v) = sp.edit().putBoolean(K_NAV_TRAFFIC_LIGHT, v).apply()

    /** 顶部地址搜索栏是否显示 */
    var navSearchBar: Boolean
        get() = sp.getBoolean(K_NAV_SEARCH_BAR, true)
        set(v) = sp.edit().putBoolean(K_NAV_SEARCH_BAR, v).apply()

    /** 未锁车时的地图缩放级别（3~20） */
    var navZoom: Int
        get() = sp.getInt(K_NAV_ZOOM, 16)
        set(v) = sp.edit().putInt(K_NAV_ZOOM, v.coerceIn(3, 20)).apply()

    /**
     * 车载蓝牙播报。
     *
     * ★ 为什么需要这个开关：车机蓝牙常常只接通话通道（HFP），没接媒体通道（A2DP）。
     *   MediaPlayer 默认走 USAGE_MEDIA，这种车机上就完全没有声音；
     *   高德能响是因为它把播报标成「导航语音」，系统会把它往蓝牙通话通道送。
     *
     *   打开后：播报期间把音频模式切到通信模式并启用蓝牙 SCO，播完立刻恢复。
     *   关掉则只改 AudioAttributes（更省事，但个别车机仍可能没声）。
     */
    var carBluetoothAudio: Boolean
        get() = sp.getBoolean(K_CAR_BT_AUDIO, true)
        set(v) = sp.edit().putBoolean(K_CAR_BT_AUDIO, v).apply()

    /**
     * 车牌号（如「甘G12345」）。
     *
     * ★ 为什么要填：高德官方文档说「小客车算路**只考虑车牌因素**」——
     *   限行是按车牌尾号算的（尾号限行、单双号限行）。
     *   不填车牌只能规避「结构性禁行」（限高杆、限宽路、货车道）。
     *
     * 填了之后算路会主动绕开自己不能走的那天。
     */
    var carNumber: String
        get() = sp.getString(K_CAR_NUMBER, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_CAR_NUMBER, v.trim().uppercase()).apply()

    // ------------------------------------------------------------ 联网

    /**
     * Tavily 搜索 Key。只从设置页读取，不进入 APK。
     *
     * 为什么是 Tavily：MiniMax API 没有内置联网，通用搜索只能接第三方；
     * 实测手机能直连 api.tavily.com（10ms），不像 Serper 那样要挂代理。
     */
    var tavilyKey: String
        get() = sp.getString(K_TAVILY_KEY, "").orEmpty().trim()
        set(v) = sp.edit().putString(K_TAVILY_KEY, v.trim()).apply()

    fun clearTavilyKeyOverride() = sp.edit().remove(K_TAVILY_KEY).apply()

    /**
     * 是否把 Tavily 搜索工具交给模型。
     *
     * ★ 2026-11-02：接本地接口时**默认不给**。
     *
     *   本地那套服务（127.0.0.1:8802）自己就带完整的检索能力，模型拿到问题会直接
     *   在服务端查完再答。这时候再塞一个 Tavily 的 `web_search` 进去是重复劳动，
     *   而且是**更差的那一份**：Tavily 要 5 秒、要单独的 Key、搜回来的中文新闻
     *   还经常是一堆短讯。模型看到两个搜索入口反而会挑错。
     *
     *   所以三态：
     *     auto（默认）—— 用本地/自定义接口时关，用云端 MiniMax 时开
     *     on          —— 强制开（本地服务没搜索能力时手动打开）
     *     off         —— 强制关
     */
    var webSearchMode: String
        get() = sp.getString(K_WEB_SEARCH_MODE, WEB_SEARCH_AUTO).orEmpty().ifBlank { WEB_SEARCH_AUTO }
        set(v) = sp.edit().putString(K_WEB_SEARCH_MODE, v.trim()).apply()

    /** 实际是否启用 Tavily 搜索工具 */
    fun webSearchEnabled(): Boolean = when (webSearchMode) {
        WEB_SEARCH_ON -> true
        WEB_SEARCH_OFF -> false
        else -> !chatEndpointIsCustom()
    }

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
