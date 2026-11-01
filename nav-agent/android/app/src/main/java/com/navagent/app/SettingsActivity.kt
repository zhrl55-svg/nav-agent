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

        section("语音识别")
        row(
            "ASR 模型", Prefs.asrModel,
            "云端语音识别。改完下一句生效"
        ) { editText("ASR 模型", Prefs.asrModel, "asr-1.0") { Prefs.asrModel = it; applied() } }

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

        row("会话休眠", humanDuration(Prefs.idleTimeoutMs), "多久没人说话就退出连续对话") {
            choose(
                "会话休眠", listOf(
                    "5 秒" to 5_000L,
                    "10 秒" to 10_000L,
                    "20 秒（推荐）" to 20_000L,
                    "30 秒" to 30_000L,
                    "60 秒" to 60_000L
                ), Prefs.idleTimeoutMs
            ) { Prefs.idleTimeoutMs = it; applied() }
        }

        rowSwitch("唤醒提示音", Prefs.wakeBeep, "命中唤醒词时「嘀」一声") {
            Prefs.wakeBeep = it; applied()
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

        section("对话大脑")
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

        row("对话模型", Prefs.chatModel, "闲聊和问答用哪个模型") {
            val presets = listOf(
                "minimax-m3.1-flash（快，推荐）" to "minimax-m3.1-flash",
                "MiniMax-M2.7（更快）" to "MiniMax-M2.7",
                "MiniMax-Text-01（稳）" to "MiniMax-Text-01",
                "MiniMax-M2.5-highspeed" to "MiniMax-M2.5-highspeed",
                "自定义…" to ""
            )
            choose("对话模型", presets, Prefs.chatModel) { pick ->
                if (pick.isEmpty()) {
                    editText("自定义对话模型", Prefs.chatModel, "模型名") { Prefs.chatModel = it; applied() }
                } else {
                    Prefs.chatModel = pick; applied()
                }
            }
        }

        row("回复长度", humanTokens(Prefs.maxTokens), "单条回复最多多少 token") {
            choose(
                "回复长度", listOf(
                    "短（150，一两句）" to 150,
                    "中（400，推荐）" to 400,
                    "长（800）" to 800
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

        row("蓝牙状态", BluetoothAutoStart.status(this), "查看权限、配对设备", chevron = false) {}

        row("查看已配对设备", "", "看看车机蓝牙叫什么名字，方便填上面那一栏") {
            val list = BluetoothAutoStart.bondedDevices()
            AlertDialog.Builder(this)
                .setTitle("已配对设备")
                .setMessage(
                    if (list.isEmpty()) "没有已配对的蓝牙设备（或缺蓝牙权限）"
                    else list.joinToString("\n")
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        section("联网搜索")
        row("搜索服务", if (Prefs.tavilyKey.isBlank()) "未配置" else "Tavily 已配置", "联网搜索依赖 Tavily Key；未配置时无法实时检索") {
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

    private fun humanDuration(ms: Long): String =
        if (ms % 1000L == 0L) "${ms / 1000} 秒" else String.format("%.1f 秒", ms / 1000f)

    private fun humanTokens(t: Int): String = when {
        t <= 200 -> "短（$t）"
        t <= 500 -> "中（$t）"
        else -> "长（$t）"
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
