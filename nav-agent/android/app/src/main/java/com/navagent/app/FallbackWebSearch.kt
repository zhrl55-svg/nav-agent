package com.navagent.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 搜索降级抓取器 —— Tavily 挂了之后的中文搜索兜底。
 *
 * ## ★ 为什么需要它（2026-11-04 实测）
 *
 * Tavily 的失败模式不是「慢」，是「安静地答错」：
 *   搜「张掖 供暖」→ 返回 Trip.com 旅行地、日照金山、辽宁高铁 —— 全不相关，
 *   而且 HTTP 200、超时正常。模型拿到这些照样能编出一段像模像样的回答。
 *   这是最坏的失败：**看起来成功了，其实没搜到**。
 *
 * 另外三种 Tavily 自身的失败也各有一票否决：
 *   · 401/403（Key 失效）—— 整轮全废
 *   · 429（额度用完，Researcher 计划 1000 次/月）—— 整轮全废
 *   · 超时 / 502 —— 整轮全废
 *
 * 而国内搜索在这台手机上**实测全部直连可达**（华为 P40 Pro，4G 移动网络，无代理）：
 *   baidu.com 41ms / sogou.com 32ms / china.bing.com 35ms
 * 所以 Tavily 任何一种失败，都应该立刻退到国内引擎，而不是把「搜不到」当答案。
 *
 * ## 为什么是搜狗 + 百度 + Bing 这三个
 *
 * 实测各引擎对中文本地内容的命中质量：
 *   · 搜狗 —— 「张掖 供暖」命中「甘肃电投张掖发电公司提前热身备战供热季」，最好
 *   · 百度 —— 「张掖 今日新闻」第一条就是「中国张掖网」，本地站命中好
 *   · Bing —— 必须带 mkt=zh-CN，否则会串到韩文/西语结果（实测踩过）
 *   · DuckDuckGo —— **设备上被 DNS 污染**（html.duckduckgo.com 解析到
 *     Facebook 的 31.13.96.193，而不是真 IP 157.240.11.40）→ 不用
 *
 * ## 降级顺序：搜狗 → 百度 → Bing
 *
 * ## 这不是「绕过 Tavily」，是「补上它没有的那部分」
 *
 * Tavily 强在英文和结构化（时间戳、正文抽取），弱在中文本地；
 * 降级链强在中文本地，弱在英文。两者互补，不是替代关系。
 */
object FallbackWebSearch {

    /** 一条降级搜索结果。 */
    data class Item(
        val title: String,
        val url: String,
        val snippet: String
    )

    /** 一次降级搜索的完整结果，连同命中它的引擎名。 */
    data class Result(
        val engine: String,
        val items: List<Item>
    )

