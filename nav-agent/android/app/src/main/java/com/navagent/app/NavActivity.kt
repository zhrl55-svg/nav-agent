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
class NavActivity : AppCompatActivity(), NavApp.ChatUi, NavApp.VoiceUi, NavApp.PlaceSearchUi, NavApp.NavStoppedUi {

    private val app: NavApp get() = application as NavApp

    private lateinit var naviView: AMapNaviView
    private lateinit var hud: View
    private lateinit var hudIcon: ImageView
    private lateinit var hudStep: TextView
    private lateinit var hudRoad: TextView
    private lateinit var hudRemain: TextView
    private lateinit var nav: AmapNavController

    /** 识别文本的自动消失计时器 */
    private val recognizedHandler = android.os.Handler(android.os.Looper.getMainLooper())


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
        // ★ 自动锁车：车标固定在地图中心，地图随车平移/旋转。
        //
        //   2026-11-03 之前这里写死 false，结果就是用户说的
        //   「地图有时候不能自动调整方向和中心」—— 车开几个路口，
        //   车标就跑到屏幕边缘甚至跑出屏幕，看着像地图卡住了。
        options.isAutoLockCar = Prefs.navAutoLock
        options.isAutoChangeZoom = Prefs.navAutoZoom
        // 自动缩放的范围。太小会糊，太大看不到下一个路口，18 封顶正合适
        runCatching { options.setAutoZoomRange(15f, 18f) }
        if (Prefs.amapBuiltinUi) {
            // 高德 HUD 限制在「上边留出状态栏高度、下边留出底部面板」的区域内。
            //
            // ★ 2026-11-04：顶部**只让状态栏**，不再让我们的顶栏。
            //   因为搜索栏挪到转向条**下方**了 —— 转向条必须从状态栏下面就开始画，
            //   我们的搜索栏再排在它下面。
            //   之前让出「状态栏 + 顶栏」的话，高德转向条会被顶到搜索栏下面，
            //   等于把顺序反过来了（搜索栏压着转向条 → 转向条被挤到中间）。
            //
            // 下边按实际测量留，不能写死 —— 不同屏幕和字号都不一样。
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val navArea = android.graphics.Rect(
                0, statusBarHeight(),
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

        // ★ 接高德视图的点击回调 —— 主要是「退出导航」那一下。
        //   底部 footer 的「退出」是高德自己画的按钮，点它弹高德内置对话框
        //   （退出导航 / 取消）。用户选「退出导航」时 SDK 回调 onNaviCancel()。
        //   不接这个回调，那个对话框就是假的：点了没反应，路线还在、播报还在
        //   （用户反馈：「点击退出导航无作用」）。
        runCatching {
            naviView.setAMapNaviViewListener(NaviViewListener {
                // 退出导航：停掉高德，但**留在地图页**（还能重新规划）
                nav.stop()
                app.destination = null
                // ★ 地图恢复到初始状态（2026-11-04）：
                //   退出导航后如果镜头还停在全程视图、或者停在路线某一段，
                //   用户看到的是一张「导航没了但地图不对」的界面。
                //   恢复成「打开 app 时的样子」：跟随视角 + 当前位置。
                resetMapToInitialState()
                toast("已退出导航 · 说个目的地重新开始")
                Dbg.log("用户确认退出导航")
            })
        }.onFailure { Dbg.log("设置高德视图监听失败：${it.message}") }

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
            // ★ 未导航时在中心画一个方向箭头。
            //   不导航的时候 SDK 不画车标，地图上只有一个十字路口，看不出"我在哪、朝哪"。
            //   高德的定位图层能补上这个空档，而且箭头会跟着方向转。
            updateLocationArrow(!nav.isNavigating)
        }

        // ★ 路况线 / 红绿灯：都跟着设置走，不再写死。
        //   原来这两项一直开着，用户在设置里关不掉，夜间跑高速时线太花。
        runCatching { naviView.setTrafficLine(Prefs.navTrafficLine) }
            .onFailure { Dbg.log("路况线开关失败：${it.message}") }

        // ★ 红绿灯。
        //   HiCar 那张图路口有红绿灯倒计时，SDK 里对应这两个方法：
        //     setTrafficLightsVisible    —— 地图上显示红绿灯图标
        //     setShowTrafficLightView   —— 额外的红绿灯提示视图
        //   倒计时秒数由 SDK 自己按路口数据画，有数据才显示；
        //   数据源用 AMapNavi.setTrafficSignalEnable 打开（见 AmapNavController.start）。
        runCatching {
            naviView.setTrafficLightsVisible(Prefs.navTrafficLight)
            naviView.setShowTrafficLightView(Prefs.navTrafficLight)
        }.onFailure { Dbg.log("红绿灯开关失败：${it.message}") }

        // ★ 导航视角：车头朝上 / 正北朝上。
        //   未锁车时还要把地图摆到定位点，否则刚打开会停在北京。
        runCatching {
            naviView.setNaviMode(
                if (Prefs.navCarUp) AMapNaviView.CAR_UP_MODE else AMapNaviView.NORTH_UP_MODE
            )
        }.onFailure { Dbg.log("导航视角设置失败：${it.message}") }

        hud = findViewById(R.id.hud)
        hudIcon = findViewById(R.id.hud_icon)
        hudStep = findViewById(R.id.hud_step)
        hudRoad = findViewById(R.id.hud_road)
        hudRemain = findViewById(R.id.hud_remain)

        // TTS 是 Application 级单例，导航播报和聊天回复共用一条队列
        nav = AmapNavController(
            context = this,
            voice = app.voice,
            onState = { msg -> runOnUiThread { toast(msg) } },
            onHud = { h -> runOnUiThread { renderHud(h) } },
            onFirstFix = { lat, lon -> runOnUiThread { centerOn(lat, lon) } },
            // 算路完成 → 先看全程，5 秒后回当前位置（2026-11-04）
            onRouteReady = { meters, seconds, isReroute ->
                runOnUiThread { showRouteOverviewBriefly(meters, seconds, isReroute) }
            }
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
        findViewById<TextView>(R.id.back_to_chat).setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        findViewById<TextView>(R.id.history_nav).setOnClickListener { chooseNavigationHistory() }
        // ★ 设置入口改成菜单：从 2026-11-03 起多了一套「地图与导航」设置，
        //   直接跳应用设置会让人找不到新的那套（用户明确要求"增加地图和导航的设置菜单"）。
        findViewById<View>(R.id.nav_settings).setOnClickListener { showSettingsMenu() }

        // ★ 退出 app（不是退出导航）。
        //   高德 footer 里那个「退出」只停导航，app 还活着、语音服务还在听麦克风，
        //   跟用户要的「退出 app」不是一回事，所以左上角这颗必须留着。
        findViewById<View>(R.id.exit_nav).setOnClickListener { exitApp() }

        // ★ 全程预览按钮（2026-11-04）
        //   点一下看整条路线，再点一下回到当前位置 —— 跟高德 App 的行为一致。
        findViewById<View>(R.id.route_overview).setOnClickListener { toggleRouteOverview() }

        setupSearchBar()

        if (hasLocationPermission()) {
            nav.start()
            startVoiceService()
            // 调试入口：冷启动时也要能接 --es say。
            // ★ 之前只在 onNewIntent 里接了，onCreate 漏了 —— 结果
            //   `am start ... --es say "..."` 在 app 没运行时静默失效，白排查半天。
            consumeSayExtra(intent)
            if (intent?.getStringExtra(EXTRA_DEST).isNullOrBlank()) {
                toast("地图已打开 · 说个目的地就开始导航")
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
     * 左上角退出按钮：**退出整个应用**，不是退出导航。
     *
     * ★ 2026-11-04：左上角退出按钮撤了（底部底框取消后高德自带的 footer
     *   「退出」会露出来，两个「退出」并排会让用户不知道按哪个）。
     *   这个方法保留，入口挪到「设置」菜单最后一项。
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
     * 不弹确认框：这是「退出应用」不是「结束导航」。出口上再设一道门只会让人烦。
     */
    private fun exitApp() {
        Dbg.log("退出应用：navigating=" + nav.isNavigating)
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
     * ★ 2026-11-04：底部面板整个取消了，现在返回 **0**。
     *   原来返回的是整块面板高度（~365px），高德 HUD 就会被压到屏幕中间 ——
     *   转向箭头、剩余距离全被挤到上面去了。
     *   现在底部只剩高德自己的 footer（退出 / 剩余距离 / 剩余时间），
     *   我们不再往上让，所以这里给 0。
     */
    private fun bottomPanelHeight(): Int = 0

/**
 * 搜索栏占多高（像素）。
 *
 * ★ 2026-11-04：搜索栏挪到转向条下方，`top_row` 这个 id 已经没有了
 *   （搜索栏和信号胶囊拆开了）。
 *   这个方法现在只用于「搜索栏定位」的兜底计算。
 */
    private fun topBarHeight(): Int {
        val box = findViewById<View>(R.id.search_box)
        val h = box?.height ?: 0
        val margin = (layoutHeightMarginOf(box))
        return if (h > 0) h + margin else dp(56)
    }

    private fun layoutHeightMarginOf(v: View?): Int {
        val lp = v?.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return dp(8)
        return lp.topMargin + lp.bottomMargin
    }

/**
     * 把搜索栏挪到高德「中间信息区」同一行（限速牌那层）的右侧。
     *
     * ★ 为什么用 middle_layout 的 **top** 而不是 bottom：
     *   middle_layout（597~819）那一行里，左边是**限速牌**（那个圆圆的 km/h 牌子），
     *   右边是空的。搜索栏跟它同行、靠右放，限速牌就完整露出来了。
     *   放到 bottom（819）以下虽然也不压限速牌，但会离转向条太远，
     *   视觉上跟其它控件脱节。
     *
     * ★ 为什么不写死 topMargin：
     *   转向条 / 中间信息区的高度随屏幕尺寸、SDK 版本、是否有车道级信息变化。
     *   写死 dp 会出现「压住转向箭头」或者「飘在半空」两种都不对。
     *
     * ★ 查找方式：遍历子树按 id 资源名匹配（getIdentifier 查不到，jar 不是 aar）。
     */
    private fun positionSearchBelowBanner() {
        val container = findViewById<View>(R.id.top_container) ?: return
        val lp = container.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        // ★ 没导航时回到屏幕顶部。
        //   转向条那一层被我们藏了，中间信息区也跟着藏了，
        //   搜索栏再按它的坐标定位就会飘到奇怪的位置。
        val top = if (nav.isNavigating) middleLayoutTop() else -1
        // 中间信息区没画出来 → 退回顶部兜底位置（状态栏 + 一小段边距）
        val target = if (top > 0) top + dp(4) else dp(12)
        if (lp.topMargin == target) return
        lp.topMargin = target
        container.layoutParams = lp
        Dbg.log("搜索栏定位 topMargin=$target（中间信息区顶部=$top）")
    }

    /**
     * 高德「中间信息区」的顶部坐标（相对 Activity 内容区）。
     *
     * 找不到就返回 -1，调用方按兜底位置处理。
     */
    private fun middleLayoutTop(): Int {
        val v = findViewByIdName(naviView, MIDDLE_LAYOUT_ID) ?: return -1
        if (v.height <= 0) return -1
        val offset = IntArray(2)
        v.getLocationInWindow(offset)
        val naviOffset = IntArray(2)
        naviView.getLocationInWindow(naviOffset)
        return offset[1] - naviOffset[1]
    }

    /**
     * 提示信息出口。
     *
     * ★ 2026-11-04：状态栏（status TextView）整个取消了。
     *   原来它贴在屏幕底部一行字，导航中没东西说的时候常驻在那里，
     *   跟高德自己的 footer 抢位置，还多占一条视觉元素。
     *
     *   现在改成 **Toast**：只在真的有话要说的时候冒一下（1.8 秒），
     *   说完就没了，地图干干净净。
     *
     *   为什么不用 Snackbar：Snackbar 会从底部滑出来压在地图上，
     *   而且要自己找合适的位置；Toast 是系统浮层，自动避让，
     *   在导航场景下更省事。
     *
     *   ★ 语音状态提示（「在听…」「识别中…」）不走这里 —— 那个频率高
     *   （每次唤醒都来一次），走 Toast 会连着弹、很吵。
     *     语音状态由语音按钮自己的图标和文字表示（见 updateTalkIndicator）。
     */
    private fun toast(msg: String) {
        if (msg.isBlank()) return
        Dbg.log("提示：$msg")
        runCatching {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 顶部搜索栏

    /** 输入防抖用，和信号轮询分开，免得互相 removeCallbacks */
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 搜索请求序号，防止旧结果覆盖新结果 */
    private var searchSeq = 0

    /** 回填地名时暂时关掉自动搜索 */
    private var suppressAutoSearch = false

    /**
     * 顶部搜索栏。
     *
     * 交互取舍：**不弹新页面**。开车时跳页面会丢掉地图视野，
     * 所以结果以浮层形式挂在搜索框下面，点一条就直接算路并收起。
     */
    private fun setupSearchBar() {
        val box = findViewById<View>(R.id.search_box)
        val input = findViewById<EditText>(R.id.search_input)
        val clear = findViewById<TextView>(R.id.search_clear)

        // 设置里可以整条关掉（有人嫌挡地图）
        box.visibility = if (Prefs.navSearchBar) View.VISIBLE else View.GONE

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(input.text.toString())
                true
            } else false
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val blank = s.isNullOrBlank()
                clear.visibility = if (blank) View.GONE else View.VISIBLE
                // ★ 输入停顿 800ms 自动搜，不依赖回车键。
                //   实测中文输入法会吃掉 IME_ACTION_SEARCH（回车只是"确认候选词"），
                //   只靠回车的话用户打完字什么都没发生，会以为搜索坏了。
                if (suppressAutoSearch) return
                searchHandler.removeCallbacksAndMessages(null)
                if (blank) {
                    hideSearchResults()
                    return
                }
                val q = s.toString().trim()
                searchHandler.postDelayed({ doSearch(q) }, SEARCH_DEBOUNCE_MS)
            }
        })
        clear.setOnClickListener {
            input.setText("")
            hideSearchResults()
        }
        // 失去焦点就收起结果，避免一直挡地图
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSearchResults()
        }
    }

    private fun doSearch(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        // ★ 不弹「搜索「X」…」toast（2026-11-04）：
        //   列表本身 0.4 秒后就出来了，那个 toast 只会让人更觉得慢
        //   （它先出现、然后列表才来，中间那段空窗被放大了）。
        Dbg.log("搜索栏搜索：$keyword")
        // 序号防串台：输入快时旧请求可能后到，不能让它覆盖新结果
        val seq = ++searchSeq
        // ★ 最多 4 条。8 条会被键盘压掉大半（实测第 5 条就看不见），
        //   而开车时也不需要第 8 个候选 —— 前面几条不对，说明关键词该改。
        //
        // ★ 走 Web API（PlaceWebSearch.searchLocalFirst），不用 SDK 的 PoiSearch：
        //   SDK 的 city 参数不生效（实测 city=张掖市 搜「水勘院超市」
        //   返回石家庄的同名地点）。Web API 的 citylimit 是准的。
        //   而且是「本地优先」：地名不带行政区划先搜本地，
        //   本地没有真正命中才放开全国（跨城地名要能搜到）。
        Thread {
            val pois = runCatching {
                PlaceWebSearch.searchLocalFirst(keyword, nav.currentCity, limit = 4)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                if (pois.isEmpty()) {
                    toast("没搜到「$keyword」")
                    hideSearchResults()
                    return@runOnUiThread
                }
                toast("搜到 ${pois.size} 个结果，点一条直接导航")
                renderSearchResults(pois)
            }
        }.apply { isDaemon = true; name = "search-bar" }.start()
    }

    private fun renderSearchResults(pois: List<AmapSdkGeocoder.Poi>) {
        val list = findViewById<LinearLayout>(R.id.search_results)
        list.removeAllViews()
        pois.forEach { poi ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = poi.name
                setTextColor(android.graphics.Color.parseColor("#E6EDF5"))
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (poi.address.isNotBlank()) {
                row.addView(TextView(this).apply {
                    text = poi.address
                    setTextColor(android.graphics.Color.parseColor("#8FA8C0"))
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.setOnClickListener {
                hideSearchResults()
                // 收起键盘，不然挡住半张地图
                runCatching {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(row.windowToken, 0)
                }
                // ★ 候选已确认，**清空输入框**（2026-11-04，用户要求）。
                //   列表消失了，搜索框里留着旧关键词会让人以为还能继续搜。
                //   清空时不要触发自动搜索（否则结果列表又弹出来）。
                suppressAutoSearch = true
                findViewById<EditText>(R.id.search_input).setText("")
                suppressAutoSearch = false
                findViewById<View>(R.id.search_clear)?.visibility = View.GONE
                searchHandler.removeCallbacksAndMessages(null)
                // ★ 候选已确认，清掉暂存 —— 否则下一次导航会误命中这批旧候选
                app.clearPendingPois()
                Prefs.rememberDestination(poi.name)
                app.destination = poi.name
                nav.navigateTo(poi.name)
                // 不弹「正在规划…」—— 中间态 1 秒就过去了，
                // 弹出来只是闪一下，还会跟高德的「准备出发」播报抢注意力
                Dbg.log("搜索栏选择 → ${poi.name} (${poi.latitude},${poi.longitude})")
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        list.visibility = View.VISIBLE
        findViewById<View>(R.id.search_results_scroll).visibility = View.VISIBLE
    }

    private fun hideSearchResults() {
        findViewById<View>(R.id.search_results_scroll).visibility = View.GONE
        findViewById<LinearLayout>(R.id.search_results).removeAllViews()
    }

    /**
     * 地图恢复到初始状态（退出导航时调用）。
     *
     * ★ 要恢复的三样：
     *   ① 视角 —— 如果正在看全程（displayOverview），先退出来
     *      （recoverLockMode 会恢复跟随视角）。
     *   ② 镜头位置 —— 回到当前定位点。
     *   ③ 路线残留 —— SDK 的 stopNavi() 会清路线，但偶尔会留下一条灰线，
     *      重新算路一次或者刷新一下图层能清掉。
     *
     * ★ 为什么需要：
     *   退出导航后如果镜头还停在全程视图，用户看到的是一张
     *   「导航没了但地图不对」的界面 —— 像是 app 卡住了。
     */
    private fun resetMapToInitialState() {
        // ① 退出全程视图，恢复跟随视角
        runCatching {
            if (naviView.isRouteOverviewNow) naviView.recoverLockMode()
        }.onFailure { Dbg.log("退出全程视图失败：${it.message}") }

        // ② 镜头回到当前位置
        val loc = nav.currentLocation()
        if (loc != null) {
            centerOn(loc.first, loc.second)
        } else {
            Dbg.log("退出导航时还没定位，镜头先不动")
        }

        // ③ 路线残留 —— SDK 的 stopNavi() **不清路线**（实测退出后地图上还挂着
        //   那条绿色路径，底部还显示「2.6公里 9分钟」，像是还在导航）。
        //
        //   ★ 反射找 SDK 内部的 RouteOverLay 失败了（深度 6 都没找到，
        //     它不在 naviView 的实例字段树里，大概在某个静态单例里）。
        //   ★ 改用「延迟清地图」：stopNavi() 之后 SDK 还会再画一帧，
        //     立刻 clear 会被它覆盖 —— 隔 300ms 再清就干净了。
        //
        //   ★ 用**独立的 handler**：overviewHandler 在下面会被
        //     removeCallbacksAndMessages(null) 清掉（停掉 5 秒自动回切），
        //     同一个 handler 会把这个延迟任务一起干掉。
        routeClearHandler.postDelayed({
            runCatching {
                naviView.map.clear()
                Dbg.log("已清除地图上的路线 overlay")
            }.onFailure { Dbg.log("清除路线失败：${it.message}") }
        }, ROUTE_CLEAR_DELAY_MS)

        // ④ 高德转向条在退出导航后**不会自己消失** ——
        //   实测退出后它还挂着「74米 进入 无名道路」，像是还在导航。
        //   统一交给 updateBannerVisibility 管（它也是导航中才显示）。
        updateBannerVisibility(false)

        // ⑤ 全程按钮隐藏（没导航就没有全程）
        findViewById<View>(R.id.route_overview)?.visibility = View.GONE

        // ⑥ 恢复定位箭头（不导航时地图上要有「我在哪」的箭头）
        updateLocationArrow(true)

        // ⑦ 计时器停掉（别让 5 秒后的自动回切去动一个已经停了的导航）
        overviewHandler.removeCallbacksAndMessages(null)

        Dbg.log("地图已恢复到初始状态")
    }

    /**
     * 导航停止（语音说「停止导航」那条路径）。
     *
     * ★ 地图恢复初始状态 —— 跟高德 footer 的「退出」是同一套处理，
     *   两条停止路径的表现必须一致。
     */
    override fun onNavigationStopped() {
        resetMapToInitialState()
    }

    // ---------------------------------------------------------------- 全程预览

    /**
     * 「全程」按钮：全程 ↔ 当前位置 切换。
     *
     * ★ 行为跟高德 App 一致：
     *   不在全程状态 → 显示全程（地图缩到整条路线）
     *   已在全程状态 → 回到当前位置（恢复跟随视角）
     */
    private fun toggleRouteOverview() {
        if (!nav.isNavigating) {
            toast("现在没在导航，没有全程可看")
            return
        }
        val nowOverview = runCatching { naviView.isRouteOverviewNow }.getOrDefault(false)
        if (nowOverview) {
            runCatching { naviView.recoverLockMode() }
            Dbg.log("全程预览 → 回到当前位置")
        } else {
            runCatching { naviView.displayOverview() }
            Dbg.log("全程预览 → 显示全程")
        }
    }

    /**
     * 算路完成 → 先显示全程，5 秒后自动切回当前位置。
     *
     * ★ 为什么：
     *   算路成功了，但用户还不知道路线长什么样、要往哪走、要开多久。
     *   直接切到跟随视角是一头雾水。
     *   先给 5 秒看全程，路线就在心里了，之后该看路看路。
     *
     * ★ 只在**首次导航**时做：
     *   导航中改道重算不打断 —— 用户正开着导航呢，突然切全程很烦。
     */
    private fun showRouteOverviewBriefly(meters: Int, seconds: Int, isReroute: Boolean) {
        // 全程按钮只在导航中显示
        findViewById<View>(R.id.route_overview)?.visibility = View.VISIBLE
        if (isReroute) {
            Dbg.log("改道重算，不切全程视图")
            return
        }
        // ★ 要**延迟**一点再切全程。
        //   算路刚完成时 SDK 自己会做一次镜头动画（移到起点并开始跟随），
        //   立刻调 displayOverview() 会被那个动画覆盖掉 ——
        //   实测 `isRouteOverviewNow` 一直是 false，画面根本没变。
        //   延后 600ms，动画结束，切全程才生效。
        overviewHandler.removeCallbacksAndMessages(null)
        overviewHandler.postDelayed({
            runCatching { naviView.displayOverview() }
            Dbg.log("算路完成 → 显示全程（${meters}m / ${seconds}s），5 秒后回当前位置")

            overviewHandler.postDelayed({
                // 用户已经自己切回去了就别动
                val nowOverview = runCatching { naviView.isRouteOverviewNow }.getOrDefault(false)
                if (nowOverview) {
                    runCatching { naviView.recoverLockMode() }
                    Dbg.log("5 秒到 → 切回当前位置")
                } else {
                    Dbg.log("5 秒到 → 不在全程状态，不动")
                }
            }, OVERVIEW_AUTO_BACK_MS)
        }, OVERVIEW_ENTER_DELAY_MS)
    }

    /** 全程视图的计时器 */
    private val overviewHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 清路线的独立计时器。
     *
     * ★ 为什么不用 overviewHandler：
     *   恢复初始状态时 overviewHandler 会被 removeCallbacksAndMessages(null) 清掉
     *   （停掉「5 秒后自动回切」），同一个 handler 会把延迟清路线的任务一起干掉。
     */
    private val routeClearHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ------------------------------------------------------------ 地点候选

    /**
     * 搜到候选了：填进搜索框 + 弹出候选列表。
     *
     * ★ 2026-11-04：候选状态存在 NavApp（Application 级），
     *   因为导航工具跑在子线程里，跟 Activity 生命周期无关。
     *   这里只负责**展示**。
     */
    override fun onPlaceCandidates(keyword: String, pois: List<AmapSdkGeocoder.Poi>) {
        // ① 填进搜索框 —— 用户看得见机器听到了什么
        val input = findViewById<EditText>(R.id.search_input) ?: return
        suppressAutoSearch = true
        input.setText(keyword)
        input.setSelection(keyword.length)
        suppressAutoSearch = false
        searchHandler.removeCallbacksAndMessages(null)
        // ② 弹出候选列表
        renderSearchResults(pois)
        Dbg.log("展示候选：$keyword → ${pois.map { it.name }}")
    }

    override fun onPlaceSearchFailed(keyword: String, reason: String) {
        Dbg.log("候选搜索失败：$keyword $reason")
    }

    /**
     * 候选已确认 → 收起列表 + **清空输入框**。
     *
     * ★ 2026-11-04：语音确认（模型调 navigate）这条路上没人收列表，
     *   用户选完了列表还挂在屏幕上（用户反馈）。
     *
     * ★ 输入框也清空（用户明确要求）：
     *   候选列表已经消失了，搜索框里留着字会让人以为还能继续搜，
     *   而且那个字是旧的关键词（「张掖西站」），不是新状态。
     *   跟点击选择后的行为保持一致（点击那条路也会清）。
     */
    override fun onPlaceCandidatesCleared(selected: AmapSdkGeocoder.Poi?) {
        hideSearchResults()
        val input = findViewById<EditText>(R.id.search_input)
        if (input != null) {
            suppressAutoSearch = true
            input.setText("")
            suppressAutoSearch = false
        }
        // 清掉「✕」按钮（它是靠文字非空才显示的）
        findViewById<View>(R.id.search_clear)?.visibility = View.GONE
        Dbg.log("候选已确认，列表和输入框已清空${selected?.let { "，选中 ${it.name}" } ?: ""}")
    }

    /** 侧边「设置」按钮：几个入口，别把两套设置混在一起 */
    private fun showSettingsMenu() {
        // ★ 2026-11-04：原来这里有一项「退出应用」，
        //   现在左上角有独立的退出按钮了（那个是真正的退出 app，
        //   高德 footer 的「退出」只停导航），这里不用重复。
        val items = arrayOf(
            "地图与导航设置",
            "应用设置（语音、模型、唤醒词）",
            "地图回到当前位置"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> MapSettingsActivity.start(this)
                    1 -> SettingsActivity.start(this)
                    2 -> {
                        val loc = nav.currentLocation()
                        if (loc != null) {
                            centerOn(loc.first, loc.second)
                            runCatching { naviView.recoverLockMode() }
                            toast("地图已回到当前位置")
                        } else {
                            toast("还没拿到定位，稍等一下")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 信号指示

    /**
     * 每秒刷一次导航信号（2026-11-04 恢复）。
     *
     * ★ 为什么自己轮询而不是等 SDK 回调：
     *   `onGpsSignalWeak` 只在**状态变化**时来一次，而「多久没定位」这件事
     *   没有任何回调 —— 进隧道后可能几十秒一声不响，界面就一直挂着旧状态。
     *   轮询 + 定位时间戳才能真正做到「自动刷新」。
     *
     * ★ 为什么不用高德内置那个：
     *   它是**静态**的 —— 进了弱信号就一直挂着「卫星信号弱」不恢复（用户投诉过）。
     *   我们这个每秒刷，颜色和文字都跟着变。
     */
    private val signalHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val signalTick = object : Runnable {
        override fun run() {
            updateSignalChip()
            // ★ 顺手把高德内置的 GPS 视图再清一次。
            //   SDK 每次 updateNaviInfo 都会把它重新 addView 回来（我们清一次它回来一次，
            //   无限循环）。挂在每秒的 tick 上是最省事的兜底 ——
            //   就算某次漏了，最多 1 秒后就消失了，用户基本看不见。
            hideSdkGpsView()
            signalHandler.postDelayed(this, 1_000)
        }
    }

    private fun updateSignalChip() {
        val chip = findViewById<TextView>(R.id.gps_signal) ?: return
        val level = nav.signalLevel()
        val (text, color) = when (level) {
            2 -> "信号好" to "#7FD8A0"
            1 -> "信号弱" to "#F0C060"
            else -> if (nav.hasLocationFix) "无信号" to "#F09595" else "定位中" to "#8FA8C0"
        }
        if (chip.text.toString() != text) chip.text = text
        chip.setTextColor(android.graphics.Color.parseColor(color))
    }

    /**
     * 干掉高德内置的 GPS 信号视图。
     *
     * ★ 四层尝试，前三层都失败过，别再改回去：
     *
     *   ① `getIdentifier("navi_gps_view", "id", packageName)` —— 永远返回 0。
     *      packageName 是我们自己的包名；高德的资源在 com.amap.api.navi 包下，
     *      而那个 jar 是**合并进我们 R 表**的，运行时按那个包名查不到。
     *
     *   ② `setGPSViewVisible(false)` —— 调用「成功」（日志有记录），但 view 照样显示。
     *      SDK 每次 updateNaviInfo 都会把它设回 VISIBLE。
     *
     *   ③ `removeView` —— 能藏掉，但 SDK 下一次更新会**重新 addView 回来**。
     *      （实测：重启导航后它又冒出来了）
     *
     *   ④ ★ remove + **把布局尺寸改成 0** —— 根治。
     *      SDK 把它加回来也没关系，尺寸是 0 就永远看不见。
     *      比单纯 removeView 稳，不会有一帧闪烁。
     *
     * 兜底：如果找不到这个 view，退回到官方 API。
     */
    private fun hideSdkGpsView() {
        runCatching {
            val found = findViewByIdName(naviView, "navi_gps_view")
            if (found != null) {
                // ① 先把尺寸清零 —— 即使 SDK 把它 addView 回来也不可见
                runCatching {
                    val lp = found.layoutParams
                    if (lp.width != 0 || lp.height != 0) {
                        lp.width = 0
                        lp.height = 0
                        found.layoutParams = lp
                    }
                }
                // ② 再从父容器摘掉
                val parent = found.parent as? android.view.ViewGroup
                if (parent != null && parent.indexOfChild(found) >= 0) {
                    parent.removeView(found)
                    if (!gpsHideWarned) {
                        gpsHideWarned = true
                        Dbg.log("已移除高德内置 GPS 视图（removeView + 尺寸清零）")
                    }
                    return@runCatching
                }
                if (found.visibility != View.GONE) found.visibility = View.GONE
                return@runCatching
            }
            // 兜底：官方 API
            var apiOk = false
            findNaviInfoLayouts().forEach { layout ->
                if (disableSdkGpsView(layout)) apiOk = true
            }
            if (apiOk) {
                if (!gpsHideWarned) {
                    gpsHideWarned = true
                    Dbg.log("已通过 setGPSViewVisible(false) 关闭高德内置 GPS 视图")
                }
            } else if (!gpsHideWarned) {
                gpsHideWarned = true
                Dbg.log("未找到高德内置 GPS 视图")
            }
        }
    }

    /** 在 naviView 子树里找所有 NaviInfoLayout 实例 */
    private fun findNaviInfoLayouts(): List<com.amap.api.navi.view.BaseNaviInfoLayout> {
        val out = ArrayList<com.amap.api.navi.view.BaseNaviInfoLayout>()
        fun walk(v: View?) {
            if (v !is android.view.ViewGroup) return
            if (v is com.amap.api.navi.view.BaseNaviInfoLayout) {
                out.add(v)
                return
            }
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(naviView)
        return out
    }

    /**
     * 调 SDK 官方的 `setGPSViewVisible(false)`。
     *
     * ★ 为什么用反射 + 遍历所有方法：
     *   javap 显示签名是 setGPSViewVisible(boolean)，
     *   但运行时 getDeclaredMethod 直接抛 NoSuchMethodException ——
     *   高德 jar 混淆过，真实的方法名跟 javap 看到的不完全一致。
     *   遍历所有含 "GPS" 的方法逐个试调，最稳。
     *
     * ★ 为什么遍历**所有** NaviInfoLayout 实例：
     *   SDK 里同时存在 NaviInfoLayout_L（横屏）和 NaviInfoLayout_P（竖屏），
     *   竖屏实际显示的是哪个不一定 —— 之前只对第一个找到的调，
     *   结果调的是 _L，竖屏那个 view 照样显示。
     */
    private fun disableSdkGpsView(layout: com.amap.api.navi.view.BaseNaviInfoLayout?): Boolean {
        if (layout == null) return false
        return runCatching {
            val candidates = layout.javaClass.declaredMethods.filter {
                it.name.contains("GPS", ignoreCase = true) || it.name.contains("Gps")
            }
            if (candidates.isEmpty()) {
                Dbg.log("${layout.javaClass.simpleName} 上没有 GPS 相关方法")
                return@runCatching false
            }
            var ok = false
            for (m in candidates) {
                runCatching {
                    m.isAccessible = true
                    val args: Array<Any?> = Array(m.parameterCount) { false }
                    m.invoke(layout, *args)
                    ok = true
                }
            }
            if (!ok) Dbg.log("${layout.javaClass.simpleName} 的 GPS 方法都调不通：${candidates.map { it.name }}")
            ok
        }.getOrElse {
            Dbg.log("setGPSViewVisible 调用异常：${it.cause?.message ?: it.message}")
            false
        }
    }

    /** 「隐藏内置 GPS 视图」的日志只打一次 */
    @Volatile
    private var gpsHideWarned = false

    /** 递归按 view 的 id 名字查找（id 资源名不是 view 类名时才用得上） */
    private fun findViewByIdName(root: View?, name: String): View? {
        if (root !is android.view.ViewGroup) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val entry = runCatching {
                child.resources.getResourceEntryName(child.id)
            }.getOrNull()
            if (entry == name) return child
            findViewByIdName(child, name)?.let { return it }
        }
        return null
    }

    /**
     * 未导航时在地图中心显示方向箭头。
     *
     * ★ 为什么需要：不导航的时候 SDK 不画车标，地图上只有路网，
     *   看不出"我在哪、朝哪边"。高德 App 在这种状态下是有定位箭头的。
     *
     * ★ 导航一开始就必须关掉：SDK 会自己画车标（我们换成了 car_model 汽车模型），
     *   两个叠在同一个位置会互相盖住，看起来像图标抖了一下。
     *
     * @param show true = 显示定位箭头；false = 交给 SDK 的车标
     */
    private fun updateLocationArrow(show: Boolean) {
        runCatching {
            val map = naviView.map
            if (!show) {
                map.isMyLocationEnabled = false
                return
            }
            if (locationArrow == null) {
                val d = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_nav_arrow)
                    ?: return
                val size = dp(30)
                val bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(canvas)
                locationArrow = com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
            }
            val style = com.amap.api.maps.model.MyLocationStyle()
                // 箭头跟着设备方向转，但**不强制居中** ——
                // 强制居中时用户想拖开地图看看周边，一松手就被拽回来，很烦。
                .myLocationType(
                    com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER
                )
                .myLocationIcon(locationArrow)
                // 不要精度圈的填充和描边：深色地图上一大块半透明蓝很脏
                .radiusFillColor(android.graphics.Color.TRANSPARENT)
                .strokeColor(android.graphics.Color.TRANSPARENT)
                .showMyLocation(true)
            map.myLocationStyle = style
            map.isMyLocationEnabled = true
        }.onFailure { Dbg.log("定位箭头开启失败：${it.message}") }
    }

    private var locationArrow: com.amap.api.maps.model.BitmapDescriptor? = null

    private fun renderHud(h: NavHud) {
        // 按钮状态跟着导航状态走，放在最前面 —— HUD 隐藏时也要更新
        updateNavButton(h.navigating)
        // 导航中让 SDK 画车标，不导航时用我们的定位箭头补位
        updateLocationArrow(!h.navigating)
        // ★ 高德转向条的高度会随车道级信息、路名等变化，
        //   搜索栏要跟着重新定位到它下面。
        positionSearchBelowBanner()
        // ★ SDK 每次更新导航信息都会把内置 GPS 视图重新设成 VISIBLE，
        //   所以得在**每次更新时**都关一次 —— 只在每秒 tick 里关一次藏不住
        //   （实测截图里它照样压在转向条右上角）。
        //   renderHud 跟着 onNaviInfoUpdate 走，频率正好。
        hideSdkGpsView()
        // ★ 反过来：退出导航时我们把转向条藏了（免得它挂着「74米」像还在导航），
        //   开始新导航时要把它放回来 —— SDK 管的是它内部的子视图，
        //   容器我们藏了它不会自己恢复。
        // ★ 而且**没导航时必须藏着** —— SDK 会画一个空的黑色卡片
        //   （转向箭头位置空着，但整块背景是黑的），很难看（用户反馈）。
        updateBannerVisibility(h.navigating)
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
        hudRemain.text = buildString {
            append(
                String.format(
                    Locale.CHINA,
                    "剩余 %s · %d 分钟",
                    formatDistance(h.remainMeters),
                    h.remainSeconds / 60
                )
            )
            // ★ 前方红绿灯个数。
            //   SDK 只在有信号的路口下发倒计时秒数，但"还剩几个灯"是全量的，
            //   稳定可拿（NaviInfo.getRouteRemainLightCount）。有就显示，没有不占位。
            if (Prefs.navTrafficLight && nav.remainLights > 0) {
                append(" · 前方 ").append(nav.remainLights).append(" 个红绿灯")
            }
        }
    }

    /**
     * 高德转向条容器的显示/隐藏。
     *
     * ★ 为什么不用 SDK 自己的逻辑：
     *   SDK 在没导航时也会把转向条画出来（空的黑色卡片，很丑），
     *   导航结束后也不自动隐藏（挂着「74米 进入 无名道路」像还在导航）。
     *   所以显示与否由我们管：
     *     导航中 → VISIBLE
     *     其他   → GONE
     */
    private fun updateBannerVisibility(navigating: Boolean) {
        runCatching {
            val banner = findViewByIdName(naviView, BANNER_VIEW_ID) ?: return
            val want = if (navigating) View.VISIBLE else View.GONE
            if (banner.visibility != want) {
                banner.visibility = want
                Dbg.log("转向条可见性 → ${if (navigating) "显示" else "隐藏"}")
                // 转向条一藏一显，中间信息区的位置就变了，搜索栏要跟着重新定位
                positionSearchBelowBanner()
            }
        }
    }

    private fun formatDistance(meters: Int): String = when {
        meters >= 1000 -> String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
        meters > 0 -> "$meters 米"
        else -> "—"
    }

    private fun chooseNavigationHistory() {
        val history = Prefs.navigationHistory()
        if (history.isEmpty()) {
            toast("还没有导航历史 · 说出或输入目的地后会自动保存")
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
                Dbg.log("历史目的地选择 → $dest")
            }
            .setNegativeButton("取消", null)
            .show()
    }

/**
     * 显示这次识别到的文字。
     *
     * 为什么需要：纯语音界面没有消息列表，用户看不到机器听成了什么。
     * 识别错了（「西站」听成「西战」）用户根本无从发现，
     * 只能眼睁睁看它导错地方。
     *
     * ★ 2026-11-04：显示 2 秒后自动消失。
     *   它只是「机器听成了什么」的瞬时确认，一直挂着会挡地图。
     *   2 秒足够读完并发现「哎说错了」，之后注意力就该回到路上了。
     */
    private fun showRecognized(text: String) {
        val tv = findViewById<TextView>(R.id.recognized) ?: return
        recognizedHandler.removeCallbacksAndMessages(null)
        if (text.isBlank()) {
            tv.visibility = View.GONE
            return
        }
        tv.text = text
        tv.visibility = View.VISIBLE
        recognizedHandler.postDelayed({ tv.visibility = View.GONE }, RECOGNIZED_HOLD_MS)
    }

    /** 清掉识别提示（模型开始回答时、或切走时） */
    private fun clearRecognized() {
        val tv = findViewById<TextView>(R.id.recognized) ?: return
        recognizedHandler.removeCallbacksAndMessages(null)
        tv.text = ""
        tv.visibility = View.GONE
    }

    /**
     * 同一个按钮的两种状态：
     *   导航中 → 「停止导航」，点一下停
     *   没导航 → 「开始导航」，点一下接着去上次那个目的地
     *
     * ★ 2026-11-04：这个按钮和底部输入框一起取消了，方法保留但不再有调用方。
     *   为什么留着：以后想在某个位置（语音长按菜单？地图长按？）重新挂出来时，
     *   逻辑已经写好，不用重写。
     *
     * 为什么保留目的地：用户停导航多半是临时绕一下或者走错了，
     * 直接清空的话再开就得重新说一遍地名，很烦。`navContext()` 只在真在导航时
     * 才吐状态（`remainingSeconds() == null` 就返回空），所以留着不会污染模型上下文。
     */
    private fun toggleNavigation() {
        if (nav.isNavigating) {
            nav.stop()
            toast("导航已停止 · 点「开始导航」可以接着走")
            Dbg.log("按钮：停止导航")
            return
        }
        val dest = app.destination
        if (dest.isNullOrBlank()) {
            toast("还没设目的地 · 在下面输入框打个地名，或者直接说")
            return
        }
        Prefs.rememberDestination(dest)
        nav.navigateTo(dest)
        app.destination = dest
        toast("正在重新规划到 $dest …")
        Dbg.log("按钮：开始导航 → $dest")
    }

    /**
     * 导航状态变化时的处理。
     *
     * ★ 2026-11-04：「停止导航 / 开始导航」按钮随底部面板一起取消了，
     *   状态栏也取消了，所以这里没再要更新的控件。
     *   停导航的入口变成高德自带的 footer「退出」，还有语音（说「停止导航」）。
     *
     *   保留这个空实现是为了让 renderHud / onResume 的调用点不用改 ——
     *   以后想在语音按钮上体现「导航中 / 已停止」，挂回这里就行。
     */
    private fun updateNavButton(navigating: Boolean) {
        // 留空
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
            toast("没有定位权限，导航不可用")
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
        toast("等待定位…（目标：$dest）")
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
            // ★ 状态栏取消了，语音状态**不走 Toast**：
            //   「在听…」「识别中…」每次唤醒都来一次，Toast 会连着弹，很吵。
            //   状态由语音按钮自己表示（图标 + 文字 + 描边，见 updateTalkIndicator）。
            //   hint 只记进日志，排查时能看。
            Dbg.log("语音状态：$hint")
            updateTalkIndicator(state)
        }
    }

    /**
     * 导航页的「语音」按钮：只留图标，状态靠图标本身表达。
     *
     * ★ 2026-11-04：去掉了「语音」两个字，按钮缩成 46×46 的圆形。
     *   地图露出来的面积更大，横向空间也不再被占。
     *
     *   图标语义（统一过，别再搞反）：
     *     麦克风 ic_mic_active = 待命 / 聆听中（它在听你说话）
     *     喇叭   ic_speaker     = TTS 播报中（它在说话）
     *   之前 ic_mic 这个名字下画的是喇叭、ic_mic_active 画的是麦克风，
     *   名字和内容对不上，改图标时特别容易搞反 —— 所以另建了 ic_speaker。
     */
    private fun updateTalkIndicator(state: VoiceEngine.State) {
        val talk = findViewById<View>(R.id.talk) ?: return
        val icon = findViewById<ImageView>(R.id.talk_icon) ?: return
        // ★ 播报中优先显示「小喇叭」。导航播报时这个提示特别有用 ——
        //   用户能看出「现在是导航在说话」，喊话要等它说完或主动打断。
        if (app.voice.isSpeaking) {
            talk.alpha = 1f
            talk.setBackgroundResource(R.drawable.bg_side_btn)
            icon.setImageResource(R.drawable.ic_speaker)
            stopTalkPulse(talk)
            return
        }
        when (state) {
            VoiceEngine.State.LISTENING -> {
                talk.alpha = 1f
                // ★ 底色跟常态一致（都是灰），聆听态靠 bg_btn_listening 的
                //   蓝色描边 + 外圈来区分 —— 不会出现「一排灰按钮里跳出一个蓝底」。
                talk.setBackgroundResource(R.drawable.bg_btn_listening)
                // 聆听中：麦克风图标（它在听你说话）
                icon.setImageResource(R.drawable.ic_mic_active)
                startTalkPulse(talk)
            }

            VoiceEngine.State.THINKING -> {
                talk.setBackgroundResource(R.drawable.bg_side_btn)
                icon.setImageResource(R.drawable.ic_mic_active)
                stopTalkPulse(talk)
            }

            VoiceEngine.State.ASLEEP -> {
                talk.setBackgroundResource(R.drawable.bg_side_btn)
                icon.setImageResource(R.drawable.ic_mic_active)
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
        runOnUiThread { toast(text) }
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
        app.addPlaceSearchUi(this)
        app.addNavStoppedUi(this)
        app.refreshVoiceUi(this)
        // 信号指示每秒刷一次（onPause 里必须摘掉，否则页面退到后台还在跑）
        signalHandler.removeCallbacks(signalTick)
        signalHandler.post(signalTick)
        // ★ 高德内置的 GPS 视图在**没导航时**也会显示（转向条右上角那个「卫星信号弱」）。
        //   它是 SDK 初始化之后才加进视图树的，所以清的动作要**延后**到那之后 ——
        //   onResume 里同步清一次，再延迟 1 秒清一次（SDK 加完就清）。
        hideSdkGpsView()
        routeClearHandler.postDelayed({ hideSdkGpsView() }, 1_000)
        // ★ 没导航时把转向条藏起来 —— SDK 会画一个空的黑色卡片，很难看（用户反馈）
        updateBannerVisibility(nav.isNavigating)
        // 订阅 TTS 播报状态：导航播报时语音按钮显示「播报中」
        speakingListener = app.voice.addSpeakingListener {
            val state = app.voiceStateSnapshotProvider?.invoke()?.first ?: VoiceEngine.State.ASLEEP
            runOnUiThread { updateTalkIndicator(state) }
        }
        // 从聊天页切回来时导航状态可能已经变了
        updateNavButton(nav.isNavigating)
        // 信号指示每秒刷一次（onPause 里必须摘掉，否则页面退到后台还在跑）
        // 从「地图与导航设置」返回时，视角/锁车/路况这些要立刻生效
        applyMapSettings()
        // ★ 布局完成后量一次转向条高度，把搜索栏摆到它下面。
        //   onCreate 里量不到 —— 那时 naviView 还没完成布局，bannerBottom() 返回 -1。
        window.decorView.postDelayed({ positionSearchBelowBanner() }, 500)
    }

    /**
     * 把「地图与导航设置」里的选项应用到当前地图。
     *
     * ★ 为什么放在 onResume 而不是只在 onCreate：
     *   AMapNaviView / Options 都是长生命周期对象，设置页改完回来必须重新推一遍，
     *   否则用户会以为开关没用（还得重启 App 才生效）。
     */
    private fun applyMapSettings() {
        runCatching {
            val options = naviView.viewOptions
            options.isAutoLockCar = Prefs.navAutoLock
            options.isAutoChangeZoom = Prefs.navAutoZoom
            // ★ 顶部只让状态栏 —— 搜索栏挪到转向条下方了，
            //   转向条必须从状态栏下面就开始画（顺序：状态栏 → 转向条 → 搜索栏）。
            if (Prefs.amapBuiltinUi) {
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                val area = android.graphics.Rect(
                    0, statusBarHeight(),
                    w, h - bottomPanelHeight()
                )
                options.setCrossLocation(area, area)
            }
            naviView.viewOptions = options
        }.onFailure { Dbg.log("应用地图设置失败：${it.message}") }
        runCatching {
            naviView.setTrafficLine(Prefs.navTrafficLine)
            naviView.setTrafficLightsVisible(Prefs.navTrafficLight)
            naviView.setShowTrafficLightView(Prefs.navTrafficLight)
            naviView.setNaviMode(
                if (Prefs.navCarUp) AMapNaviView.CAR_UP_MODE else AMapNaviView.NORTH_UP_MODE
            )
            // 关掉自动缩放时，用设置里那个固定级别锁住 —— 否则「缩放级别」形同虚设
            if (!Prefs.navAutoZoom) naviView.setLockZoom(Prefs.navZoom)
            findViewById<View>(R.id.search_box).visibility =
                if (Prefs.navSearchBar) View.VISIBLE else View.GONE
            // 锁车关掉后，镜头不会自动跟随，这里补一次定位点，免得停在旧位置
            if (!Prefs.navAutoLock && !nav.isNavigating) {
                nav.currentLocation()?.let { centerOn(it.first, it.second) }
            }
        }.onFailure { Dbg.log("应用导航视图设置失败：${it.message}") }
    }

    override fun onPause() {
        super.onPause()
        naviView.onPause()
        searchHandler.removeCallbacksAndMessages(null)
        // 信号轮询必须在 onPause 摘掉，否则页面退到后台还在每秒跑
        signalHandler.removeCallbacks(signalTick)
        // ★ 只暂停，不 stop —— 路线要留着，回来才能接着走。
        nav.pauseNavigation()
        app.removeChatUi(this)
        app.removeVoiceUi(this)
        app.removePlaceSearchUi(this)
        app.removeNavStoppedUi(this)
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

        /**
         * 高德 SDK 的资源包名。
         *
         * ★ 查高德内部 view 的 id 必须用这个，不能用 `packageName`（那是我们自己的）。
         *   搞错了 getIdentifier 永远返回 0，「隐藏内置 GPS 视图」就静默失效。
         */
        private const val AMAP_RES_PACKAGE = "com.amap.api.navi"

        /**
         * 高德转向条容器的 id 资源名。
         *
         * ★ 不能用 getIdentifier 查（高德是 jar 不是 aar，资源合并进我们 R 表，
         *   运行时按 com.amap.api.navi 查不到）。只能遍历子树按名字匹配。
         */
        private const val BANNER_VIEW_ID = "navi_sdk_lbs_navi_expand_port_container"

        /**
         * 高德「中间信息区」的 id 资源名（限速牌 + 路名条所在的那一层）。
         *
         * 搜索栏要放在这一层的**下面** —— 只挪到转向条下方会压住限速牌。
         */
        private const val MIDDLE_LAYOUT_ID = "navi_sdk_lbs_navi_middle_layout"
        private const val FIX_TIMEOUT_MS = 60_000L

        /**
         * 输入停顿多久才开始搜。太短会在打字中间乱搜，太长又显得卡。
         *
         * ★ 2026-11-04：800 → 300。
         *   用户反馈「搜索框输入完显示候选列表等的时间过长」。
         *   800ms 防抖 + 请求本身 200~700ms（那时每次还要重做 TLS 握手）
         *   = 从停手到看见列表要 1~1.5 秒，体感就是卡。
         *
         *   现在 300ms 防抖 + 连接复用后的 50~120ms 请求
         *   = 停手后 ~0.4 秒出列表。
         *   300ms 也足够区分「还在打字」和「打完了」——
         *   正常人打字间隔在 150~250ms。
         *
         * ★ 2026-11-04 再调：300 → 200。
         *   实测请求本身要 270ms（高德服务器的网络往返，客户端优化不掉），
         *   防抖是唯一还能压的一环。200ms 对「打完了」的判定仍然够用
         *   （打字间隔 150~250ms，200 刚好在边界上，靠序号防串台兜住）。
         */
        private const val SEARCH_DEBOUNCE_MS = 200L

        /** 识别文本停留多久后自动消失（毫秒） */
        private const val RECOGNIZED_HOLD_MS = 2_000L

        /** 算路完成后显示全程，多久自动切回当前位置（毫秒） */
        private const val OVERVIEW_AUTO_BACK_MS = 5_000L

        /**
         * 算路完成后延迟多久才切全程（毫秒）。
         *
         * ★ 为什么延迟：
         *   算路刚完成时 SDK 自己会做一次镜头动画（移到起点、开始跟随车头）。
         *   立刻调 displayOverview() 会被那个动画覆盖 ——
         *   实测 isRouteOverviewNow 一直是 false，画面根本没变化。
         *
         * ★ 为什么是 1.5 秒不是 0.6 秒：
         *   这段时间高德正好在播「准备出发，全程 2.6 公里…」，
         *   切视图的动作会跟它撞上（用户反馈「全程视图打断了播报」）。
         *   1.5 秒足够它播完前半句。
         */
        private const val OVERVIEW_ENTER_DELAY_MS = 1_500L

        /**
         * 退出导航后延迟多久清地图上的路线（毫秒）。
         *
         * ★ 为什么延迟：
         *   stopNavi() 之后 SDK 还会再画一帧路线，
         *   立刻 map.clear() 会被它覆盖掉（实测路线还在）。
         *   隔 300ms 再清才干净。
         */
        private const val ROUTE_CLEAR_DELAY_MS = 300L
    }
}
