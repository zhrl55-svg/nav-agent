package com.navagent.app

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 新闻垂类源：中国新闻网「滚动新闻」列表页。
 *
 * ## 为什么要有它（2026-11-09 实测）
 *
 * 端侧泛新闻一直是全链路最弱的一环，三个数据源各有死穴：
 *   · Tavily 新闻：中文标题是 GBK 乱码，或者直接给「国际新闻_央视网」这种频道页；
 *   · 搜狗/百度网页搜索：搜「国际新闻」回来的全是频道首页，被相关性过滤后剩 0 条；
 *   · 新浪 RSS / 人民网 RSS：**都在停更**（实测新浪停在 2018-09，人民网停在 2025-06）；
 *   · Bing 新闻：页面是 JS 壳，静态 HTML 里没有任何条目。
 *
 * 而中新网这个滚动页一次 GET 就给 100 条：
 * ```
 * <li><div class="dd_lm">[国际]</div>
 *     <div class="dd_bt"><a href="/gj/2026/11-09/10728873.shtml">标题</a></div>
 *     <div class="dd_time">11-9 15:52</div></li>
 * ```
 * UTF-8、无反爬、每条带分类和精确到分钟的时间 —— 实测最新一条就是几分钟前的。
 *
 * ## 适用范围
 *
 * 只解决**泛新闻**（「今天有什么新闻」「国际新闻」「财经方面呢」）。
 * 有明确主题的（「张掖马拉松报名」）这里多半没有，[pick] 会返回 null，
 * 由调用方退回搜索引擎。
 */
object NewsFeed {

    /** 一条滚动新闻 */
    data class Item(
        val title: String,
        val url: String,
        val category: String,
        /** 「今天15:52」这种给人看的时间 */
        val timeText: String,
        /** 发布时间戳，解析失败为 0 */
        val at: Long
    )

    enum class Scope { WORLD, DOMESTIC, ALL }

    private const val ENDPOINT = "https://www.chinanews.com.cn/scroll-news/news1.html"
    private const val HOST = "https://www.chinanews.com.cn"

    /** 这些栏目对「新闻播报」没信息量，整条丢掉 */
    private val SKIP_CATEGORIES = setOf("视频", "图片", "同心", "东西问", "其他", "东盟")

