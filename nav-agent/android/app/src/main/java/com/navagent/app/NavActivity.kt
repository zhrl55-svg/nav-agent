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
    private lateinit var overlay: LinearLayout
    private lateinit var nav: AmapNavController

    private val bubbles = ArrayDeque<TextView>()

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
        // 关掉 SDK 内置界面（退出/继续导航/设置那条底栏会和我们的按钮重叠），
        // 只保留地图与路线渲染，HUD 自己画。
        // 官方示例的写法是「取出 options → 改 → 再 setViewOptions 回去」，
        // getViewOptions() 返回的对象不保证就是视图正在用的那份，所以这里照官方来。
        val options = naviView.viewOptions
        options.isLayoutVisible = false
        options.isAutoLockCar = false
        options.isAutoChangeZoom = true
        naviView.viewOptions = options

        status = findViewById(R.id.status)
        hud = findViewById(R.id.hud)
        hudIcon = findViewById(R.id.hud_icon)
        hudStep = findViewById(R.id.hud_step)
        hudRoad = findViewById(R.id.hud_road)
        hudRemain = findViewById(R.id.hud_remain)
        overlay = findViewById(R.id.chat_overlay)

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

        // 打字发指令：旁边有人、或者语音识别不准时用
        findViewById<Button>(R.id.send).setOnClickListener { submitInput() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput(); true
            } else false
        }

        if (hasLocationPermission()) {
            nav.start()
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

    private fun renderHud(h: NavHud) {
        // 按钮状态跟着导航状态走，放在最前面 —— HUD 隐藏时也要更新
        updateNavButton(h.navigating)
        if (!h.navigating) {
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
        // 整块换 drawable，不能再用 backgroundTint —— 那样会把圆角盖掉
        b.setBackgroundResource(
            if (navigating) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary
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

    override fun onUser(text: String) {
        runOnUiThread { addBubble("我：$text", "#B8CDE0") }
    }

    override fun onAssistantDelta(text: String) {
        // 流式增量不逐字刷浮层，等 onAssistantDone 一次到位，避免地图上跳字
    }

    override fun onAssistantDone(full: String) {
        runOnUiThread { addBubble("${Prefs.aiName}：$full", "#E6EDF5") }
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
                talk.setBackgroundResource(R.drawable.bg_btn_listening)
                startTalkPulse(talk)
            }

            VoiceEngine.State.THINKING -> {
                talk.text = getString(R.string.mic_thinking)
                talk.setBackgroundResource(R.drawable.bg_btn_primary)
                stopTalkPulse(talk)
            }

            VoiceEngine.State.ASLEEP -> {
                talk.text = "◉ 语音"
                talk.setBackgroundResource(R.drawable.bg_btn_primary)
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

    private fun addBubble(text: String, color: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(android.graphics.Color.parseColor("#CC1C2A38"))
                setStroke(dp(1), android.graphics.Color.parseColor("#334A6B8F"))
            }
        }
        overlay.addView(tv)
        bubbles.addLast(tv)
        while (bubbles.size > MAX_BUBBLES) {
            overlay.removeView(bubbles.removeFirst())
        }
        overlay.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        naviView.onResume()
        app.addChatUi(this)
        app.addVoiceUi(this)
        app.refreshVoiceUi(this)
        // 从聊天页切回来时导航状态可能已经变了
        updateNavButton(nav.isNavigating)
    }

    override fun onPause() {
        super.onPause()
        naviView.onPause()
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
        private const val MAX_BUBBLES = 3
    }
}
