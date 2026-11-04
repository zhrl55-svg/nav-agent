package com.navagent.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 「地图与导航」设置。
 *
 * ★ 2026-11-03 新建。
 *
 * 为什么单独一页，不塞进应用设置：
 *   应用设置那一页已经十几个分区（语音、唤醒词、模型、搜索、声纹…），
 *   再把地图视角、路况、红绿灯、锁车塞进去，找东西要滑半天。
 *   而且这两类设置的使用场景完全不同 —— 开车时调的是地图，
 *   停车时才调模型和唤醒词。
 *
 * 这里的每一项都是**改完立刻生效**（回导航页 onResume 时推一遍），
 * 不需要重启 App。
 */
class MapSettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        render()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(14))
            setBackgroundColor(BG)
        }
        bar.addView(TextView(this).apply {
            text = "地图与导航"
            setTextColor(TEXT)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "完成"
            setTextColor(ACCENT)
            textSize = 15f
            setPadding(dp(12), dp(6), dp(6), dp(6))
            isClickable = true
            setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sv = ScrollView(this).apply { isFillViewport = true }
        scroll = sv
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(28))
        }
        sv.addView(container, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        container.removeAllViews()

        section("地图跟随")
        rowSwitch(
            "自动锁车", Prefs.navAutoLock,
            "车标固定在屏幕中心，地图跟着车走。关掉后地图不动，只在手动操作时移动"
        ) {
            Prefs.navAutoLock = it
            applied()
        }
        row(
            "导航视角",
            if (Prefs.navCarUp) "车头朝上" else "正北朝上",
            "车头朝上跟着转弯转，最直观；正北朝上看方向感更稳"
        ) {
            choose(
                "导航视角",
                listOf("车头朝上（跟着转弯转）" to "car", "正北朝上（地图不转）" to "north"),
                if (Prefs.navCarUp) "car" else "north"
            ) {
                Prefs.navCarUp = it == "car"
                applied()
            }
        }
        rowSwitch(
            "自动缩放", Prefs.navAutoZoom,
            "按车速自动拉近拉远：慢速看路口，高速看全局"
        ) {
            Prefs.navAutoZoom = it
            applied()
        }
        row(
            "手动缩放级别", "${Prefs.navZoom} 级",
            "只在关闭自动缩放时起作用。数字越大看得越近"
        ) {
            choose(
                "缩放级别",
                listOf("15 级（远，看全局）" to 15, "16 级（推荐）" to 16, "17 级（近，看路口）" to 17),
                Prefs.navZoom
            ) { Prefs.navZoom = it; applied() }
        }

        section("路况与红绿灯")
        rowSwitch(
            "路况线", Prefs.navTrafficLine,
            "路线按拥堵着色（红/黄/绿）。关掉是纯蓝线，夜里更干净"
        ) {
            Prefs.navTrafficLine = it
            applied()
        }
        rowSwitch(
            "红绿灯", Prefs.navTrafficLight,
            "显示路口红绿灯图标和倒计时。只有高德有信号数据的路口才会显示秒数"
        ) {
            Prefs.navTrafficLight = it
            applied()
        }

        section("界面")
        rowSwitch(
            "地址搜索栏", Prefs.navSearchBar,
            "顶部常驻搜索框，点结果直接导航。嫌挡地图可以关掉"
        ) {
            Prefs.navSearchBar = it
            applied()
        }
        rowSwitch(
            "高德内置界面", Prefs.amapBuiltinUi,
            "车道级导航、路况条、限速牌这些官方控件。关掉换成自绘转向卡片"
        ) {
            Prefs.amapBuiltinUi = it
            applied()
        }

        section("声音")
        rowSwitch(
            "车载蓝牙播报", Prefs.carBluetoothAudio,
            "播报走蓝牙通话通道，车机才出得来声。关掉只改音频类型，个别车机仍可能没声"
        ) {
            Prefs.carBluetoothAudio = it
            applied()
        }
        row(
            "语音引擎", voiceEngineLabel(),
            "播报用哪个 TTS。云端音色更自然，本地引擎零延迟、没网也能用"
        ) {
            SettingsActivity.start(this)
        }

        section("说明")
        row(
            "红绿灯倒计时", "按路口数据", null, chevron = false
        ) {}
        row(
            "导航信号", "每秒刷新", null, chevron = false
        ) {}
    }

    // ---------------------------------------------------------------- 行组件

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(MUTED)
            textSize = 12.5f
            setPadding(dp(8), dp(18), 0, dp(7))
        })
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(CARD, 14f)
            clipToOutline = true
        }
        container.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun row(
        title: String,
        value: String,
        subtitle: String?,
        chevron: Boolean = true,
        onClick: () -> Unit
    ) {
        divider()
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(13), dp(13))
            if (chevron) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
        })
        if (!subtitle.isNullOrBlank()) {
            left.addView(TextView(this).apply {
                text = subtitle
                setTextColor(MUTED)
                textSize = 11.5f
                setPadding(0, dp(3), 0, 0)
            })
        }
        line.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (value.isNotBlank()) {
            line.addView(TextView(this).apply {
                text = value
                setTextColor(if (chevron) VALUE else ACCENT)
                textSize = 13.5f
                maxLines = 2
                maxWidth = (resources.displayMetrics.widthPixels * 0.48f).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                setPadding(dp(10), 0, 0, 0)
            })
        }
        if (chevron) {
            line.addView(TextView(this).apply {
                text = "  ›"
                setTextColor(MUTED)
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
            isClickable = true
            isFocusable = true
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
        })
        if (!subtitle.isNullOrBlank()) {
            left.addView(TextView(this).apply {
                text = subtitle
                setTextColor(MUTED)
                textSize = 11.5f
                setPadding(0, dp(3), 0, 0)
            })
        }
        line.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val sw = android.widget.Switch(this).apply {
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT_SWITCH)
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
        line.addView(sw)
        line.setOnClickListener { sw.isChecked = !sw.isChecked }
        card.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun divider() {
        if (card.childCount == 0) return
        card.addView(View(this).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { leftMargin = dp(15) })
    }

    private fun <T> choose(title: String, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
        val labels = options.map { it.first }.toTypedArray()
        val checked = options.indexOfFirst { it.second == current }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                onPick(options[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun voiceEngineLabel(): String = when (Prefs.ttsEngine) {
        "" -> "系统默认"
        com.navagent.core.VoiceGuide.CLOUD_ENGINE -> "云端合成"
        else -> Prefs.ttsEngine.substringAfterLast('.')
    }

    // ---------------------------------------------------------------- 工具

    private fun applied() {
        toast("已应用")
        val keep = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, keep) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun roundBg(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val BG = 0xFF101820.toInt()
        private const val CARD = 0xFF182430.toInt()
        private const val TEXT = 0xFFE6EDF5.toInt()
        private const val MUTED = 0xFF8FA8C0.toInt()
        private const val VALUE = 0xFFB8CDE0.toInt()
        private const val LINE = 0xFF20303E.toInt()
        private const val ACCENT = 0xFF7FB2DC.toInt()
        private const val ACCENT_SWITCH = 0xFF4A90C2.toInt()

        fun start(context: Context) {
            context.startActivity(Intent(context, MapSettingsActivity::class.java))
        }
    }
}