    private val ITEM_RE = Regex(
        """<li>\s*<div class="dd_lm">.*?</div>\s*<div class="dd_bt">.*?</div>\s*<div class="dd_time">.*?</div>\s*</li>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val CAT_RE = Regex("""<div class="dd_lm">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val LINK_RE = Regex(
        """<div class="dd_bt">\s*<a\s+href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val TIME_RE = Regex("""<div class="dd_time">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

    /** 缓存：60 秒内复用。一轮对话里「新闻呢」「国际的呢」不必各拉一次 */
    @Volatile
    private var cached: List<Item> = emptyList()

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 取滚动列表（带 60 秒缓存）。
     * @return 失败返回空列表，调用方自己兜底
     */
    fun fetch(): List<Item> {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < 60_000L) return cached
        val html = get() ?: return cached
        val items = parse(html)
        if (items.isNotEmpty()) {
            cached = items
            cachedAt = now
            Dbg.log("新闻垂类：${items.size} 条，最新「${items.first().title.take(18)}」${items.first().timeText}")
            // 分类分布写一行：解析规则失效时（比如页面换版）一眼能看出来
            val dist = items.groupingBy { it.category }.eachCount()
                .entries.sortedByDescending { it.value }.take(6)
                .joinToString(" ") { "${it.key}=${it.value}" }
            Dbg.log("新闻垂类分类：$dist")
        } else {
            Dbg.log("新闻垂类：解析出 0 条（页面结构可能变了）")
        }
        return items
    }

    /**
     * 按问题挑几条。
     *
     * @param query 已清洗的查询词（如「国际新闻」「财经 新闻」「张掖马拉松」）
     * @return 少于 2 条就返回 null —— 让调用方走搜索引擎，别拿 1 条硬凑
     */
    fun pick(query: String, limit: Int = 3): List<Item>? {
        val all = fetch()
        if (all.isEmpty()) return null
        val scope = scopeOf(query)
        val scoped = all.filter { inScope(it, scope) }
        // ★ 只认国际新闻却没有国际条目时**返回 null**，让调用方去搜。
        //   之前用 ifEmpty { all } 兜底，结果问「国际新闻」念的是国内新闻。
        if (scoped.isEmpty()) return null

        // ① 主题词直接命中栏目（「财经方面的」→ 财经）
        val byCategory = scoped.filter { item ->
            CATEGORY_WORDS.any { (word, cat) -> query.contains(word) && catKey(item.category) == cat }
        }
        if (byCategory.size >= 2) return dedupe(byCategory).take(limit)

        // ② 按标题关键词过滤（「张掖马拉松」）
        val grams = keywords(query)
        if (grams.isNotEmpty()) {
            val hits = scoped.filter { item -> grams.any { item.title.contains(it) } }
            if (hits.size >= 2) return dedupe(hits).take(limit)
            if (hits.size == 1 && grams.any { it.length >= 3 }) return dedupe(hits).take(limit)
            // 有主题但列表里没有 → 交给搜索引擎
            return null
        }

        // ③ 没有主题：直接给最新的几条
        return dedupe(scoped).take(limit).takeIf { it.size >= 2 }
    }

    fun scopeOf(query: String): Scope = when {
        Regex("国际|世界|海外|全球|外国|外事").containsMatchIn(query) -> Scope.WORLD
        Regex("国内|中国|本地|时政").containsMatchIn(query) -> Scope.DOMESTIC
        else -> Scope.ALL
    }

    /** `[国际]` / `【国际】` / `国际` 都归一成 `国际` —— 页面两种括号混着用 */
    private fun catKey(raw: String): String {
        var s = raw.trim()
        for (pair in listOf("[" to "]", "【" to "】", "(" to ")", "（" to "）")) {
            if (s.startsWith(pair.first) && s.endsWith(pair.second) && s.length > 2) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    private fun inScope(item: Item, scope: Scope): Boolean {
        val cat = catKey(item.category)
        return when (scope) {
            Scope.WORLD -> cat == "国际"
            Scope.DOMESTIC -> cat != "国际"
            Scope.ALL -> true
        }
    }

    private fun dedupe(items: List<Item>): List<Item> {
        val seen = HashSet<String>()
        return items.filter { seen.add(it.title.take(12)) }
    }

    /** 查询词里的关键词（去掉新闻/今天这类泛词后的 2 字片段，最多 6 个） */
    private fun keywords(query: String): List<String> {
        val core = query.replace(
            Regex("新闻|要闻|时事|消息|报道|最新|今天|今日|昨天|现在|最近|国际|国内|中国|方面|的|吗|呢"),
            ""
        ).trim()
        if (core.length < 2) return emptyList()
        return if (core.length <= 4) listOf(core) else core.windowed(2).distinct().take(6)
    }

    private fun parse(html: String): List<Item> {
        val body = html.substring(html.indexOf("class=\"content_list\"").takeIf { it >= 0 } ?: 0)
        val out = ArrayList<Item>()
        for (block in ITEM_RE.findAll(body)) {
            val raw = block.value
            val cat = text(CAT_RE.find(raw)?.groupValues?.get(1).orEmpty())
            if (catKey(cat) in SKIP_CATEGORIES) continue
            val link = LINK_RE.find(raw) ?: continue
            val href = link.groupValues[1]
            val title = text(link.groupValues[2])
            if (title.length < 6) continue
            if (title.contains("频道首页") || title.contains("网站首页")) continue
            val timeText = text(TIME_RE.find(raw)?.groupValues?.get(1).orEmpty())
            out += Item(
                title = title,
                url = if (href.startsWith("http")) href else HOST + href,
                category = cat,
                timeText = timeText,
                at = parseTime(timeText)
            )
        }
        // 新→旧
        return out.sortedByDescending { it.at }
    }

    /** `11-9 15:52` → 时间戳；跨年就退一年 */
    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val normalized = raw.replace(Regex("""(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})"""), "$year-$1-$2 $3:$4")
        val at = runCatching { fmt.parse(normalized)?.time }.getOrNull() ?: return 0L
        // 12 月看到的「1-3」是去年
        return if (at > System.currentTimeMillis() + 24 * 3600 * 1000L) {
            runCatching { fmt.parse((year - 1).toString() + normalized.substring(4))?.time }.getOrNull() ?: at
        } else at
    }

    /** 今天/昨天/X小时前 + 时分，念出来更自然 */
    fun freshness(item: Item): String {
        val at = item.at
        if (at <= 0L) return item.timeText
        val diff = System.currentTimeMillis() - at
        return when {
            diff < 60 * 60 * 1000L -> "${diff / 60000} 分钟前"
            diff < 6 * 60 * 60 * 1000L -> "${diff / 3600000} 小时前"
            diff < 24 * 60 * 60 * 1000L -> "今天${item.timeText.substringAfter(' ').ifBlank { "" }}"
            else -> item.timeText.substringBefore(' ')
        }
    }

    private fun text(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun get(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
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
                Dbg.log("新闻垂类 HTTP $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Dbg.log("新闻垂类异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 主题词 → 栏目。用户说「财经方面的」时直接按栏目取，比标题匹配准 */
    private val CATEGORY_WORDS = listOf(
        "财经" to "财经", "经济" to "财经", "股市" to "财经",
        "体育" to "体育", "球" to "体育",
        "社会" to "社会", "民生" to "社会",
        "文化" to "文化", "教育" to "教育", "时政" to "时政"
    )
}
