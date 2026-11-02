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

    /** 导航控制器，NavActivity 创建时挂上、销毁时摘掉 */
    @Volatile
    var nav: AmapNavController? = null

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
        // TTS 的问题在华为机上 logcat 看不到，接到文件日志里
        voice.logger = { Dbg.log("VoiceGuide: $it") }
        voice.setRate(Prefs.ttsRate)

        chat = ChatEngine(
            // 全部走 Function Calling —— 导航动作也是工具，不再有正则规则。
            // 顺序不是随便排的：navigate 最常用放前面，web_search 最"万能"放最后，
            // 免得模型什么都拿它兜。
            llm = OpenAiLlm(
                tools = listOf(
                    NavigationTool(
                        navigateTo = ::toolNavigate,
                        stop = ::toolStop,
                        openMap = ::toolOpenMap,
                        lastNearbyName = { freshNearby()?.name },
                        sleep = ::toolSleep
                    ),
                    WeatherTool { nav?.currentCity },
                    NearbyTool(
                        context = this,
                        location = { nav?.currentLocation() },
                        radius = { Prefs.nearbyRadius },
                        onResult = {
                            // 模型搜完之后用户只说「去这家」，navigate 工具能拿它兜底
                            lastNearby = it
                            lastNearbyAt = System.currentTimeMillis()
                        }
                    ),
                    WebSearchTool { Prefs.tavilyKey },
                    WebExtractTool { Prefs.tavilyKey }
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
            webSearchAvailable = { Prefs.webSearchEnabled() }
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
        Dbg.log("声纹门禁已挂载：${model.length() / 1024 / 1024}MB")
    }

    /**
     * 设置页改完之后调用，把能立即生效的设置刷一遍。
     *
     * 唤醒词是唯一的例外 —— 它固化在 vosk Recognizer 里，得走
     * [VoiceService.reloadWake] 重建，由设置页自己触发。
     */
    fun applySettings() {
        Dbg.enabled = Prefs.fileLog
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

    /** 语音识别的结果统一从这里进 */
    fun submit(text: String) {
        Dbg.log("submit: $text")
        // 先让界面把"用户说了什么"显示出来，再进大脑
        chatObservers.forEach { it.onUser(text) }
        if (!chat.send(text)) {
            Dbg.log("submit 被忽略（上一句还在处理）")
        }
    }

    // ------------------------------------------------------------ 导航工具

    /**
     * 工具实现：导航 / 改道。
     *
     * 返回给模型的是一句**结果描述**，不是给用户听的话 ——
     * 用户听到的是模型看完这个结果之后自己组织的回复。
     *
     * 为什么要说"正在算路"：`navigateTo` 是异步的（要 geocode + 算路，几百毫秒），
     * 工具返回时还没算完。说成"已经好了"会让模型答"到了"，用户白等。
     */
    private fun toolNavigate(dest: String): String {
        val cleanDest = dest.trim()
        Prefs.rememberDestination(cleanDest)
        val controller = nav
        val navigating = controller?.isNavigating == true
        lastNearby = null
        return if (controller != null) {
            // ★ 导航页已经活着（它是主界面，几乎总是这样），
            //   直接用它的控制器算路。
            //   以前这里会 startActivity 起一个新 Intent，导航页是 singleTop
            //   所以 onNewIntent 会带着新目的地过来 —— 但那条路径要把整页
            //   重建一遍，路线和地图状态全丢（用户反馈「返回后导航消失」）。
            //   直接调控制器就没有这个问题。
            controller.navigateTo(cleanDest)
            destination = cleanDest
            if (navigating) {
                "正在改道去${cleanDest}（重新算路中，几秒后地图会更新）"
            } else {
                "正在导航去${cleanDest}（正在算路）"
            }
        } else {
            // 导航页还没起来（极少见：语音服务在但 Activity 不在）
            openNavigation(cleanDest)
            destination = cleanDest
            "正在打开导航去${cleanDest}（正在算路）"
        }
    }

    private fun toolStop(): String {
        val controller = nav
        if (controller == null || !controller.isNavigating) return "现在没在导航，不用停"
        controller.stop()
        // 故意不清 destination —— 底部按钮会变成「开始导航」，点一下能接着走，
        // 不用再让用户说一遍地名
        return "导航已停止。目的地还记着，之后说继续或者点「开始导航」就能接着走"
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

    private fun navContext(): String {
        val c = nav ?: return ""
        val dest = destination ?: return ""
        val secs = c.remainingSeconds() ?: return ""
        val meters = c.distanceToNextAction() ?: 0
        return "正在导航去$dest，还有${secs / 60}分钟，下一个动作点在${meters}米后。"
    }

    private companion object {
        /** 周边结果的保质期。车在开，久了就失效 */
        const val NEARBY_TTL_MS = 2 * 60 * 1000L
    }
}
