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
        section("唤醒")

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

        rowSwitch(
            "唤醒应答",
            Prefs.wakeAckVoice,
            "唤醒后应一声「哎」。你有紧接着的指令时它不会出声，不会盖住你说话"
        ) { Prefs.wakeAckVoice = it; applied() }

        rowSwitch("唤醒提示音", Prefs.wakeBeep, "「唤醒应答」关掉、或 TTS 起不来时，退回「嘀」一声") {
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

        // 唤醒模型的状态与重下按钮放回「唤醒」区末尾。
        // 原来它们在很后面的「语音模型」独立分区，跟前文的唤醒词隔了七八屏。
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

        // ★ 2026-11-08：唤醒引擎对照实验。
        //   每帧音频现在同时喂 vosk 和 Sherpa KWS，两者功能重叠。
        //   跑几天 kws-only，看日志里「累计 kws=X vosk=Y」和唤醒手感，
        //   就能判断 vosk 那 44MB 和一路解码要不要留。
        row(
            "唤醒引擎",
            if (Prefs.wakeEngine == "kws-only") "仅 KWS（实验）" else "vosk + KWS（默认）",
            "仅 KWS 时不加载 vosk（省 44MB 内存和一路解码）；带口音的唤醒词命中率可能下降"
        ) {
            choose(
                "唤醒引擎", listOf(
                    "vosk + KWS（默认，双保险）" to "both",
                    "仅 KWS（省内存，实验）" to "kws-only"
                ), Prefs.wakeEngine
            ) {
                Prefs.wakeEngine = it
                // 唤醒器要重建：kws-only 不加载 vosk，切回来时得重新加载
                VoiceService.reloadWake(this)
                toast(
                    if (it == "kws-only") "已切仅 KWS：vosk 不再加载，1 秒后生效"
                    else "已切回双引擎，vosk 正在加载"
                )
                render()
            }
        }

        // ---------------------------------------------------------- 听：识别
        //
        // ★ 2026-11-08 重组：识别引擎原来藏在「语音服务 · MiniMax」里，而它其实
        //   有端侧/云端/影子三档，根本不是 MiniMax 专属，放在那里最容易让人误以为
        //   「识别只有云端」。「断句灵敏度」原来在「实验室」里，但它是听感的核心参数，
        //   不是实验选项。两个都收进这一栏，和声纹一起构成「听」。
        section("听：识别")

        row(
            "识别引擎",
            asrEngineLabel(),
            "端侧=离线识别，隧道地库也能用｜云端=现状｜影子=用云端结果，本地后台跑只记日志"
        ) {
            choose(
                "识别引擎", listOf(
                    "端侧优先（本地 → 云端降级）" to "local",
                    "云端（MiniMax）" to "cloud",
                    "影子对比（不改行为，只记日志）" to "shadow"
                ), Prefs.asrEngine
            ) {
                Prefs.asrEngine = it
                toast(
                    when (it) {
                        "local" -> "已切端侧；模型缺失时会自动降级云端"
                        "shadow" -> "影子模式：行为不变，对比结果写进日志"
                        else -> "已切回云端"
                    }
                )
                applied()
            }
        }

        // ★ 2026-11-08：模型不进 APK（239MB），也没有可靠的国内下载源
        //   （hf-mirror 已把 resolve 请求 308 回 huggingface.co，国内 SSL 直接断）。
        //   所以给一个状态 + 手动放置指引，App 启动时会自动从外部目录导入。
        row(
            "端侧识别模型",
            if (ModelStore.localAsrReady(filesDir)) "已就绪" else "未安装（识别走云端）",
            "点开看怎么手动放模型。没装不影响使用，只是拿不到端侧的收益"
        ) {
            showLocalModelHelp()
        }

        row(
            "断句灵敏度", "${Prefs.vadSilenceMs} ms",
            "停顿多久算说完。调小更快但容易抢话，高速噪音大建议调大"
        ) {
            choose(
                "断句灵敏度", listOf(
                    "600 ms（快，安静环境）" to 600,
                    "800 ms（默认）" to 800,
                    "1000 ms（稳）" to 1000,
                    "1200 ms（不抢话）" to 1200
                ), Prefs.vadSilenceMs
            ) { Prefs.vadSilenceMs = it; applied() }
        }

        section("听：声纹")
        rowSwitch(
            "只认我的声音", Prefs.voiceprint,
            "第一句有效语音自动注册；之后别人说话当没听见"
        ) { Prefs.voiceprint = it; applied() }

        row("声纹状态", app.voiceprintGate?.statusText() ?: "模型未就绪（后台下载中）", null, chevron = false) {}

        rowSwitch(
            "声纹打断播报", Prefs.voiceprintBargeIn,
            "播报中只有你的声音能打断；喊唤醒词也要过声纹确认。没注册声纹时退回唤醒词直断"
        ) { Prefs.voiceprintBargeIn = it; applied() }

        row(
            "打断声纹阈值", String.format("%.2f", Prefs.voiceprintBargeThreshold),
            "实测回声 0.02~0.11、主人 0.39~0.52，取中间最稳"
        ) {
            choose(
                "打断声纹阈值", listOf(
                    "0.20（很宽松）" to 0.20f,
                    "0.28（实测推荐）" to 0.28f,
                    "0.35（偏严）" to 0.35f,
                    "0.45（等于平时阈值，主人常被挡）" to 0.45f
                ), Prefs.voiceprintBargeThreshold
            ) { Prefs.voiceprintBargeThreshold = it; applied() }
        }

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

        section("听：云端识别（备用）")

        row(
            "语音 API Key", voiceKeyLabel(),
            "云端识别和云端播报共用这个 Key。只保存在本机，清空后云端语音全部停用"
        ) {
            editText("语音 API Key", Prefs.voiceKeyRaw, "sk-...", masked = true) {
                Prefs.voiceKeyRaw = it
                applied()
                toast(if (it.isBlank()) "已清空语音 API Key" else "语音 API Key 已保存")
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

        section("说：播报")
        row(
            "导航播报",
            Prefs.navVoiceModeLabel(),
            "控制高德导航提示的内容和频次；关闭后仍会正常播放你的主动对话"
        ) {
            choose(
                "导航播报",
                listOf(
                    "详细（完整播报）" to Prefs.NAV_VOICE_DETAILED,
                    "标准（过滤重复和低价值提示）" to Prefs.NAV_VOICE_STANDARD,
                    "简洁（只播关键行驶提示）" to Prefs.NAV_VOICE_CONCISE,
                    "关闭（不播导航提示）" to Prefs.NAV_VOICE_OFF
                ),
                Prefs.navVoiceMode
            ) {
                Prefs.navVoiceMode = it
                NavigationVoicePolicy.reset()
                if (it == Prefs.NAV_VOICE_OFF) {
                    toast("导航提示已关闭，主动对话仍会正常播报")
                }
                applied()
            }
        }

        row(
            "播报引擎", engineLabel(),
            "换引擎会重建合成器，正在播的那句会断"
        ) {
            choose(
                // ★ 2026-11-08：「系统默认」不再叫「华为智慧引擎」—— 两者不是一回事。
                //   这台机器的 tts_default_synth 指向没安装的 com.svox.pico，
                //   「跟随系统」只靠华为兜底才落到 hiai，纯属运气。所以显式列出华为。
                "播报引擎", listOf(
                    "华为智慧引擎（推荐）" to com.navagent.core.VoiceGuide.HUAWEI_ENGINE,
                    "讯飞语音" to com.navagent.core.VoiceGuide.IFLYTEK_ENGINE,
                    "跟随系统默认" to "",
                    "MiniMax 云端（音色好，需联网）" to com.navagent.core.VoiceGuide.CLOUD_ENGINE
                ), Prefs.ttsEngine
            ) { pkg ->
                Prefs.ttsEngine = pkg
                app.voice.setEngine(pkg)
                toast(
                    when (pkg) {
                        com.navagent.core.VoiceGuide.HUAWEI_ENGINE -> "已切到华为智慧引擎"
                        com.navagent.core.VoiceGuide.IFLYTEK_ENGINE -> "已切到讯飞，下次开口就能听出来"
                        com.navagent.core.VoiceGuide.CLOUD_ENGINE -> "已切到云端合成，需要联网"
                        else -> "已改为跟随系统默认引擎"
                    }
                )
                render()
                // TTS 初始化是异步的，等一会儿再刷一次，让这里显示真实连上的引擎
                // （引擎起不来会自动回退，回退结果只有 onInit 之后才知道）
                container.postDelayed({ if (!isFinishing) render() }, 1500)
            }
        }

        row("合成模型", Prefs.ttsCloudModel, "云端播报用的模型，只对「MiniMax 云端」引擎生效") {
            editText("合成模型", Prefs.ttsCloudModel, "speech-02-hd") {
                Prefs.ttsCloudModel = it; applied()
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

        rowSwitch(
            "播报后回声保护", Prefs.echoGuardEnabled,
            "播报结束后短时屏蔽拾音，防止把自己的播报听成用户说话；关掉可紧跟插话，但车内混响大时容易自激"
        ) { Prefs.echoGuardEnabled = it; applied() }
        row(
            "回声保护时长",
            humanDuration(Prefs.echoGuardMs),
            "只在上面开关打开时生效；只屏蔽播报结束后的回声尾巴，不影响播报中用语音打断"
        ) {
            choose(
                "回声保护时长", listOf(
                    "0.6 秒" to 600L,
                    "1.0 秒" to 1_000L,
                    "1.5 秒（推荐）" to 1_500L,
                    "2.5 秒（车内混响大）" to 2_500L
                ), Prefs.echoGuardMs
            ) { Prefs.echoGuardMs = it; applied() }
        }

        rowSwitch(
            "播报时唤醒词可打断",
            Prefs.bargeIn,
            "播报中喊唤醒词立即停播并开始拾音，其他声音不会打断"
        ) {
            Prefs.bargeIn = it; applied()
        }

        // ---------------------------------------------------------- 对话大脑
        //
        // ★ 2026-11-02 解耦：不再是"MiniMax 专用"。
        //   协议本来就是 OpenAI 那套，只要对方支持 `{地址}/chat/completions`，
        //   云端中转、局域网里的 Ollama / LM Studio / vLLM 都能接。
        //   地址和 Key 跟上面「语音服务」完全独立，改这边不会影响听和说。
        section("对话大脑")

        // ★ 2026-11-08：两档后端。cloud 走下面的服务商/地址；local 用进程内的
        //   MNN 0.3B（模型在 filesDir/models/mnn-llm，缺文件会自动落回 cloud）。
        row(
            "对话后端",
            chatEngineLabel(),
            "自动=有网云端、断网降级本地（导航靠规则，仍可用）｜可手动锁定某一档"
        ) {
            choose(
                "对话后端", listOf(
                    "自动（有网云端，断网本地）" to "auto",
                    "云端（MiniMax，工具全可用）" to "cloud",
                    "本地（0.3B，纯离线）" to "local"
                ), Prefs.chatEngine
            ) {
                Prefs.chatEngine = it
                toast(
                    when (it) {
                        "local" -> "已锁本地：模型首次加载约 2 秒，只能聊天和规则导航"
                        "cloud" -> "已锁云端：始终走 MiniMax，断网会失败"
                        else -> "已切自动：有网用云端，断网自动降级本地"
                    }
                )
                applied()
            }
        }

        // ★ 2026-11-08：选了本地之后，下面这一整块云端配置（服务商/地址/Key/模型/
        //   连接测试/工具调用）全部不显示。理由：这些东西对本地后端一条都不生效，
        //   摆在界面上只会让人以为「改了会有效果」，反而更难懂。
        //   想改云端配置就先把后端切回云端，切回去它们就都在。
        if (Prefs.chatEngine == "local") {
            row(
                "本地模型",
                localLlmLabel(),
                "进程内 0.3B，不联网、不经过任何服务。不支持工具调用，" +
                    "导航 / 搜索 / 天气在这里是聊不出来的",
                chevron = false
            ) {}
        } else {
            // ★ 2026-11-09：用户反馈「云端两个配置容易混」。
            //   原来这里只显示「Minis 本地网关」，看不出实际请求打到哪里。
            //   现在把生效的地址和模型直接写在摘要里，缺 Key 也标出来。
            row("服务商", providerSummary(), "换一家会同时改接口地址和模型名，之后可以再手动改") {
                choose(
                    "对话大脑服务商", listOf(
                        "Minis 本地网关（默认）" to Prefs.PROVIDER_MINIS,
                        "MiniMax 云端" to Prefs.PROVIDER_MINIMAX,
                        "OpenAI 兼容（自填地址）" to Prefs.PROVIDER_OPENAI,
                        "其他本地 / 局域网服务" to Prefs.PROVIDER_LOCAL
                    ), Prefs.chatProvider
                ) { applyProvider(it) }
            }

            row(
                "接口地址", chatBaseLabel(),
                "OpenAI 兼容格式。Minis 默认使用 http://127.0.0.1:8802/v1"
            ) {
                editText("接口地址", Prefs.chatBaseRaw, "http://127.0.0.1:8802/v1") {
                    Prefs.chatBaseRaw = it
                    applied()
                    toast(
                        when {
                            it.isBlank() -> "已恢复当前服务商的默认地址"
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
                        when (Prefs.chatProvider) {
                            Prefs.PROVIDER_MINIS -> MINIS_MODELS
                            Prefs.PROVIDER_OPENAI -> OPENAI_MODELS
                            else -> MINIMAX_MODELS
                        },
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
                "云端模型靠它调用导航 / 搜索 / 天气。端侧由规则执行，关掉不影响端侧"
            ) {
                Prefs.toolsEnabled = it
                applied()
                if (!it) toast("已关闭：云端模型不再调用工具")
            }
        }

        // 端侧搜索不经过模型生成工具调用；模型工具 JSON 会在流式过程中被 TTS 念出来。
        if (Prefs.chatEngine == "local") {
            row("端侧搜索", "搜索结果直出", "搜索由规则触发并直接播报来源信息，避免小模型改写或念出工具协议", chevron = false) {}
            rowSwitch(
                "端侧思考模式", Prefs.localThinkingEnabled,
                "开启 Qwen3 思考模式；回复会更慢，测试后可在这里关闭"
            ) {
                Prefs.localThinkingEnabled = it
                applied()
            }
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

        row(
            "上下文轮数", "${Prefs.historyTurns} 轮",
            "每次携带多少轮最近对话。云端照此执行；本地最多 8 轮，且每轮都会增加首字延迟"
        ) {
            choose(
                "上下文轮数", listOf(
                    "2 轮（最快）" to 2,
                    "3 轮" to 3,
                    "4 轮" to 4,
                    "8 轮（推荐）" to 8
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

        // ★ 2026-11-08：本地后端下整块搜索配置无意义——0.3B 没有工具调用，
        //   给它 Tavily 也用不上。跟「对话大脑」的云端配置一样，按档位隐藏。
        if (Prefs.chatEngine == "local") {
            row(
                "联网搜索", "本地模式不可用",
                "本地 0.3B 没有工具调用能力，搜不了网。对话后端切回云端即可用",
                chevron = false
            ) {}
        } else {

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
        }

        // ---------------------------------------------------------- 实验选项
        // 2026-11-07：借鉴桌面 D:/AI 语音链路的几处工程细节。凡是会改变
        // 拾音 / 识别行为的一律默认关，真机验过再决定要不要长期开。
        // 2026-11-08：断句灵敏度已移到「听：识别」——它是常规参数，不是实验项。
        section("高级：语音链路")
        rowSwitch(
            "上传噪声门", Prefs.noiseGate,
            "低于阈值的段落渐弱后再送 ASR；不影响 VAD 和声纹（照 D:/AI 的 gate 包络）"
        ) { Prefs.noiseGate = it; applied() }
        row(
            "噪声门阈值", "${Prefs.noiseGateThresholdDb.toInt()} dB",
            "只在「上传噪声门」开启时生效；数值越小越容易放行"
        ) {
            choose(
                "噪声门阈值", listOf(
                    "-55 dB（松）" to -55f,
                    "-45 dB（默认）" to -45f,
                    "-35 dB（严）" to -35f
                ), Prefs.noiseGateThresholdDb
            ) { Prefs.noiseGateThresholdDb = it; applied() }
        }
        rowSwitch(
            "全双工（实验）", Prefs.fullDuplex,
            "实验性：播报中仍切句。真机实测会录进 TTS 回音造成自问自答，默认关，不建议开"
        ) { Prefs.fullDuplex = it; applied() }
        row(
            "ASR 延迟", app.voiceEngineProvider?.invoke()?.asrStats() ?: "服务未启动",
            "p50 / p90 / 成功失败次数，用来看连接复用的效果", chevron = false
        ) {}

        section("诊断")
        rowSwitch("文件日志", Prefs.fileLog, "写到 nav.log，关闭能省点 IO") {
            Prefs.fileLog = it; applied()
        }
        row("查看日志", logSize(), "点开看最近 40 行") { showLog() }

        section("维护")
        row(
            "本地记忆",
            memorySummary(),
            "跨重启保存最近对话和你说过「记住……」的内容，换聊天接口也不会丢"
        ) {
            showLocalMemory()
        }
        row("清空对话与长期记忆", "", "删除全部本地消息和偏好。此操作不可撤销") {
            AlertDialog.Builder(this)
                .setTitle("清空本地记忆")
                .setMessage(
                    "当前 ${app.memory.stats().messages} 条消息、" +
                        "${app.memory.stats().facts} 条长期偏好会全部删除。继续？"
                )
                .setPositiveButton("清空") { _, _ ->
                    app.chat.reset()
                    toast("对话和长期记忆已清空")
                    render()
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

    /** 查看本地记忆。最近对话只展示摘要，长期偏好可新增、编辑和删除。 */
    private fun showLocalMemory() {
        val stats = app.memory.stats()
        val facts = app.memory.facts()
        val recent = app.memory.loadRecent(12)
        val preview = if (recent.isEmpty()) {
            "最近对话：无"
        } else {
            recent.joinToString("\n") {
                val who = if (it.role == com.navagent.core.ChatMessage.Role.USER) "你" else Prefs.aiName
                "$who：${it.text.replace('\n', ' ').take(80)}"
            }
        }
        val body = buildString {
            append("消息 ").append(stats.messages).append(" 条，长期偏好 ")
                .append(stats.facts).append(" 条。\n\n")
            if (facts.isNotEmpty()) {
                append("长期偏好：\n")
                facts.take(8).forEach { append("• ").append(it.value).append('\n') }
                if (facts.size > 8) append("…另有 ").append(facts.size - 8).append(" 条\n")
                append('\n')
            }
            append(preview)
        }
        AlertDialog.Builder(this)
            .setTitle("本地记忆")
            .setMessage(body)
            .setPositiveButton("管理长期记忆") { _, _ -> showFactManager() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showFactManager() {
        val facts = app.memory.facts()
        val builder = AlertDialog.Builder(this)
            .setTitle("长期记忆")
            .setPositiveButton("添加") { _, _ -> showFactEditor(null) }
            .setNegativeButton("关闭", null)
        if (facts.isEmpty()) {
            builder.setMessage(
                "还没有长期记忆。\n\n" +
                    "可以在这里手动添加，也可以在对话中说“记住我不走高速”。"
            )
        } else {
            builder.setItems(facts.map { it.value }.toTypedArray()) { _, which ->
                showFactActions(facts[which])
            }
        }
        builder.show()
    }

    private fun showFactActions(fact: MemoryFact) {
        AlertDialog.Builder(this)
            .setTitle(fact.value)
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> showFactEditor(fact)
                    1 -> confirmDeleteFact(fact)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteFact(fact: MemoryFact) {
        AlertDialog.Builder(this)
            .setTitle("删除这条长期记忆？")
            .setMessage(fact.value)
            .setPositiveButton("删除") { _, _ ->
                app.memory.deleteFact(fact.id)
                toast("已删除")
                render()
                showFactManager()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFactEditor(fact: MemoryFact?) {
        val input = EditText(this).apply {
            setText(fact?.value.orEmpty())
            hint = "例如：我走高速时不走夜间路线"
            setTextColor(Color.parseColor("#1B1B1B"))
            setHintTextColor(Color.parseColor("#8A8A8A"))
            background = roundBg(Color.parseColor("#F1F3F2"), 8f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (fact == null) "添加长期记忆" else "编辑长期记忆")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                when {
                    value.length < 2 -> input.error = "至少输入 2 个字"
                    value.length > LocalMemoryStore.MAX_FACT_CHARS ->
                        input.error = "最多 ${LocalMemoryStore.MAX_FACT_CHARS} 个字"
                    app.memory.saveFact(fact?.id, value) -> {
                        toast(if (fact == null) "长期记忆已添加" else "长期记忆已更新")
                        dialog.dismiss()
                        render()
                        showFactManager()
                    }
                    else -> input.error = "保存失败，请重试"
                }
            }
        }
        dialog.show()
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

    /** 服务商 + 实际生效的地址和模型，避免「选了一家、打的是另一家」 */
    private fun providerSummary(): String {
        val base = Prefs.chatBaseRaw.ifBlank {
            if (Prefs.chatProvider == Prefs.PROVIDER_MINIMAX) Prefs.voiceBaseRaw.ifBlank { "api.minimaxi.com" }
            else Prefs.MINIS_BASE_URL
        }
        val keyState = if (Prefs.chatKeyRaw.isBlank()) "无 Key" else "已配 Key"
        return "${providerLabel()} · ${base.replace("https://", "")} · ${Prefs.chatModel} · $keyState"
    }

    private fun providerLabel(): String = when (Prefs.chatProvider) {
        Prefs.PROVIDER_MINIS -> "Minis 本地网关（默认）"
        Prefs.PROVIDER_LOCAL -> if (Prefs.chatEndpointIsLocal()) "本地 / 局域网" else "自定义地址"
        Prefs.PROVIDER_OPENAI -> "OpenAI 兼容"
        else -> "MiniMax 云端"
    }

    private fun chatBaseLabel(): String = when {
        Prefs.chatBaseRaw.isEmpty() && Prefs.chatProvider == Prefs.PROVIDER_MINIS ->
            "Minis · ${Prefs.MINIS_BASE_URL}"
        Prefs.chatBaseRaw.isEmpty() -> "MiniMax 官方（跟随语音服务）"
        else -> Prefs.chatBaseRaw
    }

    private fun memorySummary(): String {
        val stats = app.memory.stats()
        return "${stats.messages} 条消息 · ${stats.facts} 条偏好"
    }

    private fun chatKeyLabel(): String {
        val k = Prefs.chatApiKey()
        if (k.isBlank()) {
            return if (Prefs.chatEndpointIsCustom()) {
                "未填（不发 Key）"
            } else {
                "未配置（请填写语音 API Key）"
            }
        }
        return if (Prefs.chatEndpointIsCustom()) {
            "自定义 ${Prefs.maskedChatKey()}"
        } else {
            "沿用语音 Key ${Prefs.maskedChatKey()}"
        }
    }

    private fun voiceKeyLabel(): String =
        if (Prefs.voiceKeyConfigured()) "已配置 ${Prefs.maskedVoiceKey()}" else "未配置"

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
            Prefs.PROVIDER_MINIS -> {
                Prefs.chatBaseRaw = Prefs.MINIS_BASE_URL
                Prefs.chatKeyRaw = ""
                Prefs.chatModel = Prefs.MINIS_CHAT_MODEL
                toast("已切到 Minis 本地网关，本地记忆不受影响")
            }
            Prefs.PROVIDER_MINIMAX -> {
                // 空 = 跟随语音服务；未填 Key 时由设置页明确提示未配置
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
                // 其他本地服务常用 Ollama 的 11434；LM Studio 可再手动改 1234。
                Prefs.chatBaseRaw = "http://127.0.0.1:11434/v1"
                Prefs.chatModel = "qwen2.5:7b"
                toast("已填 Ollama 预设；LM Studio 等其他服务请改地址和模型")
            }
        }
        app.applySettings()
        render()
    }

    /** MiniMax 云端可选模型 */
    private val MINIS_MODELS = listOf(
        "minis（默认网关）" to "minis",
        "workbuddy/deepseek-v4.1-flash" to "workbuddy/deepseek-v4.1-flash",
        "自定义…" to ""
    )

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
    /** 本地模型的手动放置指引（自动下载源不可靠，改人工导入） */
    private fun showLocalModelHelp() {
        val asrOk = ModelStore.localAsrReady(filesDir)
        val mnnOk = MnnLocalLlm.get(this).modelPresent()
        val msg = buildString {
            append("【端侧识别 SenseVoice】")
            append(if (asrOk) "　已就绪 ✓" else "　未安装")
            append("\n需要 2 个文件（约 239MB）：model.int8.onnx、tokens.txt\n")
            append("放到：Android/data/com.navagent/files/models/asr-sensevoice/\n\n")
            append("【本地对话 MNN 0.3B】")
            append(if (mnnOk) "　已就绪 ✓" else "　未安装")
            append("\n需要 5 个文件（约 166MB）：llm.mnn、llm.mnn.weight、\n")
            append("tokenizer.mtok、config.json、llm_config.json\n")
            append("放到：Android/data/com.navagent/files/models/mnn-llm/\n\n")
            append("放好后重启 App，会自动从外部目录搬进私有目录。\n")
            append("用数据线连电脑或用文件管理器都能放进上面这两个路径。\n\n")
            append("下载源：huggingface.co/csukuangfj/\n")
            append("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17\n")
            append("（国内可能需代理；MNN 那套可从 MNN Chat 下载后拷出）")
        }
        AlertDialog.Builder(this)
            .setTitle("本地模型放置说明")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 对话后端那一行右侧显示的文字：auto 档要显示**当前实际**走的是哪条 */
    private fun chatEngineLabel(): String = LlmRouter.describe(this)

    /** 本地模型状态：文件在不在、有没有加载成功 */
    private fun localLlmLabel(): String {
        // 状态从 MnnLocalLlm 单例读，不用碰 VoiceEngine
        val m = MnnLocalLlm.get(this)
        return when {
            m.ready -> "已就绪（${m.lastLoadMs}ms 加载）"
            m.modelPresent() -> "模型已装，尚未加载"
            else -> "缺模型文件"
        }
    }

    /** 识别引擎那一行右侧显示的文字。带就绪状态，不用翻日志也知道模型有没有加载成功 */
    private fun asrEngineLabel(): String {
        val ready = app.voiceEngineProvider?.invoke()?.localAsrReady == true
        return when (Prefs.asrEngine) {
            "local" -> if (ready) "端侧（已就绪）" else "端侧（模型未就绪）"
            "shadow" -> "影子对比"
            else -> "云端（MiniMax）"
        }
    }

    private fun engineLabel(): String {
        val actual = app.voice.activeEngine
        val name = when (actual) {
            com.navagent.core.VoiceGuide.IFLYTEK_ENGINE -> "讯飞"
            com.navagent.core.VoiceGuide.HUAWEI_ENGINE -> "华为智慧引擎"
            com.navagent.core.VoiceGuide.CLOUD_ENGINE -> "MiniMax 云端"
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
