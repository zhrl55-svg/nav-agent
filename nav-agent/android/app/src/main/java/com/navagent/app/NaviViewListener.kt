package com.navagent.app

import com.amap.api.navi.AMapNaviViewListener

/**
 * 高德导航视图的点击回调。
 *
 * ★ 为什么必须实现它：
 *   底部 footer 那个「退出」按钮是**高德自己画的**（不是我们的控件），
 *   点它会弹高德内置的对话框：「退出导航 / 取消」。
 *   用户选「退出导航」时，SDK 回调 `onNaviCancel()`。
 *
 *   不接这个回调，那个对话框就是**假的** ——
 *   点了「退出导航」什么都不会发生，路线还在，播报还在，
 *   用户会以为 app 卡死（用户反馈：「点击退出导航无作用」）。
 *
 * 其它回调都走默认行为：
 *   onNaviBackClick 返回 false = 交给 SDK 默认处理（返回键正常退出导航流程）
 *   onNaviSetting / onNaviCancel 我们自己接管
 */
class NaviViewListener(
    /** 用户确认退出导航 */
    private val onCancelNavi: () -> Unit
) : AMapNaviViewListener {

    override fun onNaviCancel() {
        Dbg.log("高德退出对话框：用户选择「退出导航」")
        onCancelNavi()
    }

    /**
     * 返回键。
     *
     * ★ 返回 false：让 SDK 走它自己的流程（弹退出确认框），
     *   而不是直接 finish —— 导航中误按返回键不该直接退出应用。
     */
    override fun onNaviBackClick(): Boolean = false

    /** 点设置按钮。我们有自己的一套设置菜单，不走高德这个 */
    override fun onNaviSetting() {
        Dbg.log("高德设置按钮：忽略（走我们自己的设置菜单）")
    }

    override fun onNaviMapMode(p0: Int) {}
    override fun onNaviTurnClick() {}
    override fun onNextRoadClick() {}
    override fun onScanViewButtonClick() {}
    override fun onLockMap(p0: Boolean) {}
    override fun onNaviViewLoaded() {}
    override fun onMapTypeChanged(p0: Int) {}
    override fun onNaviViewShowMode(p0: Int) {}

    // ★ 下面这几个在 javap 的输出里没列全，但编译要求实现 ——
    //   接口实际方法比 javap 显示的多（javap 只列了 11 个，编译器要 17 个）。
    //   全部空实现：这些我们都用不到。
    override fun onStopSpeaking() {}
    override fun onViewTypeChanged(p0: com.amap.api.navi.AmapPageType?) {}
    override fun onAMapNaviViewExit() {}
    override fun onStrategyChanged(p0: Int) {}
    override fun onBroadcastModeChanged(p0: Int) {}
    override fun onDayAndNightModeChanged(p0: Int) {}
    override fun onScaleAutoChanged(p0: Boolean) {}
    override fun onListenToVoiceDuringCallChanged(p0: Boolean) {}
    override fun onControlMusicVolumeModeChanged(p0: Int) {}
    override fun onEagleChanged(p0: Boolean) {}
    override fun onNaviRouteHighlightChange(p0: Long, p1: Int) {}
}
