package com.navagent.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 设置页。全部用代码构建，不引 material 依赖 —— 这个工程只有 appcompat，
 * 引一套 Material 只为一个设置页不值当（APK +1MB）。
 *
 * 生效策略：
 *  - 除唤醒词外全部**立即生效**，各处都是每次用时读 `Prefs`，不缓存
 *  - 唤醒词改完要重建 vosk Recognizer，走 `VoiceService.reloadWake()`
 */
class SettingsActivity : AppCompatActivity() {

    private val app: NavApp get() = application as NavApp

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    /** 当前正在往里加行的卡片 */
    private lateinit var card: LinearLayout

    /** 模型重新下载中，禁止重复点 */
    @Volatile
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        render()
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.GROUND)
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(8), dp(10), dp(14), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(C.MUTED)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "设置"
            setTextColor(C.TEXT)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "恢复默认"
            setTextColor(C.ACCENT_TEXT)
            textSize = 13f
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { confirmReset() }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scroll = scrollView
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(28))
        }
        scrollView.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    /** 每次改完设置重建整个列表，保证显示值和实际值永远一致。滚动位置要保住，否则一跳回顶部很难用 */
    private fun render() {
        val keepY = if (::scroll.isInitialized) scroll.scrollY else 0
        container.removeAllViews()

        // ---------------------------------------------------------- 唤醒与会话
        //
        // ★ 2026-11-02 收拢：以前「唤醒词」在「语音识别」栏、「AI 名字 / 唤醒词跟随名字」
        //   在「对话大脑」栏，改一个唤醒相关的东西要跨两个分区找。
        //   凡是跟"叫醒它"和"会话什么时候结束"有关的，全放这一栏。
        section("唤醒与会话")

        row(
            "唤醒词", wakeSummary(),
            "逗号分隔多个说法。改完会自动重载模型"
        ) {
            editText(
                "唤醒词", Prefs.wakeWords,
                "你好小高,小高,小高你好", multiline = true
            ) {
                Prefs.wakeWords = it
                // 唯一需要重建识别器的设置
                VoiceService.reloadWake(this)
                toast("唤醒词已更新，稍等 1 秒生效")
                render()
            }
        }

        rowSwitch("唤醒提示音", Prefs.wakeBeep, "命中唤醒词时「嘀」一声") {
            Prefs.wakeBeep = it; applied()
        }

        row("会话休眠", humanDuration(Prefs.idleTimeoutMs), "唤醒后这么久没人说话就退出连续对话") {
            choose(
                "会话休眠", listOf(
                    "10 秒" to 10_000L,
                    "20 秒" to 20_000L,
                    "30 秒" to 30_000L,
                    "60 秒（推荐）" to 60_000L,
                    "120 秒（长时间不打断）" to 120_000L
                ), Prefs.idleTimeoutMs
            ) { Prefs.idleTimeoutMs = it; applied() }
        }

        row("AI 名字", Prefs.aiName, "模型自称、聊天页标题都用它，改完下一句生效") {
            editText("AI 名字", Prefs.aiName, "小高") {
                val name = it.trim().ifBlank { "小高" }
                Prefs.aiName = name
                if (Prefs.wakeFollowsName) {
                    Prefs.wakeWords = "${name},${name}你好,你好${name}"
                    VoiceService.reloadWake(this)
                }
                applied()
                toast(if (Prefs.wakeFollowsName) "名字和唤醒词已同步为「$name」" else "AI 名字已改为「$name」")
            }
        }
        rowSwitch("唤醒词跟随 AI 名字", Prefs.wakeFollowsName, "开启后，唤醒词自动设为「名字」「名字你好」「你好名字」") {
            Prefs.wakeFollowsName = it
            if (it) {
                val name = Prefs.aiName
                Prefs.wakeWords = "${name},${name}你好,你好${name}"
                VoiceService.reloadWake(this)
                toast("唤醒词已同步为「$name」")
            }
            render()
        }

        section("声纹")
        rowSwitch(
            "只认我的声音", Prefs.voiceprint,
            "第一句有效语音自动注册；之后别人说话当没听见"
        ) { Prefs.voiceprint = it; applied() }

        row("声纹状态", app.voiceprintGate?.statusText() ?: "模型未就绪（后台下载中）", null, chevron = false) {}

        row("重新注册声纹", "清除", "清掉当前声纹，下次开口重新录一遍") {
            val g = app.voiceprintGate
            if (g == null) {
                toast("模型还没准备好")
            } else {
                g.clear()
                toast("已清除，下次说话重新注册")
                render()
            }
        }

        row(
            "匹配阈值", String.format("%.2f", Prefs.voiceprintThreshold),
            "越小越宽松。实测同人 0.56~0.77、异人 -0.09~0.28"
        ) {
            choose(
                "匹配阈值", listOf(
                    "0.25（宽松，容易放行）" to 0.25f,
                    "0.35（实测推荐）" to 0.35f,
                    "0.45（严格）" to 0.45f,
                    "0.55（很严格）" to 0.55f
                ), Prefs.voiceprintThreshold
            ) { Prefs.voiceprintThreshold = it; applied() }
        }

        // ---------------------------------------------------------- 语音服务
        //
        // ★ 语音（识别/合成）目前只有 MiniMax，和「对话大脑」是两套独立的配置。
        //   以前它们共用一个地址和一个 Key，于是"把对话接到本地模型"必然会把
        //   语音识别一起打坏。现在分开：这一栏改坏了只影响听和说，不影响思考。
        section("语音服务 · MiniMax")

        row(
            "语音 API Key", voiceKeyLabel(),
            "识别和合成共用。填自己的额度，不用重新编译；清空恢复内置"
        ) {
            editText("语音 API Key", Prefs.voiceKeyRaw, "sk-...", masked = true) {
                Prefs.voiceKeyRaw = it
                applied()
                toast(if (it.isBlank()) "已恢复内置 Key" else "已切换到自定义 Key")
            }
        }

        row(
            "语音 API 地址",
            Prefs.voiceBaseRaw.ifEmpty { "默认（api.minimaxi.com）" },
            "换中转才需要改。接本地大模型请去下面「对话大脑」，别改这里"
        ) {
            editText("语音 API 地址", Prefs.voiceBaseRaw, "https://api.minimaxi.com/v1") {
                Prefs.voiceBaseRaw = it
                applied()
            }
        }

        row("识别模型", Prefs.asrModel, "云端语音识别，改完下一句生效") {
            editText("识别模型", Prefs.asrModel, "asr-1.0") { Prefs.asrModel = it; applied() }
        }

        row("合成模型", Prefs.ttsCloudModel, "云端播报用的模型，只对「MiniMax 云端」引擎生效") {
            editText("合成模型", Prefs.ttsCloudModel, "speech-02-hd") {
                Prefs.ttsCloudModel = it; applied()
            }
        }

        section("语音播报")
        row(
            "播报引擎", engineLabel(),
            "换引擎会重建合成器，正在播的那句会断"
        ) {
            choose(
                "播报引擎", listOf(
                    "系统默认（华为智慧引擎）" to "",
                    "讯飞语音" to "com.iflytek.speechsuite",
                    "MiniMax 云端（音色好，需联网）" to "cloud"
                ), Prefs.ttsEngine
            ) { pkg ->
                Prefs.ttsEngine = pkg
                app.voice.setEngine(pkg)
                toast(if (pkg.isEmpty()) "已切回系统引擎" else "已切到讯飞，下次开口就能听出来")
                render()
                // TTS 初始化是异步的，等一会儿再刷一次，让这里显示真实连上的引擎
                // （引擎起不来会自动回退，回退结果只有 onInit 之后才知道）
                container.postDelayed({ if (!isFinishing) render() }, 1500)
            }
        }

        row("音色", voiceLabel(), "只对 MiniMax 云端生效；选完自动试听一句") {
            choose("选择音色", VOICES, Prefs.ttsVoiceId) { id ->
                Prefs.ttsVoiceId = id
                if (Prefs.ttsEngine != "cloud") {
                    toast("先在上面把「播报引擎」切到 MiniMax 云端才能听出区别")
                } else {
                    app.voice.speak(
                        "我是${labelOfVoice(id)}，这个声音还合适吗？",
                        com.navagent.core.VoiceGuide.Priority.USER
                    )
                }
                render()
            }
        }

        row("语速", String.format("%.1fx", Prefs.ttsRate), "车内噪音大可以调快一点") {
            choose(
                "语速", listOf(
                    "0.8x（慢）" to 0.8f,
                    "1.0x（正常）" to 1.0f,
                    "1.2x" to 1.2f,
                    "1.5x（快）" to 1.5f
                ), Prefs.ttsRate
            ) { Prefs.ttsRate = it; applied() }
        }

        row("回声保护", humanDuration(Prefs.echoGuardMs), "播报结束后多久内不拾音") {
            choose(
                "回声保护", listOf(
                    "0.6 秒" to 600L,
                    "1.0 秒" to 1_000L,
                    "1.5 秒（推荐）" to 1_500L,
                    "2.5 秒（车内混响大）" to 2_500L
                ), Prefs.echoGuardMs
            ) { Prefs.echoGuardMs = it; applied() }
        }

        rowSwitch("播报时可打断", Prefs.bargeIn, "你开口就停播报，不用等它念完") {
            Prefs.bargeIn = it; applied()
        }

        // ---------------------------------------------------------- 对话大脑
        //
        // ★ 2026-11-02 解耦：不再是"MiniMax 专用"。
        //   协议本来就是 OpenAI 那套，只要对方支持 `{地址}/chat/completions`，
        //   云端中转、局域网里的 Ollama / LM Studio / vLLM 都能接。
        //   地址和 Key 跟上面「语音服务」完全独立，改这边不会影响听和说。
        section("对话大脑")

        row("服务商", providerLabel(), "换一家会同时改接口地址和模型名，之后可以再手动改") {
            choose(
                "对话大脑服务商", listOf(
                    "MiniMax（默认，云端）" to Prefs.PROVIDER_MINIMAX,
                    "OpenAI 兼容（自填地址）" to Prefs.PROVIDER_OPENAI,
                    "本地 / 局域网（Ollama、LM Studio、vLLM）" to Prefs.PROVIDER_LOCAL
                ), Prefs.chatProvider
            ) { applyProvider(it) }
        }

        row(
            "接口地址", chatBaseLabel(),
            "OpenAI 兼容格式。默认预设 http://127.0.0.1:8802/v1"
        ) {
            editText("接口地址", Prefs.chatBaseRaw, "http://127.0.0.1:8802/v1") {
                Prefs.chatBaseRaw = it
                applied()
                toast(
                    when {
                        it.isBlank() -> "已恢复默认：跟随语音服务的 MiniMax 地址"
                        !it.startsWith("http") -> "地址要以 http:// 或 https:// 开头"
                        else -> "接口地址已保存"
                    }
                )
            }
        }

        row("API Key", chatKeyLabel(), "本地模型一般不用填；清空就不发送 Key") {
            editText("对话大脑 API Key", Prefs.chatKeyRaw, "sk-...（本地可留空）", masked = true) {
                Prefs.chatKeyRaw = it
                applied()
                toast(if (it.isBlank()) "已清空，请求不再带 Key" else "Key 已保存")
            }
        }

        row("对话模型", Prefs.chatModel, "填服务端实际的模型名，写错会 404") {
            val custom = {
                editText("对话模型", Prefs.chatModel, "服务端里的模型名") {
                    Prefs.chatModel = it; applied()
                }
                Unit
            }
            when (Prefs.chatProvider) {
                // 本地跑什么模型只有用户自己知道，直接让他填，别拿预设误导
                Prefs.PROVIDER_LOCAL -> custom()
                else -> choose(
                    "对话模型",
                    if (Prefs.chatProvider == Prefs.PROVIDER_OPENAI) OPENAI_MODELS else MINIMAX_MODELS,
                    Prefs.chatModel
                ) { pick -> if (pick.isEmpty()) custom() else { Prefs.chatModel = pick; applied() } }
            }
        }

        // 接本地模型最容易踩的三个坑（地址、端口、模型名）表现完全一样：说了没反应。
        // 日志要连 adb 才看得到，车里没这个条件，所以给一个能当场出结论的按钮。
        row("连接测试", "测一下", "发一句最短的话，验证地址、模型名和 Key") {
            toast("正在测试…")
            Thread {
                val result = ChatProbe.run()
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("对话大脑连通性")
                        .setMessage(result)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }.apply { isDaemon = true; name = "chat-probe" }.start()
        }

        rowSwitch(
            "工具调用", Prefs.toolsEnabled,
            "导航、搜索、天气都靠它。本地小模型不会 Function Calling 时关掉，退化成纯聊天"
        ) {
            Prefs.toolsEnabled = it
            applied()
            if (!it) toast("已关闭：只能聊天，导航和搜索都用不了")
        }

        row("回复长度", humanTokens(Prefs.maxTokens), "单条回复最多多少 token") {
            choose(
                "回复长度", listOf(
                    "短（150，一两句）" to 150,
                    "中（400，推荐）" to 400,
                    "长（800）" to 800,
                    "超长（2000）" to 2000
                ), Prefs.maxTokens
            ) { Prefs.maxTokens = it; applied() }
        }

        row("记忆轮数", "${Prefs.historyTurns} 轮", "带多少轮历史给模型，越多越贵越慢") {
            choose(
                "记忆轮数", listOf(
                    "4 轮" to 4,
                    "8 轮（推荐）" to 8,
                    "16 轮" to 16
                ), Prefs.historyTurns
            ) { Prefs.historyTurns = it; applied() }
        }

        section("导航")
        rowSwitch("高德官方导航界面", Prefs.amapBuiltinUi, "用高德内置的转向图、车道线、路况条。关掉则用自绘 HUD") {
            Prefs.amapBuiltinUi = it
            applied()
            toast(if (it) "下次打开导航页生效" else "下次打开导航页生效")
        }

        row("周边搜索半径", humanRadius(Prefs.nearbyRadius), "问「附近有没有加油站」时的搜索范围") {
            choose(
                "周边搜索半径", listOf(
                    "1 公里" to 1_000,
                    "3 公里（推荐）" to 3_000,
                    "5 公里" to 5_000,
                    "10 公里" to 10_000
                ), Prefs.nearbyRadius
            ) { Prefs.nearbyRadius = it; applied() }
        }

        row(
            "车牌号", Prefs.carNumber.ifBlank { "未填写" },
            "算路时绕开限行路段。限行按车牌尾号算，填了才管用"
        ) {
            editText("车牌号", Prefs.carNumber, "如：甘G12345") {
                Prefs.carNumber = it
                applied()
                toast(if (it.isBlank()) "已清空车牌" else "车牌已保存：${it.uppercase()}")
                render()
            }
        }

        section("车载蓝牙")
        rowSwitch("蓝牙自动启动", Prefs.bluetoothAutoStart, "车机连上后自动唤醒语音助手") {
            Prefs.bluetoothAutoStart = it
            if (it) {
                requestBluetoothPermissionThenRegister()
            } else {
                BluetoothAutoStart.unregister(this)
            }
            applied()
        }

        row(
            "目标设备名", Prefs.bluetoothTargetName.ifBlank { "未配置" },
            "车机蓝牙名里包含这个关键词就触发，如「车机」「Ford」"
        ) {
            editText("目标设备名", Prefs.bluetoothTargetName, "如：车机 / Ford / 迈巴赫") {
                Prefs.bluetoothTargetName = it
                applied()
                toast(if (it.isBlank()) "已清空，蓝牙自动启动不再触发" else "已保存：$it")
                render()
            }
        }

        row("蓝牙设备列表", "", "列出手机里的蓝牙设备，点一下直接填进上面那栏") {
            showBluetoothDevices()
        }

        section("联网搜索")

        // ★ 2026-11-02：接本地接口时不再塞 Tavily。
        //   本地那套服务（127.0.0.1:8802）自带完整检索，模型在服务端就查完了；
        //   再给它一个 web_search 等于让它二选一，而 Tavily 那份又慢又碎。
        row(
            "搜索工具", webSearchLabel(),
            "接本地模型时默认关闭：本地服务自己会检索，不用再塞一份 Tavily"
        ) {
            choose(
                "搜索工具", listOf(
                    "自动（本地接口关闭，云端开启）" to Prefs.WEB_SEARCH_AUTO,
                    "始终开启（用 Tavily）" to Prefs.WEB_SEARCH_ON,
                    "始终关闭" to Prefs.WEB_SEARCH_OFF
                ), Prefs.webSearchMode
            ) { Prefs.webSearchMode = it; applied() }
        }

        row("搜索服务", if (Prefs.tavilyKey.isBlank()) "未配置" else "Tavily 已配置", "只在上面「搜索工具」启用时才用得到") {
            editText("Tavily 搜索 Key", Prefs.tavilyKey, "tvly-…", masked = true) {
                val key = it.trim()
                Prefs.tavilyKey = key
                if (key.isBlank()) Prefs.clearTavilyKeyOverride()
                toast(if (key.isBlank()) "搜索 Key 已恢复默认" else "搜索 Key 已保存")
                render()
            }
        }
        row("清除搜索 Key", "清除", "删除自定义 Key，回到编译时默认配置") {
            Prefs.clearTavilyKeyOverride()
            toast("已清除自定义搜索 Key")
            render()
        }

        section("语音模型")
        row("唤醒模型", modelStatus(), null, chevron = false) {}
        row("重新下载模型", "", "删掉现有的，重新下 44MB。网络不好时别点") {
            if (downloading) { toast("正在下载中…"); return@row }
            AlertDialog.Builder(this)
                .setTitle("重新下载语音模型")
                .setMessage("会删除现有模型并重新下载约 44MB。继续？")
                .setPositiveButton("开始下载") { _, _ -> redownloadModel() }
                .setNegativeButton("取消", null)
                .show()
        }

        section("诊断")
        rowSwitch("文件日志", Prefs.fileLog, "写到 nav.log，关闭能省点 IO") {
            Prefs.fileLog = it; applied()
        }
        row("查看日志", logSize(), "点开看最近 40 行") { showLog() }

        section("维护")
        row("清空对话历史", "", "下次打开是干净的，我什么都不记得了") {
            AlertDialog.Builder(this)
                .setTitle("清空对话历史")
                .setMessage("当前 ${app.chat.snapshot().size} 条消息会全部删除。继续？")
                .setPositiveButton("清空") { _, _ ->
                    app.chat.reset()
                    toast("已清空")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        section("关于")
        row("版本", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", null, chevron = false) {}
        row("唤醒引擎", "vosk 离线 · 中文小模型", null, chevron = false) {}

        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
    }

    // ---------------------------------------------------------------- 行组件

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(C.MUTED)
            textSize = 12.5f
            setPadding(dp(8), dp(18), 0, dp(7))
        })
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(C.CARD, 14f)
            // 不加这个，行内的 ripple 是直角的，会溢出卡片圆角
            clipToOutline = true
        }
        container.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /**
     * 一行：标题 / 副标题 / 当前值 / ›
     * @param chevron false 时当只读信息行展示（不显示箭头、不可点）
     */
    private fun row(
        title: String,
        value: String,
        subtitle: String? = null,
        chevron: Boolean = true,
        onClick: () -> Unit
    ) {
        divider()
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(13), dp(13))
            if (chevron) {
                background = ripple()
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            setTextColor(C.TEXT)
            textSize = 15f
        })
        if (!subtitle.isNullOrBlank()) {
            left.addView(TextView(this).apply {
                text = subtitle
                setTextColor(C.MUTED)
                textSize = 11.5f
                setPadding(0, dp(3), 0, 0)
            })
        }
        line.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (value.isNotBlank()) {
            line.addView(TextView(this).apply {
                text = value
                setTextColor(if (chevron) C.VALUE else C.ACCENT_TEXT)
                textSize = 13.5f
                maxLines = 2
                // ★ 必须限宽。唤醒词这种长串如果不限，横向 LinearLayout 会把
                //   左边 weight=1 的标题挤成 0 宽，整行看起来像空的（实测踩过）。
                maxWidth = (resources.displayMetrics.widthPixels * 0.48f).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                setPadding(dp(10), 0, 0, 0)
            })
        }
        if (chevron) {
            line.addView(TextView(this).apply {
                text = "  ›"
                setTextColor(C.MUTED)
                textSize = 16f
            })
        }
        card.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun rowSwitch(title: String, checked: Boolean, subtitle: String?, onChange: (Boolean) -> Unit) {
        divider()
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = ripple()
            isClickable = true
            isFocusable = true
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            setTextColor(C.TEXT)
            textSize = 15f
        })
        if (!subtitle.isNullOrBlank()) {
            left.addView(TextView(this).apply {
                text = subtitle
                setTextColor(C.MUTED)
                textSize = 11.5f
                setPadding(0, dp(3), 0, 0)
            })
        }
        line.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val sw = android.widget.Switch(this).apply {
            isChecked = checked
            buttonTintList = ColorStateList.valueOf(C.ACCENT)
            setOnCheckedChangeListener { _, isOn -> onChange(isOn); render() }
        }
        line.addView(sw)
        line.setOnClickListener { sw.isChecked = !sw.isChecked }
        card.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun divider() {
        if (card.childCount == 0) return
        card.addView(View(this).apply {
            setBackgroundColor(C.LINE)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { leftMargin = dp(15) })
    }

    // ---------------------------------------------------------------- 弹窗

    private fun <T> choose(title: String, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
        val labels = options.map { it.first }.toTypedArray()
        val idx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { dialog, which ->
                dialog.dismiss()
                onPick(options[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editText(
        title: String,
        current: String,
        hint: String,
        multiline: Boolean = false,
        masked: Boolean = false,
        onDone: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(current)
            this.hint = hint
            // ★ 系统 AlertDialog 是浅色底，这里必须用深色字 + 浅灰输入框。
            //   之前设成白字白底，整段内容完全看不见（实测踩过）。
            setTextColor(Color.parseColor("#1B1B1B"))
            setHintTextColor(Color.parseColor("#8A8A8A"))
            background = roundBg(Color.parseColor("#F1F3F2"), 8f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = when {
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                masked -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 列出蓝牙设备，点一下直接把名字填进「目标设备名」。
     *
     * 为什么要能点选：车机蓝牙名用户往往不知道精确写法
     * （「Ford Sync 3」「我的车机」这种），手打容易打错导致永远不触发。
     */
    private fun showBluetoothDevices() {
        if (!BluetoothAutoStart.hasPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要蓝牙权限")
                .setMessage("没拿到「附近的设备」权限，读不到蓝牙设备名。\n请到系统设置里给 nav-agent 授权，或先打开「蓝牙自动启动」开关。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (!BluetoothAutoStart.isBluetoothOn()) {
            AlertDialog.Builder(this)
                .setTitle("蓝牙未开启")
                .setMessage("请先打开手机蓝牙。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val devices = BluetoothAutoStart.listDevices(this)
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("蓝牙设备列表")
                .setMessage("没有找到已配对或已连接的蓝牙设备。\n\n先在系统蓝牙设置里把车机连上，这里就会列出来。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val labels = devices.map { it.display }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("蓝牙设备（点一下填入目标名）")
            .setItems(labels) { _, which ->
                val dev = devices[which]
                // 用设备名（可能为空）作为关键词；没名字就用地址前段
                val keyword = dev.name.ifBlank { dev.address }
                Prefs.bluetoothTargetName = keyword
                applied()
                toast("已填入目标设备名：$keyword")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 申请蓝牙权限并注册广播接收器。
     *
     * Android 12+ 的 `BLUETOOTH_CONNECT` 是运行时权限，不给就：
     *  - 读不到设备名（`device.name` 抛 SecurityException）
     *  - 收不到 `ACTION_ACL_CONNECTED` 广播
     *
     * Android 11 及以下不需要运行时权限，直接注册。
     */
    private fun requestBluetoothPermissionThenRegister() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            BluetoothAutoStart.register(this)
            toast("蓝牙自动启动已开启")
            render()
            return
        }
        val perms = arrayOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN"
        )
        val need = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) {
            BluetoothAutoStart.register(this)
            toast("蓝牙自动启动已开启")
            render()
            return
        }
        btPermissionLauncher.launch(need.toTypedArray())
    }

    /** 权限结果回来后再注册接收器 */
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            BluetoothAutoStart.register(this)
            toast("蓝牙自动启动已开启")
        } else {
            Prefs.bluetoothAutoStart = false
            toast("没拿到蓝牙权限，蓝牙自动启动已关闭")
        }
        render()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认")
            .setMessage("所有设置回到编译时的默认值，对话历史也会清空。继续？")
            .setPositiveButton("恢复") { _, _ ->
                Prefs.resetAll()
                app.chat.reset()
                app.applySettings()
                VoiceService.reloadWake(this)
                toast("已恢复默认")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLog() {
        val path = Dbg.logPath()
        val tail = runCatching {
            File(path ?: return@runCatching "日志未初始化").takeLastLines(40)
        }.getOrDefault("读不到日志")
        AlertDialog.Builder(this)
            .setTitle("最近 40 行")
            .setMessage(tail.ifBlank { "日志是空的" })
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------------------------------------------------------- 模型

    private fun modelStatus(): String {
        val root = getExternalFilesDir(null) ?: filesDir
        val dir = ModelStore.voskDir(root)
            ?: return "未就绪，首次说话时会自动下载"
        return "已就绪 · ${dirSizeMb(dir)}MB"
    }

    private fun redownloadModel() {
        downloading = true
        val root = getExternalFilesDir(null) ?: filesDir
        Thread {
            File(root, "models/vosk-cn").deleteRecursively()
            File(root, "models/vosk-cn.zip").delete()
            val d = ModelStore.ensureVosk(root) { msg -> runOnUiThread { toast(msg) } }
            runOnUiThread {
                downloading = false
                if (d != null) {
                    VoiceService.reloadWake(this)
                    toast("模型已就绪")
                } else {
                    toast("下载失败，换个网络再试")
                }
                render()
            }
        }.apply { isDaemon = true; name = "model-download" }.start()
    }

    private fun dirSizeMb(dir: File): Long {
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes / 1024 / 1024
    }

    // ---------------------------------------------------------------- 工具

    private fun applied() {
        app.applySettings()
        render()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 毫秒 → 人话。
     *
     * 整分钟优先显示分钟：会话休眠的选项里有 120 秒，写成「120 秒」读起来
     * 还得在脑子里换算一下，「2 分钟」一眼就懂。60 秒以下的行为不变。
     */
    private fun humanDuration(ms: Long): String = when {
        ms >= 60_000L && ms % 60_000L == 0L -> "${ms / 60_000} 分钟"
        ms % 1000L == 0L -> "${ms / 1000} 秒"
        else -> String.format("%.1f 秒", ms / 1000f)
    }

    private fun humanTokens(t: Int): String = when {
        t <= 200 -> "短（$t）"
        t <= 500 -> "中（$t）"
        t <= 1000 -> "长（$t）"
        else -> "超长（$t）"
    }

    private fun humanRadius(m: Int): String =
        if (m >= 1000) "${m / 1000} 公里" else "${m} 米"

    /** MiniMax speech-02 可用音色（2026-11-01 逐个实测 status=0） */
    private val VOICES = listOf(
        "青涩青年（男）" to "male-qn-qingse",
        "精英青年（男）" to "male-qn-jingying",
        "霸道青年（男）" to "male-qn-badao",
        "大学生（男）" to "male-qn-daxuesheng",
        "少女（女）" to "female-shaonv",
        "御姐（女）" to "female-yujie",
        "成熟女性" to "female-chengshu",
        "甜美女性" to "female-tianmei",
        "男主播" to "presenter_male",
        "女主播" to "presenter_female"
    )

    private fun labelOfVoice(id: String): String =
        VOICES.firstOrNull { it.second == id }?.first ?: id

    private fun voiceLabel(): String = labelOfVoice(Prefs.ttsVoiceId)

    // ---------------------------------------------------------------- 对话大脑

    private fun providerLabel(): String = when (Prefs.chatProvider) {
        Prefs.PROVIDER_LOCAL -> if (Prefs.chatEndpointIsLocal()) "本地 / 局域网" else "自定义地址"
        Prefs.PROVIDER_OPENAI -> "OpenAI 兼容"
        else -> "MiniMax（默认）"
    }

    private fun chatBaseLabel(): String = when {
        Prefs.chatBaseRaw.isEmpty() -> "MiniMax 官方（跟随语音服务）"
        else -> Prefs.chatBaseRaw
    }

    private fun chatKeyLabel(): String {
        val k = Prefs.chatApiKey()
        if (k.isBlank()) return "未填（不发 Key）"
        return if (Prefs.chatEndpointIsCustom()) {
            "自定义 ${Prefs.maskedChatKey()}"
        } else {
            "内置 ${Prefs.maskedChatKey()}"
        }
    }

    private fun voiceKeyLabel(): String =
        if (Prefs.voiceUsingCustomKey()) "自定义 ${Prefs.maskedVoiceKey()}" else "内置 ${Prefs.maskedVoiceKey()}"

    private fun webSearchLabel(): String = when (Prefs.webSearchMode) {
        Prefs.WEB_SEARCH_ON -> "开启（Tavily）"
        Prefs.WEB_SEARCH_OFF -> "关闭"
        else -> if (Prefs.webSearchEnabled()) "自动 · 开启" else "自动 · 关闭"
    }

    /**
     * 切服务商：一次把地址、模型改到位。
     *
     * 不这么做的话，用户从 MiniMax 切到本地模型时地址改了、模型名还留着
     * `minimax-m3.1-flash`，请求必然 404，而报错只写进日志 ——
     * 体感就是"接上本地模型之后它不说话了"。
     */
    private fun applyProvider(provider: String) {
        Prefs.chatProvider = provider
        when (provider) {
            Prefs.PROVIDER_MINIMAX -> {
                // 空 = 跟随语音服务，老配置和内置 Key 都能直接用
                Prefs.chatBaseRaw = ""
                Prefs.chatKeyRaw = ""
                Prefs.chatModel = "minimax-m3.1-flash"
                toast("已切回 MiniMax 官方，不用再填地址")
            }
            Prefs.PROVIDER_OPENAI -> {
                Prefs.chatBaseRaw = "https://api.openai.com/v1"
                Prefs.chatModel = "gpt-4o-mini"
                toast("把地址换成你自己的中转，再填 Key")
            }
            else -> {
                // 用户指定：本地模型默认预设 127.0.0.1:8802（手机上的转发/本机服务）。
                // 接电脑上的 LM Studio（1234）或 Ollama（11434）时把地址改掉即可。
                Prefs.chatBaseRaw = "http://127.0.0.1:8802/v1"
                Prefs.chatModel = "qwen2.5:7b"
                toast("已填本地预设 127.0.0.1:8802；接电脑上的服务就把地址改成电脑 IP")
            }
        }
        app.applySettings()
        render()
    }

    /** MiniMax 云端可选模型 */
    private val MINIMAX_MODELS = listOf(
        "minimax-m3.1-flash（快，推荐）" to "minimax-m3.1-flash",
        "MiniMax-M2.7（更快）" to "MiniMax-M2.7",
        "MiniMax-Text-01（稳）" to "MiniMax-Text-01",
        "MiniMax-M2.5-highspeed" to "MiniMax-M2.5-highspeed",
        "自定义…" to ""
    )

    private val OPENAI_MODELS = listOf(
        "gpt-4o-mini（便宜）" to "gpt-4o-mini",
        "gpt-4o" to "gpt-4o",
        "自定义…" to ""
    )

    /** 唤醒词太长，列表里只显示摘要；点进去看全部 */
    private fun wakeSummary(): String {
        val list = Prefs.wakeWordList()
        return when {
            list.isEmpty() -> "未设置"
            list.size == 1 -> list[0]
            else -> "${list[0]} 等 ${list.size} 个"
        }
    }

    /**
     * 显示**实际连上的**引擎，不是设置里选的那个。
     * 引擎没装或者构造失败会回退系统默认，选的和用的可能不是一回事。
     */
    private fun engineLabel(): String {
        val actual = app.voice.activeEngine
        val name = when (actual) {
            "com.iflytek.speechsuite" -> "讯飞"
            "com.huawei.hiai" -> "华为（系统）"
            "cloud" -> "MiniMax 云端"
            "" -> "初始化中…"
            else -> actual
        }
        // 选的和实际连上的不一致 = 指定引擎起不来被回退了，得让用户看见
        val want = Prefs.ttsEngine
        return if (want.isNotBlank() && actual.isNotBlank() && want != actual) {
            "$name（切换失败已回退）"
        } else name
    }

    private fun logSize(): String {
        val f = Dbg.logPath()?.let { File(it) } ?: return "未初始化"
        if (!f.exists()) return "无"
        val kb = f.length() / 1024
        return if (kb >= 1024) "${kb / 1024}MB" else "${kb}KB"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(color)
    }

    private fun ripple(): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(C.RIPPLE),
            roundBg(C.CARD, 0f),
            null
        )

    private fun File.takeLastLines(n: Int): String {
        val lines = readLines()
        return lines.takeLast(n).joinToString("\n")
    }

    /** 配色跟聊天页对齐 */
    private object C {
        val GROUND = Color.parseColor("#101820")
        val BAR = Color.parseColor("#182430")
        val CARD = Color.parseColor("#1C2A38")
        val LINE = Color.parseColor("#25384A")
        val TEXT = Color.parseColor("#E6EDF5")
        val VALUE = Color.parseColor("#B8CDE0")
        val MUTED = Color.parseColor("#8FA8C0")
        val ACCENT = Color.parseColor("#4A7BA7")
        val ACCENT_TEXT = Color.parseColor("#7FB2DC")
        val RIPPLE = Color.parseColor("#334A7BA7")
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
