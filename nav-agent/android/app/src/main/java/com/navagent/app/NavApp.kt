package com.navagent.app

import android.app.Application
import android.content.Intent
import com.navagent.core.ChatEngine
import com.navagent.core.VoiceGuide
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级单例。三个东西必须活得比 Activity 久：
 *  - [voice]  TTS 队列（导航播报和聊天回复共用，切页不能断）
 *  - [chat]   对话历史 + 大脑（切到导航页再切回来，上下文不能丢）
 *  - [nav]    导航引擎（由 NavActivity 挂上来，聊天页问"还有多久"要读它）
 */
class NavApp : Application() {

    lateinit var voice: VoiceGuide
        private set

    lateinit var chat: ChatEngine
        private set

    /** App 本地长期记忆，独立于当前对话模型和网关。 */
    lateinit var memory: LocalMemoryStore
        private set

    /**
     * 车载蓝牙播报路由。
     *
     * 必须进程级单例：播报和录音是同一套音频通道，
     * Activity 重建时如果丢了这个对象，SCO 就没人负责关了 ——
     * 结果是麦克风一直卡在通信模式，识别全废。
     */
    lateinit var carAudio: CarAudioRoute
        private set

    /** 导航控制器，NavActivity 创建时挂上、销毁时摘掉 */
    @Volatile
    var nav: AmapNavController? = null

    /** 共享的导航工具实例；本地确定性兜底和模型 Function Calling 共用执行路径 */
    @Volatile
    private var navigationTool: NavigationTool? = null

    /** 当前导航目的地，给 system prompt 用 */
    @Volatile
    var destination: String? = null

    /**
     * 声纹门禁。模型还没下好时是 null —— [VoiceEngine] 那边会自动放行，
     * 所以下载过程中不影响正常使用。
     */
    @Volatile
    var voiceprintGate: VoiceprintGate? = null
        private set

    /** 前台 Activity 注册自己来收聊天增量 */
    private val chatObservers = CopyOnWriteArrayList<ChatUi>()

    /** 语音状态观察者（聊天页、导航页各一个） */
    private val voiceObservers = CopyOnWriteArrayList<VoiceUi>()

    @Volatile
    private var currentVoiceState: Pair<VoiceEngine.State, String> =
        VoiceEngine.State.ASLEEP to "待命"

    @Volatile
    var voiceStateSnapshotProvider: (() -> Pair<VoiceEngine.State, String>)? = null

    /**
     * 语音引擎引用。由 [VoiceService] 挂上。
     *
     * 工具层要用它做「主动休眠」（用户说「你休息吧」时）。
     * 有了这个就不用绕 ACTION_SLEEP 的 Intent 通路。
     */
    @Volatile
    var voiceEngineProvider: (() -> VoiceEngine)? = null

    interface ChatUi {
        fun onUser(text: String)
        fun onAssistantDelta(text: String)
        fun onAssistantDone(full: String)
    }

    interface VoiceUi {
        fun onVoiceState(state: VoiceEngine.State, hint: String)
        fun onNotice(text: String)
    }

    fun addVoiceUi(ui: VoiceUi) {
        if (!voiceObservers.contains(ui)) voiceObservers.add(ui)
        // 页面恢复时以回调执行时的引擎状态为准，避免排队中的旧快照覆盖新状态。
        val refresh = Runnable { if (voiceObservers.contains(ui)) refreshVoiceUi(ui) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(refresh)
        }
    }

    fun removeVoiceUi(ui: VoiceUi) {
        voiceObservers.remove(ui)
    }

    fun dispatchVoiceState(state: VoiceEngine.State, hint: String) {
        currentVoiceState = state to hint
        voiceObservers.forEach { it.onVoiceState(state, hint) }
    }

