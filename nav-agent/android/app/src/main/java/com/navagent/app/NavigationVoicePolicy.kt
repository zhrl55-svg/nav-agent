package com.navagent.app

import com.amap.api.navi.enums.NaviTTSType

/**
 * 把高德下发的导航文本整理成四档播报策略。
 *
 * 这里只决定“说不说”，不修改文本。高德仍然负责生成准确的距离、路名和转向，
 * 所以简洁模式也不能改写句子，否则很容易把“前方靠左”变成“左转”。
 */
internal object NavigationVoicePolicy {

    data class Decision(val speak: Boolean, val reason: String)

    private data class LastUtterance(val normalizedText: String, val atMs: Long)

    private var lastUtterance: LastUtterance? = null

    /** 新一次导航从干净状态开始，不能把上一条路线的重复判断带过来。 */
    @Synchronized
    fun reset() {
        lastUtterance = null
    }

    /**
     * @param type 高德 NaviTTSType
     * @param text 高德原始播报文本
     * @param mode [Prefs.navVoiceMode]
     * @param nowMs 单调时钟，便于日志和稳定去重
     */
    @Synchronized
    fun decide(type: Int, text: String, mode: String, nowMs: Long): Decision {
        if (text.isBlank()) return Decision(false, "空文本")
        if (mode == Prefs.NAV_VOICE_OFF) return Decision(false, "导航播报已关闭")
        if (mode == Prefs.NAV_VOICE_DETAILED) return Decision(true, "详细模式")

        val duplicateWindowMs = if (mode == Prefs.NAV_VOICE_CONCISE) {
            CONCISE_DUPLICATE_WINDOW_MS
        } else {
            STANDARD_DUPLICATE_WINDOW_MS
        }
        val normalized = normalize(text)
        val previous = lastUtterance
        if (
            previous != null &&
            previous.normalizedText == normalized &&
            nowMs - previous.atMs in 0..duplicateWindowMs
        ) {
            return Decision(false, "短时间重复")
        }

        if (mode == Prefs.NAV_VOICE_CONCISE) {
            val critical = type in ALWAYS_SPEAK_TYPES || CRITICAL_MARKERS.any(text::contains)
            if (!critical) return Decision(false, "简洁模式仅保留关键提示")
        } else {
            val lowValue = LOW_VALUE_MARKERS.any(text::contains)
            if (lowValue) return Decision(false, "标准模式过滤低价值提示")
        }

        lastUtterance = LastUtterance(normalized, nowMs)
        return Decision(true, if (mode == Prefs.NAV_VOICE_CONCISE) "简洁模式关键提示" else "标准模式")
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private val ALWAYS_SPEAK_TYPES = setOf(
        NaviTTSType.NAVI_START_TEXT,
        NaviTTSType.NAVI_END_TEXT,
        NaviTTSType.NAVI_YAW
    )

    /**
     * 只用明确影响行驶动作、安全或路线的事件做简洁模式白名单。
     * 服务区、收费站和隧道仍保留，因为长途驾驶时用户需要提前做决定。
     */
    private val CRITICAL_MARKERS = listOf(
        "左转", "右转", "掉头", "直行",
        "靠左", "靠右", "向左", "向右",
        "并线", "变道", "汇入", "驶出",
        "出口", "匝道", "环岛", "进入主路",
        "测速", "限速", "违法", "拍照", "闯红灯",
        "红绿灯", "拥堵", "事故", "施工", "封路",
        "道路封闭", "重新规划", "服务区", "收费站", "隧道",
        "到达目的地", "到达终点"
    )

    /** 这些句子不影响驾驶决策，标准模式没有必要每次都念。 */
    private val LOW_VALUE_MARKERS = listOf(
        "欢迎使用高德",
        "高德地图提醒您",
        "请遵守交通规则",
        "请勿疲劳驾驶",
        "系好安全带",
        "保持车距",
        "谨慎驾驶",
        "安全驾驶",
        "路况良好"
    )

    private const val STANDARD_DUPLICATE_WINDOW_MS = 20_000L
    private const val CONCISE_DUPLICATE_WINDOW_MS = 30_000L
}
