package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONObject

/**
 * 导航控制工具。
 *
 * 为什么整条链路都交给模型（2026-11-01 用户拍板）：
 *   原来是 `IntentParser` 的正则匹配，实测漏得厉害 —— 「打开导航去X」「拐到前面那家」
 *   「回刚才那个地方」「去一下西站」全都匹配不上，用户以为导航坏了。
 *   正则能覆盖的是"说法"，覆盖不了"意图"。宁可多花 1.5 秒，也别再答"我没听懂"。
 *
 * 代价（已知并接受）：
 *   - 延迟：规则 7ms → 模型 1.5~3s
 *   - 离线不可用
 *
 * 三个 action 合成一个工具而不是拆三个，是为了省 tools 定义占的上下文。
 * 模型对 `enum` 的理解很好，不会选错。
 */
class NavigationTool(
    /** 导航/改道。返回给模型看的结果描述 */
    private val navigateTo: (String) -> String,
    private val stop: () -> String,
    private val openMap: () -> String,
    /** 模型没给目的地时，用上一次周边搜索的结果兜底 */
    private val lastNearbyName: () -> String?
) : LlmTool {

    override val name = "navigate"

    override val description =
        "导航控制。用户要去某个地方、改去别处、停止导航、只打开地图时调用。\n" +
            "用户说「去X」「导航到X」「带我去X」「改去X」「回刚才那个地方」「就去这家」都算 navigate。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["navigate","stop","open_map"],"description":"navigate=导航去某地；stop=停止/结束导航；open_map=只打开地图不算路"},
          "destination":{"type":"string","description":"目的地地名。action=navigate 时必填。照用户原话写，**保留省市县这类行政区划**（如「景泰县条山路金星家园」），不要自己简化"}
        },"required":["action"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val obj = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "参数解析失败，重试一次"

        return when (obj.optString("action").trim()) {
            "navigate" -> {
                val asked = obj.optString("destination").trim()
                // 模型有时候只给 action 不给地名（用户说「就去这家」），用最近一次周边结果兜
                val dest = asked.ifBlank { lastNearbyName()?.trim().orEmpty() }
                if (dest.isBlank()) {
                    "用户没说要去的具体地名，先问一句「去哪儿」"
                } else {
                    navigateTo(dest)
                }
            }

            "stop" -> stop()
            "open_map" -> openMap()
            else -> "不认识的 action：${obj.optString("action")}"
        }
    }
}
