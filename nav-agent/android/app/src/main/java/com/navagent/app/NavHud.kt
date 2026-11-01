package com.navagent.app

import android.graphics.Bitmap

/**
 * 导航抬头显示的数据快照。
 *
 * 之所以自己画 HUD 而不用 AMapNaviView 自带的界面：
 * 自带界面在未导航时也会渲染「退出 / 继续导航 / 设置」这一条底部栏，
 * 和我们自己的语音按钮完全重叠，且无法单独关闭某一项（只有 setLayoutVisible 全关）。
 * 自己画的话布局完全可控，而转向图标可以从 NaviInfo.getIconBitmap() 直接拿，
 * 不损失任何导航信息。
 */
data class NavHud(
    val navigating: Boolean,
    /** 高德的转向图标类型，见 NaviInfo.getIconType() */
    val iconType: Int = 0,
    /** 转向箭头位图，SDK 直接给，不用自己找图标 */
    val iconBitmap: Bitmap? = null,
    /** 距下一个动作点距离（米） */
    val stepMeters: Int = 0,
    /** 下一条路名 */
    val nextRoad: String = "",
    /** 全程剩余距离（米） */
    val remainMeters: Int = 0,
    /** 全程剩余时间（秒） */
    val remainSeconds: Int = 0
)
