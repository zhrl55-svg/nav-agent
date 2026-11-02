package com.navagent.app

import android.Manifest
import android.content.Context
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
            //
            // ★ 不要为了左上角退出按钮动这个矩形。踩过两次：
            //   往下推 → 顶部空出一条深色带，很难看；
            //   往右推（left=56dp）→ 高德切成窄条模式，转向箭头直接消失。
            //   按钮压住箭头一点无所谓，功能优先。
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val topInset = statusBarHeight()
            val navArea = android.graphics.Rect(
                0, topInset,
                screenW, screenH - bottomPanelHeight()
            )
            options.setCrossLocation(navArea, navArea)

        // ★ 地图样式 / 图层 —— 全部按官方 V11.3.100 API Doc 来。
        //
        // 官方文档明确：
        //   setNaviNight / setAutoNaviViewNightMode / setCustomMapStylePath
        //   **全部已过时**，统一用 setMapStyle(MapStyle, customStylePath)。
        //   MapStyle.AUTO = 昼夜自动切换，正好跟着系统走，不用自己判。
        options.setMapStyle(com.amap.api.navi.enums.MapStyle.AUTO, "")
        // 车道级导航：路口有车道箭头那种，官方才有，自己画不出来
        options.isLaneInfoShow = true
        // 路况条：显示前方拥堵情况
        options.isTrafficBarEnabled = true
        // ★ 路况图层 + 路况线 —— 拉近跟高德 App 观感的关键。
        //   HiCar 里那张图上，路线是绿/红相间的，那是路况线。
        //   之前只有顶部一条路况条，地图上路线始终是纯蓝，看着很"素"。
        options.isTrafficLayerEnabled = true
        options.isTrafficLine = true
        // 路线详情列表按钮：底部空间有限，让给我们的按钮栏
        options.isRouteListButtonShow = false
        // 设置菜单按钮同理，让给我们的按钮栏
        options.isSettingMenuEnabled = false
            // ★ 车辆模型：换成俯视的汽车，别用 SDK 默认的蓝色三角箭头。
            //   高德 App 本体画的是汽车模型，SDK 默认画的是定位针形状 ——
            //   这是用户对比 HiCar 时觉得"不像"的主要来源之一。
            //   ★ 必须在 setViewOptions 之前设，View 建好后就改不动了。
            //   setCarBitmap 要的是 Bitmap，矢量图得先渲染一遍。
            runCatching {
                val d = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.car_model)
                if (d != null) {
                    val size = (48 * resources.displayMetrics.density).toInt()
                    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, size, size)
                    d.draw(canvas)
                    options.setCarBitmap(bmp)
                }
            }.onFailure { Dbg.log("车辆模型设置失败：${it.message}") }
        }
        naviView.viewOptions = options

        // ★ 关掉地图原生缩放按钮 —— 它在右下角，被我们的底部面板压住了一半，
        //   点不到也看不清（用户反馈）。
        //   只关按钮，**保留手势缩放**（setZoomGesturesEnabled 默认开着），
        //   双指缩放照常能用，比按按钮方便。
        runCatching {
            naviView.map.uiSettings.setZoomControlsEnabled(false)
        }.onFailure { Dbg.log("缩放按钮关闭失败：${it.message}") }

        // ★ 地图样式 / 图层要在**地图加载完成后**设。
        //
        //   官方文档给的入口是 `setOnMapLoadedListener(AMap.OnMapLoadedListener)`
        //   ——「设置自定义导航view中地图的加载完成事件监听」。
        //   之前在 onCreate 里直接设 mapType / setMapStyle，
        //   那时地图还没初始化完，设置被吃掉了（实测地图一直是浅色）。
        //
        //   所以这里挂个监听，等地图真的加载好再设样式。
        naviView.setOnMapLoadedListener {
            Dbg.log("地图加载完成 → 应用夜间样式与图层")
            runCatching {
                naviView.map.mapType = if (isNightMode()) {
                    com.amap.api.maps.AMap.MAP_TYPE_NAVI_NIGHT
                } else {
                    com.amap.api.maps.AMap.MAP_TYPE_NAVI
                }
                Dbg.log("地图类型已设 night=" + isNightMode())
            }.onFailure { Dbg.log("地图类型设置失败：${it.message}") }
        }

        // ★ 路况线要在 View 上再设一次。
        //   Options 里那遍只是初始值，AMapNaviView.setTrafficLine() 才是运行时开关。
        //   HiCar 那张图里路线是绿/红相间的 —— 那是路况线，
        //   没开的话路线始终纯蓝，地图看着很"素"。
        runCatching { naviView.setTrafficLine(true) }
            .onFailure { Dbg.log("路况线开关失败：${it.message}") }

        // ★ 红绿灯。
        //   HiCar 那张图路口有红绿灯倒计时，SDK 里对应这两个方法：
        //     setTrafficLightsVisible    —— 地图上显示红绿灯图标
        //     setShowTrafficLightView   —— 额外的红绿灯提示视图
        //   不开的话路口只有转向箭头，看不出要等几个灯。
        runCatching {
            naviView.setTrafficLightsVisible(true)
            naviView.setShowTrafficLightView(true)
        }.onFailure { Dbg.log("红绿灯开关失败：${it.message}") }

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

        // 调试：固定算路起点，排除室内 GPS 漂移的干扰
        //   am start ... --es dest 张掖西站 --es slat 38.945004 --es slon 100.418646
        readDebugStart(intent)

        // 播报期间点按钮是"打断播报"，不是"开始拾音"（半双工兜底）
        findViewById<View>(R.id.talk).setOnClickListener {
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

        // ★ 左上角退出按钮（退出整个应用）。
        //   topMargin 必须在代码里按状态栏实际高度算 —— xml 里那个 12dp 只是占位，
        //   刘海/挖孔屏上会被状态栏压掉一半（和高德顶部条踩过同一个坑）。
        findViewById<View>(R.id.exit_nav).apply {
            (layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = exitButtonTop()
            setOnClickListener { exitApp() }
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
            // 调试入口：冷启动时也要能接 --es say。
            // ★ 之前只在 onNewIntent 里接了，onCreate 漏了 —— 结果
            //   `am start ... --es say "..."` 在 app 没运行时静默失效，白排查半天。
            consumeSayExtra(intent)
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
    /**
     * 调试入口：固定算路起点。
     *
     * 室内 GPS 在小区里能漂 70 米，而起点差 30 米路线就从南门变北门。
     * 不钉住起点，任何「改配置有没有用」的对比都是假的（实测被坑过一次）。
     */
    private fun readDebugStart(intent: Intent?) {
        val lat = intent?.getStringExtra(EXTRA_SLAT)?.toDoubleOrNull() ?: return
        val lon = intent.getStringExtra(EXTRA_SLON)?.toDoubleOrNull() ?: return
        nav.debugStart = lat to lon
        Dbg.log("NavActivity 固定起点：$lat,$lon")
    }

    private fun startVoiceService() {
        runCatching { VoiceService.start(this) }
            .onFailure { Dbg.log("语音服务启动失败：${it.message}") }
    }

    /**
     * 写入高德 SDK 的昼夜模式偏好。
     *
     * ★ 这是新版 SDK 夜间样式的**关键前置**（反编译 AmapRouteActivity 得出）：
     *   官方代码是
     *     `MapStyle.getEnum(sp.getInt("DAY_NIGHT_MODE", MapStyle.AUTO.getValue()))`
     *   —— 样式类型是从 SharedPreferences 读的，不是直接传常量。
     *   偏好文件名叫 `NAVI_STRATEGY`（SDK 内部工具类 b7.c 里写死的）。
     *
     *   不写这个键，setMapStyle(NIGHT, "") 也不生效（实测地图仍是浅色）。
     */
    private fun writeDayNightPreference(context: Context, night: Boolean) {
        runCatching {
            val style = if (night) {
                com.amap.api.navi.enums.MapStyle.NIGHT
            } else {
                com.amap.api.navi.enums.MapStyle.DAY
            }
            context.getSharedPreferences("NAVI_STRATEGY", Context.MODE_PRIVATE)
                .edit()
                .putInt("DAY_NIGHT_MODE", style.value)
                .apply()
            Dbg.log("高德昼夜模式偏好已写：DAY_NIGHT_MODE=${style.value} night=$night")
        }.onFailure { Dbg.log("写高德昼夜偏好失败：${it.message}") }
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
     * 退出按钮的顶部偏移（像素）。
     *
     * ★ 这里**不能**再加状态栏高度。本主题（Theme.NavAgent）没有做全屏，
     *   `android:id/content` 本身就从状态栏下沿开始（真机 dump：状态栏 0~154px，
     *   content 从 154px 起）。之前写成 statusBarHeight() + 8dp，
     *   等于把状态栏高度算了两遍，按钮整整低了 46dp —— 看着像陷在高德卡片里。
     */
    private fun exitButtonTop(): Int = dp(8)

    /**
     * 左上角退出按钮：**退出整个应用**，不是退出导航。
     *
     * 四件事必须一起做，少一件都算「没退干净」（真机逐项验证过）：
     *   1. 停导航 —— 不然高德还在后台播报、还在刷定位
     *   2. 停语音前台服务 —— 不然通知栏那条「语音待命」留着，唤醒词还在听，
     *      用户以为退了，其实麦克风还开着
     *   3. finishAffinity —— 结束整个任务栈（ChatActivity 可能还在栈里，
     *      只 finish 自己会把它露出来）
     *   4. 杀进程 —— 高德的 APSService（定位辅助服务）不跟着 Activity 走，
     *      finish 完它还在后台赖着，`pidof` 依然有值。不杀就等于没退。
     *
     * 不弹确认框：这是「退出应用」不是「结束导航」。底部已经有停止导航的入口，
     * 出口上再设一道门只会让人烦。
     */
    private fun exitApp() {
        Dbg.log("左上角退出应用：navigating=" + nav.isNavigating)
        runCatching { nav.stop() }
        runCatching { app.voice.silence() }
        runCatching { VoiceService.stop(this) }
        finishAffinity()
        // 留 400ms 让 finishAffinity 走完 onDestroy（那里才是 nav.release() 和
        // naviView.onDestroy() 的正确顺序），再终结进程
        window.decorView.postDelayed({
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }, 400)
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
        // 打字发出去了，上面的识别提示就没意义了
        clearRecognized()
        app.submit(text)
    }

    /**
     * 显示这次识别到的文字。
     *
     * 为什么需要：纯语音界面没有消息列表，用户看不到机器听成了什么。
     * 识别错了（「西站」听成「西战」）用户根本无从发现，
     * 只能眼睁睁看它导错地方。
     */
    private fun showRecognized(text: String) {
        val tv = findViewById<TextView>(R.id.recognized) ?: return
        if (text.isBlank()) {
            tv.visibility = View.GONE
            return
        }
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    /** 清掉识别提示（发送后、或模型开始回答时） */
    private fun clearRecognized() {
        val tv = findViewById<TextView>(R.id.recognized) ?: return
        tv.text = ""
        tv.visibility = View.GONE
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
        readDebugStart(intent)
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
     * ★ 但**识别到的原文要显示**（用户后续要求）：
     *   输入框上方显示每次识别成功的文字，发送后自动消失。
     *   这是唯一保留的文字 —— 它不是聊天内容，是「机器听成了什么」的确认，
     *   识别错了用户能立刻发现并用输入框改掉。
     */
    override fun onUser(text: String) {
        runOnUiThread { showRecognized(text) }
    }

    override fun onAssistantDelta(text: String) {
        // 导航页不显示聊天内容
    }

    override fun onAssistantDone(full: String) {
        // 导航页不显示聊天内容，答案由 TTS 播报。
        // 模型开始回答了，识别提示就没用了。
        runOnUiThread { clearRecognized() }
    }

    override fun onVoiceState(state: VoiceEngine.State, hint: String) {
        runOnUiThread {
            status.text = hint
            updateTalkIndicator(state)
        }
    }

    /**
     * 导航页的「语音」按钮也做同样的呼吸反馈。
     *
     * ★ 按钮是 LinearLayout（图标 + 文字），不是 Button —— 所以文字和图标要分开设。
     *   用 Button 的 drawableStart 时「图标+文字」整体偏左，实测左边 1dp、右边 8dp。
     */
    private fun updateTalkIndicator(state: VoiceEngine.State) {
        val talk = findViewById<View>(R.id.talk) ?: return
        val label = findViewById<TextView>(R.id.talk_label) ?: return
        val icon = findViewById<ImageView>(R.id.talk_icon) ?: return
        // ★ 播报中优先显示「播报中」。导航播报时这个提示特别有用 ——
        //   用户能看出「现在是导航在说话」，喊话要等它说完或主动打断。
        if (app.voice.isSpeaking) {
            label.text = getString(R.string.mic_speaking)
            talk.alpha = 1f
            talk.setBackgroundResource(R.drawable.bg_input)
            // 播报中用喇叭图标：这时候是「它在说话」
            icon.setImageResource(R.drawable.ic_mic)
            stopTalkPulse(talk)
            return
        }
        when (state) {
            VoiceEngine.State.LISTENING -> {
                label.text = getString(R.string.mic_listening)
                talk.alpha = 1f
                // ★ 底色统一用 bg_input（跟输入框一致），但聆听态要能看出来，
                //   所以用 bg_btn_listening 那个双层蓝框 —— 它是描边样式，
                //   不会破坏底色统一。
                talk.setBackgroundResource(R.drawable.bg_btn_listening)
                // 聆听中换成麦克风图标：这时候是「在听你说话」
                icon.setImageResource(R.drawable.ic_mic_active)
                startTalkPulse(talk)
            }

            VoiceEngine.State.THINKING -> {
                label.text = getString(R.string.mic_thinking)
                talk.setBackgroundResource(R.drawable.bg_input)
                icon.setImageResource(R.drawable.ic_mic)
                stopTalkPulse(talk)
            }

            VoiceEngine.State.ASLEEP -> {
                label.text = "语音"
                talk.setBackgroundResource(R.drawable.bg_input)
                icon.setImageResource(R.drawable.ic_mic)
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
        // 订阅 TTS 播报状态：导航播报时语音按钮显示「播报中」
        speakingListener = app.voice.addSpeakingListener {
            val state = app.voiceStateSnapshotProvider?.invoke()?.first ?: VoiceEngine.State.ASLEEP
            runOnUiThread { updateTalkIndicator(state) }
        }
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
        speakingListener?.invoke()
        speakingListener = null
    }

    /** TTS 播报状态订阅的注销函数 */
    private var speakingListener: (() -> Unit)? = null

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
        const val EXTRA_SLAT = "slat"
        const val EXTRA_SLON = "slon"
        private const val REQ_PERMS = 1002
        private const val FIX_TIMEOUT_MS = 60_000L
    }
}