    /**
     * 依次尝试各引擎，返回第一个拿到结果的。
     *
     * @param query 查询词
     * @param limit 最多取几条
     * @return null 表示所有引擎都失败
     */
    fun search(query: String, limit: Int = 6): Result? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val keywords = queryKeywords(q)
        val collected = LinkedHashMap<String, Item>()
        var selectedEngine = ""
        for (engine in ENGINES) {
            val items = runCatching { engine.fetch(q, limit) }
                .getOrElse {
                    Dbg.log("降级搜索 ${engine.name} 异常：${it.message}")
                    emptyList()
                }
            val relevant = items.filter { isUseful(it, keywords) }
            Dbg.log("降级搜索 ${engine.name} → ${items.size} 条，可用 ${relevant.size}")
            if (relevant.isNotEmpty()) {
                if (selectedEngine.isEmpty()) selectedEngine = engine.name
                relevant.forEach { collected.putIfAbsent(it.title.lowercase(), it) }
            }
            // ★ 2026-11-09：够数还不够，得**有摘要**才能提前收工。
            //   实测搜狗只回标题（h3 里没有摘要块），拿到 3 条标题就走，
            //   端侧念出来只剩「查到一条：张掖限行 时间规定2026」——
            //   用户问「限行吗」，听到的是一个标题，等于没答。
            //   多跑一个百度（摘要来自 c-abstract）能让回答有内容；
            //   云端那边也不吃亏：有摘要模型才不用再花一次 web_extract。
            //
            // ★ 2026-11-09 二次调整：**拿到权威源就立刻收工**，没有才继续问下一家。
            //   实测「兰州限行」：搜狗给的是本地宝/头条（对题），必应给的是
            //   「兰州旅游十大景点」（完全跑题）—— 以前谁先够数用谁，
            //   等于让相关性最差的引擎决定答案。
            val trustedCount = collected.values.count { trustRank(it.url) >= TRUST_OFFICIAL }
            if (trustedCount >= minOf(2, limit)) break
            val hasSnippet = collected.values.any { it.snippet.isNotBlank() }
            if (collected.size >= minOf(3, limit) && hasSnippet) break
        }
        if (collected.isEmpty()) return null
        // 权威源排前面，其余保持引擎返回顺序（引擎自己已经按相关度排过）
        val ordered = collected.values.sortedByDescending { trustRank(it.url) }.take(limit)
        return Result(selectedEngine, ordered)
    }

    private const val TRUST_OFFICIAL = 2
    private const val TRUST_LOCAL = 1

    /**
     * 来源可信度：2=官方/权威媒体，1=本地信息站，0=普通网页。
     *
     * ★ 为什么必须排序：本地政策类问题（限行、社保、落户）搜索结果里
     *   混着大量内容农场 —— 它们给每个城市套一份「XX年限行规定」模板，
     *   内容还互相抄。端侧是「直出」模式，排第一的就会被原样念出来。
     *   实测张掖根本没有限行规定，搜出来的却是「张掖限行时间规定2026」。
     */
    private fun trustRank(url: String): Int {
        if (url.isBlank()) return 0
        val host = runCatching { java.net.URL(url).host.lowercase().removePrefix("www.") }.getOrNull()
            ?: return 0
        return when {
            OFFICIAL_HOSTS.any { host == it || host.endsWith(".$it") } -> TRUST_OFFICIAL
            LOCAL_INFO_HOSTS.any { host == it || host.endsWith(".$it") } -> TRUST_LOCAL
            else -> 0
        }
    }

    /** 政府站 + 央媒。`gov.cn` 覆盖全部地方政府站 */
    private val OFFICIAL_HOSTS = listOf(
        "gov.cn", "people.com.cn", "news.cn", "xinhuanet.com",
        "cctv.com", "chinanews.com.cn", "gmw.cn", "ce.cn", "china.com.cn",
        "thepaper.cn", "chinadaily.com.cn"
    )

    /** 本地生活信息站：非官方，但限行/办事这类本地信息通常比农场页准且每天更新 */
    private val LOCAL_INFO_HOSTS = listOf("bendibao.com", "jiuwu.com", "dianhua.cn")

    /** 去掉新闻/时间等泛词后，要求标题或摘要至少命中一个查询关键词。 */
    private fun queryKeywords(query: String): List<String> {
        val normalized = query.lowercase().replace(Regex("\\s+"), "")
        val core = normalized.replace(
            Regex("今天|今日|现在|目前|最新|最近|新闻|要闻|消息|查一下|查下|查询|搜索|搜一下|搜下|帮我|网上|关于|有关|价格|多少|呢|的"),
            ""
        )
        val source = core.ifBlank { normalized }
        return if (source.any { it.code in 0x4E00..0x9FFF }) {
            if (source.length < 2) listOf(source) else source.windowed(2).distinct()
        } else {
            Regex("[a-z0-9]{3,}").findAll(source).map { it.value }.toList()
        }
    }

    private fun isUseful(item: Item, keywords: List<String>): Boolean {
        val title = item.title.trim()
        if (title.length < 5 || title.matches(GENERIC_RESULT_TITLE)) return false
        if (title.contains(Regex("重磅.{0,8}(消息|宣布)|身份已变|舆论迅速轰动|全网炸锅|惊呆了|不看后悔|惊天大反转|紧急释放.{0,8}消息"))) return false
        if (title.contains(Regex("大家还在搜|相关搜索|搜索结果|网页导航|频道首页|栏目首页|新闻滚动新闻|滚动新闻首页|网站第一品牌|新闻网站|新闻门户|门户首页|新闻首页|频道主页|公共数据开放平台|数据开放平台|数据平台首页"))) return false
        if (title.contains(Regex("的意思|是什么意思|的解释|怎么造句|造句|词语释义"))) return false
        if (title.contains(Regex("简介.{0,8}联系方式|联系方式.{0,8}最新分享|个人主页|个人资料"))) return false
        if (title.contains(Regex("百度百科|维基百科|百科词条|百科全书"))) return false
        val text = (title + " " + item.snippet).lowercase().replace(Regex("\\s+"), "")
        // 短查询只会产生一个关键词；较长查询至少命中两个相邻字片段，
        // 避免「阿里巴巴」仅因“关于”之类的泛词命中百科或栏目页。
        val requiredHits = minOf(2, keywords.size)
        val hits = keywords.count { it.length >= 2 && it in text }
        return requiredHits > 0 && hits >= requiredHits
    }

    // ------------------------------------------------------------------ 引擎

    private interface Engine {
        val name: String
        fun fetch(query: String, limit: Int): List<Item>
    }

    /**
     * 搜狗。**排第一**，实测结果质量最高
     * （搜「张掖 供暖」能命中「甘肃电投张掖发电公司提前热身备战供热季」）。
     *
     * 页面结构有两个坑，都踩过：
     *
     * ① h3 里装的是**跳转链**，不是真实 URL：
     *     href="./id=xxx/...&url=http%3A%2F%2F...&pcurl=http%3A%2F%2Fwww.zhangye.gov.cn%2F..."
     *     其中 `pcurl` 参数里才是真实地址（URL 编码过一次）。
     *     真正要抓的是 pcurl，不是 href —— href 是搜狗自己的相对路径，
     *     直接给模型它根本没法用。
     *
     * ② h3 之间混着「大家还在搜」「entity-title」这类**推荐词块**，
     *     它们不是搜索结果（张掖市概况 / 张掖市旅游景点 / 大家还在搜）。
     *     判据：块里没有 resultLink 链接 → 不是结果，丢掉。
     */
    private object Sogou : Engine {
        override val name = "搜狗"

        override fun fetch(query: String, limit: Int): List<Item> {
            val html = get("https://www.sogou.com/web?query=${enc(query)}")
            val items = ArrayList<Item>()
            for (m in Regex("(?s)<h3[^>]*>(.*?)</h3>").findAll(html)) {
                if (items.size >= limit) break
                val block = m.groupValues[1]
                // 推荐词块（entity-title / 大家还在搜）没有 resultLink，不是搜索结果
                val href = Regex("class=\"[^\"]*resultLink[^\"]*\"[^>]*href=\"([^\"]+)\"")
                    .find(block)?.groupValues?.get(1) ?: continue
                val title = clean(Regex("(?s)<a[^>]*>(.*?)</a>").find(block)?.groupValues?.get(1) ?: block)
                if (title.isBlank() || title.length < 4) continue
                // 真实 URL 在 pcurl 参数里（URL 编码过一次）
                val realUrl = Regex("[?&]pcurl=([^&\"]+)").find(href)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
                    ?: ""
                items += Item(title = title, url = realUrl, snippet = "")
            }
            return items
        }
    }

    /**
     * 百度。命中本地内容好，但链接是 `/link?url=` 跳转链。
     *
     * 处理办法：把跳转链**丢掉**，只留标题和摘要。
     * 反正降级结果只是给模型找线索用的，模型要读全文还有 web_extract；
     * 给一个跳转链反而会让模型去 fetch 一个它看不懂的长串。
     */
    private object Baidu : Engine {
        override val name = "百度"

        override fun fetch(query: String, limit: Int): List<Item> {
            val html = get("https://www.baidu.com/s?wd=${enc(query)}")
            val items = ArrayList<Item>()
            val blocks = Regex(
                "(?s)<div[^>]*class=\"[^\"]*result[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*result|$)"
            ).findAll(html)
            for (m in blocks) {
                if (items.size >= limit) break
                val block = m.groupValues[1]
                val title = clean(Regex("(?s)<h3[^>]*>(.*?)</h3>").find(block)?.groupValues?.get(1) ?: "")
                if (title.isBlank() || title.length < 4) continue
                val snippet = clean(
                    Regex("(?s)class=\"[^\"]*(?:c-abstract|content-right_)[^\"]*\"[^>]*>(.*?)</span>")
                        .find(block)?.groupValues?.get(1)
                        ?: Regex("(?s)<span[^>]*>(.*?)</span>").find(block)?.groupValues?.get(1)
                        ?: ""
                )
                items += Item(title = title, url = "", snippet = snippet)
            }
            // 上面按 result 容器切分不总是命中（百度改版频繁），
            // 退一步用 h3 兜底，至少把标题给模型。
            if (items.isEmpty()) {
                for (m in Regex("(?s)<h3[^>]*>(.*?)</h3>").findAll(html)) {
                    if (items.size >= limit) break
                    val title = clean(m.groupValues[1])
                    if (title.isBlank() || title.length < 4) continue
                    items += Item(title = title, url = "", snippet = "")
                }
            }
            return items
        }
    }

    /**
     * Bing。**必须带 `mkt=zh-CN`**，否则会串到韩文/西语结果。
     *
     * 实测：不带参数搜「张掖 今日新闻」返回的是「한컴 타자」（韩文打字练习网站），
     * 加了 mkt=zh-CN 才正常。`ensearch=0` 强制中文索引。
     */
    private object Bing : Engine {
        override val name = "Bing"

        override fun fetch(query: String, limit: Int): List<Item> {
            val html = get(
                "https://www.bing.com/search?q=${enc(query)}&mkt=zh-CN&setlang=zh-Hans&ensearch=0"
            )
            val items = ArrayList<Item>()
            for (m in Regex("(?s)<li class=\"b_algo\".*?</li>").findAll(html)) {
                if (items.size >= limit) break
                val block = m.groupValues[0]
                val title = clean(Regex("(?s)<h2[^>]*>(.*?)</h2>").find(block)?.groupValues?.get(1) ?: "")
                if (title.isBlank() || title.length < 4) continue
                val href = Regex("<h2[^>]*>\\s*<a[^>]*href=\"(https?://[^\"]+)\"")
                    .find(block)?.groupValues?.get(1) ?: ""
                val snippet = clean(Regex("(?s)<p[^>]*>(.*?)</p>").find(block)?.groupValues?.get(1) ?: "")
                items += Item(title = title, url = href, snippet = snippet)
            }
            return items
        }
    }

    /**
     * 降级顺序。
     *
     * 搜狗第一（结果最准）→ 百度（本地命中好）→ Bing（兜底）。
     * DuckDuckGo 不在列：设备上 DNS 被污染，实测不可用。
     */
    private val ENGINES = listOf(Sogou, Baidu, Bing)
    private val GENERIC_RESULT_TITLE = Regex(
        "^(国际新闻|国内新闻|新闻|要闻|国际|国内|财经|新闻频道|国际频道|国内频道)(\\s*(?:[-|｜].*|的意思.*|是什么意思.*|的解释.*|造句.*))?$"
    )

    // ------------------------------------------------------------------ HTTP

    /**
     * 抓 HTML。
     *
     * UA 必须伪装成手机浏览器：实测不伪装时百度/搜狗会返回简化版页面，
     * 搜不到 h3 结果块（实测 h3 count=0）。
     */
    private fun get(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 10_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; HUAWEI ELS-AN00) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Dbg.log("降级抓取 HTTP $code $url")
                return ""
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Dbg.log("降级抓取异常 ${t.javaClass.simpleName}: ${t.message}")
            ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 去标签 + 压空白 + 解常见实体 */
    private fun clean(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 把降级结果渲染成给模型看的文本。
     *
     * 和 Tavily 的渲染有两个区别：
     *   1. 标了来源引擎名 —— 模型知道这是兜底搜到的，措辞上不会当成权威来源报。
     *   2. 明确说「没有发布时间」—— 降级结果拿不到时间戳，
     *      模型如果照着新闻格式说「今天」就是编的。
     */
    fun render(result: Result, query: String): String {
        if (result.items.isEmpty()) return ""
        // ★ 2026-11-09：政策类问题如果一条权威源都没有，在**第一条正文里**加一句限定。
        //   放在这里而不是标题行的原因：端侧是直出模式，标题行不会被念，
        //   只有 `- ` 行和它下面缩进的那行会被念出来。
        //   实测张掖本地没有限行规定，搜到的却是满屏「XX年限行时间规定」的农场页。
        val weakSource = POLICY_LIKE.containsMatchIn(query) &&
            trustRank(result.items.first().url) == 0
        return buildString {
            append("备用搜索结果（来源：").append(result.engine).append("）\n")
            append("以下是「").append(query).append("」的备用搜索结果。")
            append("这些结果没有精确的发布时间，只有网页标题和摘要：\n")
            result.items.forEachIndexed { index, item ->
                append("- ").append(item.title)
                if (item.url.isNotBlank()) append("（").append(item.url.take(120)).append("）")
                append("\n")
                val body = buildString {
                    if (weakSource && index == 0) append("网上非官方说法，仅供参考：")
                    if (item.snippet.isNotBlank()) append(item.snippet.take(300))
                }
                if (body.isNotBlank()) append("  ").append(body).append("\n")
            }
            // 备用来源的可信度和时效性要求属于 system prompt；不要把指令附在
            // 搜索正文后面，否则小模型可能把它当成新闻内容复述给用户。
        }.trim()
    }

    /** 这类问题一旦引用错源，用户会按错误规则行动，必须加限定 */
    private val POLICY_LIKE = Regex("限行|政策|规定|社保|公积金|补贴|摇号|落户|年检|驾照|违章|收费|标准")
}
