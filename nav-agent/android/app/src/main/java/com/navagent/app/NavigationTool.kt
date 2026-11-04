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
 *
 * ## ★ 候选确认机制（2026-11-04）
 *
 * 用户反馈：「用语音说地址导航的时候，没有候选项」。
 *
 * 原来 navigate 直接把地名丢给地理编码 → 解析歪了没人知道，
 * 可能导航到 400 公里外的同名 POI，用户全程不知情。
 *
 * 现在改成两段式：
 *   第 1 段：模型调 navigate(destination="景泰县条山路金星家园")
 *           → 工具先查**当前候选**里有没有匹配的
 *           → 没有就用高德 POI 搜索搜一批候选
 *           → **不导航**，把候选返回给模型，同时填进搜索框显示给用户
 *   第 2 段：模型问用户「是哪个」，用户回「2」
 *           → 模型再调 navigate(destination="候选名")
 *           → 这次命中候选，直接用精确坐标导航
 *
 * 所以用户看到的流程是：
 *   说「导航去XX」→ 搜索框自动填上 XX，候选列表弹出来 → 说「2」或点一条 → 开始导航
 */
class NavigationTool(
    /** 导航/改道（按地名）。返回给模型看的结果描述 */
    private val navigateTo: (String) -> String,
    /** 导航到**已确认的候选 POI**（用它的精确坐标，不重新解析地名） */
    private val navigateToPoi: (AmapSdkGeocoder.Poi) -> String = { navigateTo(it.name) },
    private val stop: () -> String,
    private val openMap: () -> String,
    /** 模型没给目的地时，用上一次周边搜索的结果兜底 */
    private val lastNearbyName: () -> String?,
    /** 用户要求助手别打扰时，主动休眠 */
    private val sleep: () -> String = { "当前不支持休眠" },
    /**
     * 搜地点候选（高德 POI 搜索，跟地图自带搜索栏同一个引擎）。
     * 返回空列表表示没搜到。
     */
    private val searchPlaces: (String) -> List<AmapSdkGeocoder.Poi> = { emptyList() },
    /** 把候选展示给用户（填搜索框 + 弹列表） */
    private val showCandidates: (String, List<AmapSdkGeocoder.Poi>) -> Unit = { _, _ -> },
    /** 读当前待确认候选 */
    private val currentCandidates: () -> List<AmapSdkGeocoder.Poi> = { emptyList() },
    /** 清空待确认候选（已确认 / 换了新需求）。参数是选中/清空的那个 POI（可空） */
    private val clearCandidates: (AmapSdkGeocoder.Poi?) -> Unit = { },
    /**
     * 最近一次**用户确认过**的候选（带精确坐标）。
     *
     * ★ 为什么需要（2026-11-04）：
     *   用户确认候选 A 导航完成后，候选列表就清掉了。
     *   之后模型如果再调 navigate("A 的名字", confirmed=true)，
     *   候选已经找不到 → 退化成走地名解析 →
     *   高德 POI 搜索的 city 参数不生效，「水勘院超市」命中了**石家庄**的
     *   「槐中路135号水勘院(顺丰便利店)」，一路1517 公里（用户反馈）。
     *
     *   记住这个坐标，同名地点再怎么解析都不会跑偏。
     */
    private val lastConfirmedPoi: () -> AmapSdkGeocoder.Poi? = { null }
) : LlmTool {

    override val name = "navigate"

    override val description =
        "导航控制。用户要去某个地方、改去别处、停止导航、只打开地图时调用。\n" +
            "用户说「去X」「导航到X」「带我去X」「改去X」「回刚才那个地方」「就去这家」都算 navigate。\n" +
            "用户说「你休息吧」「你先休息吧」「不要打扰我」「你先不要打扰我」「安静点」这类要你闭嘴的，调用 action=sleep。\n" +
            "★ 用户只说了一个地名时，本工具会先搜索出候选并返回给你，**这时不会导航**；" +
            "你要用 1、2、3 把候选列出来问用户「是哪个」。" +
            "用户回答之后，再调一次 navigate，destination 用候选的完整名称，**并且 confirmed=true**。" +
            "只有**一个**候选时可以直接调 navigate 并传 confirmed=true。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["navigate","stop","open_map","sleep"],"description":"navigate=导航去某地；stop=停止/结束导航；open_map=只打开地图不算路；sleep=用户要求你别打扰，进入休眠"},
          "destination":{"type":"string","description":"目的地地名。action=navigate 时必填。照用户原话写，**保留省市县这类行政区划**（如「景泰县条山路金星家园」），不要自己简化"},
          "confirmed":{"type":"boolean","description":"**只有用户已经明确选了某个候选之后**才传 true。第一次提到某个地名时不要传（默认 false），工具会返回候选让你问用户。传 true 表示用户已经确认，直接导航"}
        },"required":["action"]}
    """.trimIndent()

    private fun isConcreteDestination(destination: String): Boolean {
        val value = destination.trim().lowercase()
        if (value.length < 2) return false
        if (value in setOf(
                "过去", "那里", "那儿", "这儿", "这里", "这家", "那家",
                "这个位置", "那个位置", "这个地方", "那个地方",
                "目的地", "测试", "测试地点", "测试目的地", "某个地方", "一个地方"
            )
        ) return false
        return !Regex("^(?:测试|某地|某个|一个)?(?:地方|位置|目的地)$").matches(value)
    }

    /**
     * 把 destination 匹配到当前候选。
     *
     * 匹配规则（从宽到严）：
     *   ① 序号 —— 模型有时就回「2」「第二个」（用户在语音里说的）
     *   ② 完全等于候选名
     *   ③ 包含关系（「张掖西站」vs「张掖西站(高铁站)」）
     *
     * ★ 为什么序号要支持：
     *   模型把候选列给用户后，用户回「2」，
     *   模型很可能就把 destination 填成「第二个」而不是完整名字。
     *   不支持序号的话这一步就断了。
     */
    private fun matchCandidate(destination: String): AmapSdkGeocoder.Poi? {
        val candidates = currentCandidates()
        if (candidates.isEmpty()) return null
        val d = destination.trim()
        if (d.isEmpty()) return null

        // ① 序号
        val idx = when {
            d.matches(Regex("^[1-9]$")) -> d.toInt() - 1
            d.matches(Regex("^第?[1-9]个?$")) ->
                (d.replace("第", "").replace("个", "").toIntOrNull() ?: 0) - 1
            d.matches(Regex("^第[一二三四五六七八九]个?$")) -> {
                val cn = "一二三四五六七八九"
                val ch = d.replace("第", "").replace("个", "")
                val i = cn.indexOf(ch)
                if (i >= 0) i else -1
            }
            else -> -1
        }
        if (idx in candidates.indices) return candidates[idx]

        // ② 完全相等
        candidates.firstOrNull { it.name == d }?.let { return it }
        // ③ 包含关系
        candidates.firstOrNull { it.name.contains(d) || d.contains(it.name) }?.let { return it }
        return null
    }

    override fun execute(argumentsJson: String): String {
        val obj = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "参数解析失败，重试一次"

        return when (obj.optString("action").trim()) {
            "navigate" -> {
                val asked = obj.optString("destination").trim()
                // 模型有时候只给 action 不给地名（用户说「就去这家」），用最近一次周边结果兜
                val dest = asked.ifBlank { lastNearbyName()?.trim().orEmpty() }
                if (dest.isBlank() || !isConcreteDestination(dest)) {
                    "目的地还不具体，先问用户要去哪个明确地点；不要猜测或启动导航。"
                } else {
                    // ★ ★ 用户确认了吗？
                    //   confirmed=true 是模型在说「用户已经选了某个候选」。
                    //   没确认的话，即使 destination 正好等于某个候选名，
                    //   也**不能**直接导航 —— 同名的地方往往有好几个
                    //   （张掖西站 / 张掖西站(进站口) / 张掖西站停车场），
                    //   选错了用户开半天才发现（用户反馈：模型没问就导航了）。
                    val confirmed = obj.optBoolean("confirmed", false)
                    val hit = matchCandidate(dest)

                    if (confirmed && hit != null) {
                        clearCandidates(hit)
                        return navigateToPoi(hit)
                    }
                    // ★ 候选已清掉，但**名字跟刚确认过的那个一样** → 用记住的坐标。
                    //   绝不能退化成地名解析 —— 那条路会命中同名的外地地点
                    //   （「水勘院超市」→ 石家庄的「槐中路135号水勘院」，1517 公里）。
                    if (confirmed) {
                        val recent = lastConfirmedPoi()
                        if (recent != null && (recent.name == dest || recent.name.contains(dest) || dest.contains(recent.name))) {
                            return navigateToPoi(recent)
                        }
                        // ★ 从来没确认过任何候选，却传了 confirmed=true ——
                        //   这是模型**自作主张**（用户压根没说地址，模型自己重放了一个
                        //   历史目的地）。必须走确认流程。
                        //   实测：用户说「哎呀，现在很完美呀」这句闲聊，
                        //   模型看到历史里的目的地就自己调了 navigate，
                        //   结果跑到石家庄同名地点，1517 公里（用户反馈）。
                        if (recent == null) {
                            return "用户还没确认过是哪个地点，不能直接导航。" +
                                "请调一次 navigate（**不带** confirmed）来搜索候选，" +
                                "用 1、2、3 列出来问用户「是哪个」；" +
                                "等用户选了之后，再调一次（destination 用完整名称、confirmed=true）。"
                        }
                        clearCandidates(null)
                        // ★ 明确告诉模型「候选没了」，别让它自作主张再导航一次。
                        return "刚才的候选已经失效了（用户没确认或已结束导航）。" +
                            "请先调一次 navigate（不带 confirmed）重新搜索候选，问用户是哪个；" +
                            "**不要**直接调 confirmed=true 导航。"
                    }

                    // ★ 没确认 → 先搜候选，不直接导航
                    val pois = searchPlaces(dest)
                    if (pois.isEmpty()) {
                        // 搜不到就明说，让模型问用户换个说法，别瞎猜一个地方导航过去
                        "没搜到「$dest」。请让用户换个说法或说更详细的地名（加上城市、区县、门牌号），不要猜测、不要直接导航。"
                    } else {
                        showCandidates(dest, pois)
                        buildString {
                            append("搜到 ${pois.size} 个「$dest」的候选：\n")
                            pois.forEachIndexed { i, poi ->
                                append("${i + 1}. ${poi.name}")
                                if (poi.address.isNotBlank()) append("（${poi.address}）")
                                if (poi.distance > 0) append(" 距你 ${poi.distance}米")
                                append('\n')
                            }
                            append("这些候选已经填进地图上的搜索框并显示列表了。")
                            append("请用 1、2、3 列给用户，问「是哪个」。")
                            append("用户回答后，再调一次 navigate，destination 用**候选项的完整名称**，")
                            append("并且 confirmed=true（表示用户已经确认过）。")
                            append("★ 现在**不要**自己调用 navigate 去导航 —— 用户还没确认。")
                        }
                    }
                }
            }

            "stop" -> stop()
            "open_map" -> openMap()
            "sleep" -> sleep()
            else -> "不认识的 action：${obj.optString("action")}"
        }
    }
}
