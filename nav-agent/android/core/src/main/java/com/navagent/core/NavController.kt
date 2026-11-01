package com.navagent.core

/**
 * 导航引擎抽象。app 模块用高德 Navi SDK 实现，core 不认识任何高德类型。
 */
interface NavController {

    /** 开始或重设目的地 */
    fun navigateTo(destination: String)

    /** 停止导航 */
    fun stop()

    /** 当前是否在导航中 */
    val isNavigating: Boolean

    /** 距下一个动作点的剩余距离（米），未导航时返回 null */
    fun distanceToNextAction(): Int?

    /** 预计剩余时间（秒），未导航时返回 null */
    fun remainingSeconds(): Int?
}