    fun refreshVoiceUi(ui: VoiceUi) {
        val (state, hint) = voiceStateSnapshotProvider?.invoke() ?: currentVoiceState
        currentVoiceState = state to hint
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            ui.onVoiceState(state, hint)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (voiceObservers.contains(ui)) ui.onVoiceState(state, hint)
            }
        }
    }

    fun dispatchNotice(text: String) {
        voiceObservers.forEach { it.onNotice(text) }
    }

    override fun onCreate() {
        super.onCreate()
        // 顺序不能反：Dbg.init 要读 Prefs.fileLog 决定开不开
        Prefs.init(this)
        memory = LocalMemoryStore(this)
        Dbg.init(this)
        // 高德 SDK 的隐私合规。必须在任何 SDK 调用（定位/地图/搜索）之前做，
        // 否则高德会静默失败——查不到城市、周边搜索返回空，都查不出原因。
        AmapSdkGeocoder.init(this)
        // 蓝牙自动启动：应用一启动就注册，否则杀进程重启后功能失效
        //（设置里只管开关和目标名，注册逻辑放在这里才能扛住进程重启）。
        // 没权限时注册会抛 SecurityException，捕获掉即可——用户可以在设置页授权。
        if (Prefs.bluetoothAutoStart) {
            runCatching { BluetoothAutoStart.register(this) }
                .onFailure { Dbg.log("蓝牙自动启动注册失败：${it.message}") }
        }
        // 引擎包名构造时传入，避免建完再切一次（那会多一次 TTS 初始化的抖动）
        voice = VoiceGuide(this, Prefs.ttsEngine)
        // 云端合成器：设置里选「MiniMax 云端」时才会被用到
        voice.cloudTts = MinimaxTts(this)
        // ★ 车载蓝牙路由：播报走 A2DP 媒体通道，不切通信模式或 SCO
        carAudio = CarAudioRoute(this)
        voice.speakWindowListener = { speaking, navPriority ->
            runCatching {
                if (speaking) carAudio.begin(navPriority) else carAudio.end()
            }.onFailure { Dbg.log("车载音频路由异常：${it.message}") }
        }
        // TTS 的问题在华为机上 logcat 看不到，接到文件日志里
        voice.logger = { Dbg.log("VoiceGuide: $it") }
        voice.setRate(Prefs.ttsRate)

        val navTool = NavigationTool(
            navigateTo = ::toolNavigate,
            navigateToPoi = ::toolNavigateToPoi,
            stop = ::toolStop,
            openMap = ::toolOpenMap,
            lastNearbyName = { freshNearby()?.name },
            sleep = ::toolSleep,
            // ★ 候选确认（2026-11-04）：语音说地址时先搜候选给用户确认
            searchPlaces = { keyword -> searchPlaceCandidates(keyword) },
            showCandidates = { keyword, pois -> setPendingPois(keyword, pois) },
            currentCandidates = { pendingPois },
            clearCandidates = { selected -> clearPendingPois(selected) },
            lastConfirmedPoi = ::freshLastConfirmedPoi
        )
        navigationTool = navTool

        chat = ChatEngine(
            // 模型原生 Function Calling 优先；明确的出发指令另有本地兜底，
            // 避免不支持工具调用的本地模型只说「好的」却不执行。
            llm = OpenAiLlm(
                tools = listOf(
                    navTool,
                    WeatherTool { nav?.currentCity },
                    NearbyTool(
                        context = this,
                        location = { nav?.currentLocation() },
                        route = { nav?.remainingRoutePoints() },
                        radius = { Prefs.nearbyRadius },
                        onResult = {
                            // 模型搜完之后用户只说「去这家」，navigate 工具能拿它兜底
                            lastNearby = it
                            lastNearbyAt = System.currentTimeMillis()
                        }
                    ),
                    WebSearchTool { Prefs.tavilyKey },
                    // 需要 Context：WebView 兜底要在本机浏览器内核里渲染页面
                    WebExtractTool(this) { Prefs.tavilyKey }
                )
            ),
            onDelta = { piece -> chatObservers.forEach { it.onAssistantDelta(piece) } },
            onReply = { full -> chatObservers.forEach { it.onAssistantDone(full) } },
            speak = { text -> voice.speak(text, VoiceGuide.Priority.CHAT) },
            context = ::navContext,
            // 名字固化在 prompt 里，每轮都从设置取 —— 改名下一句就自称新名字
            assistantName = { Prefs.aiName },
            // 两次请求全失败才算"没连上"。用系统提示的形式告知，
            // 不伪装成 AI 的对话气泡 —— 这条不会进对话历史。
            onFailure = { msg -> dispatchNotice(msg) },
            // 接本地接口时不给 Tavily（本地服务自带检索），prompt 也要跟着改口
            webSearchAvailable = { Prefs.webSearchEnabled() },
            interceptAction = ::interceptNavigation,
            memory = memory
        )

        prewarm()
        initVoiceprint()
    }

    /**
     * 声纹模型：有就挂上，没有就后台下一个（26MB，不挡启动）。
     */
    private fun initVoiceprint() {
        val root = getExternalFilesDir(null) ?: filesDir
        val model = ModelStore.voiceprintModel(root)
        if (model != null) {
            attachVoiceprint(model, root)
            return
        }
        Thread {
            val f = ModelStore.ensureVoiceprint(root) { Dbg.log("Voiceprint 模型：$it") }
            if (f != null) {
                attachVoiceprint(f, root)
            } else {
                Dbg.log("声纹模型不可用，门禁保持关闭（全部放行）")
            }
        }.apply { isDaemon = true; name = "wp-download" }.start()
    }

    @Synchronized
    private fun attachVoiceprint(model: File, root: File) {
        if (voiceprintGate != null) return
        val g = VoiceprintGate(
            SpeakerEncoder(model),
            File(root, "voiceprint.bin"),
            threshold = { Prefs.voiceprintThreshold }
        )
        voiceprintGate = g
        g.warmup()
        Dbg.log(
            "声纹门禁已挂载：${model.length() / 1024 / 1024}MB " +
                "model=${model.name}"
        )
    }

    /**
     * 设置页改完之后调用，把能立即生效的设置刷一遍。
     *
     * 唤醒词是唯一的例外 —— 它固化在 vosk Recognizer 里，得走
     * [VoiceService.reloadWake] 重建，由设置页自己触发。
     */
    fun applySettings() {
        Dbg.enabled = BuildConfig.DEBUG && Prefs.fileLog
        voice.setRate(Prefs.ttsRate)
        Dbg.log(
            "设置生效 chat=${Prefs.chatModel} asr=${Prefs.asrModel} " +
                "idle=${Prefs.idleTimeoutMs} echo=${Prefs.echoGuardMs} " +
                "radius=${Prefs.nearbyRadius} log=${Prefs.fileLog}"
        )
    }

    /**
     * DNS + TCP 预热。
     *
     * 实测第一句聊天要 2.9~3.0s 才出首字，后面只要 1.1~1.5s —— 差的那 1.5 秒
     * 全花在域名解析和 TLS 握手上。启动时先握一次，把这段成本挪到用户看不见的时候。
     *
     * ★ 2026-11-02：端口从地址里取，不再写死 443。
     *   接本地模型时地址是 `http://192.168.1.100:1234/v1`，往 443 上握
     *   等于握了个空，还白等 4 秒超时。
     */
    private fun prewarm() {
        Thread {
            val uri = runCatching { java.net.URI(Prefs.chatBaseUrl()) }.getOrNull()
                ?: return@Thread
            val host = uri.host ?: return@Thread
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("http", ignoreCase = true) -> 80
                else -> 443
            }
            val t0 = System.currentTimeMillis()
            runCatching {
                val addr = java.net.InetAddress.getByName(host)
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(addr, port), 4000) }
                Dbg.log("预热 $host:$port 完成 ${System.currentTimeMillis() - t0}ms")
            }.onFailure { Dbg.log("预热失败 ${it.javaClass.simpleName}: ${it.message}") }
        }.apply { isDaemon = true; name = "prewarm" }.start()
    }

    fun addChatUi(ui: ChatUi) {
        if (!chatObservers.contains(ui)) chatObservers.add(ui)
    }

    fun removeChatUi(ui: ChatUi) {
        chatObservers.remove(ui)
    }

    // ------------------------------------------------------------ 地点候选

    /**
     * 地点候选展示者。
     *
     * ★ 为什么单独一个接口而不是塞进 ChatUi：
     *   ChatUi 是「对话消息」的通路（用户说了什么、模型答了什么），
     *   这里要传的是**结构化候选列表 + 关键词**，
     *   混在一起以后 ChatUi 会变成什么都能塞的大杂烩。
     *
     * ★ 为什么要展示候选（2026-11-04）：
     *   语音说「导航去XX」直接解析地名，解析歪了没人知道 ——
     *   可能导航到 400 公里外的同名 POI，用户全程不知情。
     *   先搜候选、摆出来让用户点/说编号，才靠谱。
     */
    interface PlaceSearchUi {
        /** 搜到候选了：填进搜索框 + 弹出候选列表 */
        fun onPlaceCandidates(keyword: String, pois: List<AmapSdkGeocoder.Poi>)

        /** 搜索失败/无结果 */
        fun onPlaceSearchFailed(keyword: String, reason: String)

        /**
         * 候选已确认（用户选完了），把列表收起来。
         *
         * ★ 为什么需要（2026-11-04）：
         *   语音确认走的是「模型调工具」这条路，不经过点击处理，
         *   列表没人收 —— 用户确认完了列表还挂在屏幕上（用户反馈）。
         *
         * @param selected 用户选中的那个（可空 —— 没有匹配到具体 POI 时）
         */
        fun onPlaceCandidatesCleared(selected: AmapSdkGeocoder.Poi?)
    }

    private val placeSearchObservers = CopyOnWriteArrayList<PlaceSearchUi>()

    fun addPlaceSearchUi(ui: PlaceSearchUi) {
        if (!placeSearchObservers.contains(ui)) placeSearchObservers.add(ui)
    }

    fun removePlaceSearchUi(ui: PlaceSearchUi) {
        placeSearchObservers.remove(ui)
    }

    /**
     * 待确认的候选（导航工具搜出来后暂存）。
     *
     * ★ 为什么要存：
     *   模型搜到候选后问用户「是哪个」，用户回「2」。
     *   模型再调 navigate(destination="第二个") —— 它未必会把完整名字给你，
     *   可能就回个序号。所以这里存着 navigate 时好按序号/名字回查。
     *
     * ★ 存在 Application 而不是 Activity：
     *   导航工具跑在 Application 的子线程里，跟 Activity 生命周期无关。
     */
    @Volatile
    var pendingPois: List<AmapSdkGeocoder.Poi> = emptyList()
        private set

    /** 待确认候选对应的关键词（用户/模型后续提到它时用来判断是不是同一批） */
    @Volatile
    var pendingKeyword: String = ""
        private set

    /**
     * 最近一次**用户确认过**的候选（带精确坐标）。
     *
     * ★ 为什么留着（2026-11-04）：
     *   候选列表用完就清，但目的地本身还可能再被提到
     *   （比如模型在闲聊后自作主张又调一次 navigate）。
     *   有了这个坐标，同名地点再怎么解析都不会跑到外地去。
     */
    @Volatile
    var lastConfirmedPoi: AmapSdkGeocoder.Poi? = null
        private set

    /** lastConfirmedPoi 的记录时刻（毫秒），用来判过期 */
    @Volatile
    private var lastConfirmedAt = 0L

    /**
     * 最近一次**用户确认过**的候选（带坐标），超过 30 分钟就不算数。
     *
     * ★ 为什么要时效：
     *   一路导航可能开几小时。用户中途说「去刚才那个地方」，
     *   几小时前确认的候选早就不是他说的那个了。
     */
    fun freshLastConfirmedPoi(): AmapSdkGeocoder.Poi? {
        val poi = lastConfirmedPoi ?: return null
        if (System.currentTimeMillis() - lastConfirmedAt > CONFIRMED_TTL_MS) {
            Dbg.log("已确认的候选过期 chars=${poi.name.length}")
            return null
        }
        return poi
    }

    /** 记下候选并推给界面 */
    fun setPendingPois(keyword: String, pois: List<AmapSdkGeocoder.Poi>) {
        pendingPois = pois
        pendingKeyword = keyword
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            placeSearchObservers.forEach { it.onPlaceCandidates(keyword, pois) }
        }
    }

    /**
     * 清掉待确认候选（用户已确认 / 已导航 / 换了新需求）。
     *
     * @param selected 用户选中的那个（语音确认时传，界面会把它写进搜索框）
     */
    fun clearPendingPois(selected: AmapSdkGeocoder.Poi? = null) {
        pendingPois = emptyList()
        pendingKeyword = ""
        // ★ 记住用户确认过的那个（带坐标）——
        //   之后模型再提到这个名字时，用坐标而不是重新解析地名
        //   （重新解析会命中同名的外地地点，实测「水勘院超市」→ 石家庄，1517 公里）。
        if (selected != null) {
            lastConfirmedPoi = selected
            lastConfirmedAt = System.currentTimeMillis()
            Dbg.log("记住已确认的候选 chars=${selected.name.length}")
        }
        // ★ 通知界面把候选列表收起来（2026-11-04）。
        //   语音确认走的是「模型调工具」这条路，不经过点击处理，
        //   列表没人收 —— 用户确认完了列表还挂在屏幕上（用户反馈）。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            placeSearchObservers.forEach { it.onPlaceCandidatesCleared(selected) }
        }
    }

    /** 语音识别的结果统一从这里进 */
    fun submit(text: String) {
        Dbg.log("submit chars=${text.length}")
        // 先让界面把"用户说了什么"显示出来，再进大脑
        chatObservers.forEach { it.onUser(text) }
        if (!chat.send(text)) {
            Dbg.log("submit 被忽略（上一句还在处理）")
        }
    }

    // ------------------------------------------------------------ 导航工具

    /**
     * 本地兜底：OpenAI 兼容接口声明了 tools，但部分模型仍只返回文字。
     * 对明确的导航命令，或对「要不要去 X」的肯定答复，直接复用同一个导航工具执行。
     */
    private fun interceptNavigation(text: String, history: List<com.navagent.core.ChatMessage>): String? {
        val normalized = text.trim().lowercase()
        val affirmative = normalized.trimEnd('。', '！', '？', '!', '?', '.', ' ')
            .removeSuffix("啊").removeSuffix("吧")
            .let { it in setOf("去", "好", "好啊", "可以", "行", "走", "走吧", "就去", "去吧") }
        val priorAssistant = history.dropLast(1)
            .lastOrNull { it.role == com.navagent.core.ChatMessage.Role.ASSISTANT }?.text.orEmpty()
        val askedToGo = priorAssistant.contains(Regex("(要不要|是否|想不想|去不去|要去).{0,30}(吗|么|？|\\?)"))
        val explicit = Regex("(导航(过去|到|去)|带我(去|到)|开(到|过去)|前往|去一下|就去)")
            .containsMatchIn(normalized) && !Regex("(不要|别|不用|先不|不想|停止|取消).{0,6}(导航|去|前往)")
            .containsMatchIn(normalized)
        if (!explicit && !(affirmative && askedToGo)) return null

        val referential = Regex("(刚才那个位置|那个位置|刚才那里|刚才那个地方|这个地方|这个位置|这家|那家|那里|那儿|过去)")
            .containsMatchIn(normalized)
        val destination = (if (!referential) extractDestination(text) else null)
            ?: extractDestination(priorAssistant)
            ?: destinationMentionedBeforeQuestion(priorAssistant)
            ?: freshNearby()?.name
        if (destination.isNullOrBlank()) {
            Dbg.log("导航兜底命中但无法从上下文确定目的地 chars=${text.length}")
            return null
        }

        val args = org.json.JSONObject()
            .put("action", "navigate")
            .put("destination", destination)
            .toString()
        val result = navigationTool?.executeDetailed(args) ?: return null
        // ★ ★ 关键（2026-11-04）：工具返回「搜到 N 个候选」时**不能**在这里接管。
        //   那是**中间态**，不是导航结果 —— 接管了就等于跳过用户确认，
        //   模型根本没机会问「是哪个」，用户看到的就是直接导航去了（用户反馈）。
        //
        //   这里返回 null，让流程继续走模型：
        //     候选已经展示在地图的搜索框/列表里了（用户看得见），
        //     候选也存进了 pendingPois，navContext() 会把它们列进 system prompt，
        //     模型据此列 1、2、3 问用户。
        if (result.status == NavigationToolResult.Status.CANDIDATES) {
            Dbg.log("导航兜底让位给模型（搜到候选，等用户确认）")
            return null
        }
        Dbg.log(
            "导航兜底已执行 destinationChars=${destination.length} " +
                "status=${result.status} resultChars=${result.text.length}"
        )
        return when (result.status) {
            NavigationToolResult.Status.STARTED,
            NavigationToolResult.Status.REROUTED -> result.userText ?: "好，正在导航。"
            // 失败也必须如实说，不能把「没搜到 / 没定位 / 算路失败」说成已开始导航。
            NavigationToolResult.Status.FAILED,
            NavigationToolResult.Status.NEEDS_CLARIFICATION -> result.userText
            else -> null
        }
    }

    private fun destinationMentionedBeforeQuestion(text: String): String? {
        val questionAt = listOf("要不要", "想不想", "去不去", "吗", "么", "？", "?")
            .map { text.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .minOrNull() ?: Int.MAX_VALUE
        if (questionAt == Int.MAX_VALUE) return null
        val prefix = text.substring(0, questionAt)
        val pattern = Regex("(?:找到|搜到|查到|推荐(?:了|的是)?|地点是|目的地是|是|到|有)\\s*([\\u4e00-\\u9fffA-Za-z0-9·.-]{2,16})")
        val raw = pattern.findAll(prefix).lastOrNull()?.groupValues?.getOrNull(1).orEmpty()
        val value = raw.trimStart('了').trimEnd('的', '这', '那', '一', '家', '。', '，', ',')
        return value.takeIf { isUsableNavigationTarget(it) }
    }

    private fun isUsableNavigationTarget(value: String): Boolean {
        val target = value.trim()
        if (target.length < 2) return false
        if (target in setOf(
                "过去", "那里", "那儿", "这儿", "这里", "这家", "那家",
                "这个位置", "那个位置", "这个地方", "那个地方",
                "目的地", "测试", "测试地点", "测试目的地", "某个地方", "一个地方"
            )
        ) return false
        return !Regex("^(?:测试|某地|某个|一个)?(?:地方|位置|目的地)$").matches(target)
    }

    private fun extractDestination(text: String): String? {
        val pattern = Regex("(?:导航(?:到|去)|带我(?:去|到)|带你去|前往|开到|开车去|去|到)\\s*([\\u4e00-\\u9fffA-Za-z0-9·.-]{2,24})")
        val raw = pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val value = raw.trimEnd('吗', '么', '吧', '呢', '呀', '啊', '？', '?', '。', '，', ',', '！', '!')
        return value.takeIf { isUsableNavigationTarget(it) }
    }

    /**
     * 搜地点候选（同步）。
     *
     * ★ 用**高德 Web API**（PlaceWebSearch），不用 SDK 的 PoiSearch：
     *   SDK 那边在工具链路上回调 8 秒都不来（实测），
     *   工具必须同步拿到结果给模型，不能赌回调什么时候来。
     *   Web API 同步 HTTP，简单可靠。
     *
     * ChatEngine 已把拦截、模型和工具统一放到后台单线程执行器里。
     * 这里仍防御性检查调用线程：万一以后有人从主线程误调，直接返回失败，
     * 绝不在 UI 线程上同步等待 HTTP。
     */
    private fun searchPlaceCandidates(keyword: String): List<AmapSdkGeocoder.Poi> {
        // ★ 一次请求就够：city=当前城市 + citylimit=false（city 只作偏置）。
        //   高德自己的排序在这个组合下是对的（实测）：
        //     「水勘院超市」→ 张掖市甘州区的水勘院超市（本地优先）
        //     「景泰县条山路金星家园」→ 白银市景泰县的金星家园（跨城也对）
        //   之前自己分两段搜（先 citylimit=true 再 false）反而错 ——
        //   citylimit=true 搜不到跨城地名，本市又只有「金星灯饰」这种碰巧含「金星」的店。
        val pois = searchInBackground(keyword, nav?.currentCity, cityLimit = false)
        Dbg.log("候选搜索 chars=${keyword.length} → ${pois.size} 条")
        return pois
    }

    /** 在后台线程跑一次 Web API 搜索。 */
    private fun searchInBackground(
        keyword: String,
        city: String?,
        cityLimit: Boolean
    ): List<AmapSdkGeocoder.Poi> {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Dbg.log("拒绝在主线程同步搜索地点")
            return emptyList()
        }
        return doPlaceSearch(keyword, city, cityLimit)
    }

    /** 真正发 HTTP 的地方。**只在子线程调** */
    private fun doPlaceSearch(
        keyword: String,
        city: String?,
        cityLimit: Boolean
    ): List<AmapSdkGeocoder.Poi> = runCatching {
        PlaceWebSearch.search(keyword, city, cityLimit = cityLimit, limit = 5)
    }.getOrElse {
        Dbg.log("地点搜索异常：${it.message}")
        emptyList()
    }

    /**
     * 工具实现：导航 / 改道。
     *
     * 返回给模型的是一句**结果描述**，不是给用户听的话 ——
     * 用户听到的是模型看完这个结果之后自己组织的回复。
     *
     * 为什么要说"正在算路"：`navigateTo` 是异步的（要 geocode + 算路，几百毫秒），
     * 工具返回时还没算完。说成"已经好了"会让模型答"到了"，用户白等。
     */
    private fun toolNavigate(dest: String): NavigationToolResult {
        val cleanDest = dest.trim()
        Prefs.rememberDestination(cleanDest)
        val controller = nav
        lastNearby = null
        return if (controller != null) {
            // ★ 导航页已经活着（它是主界面，几乎总是这样），
            //   直接用它的控制器算路。
            //   以前这里会 startActivity 起一个新 Intent，导航页是 singleTop
            //   所以 onNewIntent 会带着新目的地过来 —— 但那条路径要把整页
            //   重建一遍，路线和地图状态全丢（用户反馈「返回后导航消失」）。
            //   直接调控制器就没有这个问题。
            //
            // ★ ★ **等路线算完再返回**（2026-11-04）：
            //   算路是异步的，不等的话工具返回「正在算路」这种中间态，
            //   模型会照着念 —— 用户听到「正在规划张掖西站」，
            //   但地图其实已经开始导航了，两边对不上（用户反馈）。
            val attempt = controller.navigateAndAwait(cleanDest)
            if (!attempt.succeeded) {
                val reason = attempt.message.ifBlank { "路线规划没有完成" }
                return NavigationToolResult(
                    text = "导航没有开始：$reason。请如实告诉用户，不要声称已经在导航。",
                    status = NavigationToolResult.Status.FAILED,
                    userText = "导航没有开始，$reason。"
                )
            }
            destination = cleanDest
            val prefix =
                if (attempt.status == NavigationAttempt.Status.REROUTED) "已改道去" else "已开始导航去"
            NavigationToolResult(
                text = buildString {
                    append(prefix).append(cleanDest)
                    if (attempt.routeInfo.isNotBlank()) append("，").append(attempt.routeInfo)
                },
                status = if (attempt.status == NavigationAttempt.Status.REROUTED) {
                    NavigationToolResult.Status.REROUTED
                } else {
                    NavigationToolResult.Status.STARTED
                },
                userText = if (attempt.status == NavigationAttempt.Status.REROUTED) {
                    "好，已改道。"
                } else {
                    "好，正在导航。"
                }
            )
        } else {
            // 导航页还没起来（极少见：语音服务在但 Activity 不在）
            openNavigation(cleanDest)
            NavigationToolResult(
                text = "正在打开导航页去${cleanDest}，路线尚未开始。",
                status = NavigationToolResult.Status.MAP_OPENED
            )
        }
    }

    /**
     * 工具实现：按**候选 POI 的精确坐标**导航。
     *
     * ★ 为什么要单独一条路（2026-11-04）：
     *   用户从候选列表里确认了某个地点，坐标已经拿到了。
     *   再拿地名走一遍解析，可能落到**另一个同名地点**上
     *   （「张掖西站」vs「张掖西站(南广场)」）。
     *   直接用候选坐标，百分之百是用户选的那个。
     */
    private fun toolNavigateToPoi(poi: AmapSdkGeocoder.Poi): NavigationToolResult {
        Prefs.rememberDestination(poi.name)
        val controller = nav
        lastNearby = null
        if (controller == null) {
            // 导航页没起来时只能退回按名字来
            openNavigation(poi.name)
            return NavigationToolResult(
                text = "正在打开导航页去${poi.name}，路线尚未开始。",
                status = NavigationToolResult.Status.MAP_OPENED
            )
        }
        // ★ **等路线算完再返回**（2026-11-04）—— 同 toolNavigate，
        //   不等的话模型会念「正在规划…」，但地图早就开始导航了。
        val attempt = controller.navigateToPointAndAwait(
            poi.name, poi.latitude, poi.longitude, poi.address.ifBlank { poi.name }
        )
        if (!attempt.succeeded) {
            val reason = attempt.message.ifBlank { "路线规划没有完成" }
            return NavigationToolResult(
                text = "导航没有开始：$reason。请如实告诉用户，不要声称已经在导航。",
                status = NavigationToolResult.Status.FAILED,
                userText = "导航没有开始，$reason。"
            )
        }
        destination = poi.name
        val prefix =
            if (attempt.status == NavigationAttempt.Status.REROUTED) "已改道去" else "已开始导航去"
        return NavigationToolResult(
            text = buildString {
                append(prefix).append(poi.name)
                if (attempt.routeInfo.isNotBlank()) append("，").append(attempt.routeInfo)
            },
            status = if (attempt.status == NavigationAttempt.Status.REROUTED) {
                NavigationToolResult.Status.REROUTED
            } else {
                NavigationToolResult.Status.STARTED
            },
            userText = if (attempt.status == NavigationAttempt.Status.REROUTED) {
                "好，已改道。"
            } else {
                "好，正在导航。"
            }
        )
    }

    private fun toolStop(): String {
        val controller = nav
        if (controller == null || !controller.isNavigating) return "现在没在导航，不用停"
        controller.stop()
        // ★ 通知界面把地图恢复成初始状态（2026-11-04）
        //   工具跑在子线程，地图操作必须在主线程做。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            navStoppedObservers.forEach { it.onNavigationStopped() }
        }
        // 故意不清 destination —— 底部按钮会变成「开始导航」，点一下能接着走，
        // 不用再让用户说一遍地名
        return "导航已停止。目的地还记着，之后说继续或者点「开始导航」就能接着走"
    }

    /**
     * 「导航已停止」的观察者。
     *
     * ★ 为什么需要：
     *   停止导航后地图要恢复初始状态（退出全程视图、镜头回当前位置）。
     *   停止有两条路径（工具 / 高德 footer 的退出按钮），
     *   都要触发这个恢复，所以做成观察者模式而不是在某处硬调。
     */
    interface NavStoppedUi {
        fun onNavigationStopped()
    }

    private val navStoppedObservers = CopyOnWriteArrayList<NavStoppedUi>()

    fun addNavStoppedUi(ui: NavStoppedUi) {
        if (!navStoppedObservers.contains(ui)) navStoppedObservers.add(ui)
    }

    fun removeNavStoppedUi(ui: NavStoppedUi) {
        navStoppedObservers.remove(ui)
    }

    /**
     * 主动休眠。
     *
     * 用户说「你休息吧」「你先休息吧」「不要打扰我」「你先不要打扰我」这类，
     * 模型会调这个动作。
     *
     * ★ 时序很关键：不能立刻休眠。
     *   模型调用工具后还会生成回复（「好的，我休息了」），要用 TTS 播出来。
     *   如果这时就休眠，TTS 的声音会被麦克风收进去 ——
     *   半双工下自己的播报会被当成用户说话，可能又触发一轮识别。
     *   所以走 [VoiceEngine.sleepAfterSpeaking]：等播报彻底结束再进 ASLEEP。
     */
    private fun toolSleep(): String {
        val engine = voiceEngineProvider?.invoke()
        if (engine == null) {
            return "语音服务没启动，无法休眠"
        }
        engine.sleepAfterSpeaking()
        Dbg.log("用户要求休息，进入休眠")
        return "用户要求不要打扰，已进入休眠。等他说唤醒词再继续。"
    }

    private fun toolOpenMap(): String {
        openNavPage()
        return if (nav?.isNavigating == true) "地图已打开，正在导航中" else "地图已打开，还没设目的地"
    }

    /** 最近一次周边搜索结果，用户说「去」时导航到它 */
    @Volatile
    private var lastNearby: AmapSdkGeocoder.Poi? = null

    /** 上次周边搜索的时间。开车时几分钟就开出好几公里，旧结果不能再拿来导航 */
    @Volatile
    private var lastNearbyAt = 0L

    /**
     * 取仍在有效期内的周边结果。
     *
     * 过期就作废：用户 10 分钟前搜的加油站，这会儿说「去」，车已经开出去三五公里，
     * 再导航到那个点等于往回开。宁可重新问一句「去哪儿」。
     */
    private fun freshNearby(): AmapSdkGeocoder.Poi? {
        val p = lastNearby ?: return null
        if (System.currentTimeMillis() - lastNearbyAt > NEARBY_TTL_MS) {
            Dbg.log("周边结果已过期，丢弃")
            lastNearby = null
            return null
        }
        return p
    }

    /** dest 为 null 表示"只打开地图，先不算路" */
    private fun openNavigation(dest: String?) {
        destination = dest
        val i = Intent(this, NavActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (dest != null) putExtra(NavActivity.EXTRA_DEST, dest)
        }
        startActivity(i)
    }

    /** 首页「导航」按钮：导航页若仍在任务栈就直接带回前台，否则新建地图页。 */
    fun openNavPage() {
        val intent = Intent(this, NavActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    /**
     * 导航上下文，拼进 system prompt。
     *
     * ★ 待确认候选也要拼进去（2026-11-04）：
     *   用户说「导航去XX」→ 兜底路径搜出候选 → 兜底让位给模型 →
     *   模型必须知道有哪些候选才能列 1、2、3 问用户。
     *   不拼的话模型看不到候选，只能瞎猜或者直接导航（用户反馈的现象）。
     */
    private fun navContext(): String {
        val sb = StringBuilder()
        // ① 待确认候选（优先级最高 —— 模型现在要做的就是这个）
        val pending = pendingPois
        if (pending.isNotEmpty()) {
            sb.append("待用户确认的地点候选（关键词「").append(pendingKeyword).append("」）：")
            pending.forEachIndexed { i, poi ->
                sb.append('\n').append(i + 1).append(". ").append(poi.name)
                if (poi.address.isNotBlank()) sb.append("（").append(poi.address).append("）")
            }
            sb.append("\n用户还没确认是哪一个。请用 1、2、3 列出来问他「是哪个」，")
                .append("他回答后（回「2」或直接说名字）再调 navigate 工具，")
                .append("destination 用候选的完整名称，并且 confirmed=true。")
        }
        // ② 正在导航的状态
        val c = nav
        val dest = destination
        val secs = c?.remainingSeconds()
        if (c != null && dest != null && secs != null) {
            val meters = c.distanceToNextAction() ?: 0
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("正在导航去").append(dest).append("，还有")
                .append(secs / 60).append("分钟，下一个动作点在").append(meters).append("米后。")
        }
        return sb.toString()
    }

    private companion object {
        /** 周边结果的保质期。车在开，久了就失效 */
        const val NEARBY_TTL_MS = 2 * 60 * 1000L

        /** 已确认候选的有效期：30 分钟 */
        const val CONFIRMED_TTL_MS = 30 * 60 * 1000L
    }
}
