package com.navagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNaviView
import com.navagent.core.ChatMessage
import java.util.Locale

/**
 * 导航页。由聊天里的一句"导航去 X"打开，不是主界面。
 *
 * 导航中唤醒聊天，回答**不切页面**：底部浮层显示最近三条，同时 TTS 播报。
 * 理由：地图不能离开视野，而且开车时主要靠听。
 *
 * 生命周期上有个取舍：AMapNavi 绑在 Activity 上，所以按返回键 = 结束导航。
 * 息屏/切后台的保活是 M2-b 的事（前台服务 + location 类型），这版没做。
 */
class NavActivity : AppCompatActivity(), NavApp.ChatUi, NavApp.VoiceUi {

    private val app: NavApp get() = application as NavApp

    private lateinit var naviView: AMapNaviView
    private lateinit var status: TextView
    private lateinit var hud: View
    private lateinit var hudIcon: ImageView
    private lateinit var hudStep: TextView
    private lateinit var hudRoad: TextView
    private lateinit var hudRemain: TextView
    private lateinit var nav: AmapNavController


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dbg.log("NavActivity onCreate dest=" + intent?.getStringExtra(EXTRA_DEST))

        // 高德合规接口：必须在任何 SDK 调用之前
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        MapsInitializer.initialize(this)

        setContentView(R.layout.activity_nav)

        naviView = findViewById(R.id.navi_view)
        naviView.onCreate(savedInstanceState)
        // 高德内置界面 vs 自绘 HUD，由设置页切换。
        //
        // 之前是「永远关掉内置 UI，HUD 自己画」—— 理由是 SDK 内置底栏
        // （退出/继续导航/设置）会和我们的按钮重叠，而且只能 setLayoutVisible(false) 全关、
        // 无法单关某一项。
        //
        // 现在默认开内置界面：用官方的转向图、车道级导航、路况条、限速牌，
        // 比自己维护一套 HUD 划算得多。重叠问题用 **setCrossLocation** 解决 ——
        // 告诉高德「你的 HUD 只能画在这个矩形里」，把底部让给我们的聊天浮层和按钮。
        val options = naviView.viewOptions
        options.isLayoutVisible = Prefs.amapBuiltinUi
        options.isAutoLockCar = false
        options.isAutoChangeZoom = true
        if (Prefs.amapBuiltinUi) {
            // 高德 HUD 限制在「上边留出状态栏高度、下边留出底部面板」的区域内。
            //
            // 上边不能给 0：高德自己的顶部条会贴到状态栏下面，
            // 右上角那个路名/比例标签会被系统状态栏压掉一半。
            // 下边按实际测量留，不能写死 —— 不同屏幕和字号都不一样。
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val topInset = statusBarHeight()
            val navArea = android.graphics.Rect(
                0, topInset,
                screenW, screenH - bottomPanelHeight()
            )
            options.setCrossLocation(navArea, navArea)

            // 夜间模式跟着系统走。
            // 不开的话高德默认浅色地图，和本应用的深色蓝灰主题完全打架
            // （浅底 + 我们的深色面板，中间那道交界特别难看）。
            options.isAutoNaviViewNightMode = true
            options.isNaviNight = isNightMode()
            // 车道级导航：路口有车道箭头那种，官方才有，自己画不出来
            options.isLaneInfoShow = true
            // 路况条：显示前方拥堵情况
            options.isTrafficBarEnabled = true
            // 路线详情列表按钮：底部空间有限，让给我们的按钮栏
            options.isRouteListButtonShow = false
        }
        naviView.viewOptions = options

        status = findViewById(R.id.status)
        hud = findViewById(R.id.hud)
        hudIcon = findViewById(R.id.hud_icon)
        hudStep = findViewById(R.id.hud_step)
        hudRoad = findViewById(R.id.hud_road)
        hudRemain = findViewById(R.id.hud_remain)

        // TTS 是 Application 级单例，导航播报和聊天回复共用一条队列
        nav = AmapNavController(
            context = this,
            voice = app.voice,
            onState = { msg -> runOnUiThread { status.text = msg } },
            onHud = { h -> runOnUiThread { renderHud(h) } },
            onFirstFix = { lat, lon -> runOnUiThread { centerOn(lat, lon) } }
        )
        app.nav = nav

