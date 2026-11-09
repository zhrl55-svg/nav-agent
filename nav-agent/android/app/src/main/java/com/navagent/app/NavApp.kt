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
            resume = ::toolResume,
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

        // 工具清单只建一次：云端全用；本地只按名字取 web_search / web_extract，
        // 不把工具协议交给模型（见 MnnLlm.planSearch）
        // 天气工具留一个引用：本地档没有工具层，规则命中时直接调它
        val weather = WeatherTool { nav?.currentCity }
        weatherTool = weather

        val nearby = NearbyTool(
            context = this,
            location = { nav?.currentLocation() },
            route = { nav?.remainingRoutePoints() },
            radius = { Prefs.nearbyRadius },
            onResult = {
                // 模型搜完之后用户只说「去这家」，navigate 工具能拿它兜底
                lastNearby = it.firstOrNull()
                lastNearbyAt = System.currentTimeMillis()
                nearbyChoicePois = it.take(3)
                nearbyChoiceAt = lastNearbyAt
                // 新的周边结果开启新选择上下文，不能沿用旧的导航候选。
                clearPendingPois()
                markChoiceList(nearbyChoicePois.size)
                Dbg.log("周边选择列表更新 items=${nearbyChoicePois.size}")
            }
        )
        nearbyToolRef = nearby

        val toolList = listOf(
            navTool,
            weather,
            nearby,
            WebSearchTool { Prefs.tavilyKey },
            // 需要 Context：WebView 兜底要在本机浏览器内核里渲染页面
            WebExtractTool(this) { Prefs.tavilyKey }
        )

        chat = ChatEngine(
            // 模型原生 Function Calling 优先；明确的出发指令另有本地兜底，
            // 避免不支持工具调用的本地模型只说「好的」却不执行。
            // ★ 2026-11-08：两档路由。cloud = MiniMax（工具全可用，默认）；
            //   local = 进程内 MNN。★ 2026-11-09：端侧不再走 <tool_call>，
            //   搜索由 MnnLlm 的规则层直接执行（0.6B 实测一次都没按协议调过）。
            llm = LlmRouter(
                context = this,
                cloud = OpenAiLlm(tools = toolList),
                // 工具只用来按名字取 web_search / web_extract，不出现在 prompt 里
                local = MnnLlm(MnnLocalLlm.get(this), toolList),
                // 自动档换后端必须让用户知道，不能静默降级
                onModeChanged = { cloudMode ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this,
                            if (cloudMode) "网络已恢复，切回云端模型"
                            else "已断网，切到本地模型（导航仍可用）",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ),
            onDelta = { piece -> chatObservers.forEach { it.onAssistantDelta(piece) } },
            onReply = { full -> chatObservers.forEach { it.onAssistantDone(full) } },
            // 记一行播报原文：判断「模型到底有没有把列表编号念出来」只能看这个
            speak = { text ->
                Dbg.log("播报文本：${text.take(50)}")
                voice.speak(text, VoiceGuide.Priority.CHAT)
            },
            context = ::navContext,
            // 名字固化在 prompt 里，每轮都从设置取 —— 改名下一句就自称新名字
            assistantName = { Prefs.aiName },
            // 两次请求全失败才算"没连上"。用系统提示的形式告知，
            // 不伪装成 AI 的对话气泡 —— 这条不会进对话历史。
            onFailure = { msg -> dispatchNotice(msg) },
            // 接本地接口时不给 Tavily（本地服务自带检索），prompt 也要跟着改口
            // ★ 2026-11-09：本地档（进程内 MNN）根本没有工具，告诉它有 web_search
            //   只会让它对闲聊也回「我查一下」。搜索由宿主规则直接执行，不经过模型。
            webSearchAvailable = { Prefs.chatEngine != "local" && Prefs.webSearchEnabled() },
            // 实测关掉时间注入并不能改善「复读日期」——污染源是历史里上一轮的错误回答，
            // 不是 system prompt。关掉反而让「现在几点」答不出来，所以保持注入。
            injectTime = { true },
            interceptAction = ::interceptNavigation,
            // 模型开始念「1、2、3，要哪个」→ 打开选择窗口。
            // 用户这时说「第二个」才能打断播报并当作选择提交（见 VoiceEngine）
            onChoiceMode = { active, count ->
                if (active) markChoiceList(count) else clearChoiceMode()
            },
            memory = memory
        )

        prewarm()
        initVoiceprint()
        runLocalProbe()
    }

    /**
     * 端侧对话批量探针（local_probe 打开时启动一次）。
     *
     * 直接喂文本给 [chat]，跳过 ASR —— 测的是「规则 + 搜索 + 模型 + 播报」这一段。
     * 每题之间隔 7 秒：搜索最坏 12 秒，等短了会串轮次。
     */
    private fun runLocalProbe() {
        if (!Prefs.localProbe) return
        Thread {
            Thread.sleep(12_000)   // 等本地模型加载 + 语音服务起
            val questions = listOf(
                "搜一下今天的新闻",
                "张掖最近有什么新鲜事",
                "今天的油价多少",
                "今天张掖的天气",
                "附近有加油站吗",
                "导航去张掖西站",
                "你叫什么名字",
                "我今天有点累",
                "张掖在哪里",
                "张掖最近发生什么了",
                "92号汽油多少钱"
            )
            questions.forEachIndexed { i, q ->
                Dbg.log("PROBE ${i + 1}/${questions.size} 提问：$q")
                runCatching { chat.send(q) }
                Thread.sleep(7_000)
            }
            Dbg.log("PROBE 全部结束")
        }.apply { isDaemon = true; name = "local-probe" }.start()
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
                "radius=${Prefs.nearbyRadius} log=${Prefs.fileLog} " +
                "vad=${Prefs.vadSilenceMs} gate=${Prefs.noiseGate} " +
                "duplex=${Prefs.fullDuplex} echoGuard=${Prefs.echoGuardEnabled}"
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
            // ★ 2026-11-07：对话和语音可能不在同一个 host（本地 LLM 网关 vs
            //   MiniMax 云端 ASR），两个都要预热，别只顾着大脑那头。
            val targets = listOf(Prefs.chatBaseUrl(), Prefs.voiceBaseUrl())
                .mapNotNull { raw ->
                    val uri = runCatching { java.net.URI(raw) }.getOrNull()
                        ?: return@mapNotNull null
                    val host = uri.host ?: return@mapNotNull null
                    val port = when {
                        uri.port > 0 -> uri.port
                        uri.scheme.equals("http", ignoreCase = true) -> 80
                        else -> 443
                    }
                    host to port
                }
                .distinct()
            for ((host, port) in targets) {
                val t0 = System.currentTimeMillis()
                runCatching {
                    val addr = java.net.InetAddress.getByName(host)
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(addr, port), 4000) }
                    Dbg.log("预热 $host:$port 完成 ${System.currentTimeMillis() - t0}ms")
                }.onFailure {
                    Dbg.log("预热 $host:$port 失败 ${it.javaClass.simpleName}: ${it.message}")
                }
            }
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
        // 候选列表本身就是「让用户在几个里挑一个」，
        // 说「第二个」应该能直接打断 —— 和模型念编号列表走同一个窗口
        markChoiceList(pois.size)
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

    // ------------------------------------------------------------ 打断 / 选择列表

    /**
     * 选择列表窗口的截止时刻。
     *
     * ★ 2026-11-07 加。助手念「1、张掖西站 2、张掖宾馆，要哪个」的时候，
     *   用户说「第二个」应该能直接打断并生效；平时说「第二个」只是普通一句话。
     *   这个窗口就是区分这两种情况的开关。
     *
     * 进入时机有两个：模型回复里出现编号列表（[ChatEngine.onChoiceMode]）、
     * 地图上的地点候选列表弹出来（[setPendingPois]）。
     * 关闭时机：用户说了下一句话（[submit]）、或超时。
     */
    @Volatile
    private var choiceUntilMs = 0L

    /** 列表条数，给日志和界面用 */
    @Volatile
    var choiceItemCount = 0
        private set

    fun markChoiceList(count: Int) {
        choiceUntilMs = System.currentTimeMillis() + CHOICE_WINDOW_MS
        choiceItemCount = count
        Dbg.log("选择列表窗口开启 items=$count")
    }

    fun clearChoiceMode() {
        if (choiceUntilMs == 0L) return
        choiceUntilMs = 0L
        choiceItemCount = 0
    }

    fun choiceModeActive(): Boolean = choiceUntilMs > System.currentTimeMillis()

    /**
     * 用户打断播报。
     *
     * 两件事必须一起做，缺一个都会"打不断"：
     *   1. `voice.bargeInChat()` 停掉正在播的闲聊（导航播报它不会动）；
     *   2. `chat.muteCurrent()` 让这一轮生成闭嘴 —— 模型还在流式输出，
     *      只停 TTS 的话下一段增量攒够一句又会念出来。
     */
    fun bargeInChat(): Boolean {
        val interrupted = voice.bargeInChat()
        chat.muteCurrent()
        if (interrupted) {
            Dbg.log("用户打断：闲聊播报已停")
        } else {
            // ★ 2026-11-08：原来这里一律写「导航播报在播」。
            //   实测误导过一次 —— 导航播报卡在队列里根本没播出来，
            //   日志却说「导航在播」，把真正的问题藏了 90 秒。
            //   返回 false 有两种情况：正在播导航，或者压根没在播任何东西。
            Dbg.log(
                if (voice.speakingPriority == VoiceGuide.Priority.NAV) {
                    "打断未生效（导航播报在播，保持原样）"
                } else {
                    "打断未生效（当前没有可打断的播报）"
                }
            )
        }
        return interrupted
    }

    /** 语音识别的结果统一从这里进 */
    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // ★ 2026-11-07：打断后用云端 ASR 补识别，紧接着 VAD 可能把同一句话
        //   再切一遍送一次 —— 用户会在界面上看到两条一样的「我选第二个」。
        //   3 秒内完全相同的句子按重复丢掉。
        val now = System.currentTimeMillis()
        if (t == lastSubmitText && now - lastSubmitAtMs < DUP_SUBMIT_MS) {
            Dbg.log("重复提交忽略：$t")
            return
        }
        lastSubmitText = t
        lastSubmitAtMs = now
        Dbg.log("submit chars=${t.length}")
        // 用户开口了，上一轮的「选择列表」到此结束
        choiceTurnArmed = choiceModeActive()
        clearChoiceMode()
        // 先让界面把"用户说了什么"显示出来，再进大脑
        chatObservers.forEach { it.onUser(t) }
        if (!chat.send(t)) {
            Dbg.log("submit 被忽略（上一句还在处理）")
        }
    }

    private var lastSubmitText = ""
    private var lastSubmitAtMs = 0L
    /** 本轮输入是否紧跟在一个仍有效的编号列表之后；只消费一次。 */
    @Volatile
    private var choiceTurnArmed = false

    // ------------------------------------------------------------ 导航工具

    /**
     * 本地兜底：OpenAI 兼容接口声明了 tools，但部分模型仍只返回文字。
     * 对明确的导航命令，或对「要不要去 X」的肯定答复，直接复用同一个导航工具执行。
     */
    /**
     * 把「附近的加油站：\n- 中石化，350米\n- …」压成一句能念的话。
     *
     * 工具返回是给模型看的列表，逐条念要十几秒；开车只要「有几家、最近哪家」。
     */
    private fun compactNearby(keyword: String, raw: String): String {
        val items = raw.lines().map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ") }
            .take(3)
        if (items.isEmpty()) return raw.replace("\n", "；").take(80)
        val head = if (raw.contains("沿途")) "沿途找到${items.size}个$keyword：" else "附近找到${items.size}个$keyword："
        return head + items.joinToString("；")
    }

    /** 天气工具实例：规则层直接调，不走模型 */
    private var weatherTool: WeatherTool? = null

    /** 周边查询工具（高德 POI）：本地档没有工具层，规则命中时直接调 */
    private var nearbyToolRef: NearbyTool? = null

    /** 把口语说法和 ASR 常见残词归一到高德能理解的 POI 关键词。 */
    private fun nearbyPoiKeyword(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("停车场|停车位|停车").containsMatchIn(t) -> "停车场"
            Regex("加油站|加油").containsMatchIn(t) -> "加油站"
            Regex("充电桩|充电站|充电").containsMatchIn(t) -> "充电站"
            Regex("奶茶店|奶茶").containsMatchIn(t) -> "奶茶店"
            Regex("小吃店|小吃").containsMatchIn(t) -> "小吃店"
            Regex("咖啡厅|咖啡馆|咖啡").containsMatchIn(t) -> "咖啡厅"
            Regex("药店|药房").containsMatchIn(t) -> "药店"
            Regex("银行|取款机|atm").containsMatchIn(t) -> "银行"
            Regex("超市|便利店").containsMatchIn(t) -> if (t.contains("便利店")) "便利店" else "超市"
            Regex("餐馆|餐厅|饭店|吃饭的地方|吃饭|美食").containsMatchIn(t) -> "餐馆"
            else -> null
        }
    }

    private fun interceptNavigation(text: String, history: List<com.navagent.core.ChatMessage>): String? {
        val normalized = text.trim().lowercase()
        val canChooseFromPreviousList = choiceTurnArmed
        choiceTurnArmed = false

        // ★ 2026-11-09：休眠/别打扰也走规则。
        //   本地档没有工具能力，说「你休息吧」只会得到一句聊天话术，人还在听。
        //   放在最前面：用户要静音，优先级高于一切。
        //   只认明确指向助手本人的说法，避免「找个服务区休息」被误判。
        if (Regex("(你|你先|你先去)?(休息吧|休息一下|睡吧|休眠|退下)|(别|不要|不用)打扰|(别|不要)烦我|安静点")
                .containsMatchIn(normalized) &&
            !Regex("(服务区|导航|去|到|找)").containsMatchIn(normalized)
        ) {
            Dbg.log("休眠（规则命中）")
            runCatching { toolSleep() }
            return "好，我先休息。要找我喊「${Prefs.aiName}」。"
        }

        // ★ 2026-11-09：日期 / 星期 / 几点也走规则。
        //   端侧模型不知道自己训练到哪天 —— 实测问「今天是几号」答「2024年11月3日」，
        //   而且思考模式要等 14 秒。这种问题本来就有确定答案，不该交给模型去猜。
        //   只在短句上生效，避免「新闻联播几点开始」这种被抢走。
        if (normalized.length <= 14 &&
            Regex("几号|几月几号|星期几|周几|礼拜几|几点|日期|什么日子").containsMatchIn(normalized) &&
            !Regex("新闻|天气|导航|油价|限行|门票|开门|关门").containsMatchIn(normalized)
        ) {
            val now = java.time.LocalDateTime.now()
            val week = now.format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINA)
            )
            val asksClock = Regex("几点|时间").containsMatchIn(normalized) &&
                !Regex("几号|日期|星期|周几|礼拜").containsMatchIn(normalized)
            Dbg.log("日期/时间（规则命中）raw=${text.take(20)}")
            return if (asksClock) {
                "现在是 ${now.hour}点${now.minute}分。"
            } else {
                "今天是${now.monthValue}月${now.dayOfMonth}日，$week。"
            }
        }

        // ★ 2026-11-09：周边查询也走规则（高德 POI）。
        //   本地模型调不了 search_nearby，问「附近有加油站吗」只会瞎答。
        //   带「导航/去/到/带我」的是导航意图，交给后面的导航规则，别抢。
        val poiKeyword = nearbyPoiKeyword(normalized)
        val searchAction = Regex("搜索|查询|查找|搜一下|搜搜|搜|查一下|查查|找一下|找").containsMatchIn(normalized)
        val nearbyHit = Regex("附近|周边|周围|旁边|跟前|最近的|哪有").containsMatchIn(normalized) ||
            Regex("沿途|顺路|路上").containsMatchIn(normalized) ||
            (poiKeyword != null && searchAction)
        if (nearbyHit && !Regex("(导航|带我|前往|开到)|^(去|到|回)").containsMatchIn(normalized)) {
            val scope = if (Regex("沿途|顺路|路上").containsMatchIn(normalized)) "route" else "current"
            val keyword = poiKeyword ?: text
                .replace(Regex("(当前位置|当前|目的地|附近|周边|周围|旁边|跟前|最近的|哪有|沿途|顺路|路上)"), "")
                .replace(Regex("(搜索一下|查询一下|查找一下|搜一下|搜搜|查一下|查查|找一下|查询|搜索|帮我|给我|请问|有没有|有|找|搜|查|看看|一下|在|的|吗|呢|啊|呀|吧|你)"), "")
                .trim(' ', '，', '。', '？', '！', ',', '?', '!')
            if (keyword.isNotBlank()) {
                // 进入新的周边搜索时，旧的导航候选不再代表用户眼前听到的列表。
                if (pendingPois.isNotEmpty()) clearPendingPois()
                val out = runCatching {
                    nearbyToolRef?.execute(
                        org.json.JSONObject().put("keyword", keyword).put("scope", scope).toString()
                    )
                }.getOrNull()
                if (!out.isNullOrBlank()) {
                    Dbg.log("周边查询（规则命中）keyword=$keyword scope=$scope chars=${out.length}")
                    return compactNearby(keyword, out)
                }
            }
        }

        // ★ 2026-11-09：天气也走规则。
        //   本地模型不知道天气，问它就答「我还不了解」；云端靠模型调 get_weather，
        //   本地没工具层，所以这里直接调工具把结果念出来。
        // 开头就是「去/到」的当导航意图，别被「天气广场」这种地名骗走
        if (!Regex("^(去|到|回)|(导航|带我|前往|开到)").containsMatchIn(normalized) &&
            Regex("天气|气温|多少度|下雨|有雨|雨吗|下雪|带伞|冷不冷|热不热|穿什么").containsMatchIn(normalized)
        ) {
            val city = text.substringBefore("天气")
                .replace(Regex("我想了解|我想知道|帮我查询|帮我|请问|告诉我|查一下|查查|查询一下|查询|看看|今天|今日|明天|后天|现在|当前|本地|当地|的"), "")
                .trim(' ', '，', ',', '。', '？', '?', '！', '!')
                .takeIf { candidate ->
                    candidate.length in 2..8 &&
                        candidate.all { it in '\u4e00'..'\u9fa5' } &&
                        !Regex("查询|天气|气温|下雨|有雨|下雪").containsMatchIn(candidate)
                }
            Dbg.log("天气请求城市解析 explicit=${city ?: "<local>"} raw=${text.take(24)}")
            fun query(c: String?): String? {
                val args = if (c.isNullOrBlank()) "{}" else org.json.JSONObject().put("city", c).toString()
                return runCatching { weatherTool?.execute(args) }.getOrNull()
            }
            var out = query(city)
            // ★ 高德对「景泰县」查不到、对「景泰」有数据（日志 11:41 实测）。
            //   带行政区后缀查空时，去掉后缀再试一次。
            if (out != null && out.contains("查不到") && city != null &&
                Regex("(县|市|区|旗|盟|自治县|自治州)$").containsMatchIn(city)
            ) {
                val bare = city.replace(Regex("(自治县|自治州|县|市|区|旗|盟)$"), "")
                if (bare.isNotBlank() && bare != city) {
                    val retry = query(bare)
                    if (retry != null && !retry.contains("查不到")) {
                        Dbg.log("天气：$city 查不到，改用 $bare 成功")
                        out = retry
                    }
                }
            }
            if (!out.isNullOrBlank()) {
                // 工具返回的是给模型看的：实况 + 未来 4 天，直接念要十几秒。
                // 只留「实况 + 今天」，并去掉「（xx 更新）」这种括注。
                val compact = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("；")
                    .replace(Regex("（[^）]*）"), "")
                    .trim()
                Dbg.log("天气（规则命中）city=${city ?: "当前城市"} chars=${compact.length}")
                return compact.ifBlank { out }
            }
        }

        // ★ 2026-11-08：停止/结束导航也走规则。
        //   原来只有模型能调 action=stop，本地档没有工具能力 ——
        //   说「停止导航」只会得到一句聊天话术，导航照走。这是安全口子，必须堵。
        //   放在最前面：停止的优先级高于一切导航意图。
        if (Regex("(停止|结束|退出|取消|关闭|不用|不要|别).{0,4}导航|不导航了|别导航了")
                .containsMatchIn(normalized)
        ) {
            val r = navigationTool?.executeDetailed(
                org.json.JSONObject().put("action", "stop").toString()
            )
            if (r != null) {
                Dbg.log("停止导航（规则命中）status=${r.status}")
                return r.userText ?: r.text
            }
        }

        // ★ 2026-11-08：继续/恢复导航。
        //   ★ 只能救「暂停但路线还在」，stop 之后会如实说已结束（见 toolResume）。
        if (Regex("(继续|恢复|接着)(导航|走|开|行驶)").containsMatchIn(normalized)) {
            val r = navigationTool?.executeDetailed(
                org.json.JSONObject().put("action", "resume").toString()
            )
            if (r != null) {
                Dbg.log("继续导航（规则命中）status=${r.status}")
                return r.userText ?: r.text
            }
        }

        // ★ 2026-11-08：离线模式的候选选择。
        //   云端由模型列候选、用户回答后模型再调一次 navigate 收尾；
        //   本地 0.3B 不会做这两件事，所以这里自己解析「第2个 / 最后一个」并直接导航。
        //   只在 chat_engine=local 时启用，云端仍走模型，避免两套逻辑打架。
        if (Prefs.chatEngine == "local") {
            // 序号必须属于紧邻上一轮、实际播报过的列表。优先用最近周边搜索
            // （紧凑播报只念前三项），否则才用导航候选列表。
            if (canChooseFromPreviousList) {
                val nearbyChoices = freshNearbyChoices()
                val choices = nearbyChoices.ifEmpty { pendingPois }
                val idx = pickCandidateIndex(normalized, choices.size)
                val poi = choices.getOrNull(idx ?: -1)
                if (poi != null) {
                    if (nearbyChoices.isNotEmpty()) {
                        nearbyChoicePois = emptyList()
                        Dbg.log("离线周边候选选择 #${(idx ?: 0) + 1} → ${poi.name}")
                    }
                    val r = navigationTool?.navigateSelectedPoi(poi)
                    if (r != null) return r.userText ?: r.text
                }
            }
            // ① 候选续选：用户把地名又说了一遍（「去张掖西站」）也当成确认。
            //    ★ 2026-11-08 实测踩到：上一步列了 5 个候选，用户重复说了一遍地名，
            //      但这里只认「第2个」和数字，认不出地名 → 交给 0.3B → 它把地名复读一遍，
            //      什么都不发生（用户原话：「为什么地址出来了但是导航不出来」）。
            if (pendingPois.isNotEmpty()) {
                val poi = matchCandidateByName(normalized)
                if (poi != null) {
                    val r = navigationTool?.executeDetailed(
                        org.json.JSONObject()
                            .put("action", "navigate")
                            .put("destination", poi.name)
                            .put("confirmed", true)
                            .toString()
                    )
                    if (r != null) {
                        Dbg.log("离线候选复述命中 → ${poi.name} status=${r.status}")
                        return r.userText ?: r.text
                    }
                }
            }
            // ② 序号选择
            val idx = if (canChooseFromPreviousList) pickCandidateIndex(normalized, pendingPois.size) else null
            if (idx != null) {
                val poi = pendingPois.getOrNull(idx)
                if (poi != null) {
                    val r = navigationTool?.executeDetailed(
                        org.json.JSONObject()
                            .put("action", "navigate")
                            .put("destination", poi.name)
                            .put("confirmed", true)
                            .toString()
                    )
                    if (r != null) {
                        Dbg.log("离线候选选择 #${idx + 1} → ${poi.name} status=${r.status}")
                        return r.userText ?: r.text
                    }
                }
            }
        }

        val affirmative = normalized.trimEnd('。', '！', '？', '!', '?', '.', ' ')
            .removeSuffix("啊").removeSuffix("吧")
            .let { it in setOf("去", "好", "好啊", "可以", "行", "走", "走吧", "就去", "去吧") }
        val priorAssistant = history.dropLast(1)
            .lastOrNull { it.role == com.navagent.core.ChatMessage.Role.ASSISTANT }?.text.orEmpty()
        val askedToGo = priorAssistant.contains(Regex("(要不要|是否|想不想|去不去|要去).{0,30}(吗|么|？|\\?)"))
        // 「去张掖西站」这种最自然的说法以前不认（只认「导航去/带我/前往」），
        // 结果交给模型，模型复读一遍地名就完事了。这里补上裸动词开头的形式。
        val bareGo = Regex("^(去|到|回)\\s*.{2,}").containsMatchIn(normalized) &&
            !Regex("(哪|什么|怎么|为什么|吗|？|\\?)").containsMatchIn(normalized)
        val explicit = (Regex("(导航(过去|到|去)|带我(去|到)|开(到|过去)|前往|去一下|就去)")
            .containsMatchIn(normalized) || bareGo) && !Regex("(不要|别|不用|先不|不想|停止|取消).{0,6}(导航|去|前往)")
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
        // ★ 工具返回「搜到 N 个候选」时是**中间态**，不是导航结果。
        //
        //   云端（2026-11-04 起的约定）：这里必须返回 null 让位给模型 ——
        //   候选展示在地图列表里、也存进了 pendingPois，navContext() 会把它们
        //   列进 system prompt，由模型问「是哪个」。直接接管等于跳过确认，
        //   用户会莫名其妙被导航到同名但不对的地方（用户反馈过）。
        //
        //   本地（2026-11-08）：0.3B 没有工具能力，让位只会得到一句「好的」，
        //   什么都不发生。所以本地分支自己收尾。
        if (result.status == NavigationToolResult.Status.CANDIDATES) {
            // 云端：让位给模型 —— 由它把候选列成 1、2、3 问用户，用户回答后模型再调 navigate。
            if (Prefs.chatEngine != "local") {
                Dbg.log("导航兜底让位给模型（搜到候选，等用户确认）")
                return null
            }
            // ★ 本地（2026-11-08）：0.3B 既不会列候选、也不会二次调工具。
            //   如果还按云端那样 return null，用户只会听到一句「好的」，什么都不发生。
            //   所以这里自己收尾：只有一个就直接走，多个就报编号让用户回「第几个」。
            val pois = pendingPois
            when {
                pois.isEmpty() -> return null
                pois.size == 1 -> {
                    val only = pois.first()
                    val r = navigationTool?.executeDetailed(
                        org.json.JSONObject()
                            .put("action", "navigate")
                            .put("destination", only.name)
                            .put("confirmed", true)
                            .toString()
                    )
                    Dbg.log("离线候选仅 1 个，直接导航 ${only.name} status=${r?.status}")
                    return r?.userText ?: r?.text ?: "好，正在导航。"
                }
                else -> {
                    Dbg.log("离线候选 ${pois.size} 个，本地自己列清单")
                    return describeCandidates(pois)
                }
            }
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

    /**
     * 用户把候选地名又说了一遍，就当成「选这个」。
     *
     * 为什么要这一步：语音场景里用户的自然反应是重复地名（「张掖西站」），
     * 而不是说「第二个」。只认序号会让用户卡在原地。
     *
     * 匹配规则保守一点：要求互相包含且长度够，避免「去」这种单字误命中。
     */
    private fun matchCandidateByName(text: String): AmapSdkGeocoder.Poi? {
        // 去掉前面的动词和虚词，只留地名部分
        val clean = text
            .replace(Regex("^(导航|带我|开|前往|再去|就去|那就|就|去|到|回)\\s*"), "")
            .trimEnd('。', '！', '？', '!', '?', '.', '，', ',', ' ')
        if (clean.length < 2) return null
        return pendingPois.firstOrNull { p ->
            val n = p.name
            n == clean ||
                (clean.length >= 2 && n.contains(clean)) ||
                (n.length >= 2 && clean.contains(n)) ||
                // 「西站」对「张掖西站」：候选名包含用户说的片段，取最短的那个
                (clean.length >= 2 && n.endsWith(clean))
        }
    }

    /**
     * 解析「第2个 / 第二个 / 最后一个 / 2」。
     *
     * 只在离线模式下用：云端是模型自己理解「第二个」指的是哪个，
     * 本地模型连候选列表都看不懂，必须靠这个把序号翻译成下标。
     * 返回 null 表示这句话不是在选候选。
     */
    private fun pickCandidateIndex(text: String, candidateCount: Int): Int? {
        if (candidateCount <= 0) return null
        val t = text.trim()
        if (t.isEmpty()) return null
        // 「最后一个」「最后那个」
        if (Regex("^(就)?(最后|末尾|后面那个)").containsMatchIn(t)) return candidateCount - 1
        // 「第2个」「第二个」「第 3 个」
        Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*个?").find(t)?.let { m ->
            chineseNumber(m.groupValues[1])?.let { return it - 1 }
        }
        // 「就要2」「选2个」这种带动词的仍然认。
        Regex("(?:就要|选|要)\\s*([0-9]{1,2})\\s*个?").find(t)?.let { return it.groupValues[1].toInt() - 1 }
        // ★ 2026-11-08 已删除「光说一个数字」的匹配。
        //   原因（用户反馈）：语音播报里念到「2」被麦克风收回来后，
        //   会被当成"选第 2 个"，导航直接跑去错的地方。
        //   纯数字没有上下文，无法区分"选候选"和"播报回声"，收益远小于风险。
        return null
    }

    private fun chineseNumber(s: String): Int? {
        if (s.length == 1) {
            if (s == "十") return 10
            val idx = "一二三四五六七八九".indexOf(s)
            if (idx >= 0) return idx + 1
        }
        return s.toIntOrNull()
    }

    /** 离线模式下自己念候选清单。名字必须完整念出来，用户靠它选。 */
    private fun describeCandidates(pois: List<AmapSdkGeocoder.Poi>): String = buildString {
        append("找到 ").append(pois.size).append(" 个地方：")
        pois.take(5).forEachIndexed { i, p ->
            if (i > 0) append("；")
            append(i + 1).append("、").append(p.name)
            // 地址只在跟名字不一样时补一句，避免「张掖西站，张掖西站」这种重复
            if (p.address.isNotBlank() && !p.address.contains(p.name)) {
                append("，").append(p.address.take(14))
            }
        }
        append("。说第几个就去哪个。")
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
            // ★ 2026-11-09：从聊天页发起导航时，人还停在聊天页，得手动点「导航」才看得到路线。
            //   算路成功后把导航页提到前台（SINGLE_TOP + REORDER_TO_FRONT，不会重建页面）。
            //   已经在导航页时这个调用等于空操作。
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // 用户要求「隐藏聊天页」而不是销毁：把它压到导航页后面，返回键还能回来
                if (!ChatActivity.hideIfOpen()) runCatching { openNavPage() }
            }
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

    /**
     * 继续导航（语音「继续导航」「接着走」）。
     *
     * ★ 2026-11-08：三种状态必须分开回答，不能一律说「好，继续导航」：
     *   ① 根本没在导航（stop 过了）→ 路线已清，resume 救不回来，必须如实说结束
     *   ② 在导航且没暂停 → 如实说「一直在走」，别假装做了个动作
     *   ③ 在导航且暂停 → 真的 resume
     *
     *   ②③ 的区别很重要：用户说「继续」时如果导航一直在跑，
     *   回一句「好，继续导航」会让他以为刚才停过，反而分心去检查。
     */
    private fun toolResume(): NavigationToolResult {
        val controller = nav
            ?: return NavigationToolResult(
                "导航引擎还没准备好", NavigationToolResult.Status.FAILED
            )
        if (controller.isNavigating) {
            // ① 还在跑：暂停过就 resume，没暂停就如实说
            if (!controller.paused) {
                return NavigationToolResult(
                    text = "导航一直在进行中，不需要恢复",
                    status = NavigationToolResult.Status.STARTED,
                    userText = "导航没停过，继续往前开就行"
                )
            }
            controller.resumeNavigation()
            Dbg.log("继续导航：已恢复")
            return NavigationToolResult(
                text = "已恢复导航",
                status = NavigationToolResult.Status.STARTED,
                userText = "好，继续导航"
            )
        }
        // ② 已经停止（真停，路线清了）—— 但目的地还记着，直接重新算路过去。
        //    ★ 2026-11-08 用户要求：目的地已保存，不该让用户再说一遍地点。
        //    这是「停止」和「暂停」的唯一区别：暂停能接上原路线，停止只能重新算。
        //
        //    取值两级：控制器里的本次记录 → Prefs 导航历史第一条。
        //    ★ 历史这条能成立的前提是 rememberDestination() 每次把最新插到最前，
        //      所以第一条＝最近一次导航过的目的地（用户实测确认过）。
        val fromController = controller.lastStoppedDestination
        val last = if (!fromController.isNullOrBlank()) {
            fromController
        } else {
            Prefs.navigationHistory().firstOrNull()
        }
        Dbg.log(
            "继续导航：isNavigating=false 控制器=${fromController ?: "null"} " +
                "历史首条=${Prefs.navigationHistory().firstOrNull() ?: "null"}"
        )
        if (!last.isNullOrBlank()) {
            Dbg.log("继续导航：按上次目的地重新算路 → $last")
            controller.navigateTo(last)
            return NavigationToolResult(
                text = "已按上次的目的地「$last」重新规划路线",
                status = NavigationToolResult.Status.STARTED,
                userText = "好，继续去$last"
            )
        }
        // ③ 连目的地都没有（从没导航过）→ 只能问
        return NavigationToolResult(
            text = "没有可以恢复的导航：既没在导航，也没有上次的目的地",
            status = NavigationToolResult.Status.NEEDS_CLARIFICATION,
            userText = "还没有导航记录，要去哪？"
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
        // 故意不清 destination —— 说「继续导航」能直接回到这里，
        // 不用再让用户说一遍地名。
        //
        // ★ 2026-11-08 用户反馈：这句播报「点...」后面少字，听不完整。
        //   原因是文案里带了「」直角引号 —— 中文 TTS 遇到它对处理不一致，
        //   有的引擎直接吞掉并夹一个怪停顿。
        //   同时「点『开始导航』」这个按钮名也不准（footer 退出后没这个按钮），
        //   干脆只留语音说法 —— 这本来就是个语音助手，用嘴说最自然。
        return "导航已停止，目的地还记着，说继续导航就能接着走"
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

    /** 最近一次周边查询中实际会被紧凑播报的前三项，供用户说序号时确认。 */
    @Volatile
    private var nearbyChoicePois: List<AmapSdkGeocoder.Poi> = emptyList()

    @Volatile
    private var nearbyChoiceAt = 0L

    private fun freshNearbyChoices(): List<AmapSdkGeocoder.Poi> {
        val choices = nearbyChoicePois
        if (choices.isEmpty()) return emptyList()
        if (System.currentTimeMillis() - nearbyChoiceAt > NEARBY_TTL_MS) {
            nearbyChoicePois = emptyList()
            Dbg.log("周边候选已过期，丢弃")
            return emptyList()
        }
        return choices
    }

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
        // ★ 2026-11-09：加了 CLEAR_TOP。
        //   只加 REORDER_TO_FRONT 时，聊天页压在导航页上面，实测不会跳 ——
        //   用户说「导航没有跳转」，日志里导航确实启动了（HUD 已恢复），
        //   只是画面还停在聊天页。CLEAR_TOP + SINGLE_TOP 会把聊天页结束掉，
        //   把导航页带回前台；已经在导航页时等于空操作。
        val intent = Intent(this, NavActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        Dbg.log("打开导航页（CLEAR_TOP）")
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

        /**
         * 选择列表窗口的有效期。
         *
         * 念完列表到用户开口，中间可能隔几十秒（在开车、在犹豫），所以给宽一点；
         * 用户一旦说话就关掉（[submit]），不会误伤后面的正常对话。
         */
        const val CHOICE_WINDOW_MS = 120_000L

        /** 3 秒内完全相同的提交按重复丢掉（打断后 ASR 兜底 + VAD 会重复切同一句） */
        const val DUP_SUBMIT_MS = 3_000L
    }
}
