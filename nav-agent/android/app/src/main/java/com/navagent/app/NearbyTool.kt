package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONObject
import java.util.Locale

/**
 * 周边搜索工具（Function Calling）。
 *
 * 为什么全部交给模型：
 *   `IntentParser` 里原来有一条 `附近|周边` 的正则，但用户实际说法是
 *   「周围有什么好吃的」「搜索一下西站附近的咖啡厅」「旁边有加油站吗」
 *   「在目的地附近找一下停车场」—— 正则穷举不完，漏掉的全部掉给模型闲聊，
 *   用户以为"搜索功能根本没做"。
 *
 * 为什么可以放心交给模型：
 *   漏判的代价只是变成闲聊（不像「导航去X」漏了会走错路），
 *   而且这个工具本身有定位兜底和超时保护，不会把会话卡死。
 */
class NearbyTool(
    private val location: () -> Pair<Double, Double>?,
    private val radius: () -> Int,
    /** 记住最近一次结果，用户接着说「去」时能直接导航过去 */
    private val onResult: (AmapWebGeocoder.Poi) -> Unit = {}
) : LlmTool {

    override val name = "search_nearby"

    override val description =
        "搜索用户**当前位置**附近的店铺和地点。用户说附近、周边、周围、旁边、跟前有什么、" +
            "找一下某个地方、搜一下某个店时调用。只能搜地理实体（加油站/餐厅/停车场/银行/药店），" +
            "搜不了新闻或知识。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{"keyword":{"type":"string","description":"要找什么，用简短名词，如「加油站」「餐厅」「停车场」「药店」「咖啡厅」"}},"required":["keyword"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val keyword = runCatching { JSONObject(argumentsJson).optString("keyword") }
            .getOrDefault("")
            .trim()

        if (keyword.isBlank()) {
            return "需要知道用户想找什么（比如加油站、餐厅、停车场）。可以先反问一句。"
        }

        val loc = location()
        if (loc == null) {
            return "还没定位到用户位置。请让用户先点顶部「导航」打开地图，定位拿到后再查。"
        }

        val r = radius()
        val list = AmapWebGeocoder.searchAroundList(loc.first, loc.second, keyword, r, 3)
        Dbg.log("search_nearby($keyword) radius=$r → ${list.size} 条")

        if (list.isEmpty()) {
            val km = if (r >= 1000) "${r / 1000}公里" else "${r}米"
            return "附近${km}内没找到${keyword}"
        }

        // 记下来，用户接着只说「去」也能走
        onResult(list.first())

        return buildString {
            append("附近的").append(keyword).append("：\n")
            list.forEach {
                append("- ").append(it.name)
                if (it.distance >= 0) {
                    append("，")
                    append(
                        if (it.distance >= 1000) String.format(Locale.CHINA, "%.1f公里", it.distance / 1000.0)
                        else "${it.distance}米"
                    )
                }
                append("\n")
            }
        }.trim()
    }
}