        // 播报期间点按钮是"打断播报"，不是"开始拾音"（半双工兜底）
        findViewById<Button>(R.id.talk).setOnClickListener {
            if (app.voice.isSpeaking) {
                app.voice.silence()
            } else {
                VoiceService.start(this)
                VoiceService.wake(this)
            }
        }
        findViewById<Button>(R.id.back_to_chat).setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        findViewById<Button>(R.id.stop_nav).setOnClickListener { toggleNavigation() }
        findViewById<Button>(R.id.history_nav).setOnClickListener { chooseNavigationHistory() }
        // 设置入口：2026-11-01 从聊天页搬到这里（聊天页不再是主界面）
        findViewById<Button>(R.id.nav_settings).setOnClickListener {
            SettingsActivity.start(this)
        }

        // 打字发指令：旁边有人、或者语音识别不准时用
        findViewById<Button>(R.id.send).setOnClickListener { submitInput() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput(); true
            } else false
        }

        if (hasLocationPermission()) {
            nav.start()
            startVoiceService()
            if (intent?.getStringExtra(EXTRA_DEST).isNullOrBlank()) {
                status.text = "地图已打开 · 说个目的地就开始导航"
            } else {
                consumeDest(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                ),
                REQ_PERMS
            )
        }
    }

    /**
     * 启动语音前台服务。
     *
     * ★ 2026-11-01 起导航页是主界面，所以语音服务由这里负责拉起
     *   （以前是聊天页负责的）。唤醒词必须从一打开应用就能用。
     *
     * 缺 RECORD_AUDIO 权限时 startForeground 会失败，捕获掉即可 ——
     * 定位和地图不受影响，用户点语音按钮时再申请。
     */
    private fun startVoiceService() {
        runCatching { VoiceService.start(this) }
            .onFailure { Dbg.log("语音服务启动失败：${it.message}") }
    }

    /**
     * 状态栏高度（像素）。
     *
     * 给 `setCrossLocation` 用：高德顶部条要避开状态栏，
     * 否则右上角的路名标签会被系统状态栏压掉一半。
     */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            return resources.getDimensionPixelSize(id)
        }
        return (24 * resources.displayMetrics.density).toInt()
    }

    /**
     * 当前是不是夜间模式（跟随系统）。
     *
     * 高德内置界面有自己的地图配色，浅色地图配深色底栏会非常割裂，
     * 所以高德的夜间开关要跟系统一致。
     */
    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 底部面板（聊天浮层 + 状态 + 输入框 + 按钮）占多高。
     *
     * 给 `setCrossLocation` 用：高德 HUD 必须避开这块，
     * 否则高德的底部操作栏会压在我们的按钮上，两套 UI 叠一起没法用。
     *
     * 布局还没 inflate 完时量不准（返回 0），这时宁可给个保守值。
     */
    private fun bottomPanelHeight(): Int {
        val panel = findViewById<View>(R.id.bottom_panel)
        val h = panel?.height ?: 0
        if (h > 0) return h
        // 兜底：输入框 46dp + 按钮 48dp + 状态文字 + 内外边距，约 220dp
        return (220 * resources.displayMetrics.density).toInt()
    }

    private fun renderHud(h: NavHud) {
        // 按钮状态跟着导航状态走，放在最前面 —— HUD 隐藏时也要更新
        updateNavButton(h.navigating)
        // 用高德内置界面时，顶部转向信息由高德画，我们的 HUD 卡片要让位，
        // 否则两套转向提示叠在一起。
        if (!h.navigating || Prefs.amapBuiltinUi) {
            hud.visibility = View.GONE
            return
        }
        hud.visibility = View.VISIBLE
        hudIcon.setImageBitmap(h.iconBitmap)
        hudStep.text = formatDistance(h.stepMeters)
        hudRoad.text = h.nextRoad.ifBlank { "继续沿当前道路行驶" }
        hudRemain.text = String.format(
            Locale.CHINA,
            "剩余 %s · %d 分钟",
            formatDistance(h.remainMeters),
            h.remainSeconds / 60
        )
    }

    private fun formatDistance(meters: Int): String = when {
        meters >= 1000 -> String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
        meters > 0 -> "$meters 米"
        else -> "—"
    }

    private fun chooseNavigationHistory() {
        val history = Prefs.navigationHistory()
        if (history.isEmpty()) {
            status.text = "还没有导航历史 · 说出或输入目的地后会自动保存"
            return
        }
        val options = history.map { dest ->
            if (dest == app.destination) "$dest（当前目的地）" else dest
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("最近去过的地方")
            .setItems(options) { _, which ->
                val dest = history[which]
                Prefs.rememberDestination(dest)
                app.destination = dest
                nav.navigateTo(dest)
                status.text = "正在规划到 $dest …"
                Dbg.log("历史目的地选择 → $dest")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打字发指令，走和语音同一条链路（都进 NavApp.submit） */
    private fun submitInput() {
        val et = findViewById<EditText>(R.id.input)
        val text = et.text.toString().trim()
        if (text.isEmpty()) return
        et.setText("")
        // 收起键盘，不然挡住大半张地图
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(et.windowToken, 0)
        }
        app.submit(text)
    }

    /**
     * 同一个按钮的两种状态：
     *   导航中 → 「停止导航」，点一下停
     *   没导航 → 「开始导航」，点一下接着去上次那个目的地
     *
     * 为什么保留目的地：用户停导航多半是临时绕一下或者走错了，
     * 直接清空的话再开就得重新说一遍地名，很烦。`navContext()` 只在真在导航时
     * 才吐状态（`remainingSeconds() == null` 就返回空），所以留着不会污染模型上下文。
     */
    private fun toggleNavigation() {
        if (nav.isNavigating) {
            nav.stop()
            status.text = "导航已停止 · 点「开始导航」可以接着走"
            Dbg.log("按钮：停止导航")
            return
        }
        val dest = app.destination
        if (dest.isNullOrBlank()) {
            status.text = "还没设目的地 · 在下面输入框打个地名，或者直接说"
            return
        }
        Prefs.rememberDestination(dest)
        nav.navigateTo(dest)
        app.destination = dest
        status.text = "正在重新规划到 $dest …"
        Dbg.log("按钮：开始导航 → $dest")
    }

    /** 按钮文字和配色跟着导航状态走 */
    private fun updateNavButton(navigating: Boolean) {
        val b = findViewById<Button>(R.id.stop_nav)
        b.text = getString(if (navigating) R.string.nav_stop else R.string.nav_start)
        // ★ 底色统一用 bg_input（跟输入框和其他按钮一样）。
        //   状态区分靠**文字颜色**：停止导航时用红色字，一眼能看出是「停止」；
        //   开始导航时用普通浅色字。
        //   整块换 drawable，不能用 backgroundTint —— 那样会把圆角盖掉。
        b.setBackgroundResource(R.drawable.bg_input)
        b.setTextColor(
            if (navigating) android.graphics.Color.parseColor("#F09595")
            else android.graphics.Color.parseColor("#E6EDF5")
        )
    }

    /**
     * 把镜头挪到当前位置。
     *
     * 正在导航时不动 —— 那时 SDK 自己跟随车头，抢镜头会打架。
     * 只在「从首页点导航进来、只看看地图」的场景用。
     */
    private fun centerOn(lat: Double, lon: Double) {
        if (nav.isNavigating) return
        runCatching {
            naviView.map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15f)
            )
        }.onFailure { Dbg.log("地图居中失败 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasLocationPermission()) {
            nav.start()
            // 语音服务也在这里补启动 —— 首次安装时权限是弹窗后才有的
            startVoiceService()
            consumeDest(intent)
        } else {
            status.text = "没有定位权限，导航不可用"
        }
    }

    /**
     * launchMode=singleTop 时，第二次 am start / 外部 intent 不会重建 Activity，
     * 只回调 onNewIntent。不处理的话 extra 会被静默丢弃
     * （实测：adb am start --es dest ... 直接无效，onCreate 里拿到的是 null）。
     */
    /**
     * 调试入口：导航页也能接收测试文本。
     */
    private fun consumeSayExtra(intent: Intent?) {
        val say = intent?.getStringExtra(EXTRA_SAY)?.takeIf { it.isNotBlank() } ?: return
        Dbg.log("NavActivity consumeSayExtra: $say")
        window.decorView.postDelayed({ app.submit(say) }, 400)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        Dbg.log("NavActivity onNewIntent extras=${intent.extras?.keySet()?.toList()}")
        consumeSayExtra(intent)
        val dest = intent.getStringExtra(EXTRA_DEST)?.takeIf { it.isNotBlank() } ?: return
        app.destination = dest
        Dbg.log("NavActivity onNewIntent dest=$dest")
        consumeDest(intent)
    }

    /**
     * 等定位就绪再算路。AMapNavi 算路必须有起点坐标，刚启动时通常还没拿到定位。
     */
    private fun consumeDest(intent: Intent?) {
        val dest = intent?.getStringExtra(EXTRA_DEST)?.takeIf { it.isNotBlank() } ?: return
        app.destination = dest
        Prefs.rememberDestination(dest)
        status.text = "等待定位…（目标：$dest）"
        Thread {
            var waited = 0L
            while (waited < FIX_TIMEOUT_MS && !nav.hasLocationFix) {
                Thread.sleep(500)
                waited += 500
            }
            Dbg.log("等待定位结束 hasFix=" + nav.hasLocationFix + " 用时=" + waited + "ms")
            runOnUiThread { nav.navigateTo(dest) }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 聊天浮层

    /**
     * 导航页**不显示聊天内容**（用户要求：导航界面只做语音播报）。
     *
     * 开车时看屏幕的注意力本来就不该被文字气泡拉走，导航界面要的是干净。
     * 聊天内容在聊天页看；导航页要问什么就直接说，回答靠 TTS 播报。
     *
     * 这里保留空实现（接口必须实现），不往上屏任何东西。
     * 「我：xxx」和「助手：xxx」都吞掉。
     */
    override fun onUser(text: String) {
        // 导航页不显示聊天内容
    }

    override fun onAssistantDelta(text: String) {
        // 导航页不显示聊天内容
    }

    override fun onAssistantDone(full: String) {
        // 导航页不显示聊天内容，答案由 TTS 播报
    }

    override fun onVoiceState(state: VoiceEngine.State, hint: String) {
        runOnUiThread {
            status.text = hint
            updateTalkIndicator(state)
        }
    }

    /** 导航页的「语音」按钮也做同样的呼吸反馈 */
    private fun updateTalkIndicator(state: VoiceEngine.State) {
        val talk = findViewById<Button>(R.id.talk) ?: return
        when (state) {
            VoiceEngine.State.LISTENING -> {
                talk.text = getString(R.string.mic_listening)
                talk.alpha = 1f
                // ★ 底色统一用 bg_input（跟输入框一致），但聆听态要能看出来，
                //   所以用 bg_btn_listening 那个双层蓝框 —— 它是描边样式，
                //   不会破坏底色统一。
                talk.setBackgroundResource(R.drawable.bg_btn_listening)
                startTalkPulse(talk)
            }

            VoiceEngine.State.THINKING -> {
                talk.text = getString(R.string.mic_thinking)
                talk.setBackgroundResource(R.drawable.bg_input)
                stopTalkPulse(talk)
            }

            VoiceEngine.State.ASLEEP -> {
                talk.text = "◉ 语音"
                talk.setBackgroundResource(R.drawable.bg_input)
                stopTalkPulse(talk)
            }
        }
    }

    private var talkPulse: android.animation.ObjectAnimator? = null

    private fun startTalkPulse(v: View) {
        if (talkPulse?.isRunning == true) return
        talkPulse = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.72f).apply {
            duration = 680
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopTalkPulse(v: View) {
        talkPulse?.cancel()
        talkPulse = null
        v.alpha = 1f
    }

    override fun onNotice(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 设置页启动：返回后要刷新语音状态（设置里可能改了唤醒词等）。
     */
    override fun onResume() {
        super.onResume()
        naviView.onResume()
        // ★ 恢复导航：暂停期间高德会停止播报和定位刷新，
        //   不 resume 的话回来是「有路线但不播报」的死状态。
        nav.resumeNavigation()
        app.addChatUi(this)
        app.addVoiceUi(this)
        app.refreshVoiceUi(this)
        // 从聊天页切回来时导航状态可能已经变了
        updateNavButton(nav.isNavigating)
    }

    override fun onPause() {
        super.onPause()
        naviView.onPause()
        // ★ 只暂停，不 stop —— 路线要留着，回来才能接着走。
        nav.pauseNavigation()
        app.removeChatUi(this)
        app.removeVoiceUi(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        naviView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (app.nav === nav) {
            app.nav = null
            app.destination = null
        }
        nav.release()
        naviView.onDestroy()
    }

    companion object {
        const val EXTRA_DEST = "dest"
        const val EXTRA_SAY = "say"
        private const val REQ_PERMS = 1002
        private const val FIX_TIMEOUT_MS = 60_000L
    }
}
