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
        for (engine in ENGINES) {
            val items = runCatching { engine.fetch(q, limit) }
                .getOrElse {
                    Dbg.log("降级搜索 ${engine.name} 异常：${it.message}")
                    emptyList()
                }
            Dbg.log("降级搜索 ${engine.name} → ${items.size} 条")
            if (items.isNotEmpty()) return Result(engine.name, items)
        }
        return null
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
        return buildString {
            append("备用搜索结果（来源：").append(result.engine).append("）\n")
            append("以下是「").append(query).append("」的备用搜索结果。")
            append("这些结果没有精确的发布时间，只有网页标题和摘要：\n")
            result.items.forEach {
                append("- ").append(it.title)
                if (it.url.isNotBlank()) append("（").append(it.url.take(120)).append("）")
                append("\n")
                if (it.snippet.isNotBlank()) {
                    append("  ").append(it.snippet.take(300)).append("\n")
                }
            }
            append("\n注意：这是备用搜索的结果，可能不够权威也可能不够新。")
            append("请如实说明信息来源有限，不要把它当成已核实的实时信息。")
        }.trim()
    }
}