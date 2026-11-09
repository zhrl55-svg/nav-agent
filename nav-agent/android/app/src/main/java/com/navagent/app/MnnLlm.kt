package com.navagent.app

import com.navagent.core.ChatMessage
import com.navagent.core.Llm
import com.navagent.core.LlmTool
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地大模型适配层：把 [MnnLocalLlm] 接到 [Llm] 接口上。
 *
 * ## 与云端实现（[OpenAiLlm]）的差别
 * - **没有 Function Calling**。导航/天气/周边由 NavApp 的拦截层按规则执行，
 *   搜索在这里由**规则触发 + 直接播报结果**完成（见 [planSearch]）。
 *   0.6B 实测一次都没按 `<tool_call>` 或裸 JSON 调过工具，所以不再把工具协议塞给它。
 * - **chat 和 chatText 等价**。云端那边 chat 是「判断意图 → 调工具 → 总结」两轮，
 *   chatText 只跑最后一步；本地没有工具流程，两个方法本来就是同一件事。
 *
 * ## 搜索为什么不交给模型
 * 2026-11-09 实测：拿资料给 0.6B 4bit，它常回「没有查到相关数据」；
 * 而且 MNN 的流式接口会在工具 JSON 完整前就把片段交给 TTS，念出来是
 * `{"name":"web_search"...` 这种乱码。所以搜索做成确定性流程：
 * 规则判断要不要搜 → 规则拼查询词 → 并行取结果 → 直接念筛选过的来源。
 *
 * ## 为什么自己拼 prompt
 * MNN 的 native 侧不会自动套 jinja 模板（实测裸 prompt 只吐 1 个 token），
 * 必须显式按 Qwen 的 `<|im_start|>` 格式拼好再喂进去。
 */
class MnnLlm(
    private val engine: MnnLocalLlm,
    /** 工具清单。只按名字取 `web_search` / `web_extract` 用，不把协议交给模型 */
    private val tools: List<LlmTool> = emptyList()
) : Llm {

    /** 上一轮搜索结果，供「读出来 / 详细说说」这类追问复用，避免再等一次网络 */
    @Volatile
    private var lastSearchQuery: String = ""

    @Volatile
    private var lastSearchRaw: String = ""

    /** 上一轮是不是新闻检索 —— 追问复用缓存时要保持同样的念法 */
    @Volatile
    private var lastSearchNews: Boolean = false

    @Volatile
    private var lastSearchAt: Long = 0L

    override fun chat(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? {
        // ★ 让设置页的「回复长度」在本地也生效。
        //   不接的话，用户切到本地后调这个值毫无反应——比不显示更让人困惑。
        //   上限卡 512：decode 约 57 t/s，512 token 要 9 秒。
        engine.setMaxNewTokens(Prefs.maxTokens.coerceIn(64, 512))

        // ★ 2026-11-09：联网搜索走确定性规则，不再交给模型。
        //
        //   0.6B 实测一次都没按 `<tool_call>` 或裸 JSON 调过工具，所以「该不该搜」
        //   一直由关键词判断。这版把三件事重做了：
        //     ① 触发面从 13 个词扩成四类（新闻 / 实时事实 / 明确检索 / 详情追问），
        //        原来「限行吗」「门票多少钱」「堵不堵」全被漏掉，直接交给模型编；
        //     ② 查询词不再用一条大正则乱删 —— 原来「新闻联播几点开始」会被删成
        //        「联播几点开始」；
        //     ③ 搜索前先说一句「我查一下」，结果并行取，最坏等待从 40 秒降到 12 秒。
        //
        //   ★ 不再串 `Prefs.toolsEnabled`：搜索是宿主自己的检索能力，
        //     跟小模型会不会 function calling 无关。原来用户按提示关掉「工具调用」，
        //     搜索也一起死了。
        if (Prefs.webSearchEnabled()) {
            val lastUser = history.lastOrNull { it.role == ChatMessage.Role.USER }?.text.orEmpty()
            val tool = tools.firstOrNull { it.name == "web_search" }

            // ① 「今天的新闻」没有主题时先追问；用户补充「财经方面的」后，
            //    把主题和原问题里的时间范围合并，继续真实搜索，不交给小模型猜新闻。
            val previousAssistant = history.dropLast(1).lastOrNull {
                it.role == ChatMessage.Role.ASSISTANT
            }?.text.orEmpty()
            // ★ 2026-11-09：这里必须确认「用户这句只是在补主题」。
            //   实测踩到：上一轮刚播过新闻，用户接着问「兰州市的限行规则是什么」，
            //   被当成「新闻主题」拼成了「…规则是什么 新闻」去搜，答案全跑偏。
            //   只有**短的、不含疑问/业务词的**补充（「财经方面的」「国际方面呢」）才算主题。
            val topicOnly = lastUser.length <= 10 &&
                !Regex("什么|怎么|为什么|哪|规则|政策|规定|多少|几点|多久").containsMatchIn(lastUser)
            if (topicOnly &&
                (NEWS_TOPIC_QUESTION.containsMatchIn(previousAssistant) ||
                    previousAssistant.contains("最新的新闻"))
            ) {
                val previousNewsRequest = history.dropLast(2).asReversed().firstOrNull {
                    it.role == ChatMessage.Role.USER && isNewsRequest(it.text)
                }
                val topic = lastUser.replace(
                    Regex("方面的|方面|类的|类别|新闻|要闻|时事|就是|想看|看一下|查一下|请问|吗|呢|的"), ""
                ).trim(' ', '，', ',', '。', '？', '?')
                if (previousNewsRequest != null && topic.isNotBlank() && tool != null) {
                    val combinedPrompt = "${previousNewsRequest.text} $lastUser 新闻"
                    Dbg.log("本地新闻主题追问 topic=$topic rangeFromPrevious=true")
                    tryNewsFeed("$topic 新闻", onDelta)?.let { return it }
                    return answerWithSearch(
                        query = "$topic 新闻",
                        tool = tool,
                        originalText = combinedPrompt,
                        news = true,
                        timeRange = timeRangeOf(combinedPrompt),
                        onDelta = onDelta
                    )
                }
            }

            // ② 「读出来 / 详细说说 / 整理一下」：先用上一轮的结果，别重新搜一遍。
            if (isSearchFollowup(lastUser)) {
                val cached = cachedSearch(SEARCH_CACHE_TTL_MS)
                if (cached != null) {
                    Dbg.log("本地搜索追问命中缓存 query=$lastSearchQuery")
                    return digestCached(cached, lastSearchQuery, onDelta)
                }
                val previousRequest = history.dropLast(1).asReversed().firstOrNull {
                    it.role == ChatMessage.Role.USER &&
                        !isSearchFollowup(it.text) &&
                        planSearch(it.text) != null
                }
                val plan = previousRequest?.text?.let(::planSearch)
                if (plan != null && plan.query.isNotBlank() && tool != null) {
                    Dbg.log("本地搜索追问重新检索 query=${plan.query}")
                    return answerWithSearch(
                        query = plan.query,
                        tool = tool,
                        originalText = previousRequest.text,
                        news = plan.news,
                        timeRange = plan.timeRange,
                        onDelta = onDelta
                    )
                }
            }

            // ③ 正常一轮
            val plan = planSearch(lastUser)
            if (plan != null && tool != null) {
                if (plan.news) {
                    // ★ 2026-11-09：泛新闻优先走中新网滚动列表。
                    //   一次 GET 拿 100 条带时间的真新闻，比搜「国际新闻」搜回
                    //   一堆频道首页靠谱得多。列表里没有对应主题时返回 null，
                    //   再退回搜索引擎。
                    //   顺带：query 为空（「今天有什么新闻」）也直接给头条，
                    //   不再反问「你想了解哪方面」—— 开车时少一轮对话。
                    val feedQuery = plan.query.ifBlank { "新闻" }
                    tryNewsFeed(feedQuery, onDelta)?.let { return it }
                    if (plan.query.isBlank()) return NEWS_TOPIC_ASK
                }
                return answerWithSearch(
                    query = plan.query,
                    tool = tool,
                    originalText = lastUser,
                    news = plan.news,
                    timeRange = plan.timeRange,
                    onDelta = onDelta
                )
            }
        }

        // 不走搜索：本地没有工具调用，直接生成答复。
        //
        // ★ 2026-11-09 清理：原来这里还留着「带工具清单跑一轮 → 解析 <tool_call> →
        //   回填 <tool_response> 再跑一轮」的完整分支，但 activeTools() 恒为空，
        //   那段永远进不去。死代码留着只会让人以为端侧在走 tool_call。
        val protectThoughtChannel = engine.wantsNoThink
        if (protectThoughtChannel && !Prefs.localThinkingEnabled) {
            // 即使用户关闭思考开关，Qwen 有时仍会吐出未标记的自我旁白。
            // 所以 /no_think 模式也不把首轮增量直接送去 UI/TTS。
            val finalOnly = engine.generate(
                buildPrompt(system, history, forceNoThink = true, finalOnly = true),
                keepHistory = false,
                onDelta = null
            )?.trim()
            return userVisibleAnswer(finalOnly)
                ?: "我还没整理好答案，换个问法试试。"
        }
        val first = engine.generate(
            buildPrompt(system, history), keepHistory = false,
            onDelta = if (protectThoughtChannel) null else onDelta
        )?.trim()
        if (protectThoughtChannel) {
            // 端侧模型有时会把推理写成普通文本（没有 <think> 标签），所以首轮即使
            // 看起来像自然语言也不能直接交给 UI/TTS。只有明确的 final 通道可直接使用。
            val taggedFinal = explicitFinalAnswer(first.orEmpty())
            if (taggedFinal != null) {
                userVisibleAnswer(taggedFinal)?.let { return it }
            }
            Dbg.log("端侧思考首轮静默完成，生成仅含最终答复的第二轮")
            val finalOnly = engine.generate(
                buildPrompt(system, history, forceNoThink = true, finalOnly = true),
                keepHistory = false,
                onDelta = null
            )?.trim()
            return userVisibleAnswer(finalOnly) ?: run {
                Dbg.log("端侧最终答复仍无法确认是面向用户的内容，已抑制播报")
                "我还没整理好答案，换个问法试试。"
            }
        }
        return userVisibleAnswer(first)
    }

    // ------------------------------------------------------------------ 搜索

    /**
     * 一轮完整的「搜 → 读 → 念」。
     *
     * ★ 2026-11-09 加。三件事必须放在一处，否则又会散掉：
     *   ① 先说「我查一下」—— 搜索本身要 4~12 秒，一声不吭用户以为死机；
     *   ② 结果回灌给 [onDelta] —— ChatEngine 是靠增量来决定念不念的，
     *      只 return 不 onDelta，用户会听到「我查一下」之后的一片安静；
     *   ③ 缓存这一轮结果，供「读出来 / 详细说说」复用。
     */
    private fun answerWithSearch(
        query: String,
        tool: LlmTool,
        originalText: String,
        news: Boolean,
        timeRange: String?,
        onDelta: (String) -> Unit
    ): String {
        onDelta(SAY_SEARCHING)
        val raw = fastSearch(query, tool, news, timeRange)
        Dbg.log("本地规则搜索 query=$query news=$news chars=${raw.length}")
        rememberSearch(query, raw, news)
        val wantsDetail = news || DETAIL_WORDS.containsMatchIn(originalText)
        // 新闻结果里已经带正文时不再开浏览器（多等 4 秒不值得）
        val article = if (wantsDetail && !(news && hasNewsBody(raw))) readTopArticle(raw, query) else null
        val text = digest(raw, article, news, query)
        onDelta(text)
        return text
    }

    /**
     * 泛新闻直出：走中新网滚动列表（[NewsFeed]）。
     *
     * ★ 2026-11-09 加。为什么单独一条路：
     *   泛新闻（「今天有什么新闻」「国际新闻」）用搜索引擎是真的搜不到东西 ——
     *   搜「国际新闻」回来全是频道首页，相关性过滤后剩 0 条；Tavily 那边是乱码。
     *   而滚动列表本来就是「最近的新闻」，一次 GET 就有 100 条带时间的标题。
     *
     * @return 已经念出来的文本；null 表示列表里没有对应主题，交给搜索引擎
     */
    private fun tryNewsFeed(query: String, onDelta: (String) -> Unit): String? {
        val items = NewsFeed.pick(query, NEWS_FEED_ITEMS) ?: return null
        // ★ 2026-11-09 实测：「张掖最近发生什么了」走了新闻垂类，
        //   返回的是「甘肃张掖是个什么样的地方」+ 土耳其钢厂爆炸 —— 有主题词却给宝宝级泛新闻。
        //   只要查询里还有实义主题词，就必须在标题里出现；一条都不沾说明列表里没有这条新闻，
        //   让位给搜索引擎更靠谱。
        val topic = query.replace(NEWS_ONLY, "").replace(Regex("\\s+"), " ").trim()
        if (topic.length >= 2) {
            val tokens = topic.split(" ").filter { it.length >= 2 }
            if (tokens.isNotEmpty() && items.none { item -> tokens.any { item.title.contains(it) } }) {
                Dbg.log("新闻垂类无相关主题「$topic」，让位搜索引擎")
                return null
            }
        }
        onDelta(SAY_SEARCHING)
        val scope = NewsFeed.scopeOf(query)
        val intro = when (scope) {
            NewsFeed.Scope.WORLD -> "国际方面最新的新闻："
            NewsFeed.Scope.DOMESTIC -> "国内最新的新闻："
            else -> "最新的新闻："
        }
        val ordinals = listOf("第一条", "第二条", "第三条")
        val body = items.mapIndexed { index, item ->
            "${ordinals.getOrElse(index) { "第${index + 1}条" }}，" +
                item.title.trimEnd('。', '！', '？', '!', '?')
        }.joinToString("；")
        val text = intro + body + "。"
        Dbg.log("新闻垂类直出 items=${items.size} scope=$scope query=$query")
        onDelta(text)
        return text
    }

    private fun rememberSearch(query: String, raw: String, news: Boolean) {
        lastSearchQuery = query
        lastSearchRaw = raw
        lastSearchNews = news
        lastSearchAt = System.currentTimeMillis()
    }

    private fun cachedSearch(ttlMs: Long): String? {
        if (lastSearchRaw.isBlank()) return null
        if (System.currentTimeMillis() - lastSearchAt > ttlMs) return null
        return lastSearchRaw
    }

    /** 追问命中缓存：不再上网，直接读/念上一轮的结果 */
    private fun digestCached(raw: String, query: String, onDelta: (String) -> Unit): String {
        val news = lastSearchNews
        val article = if (!(news && hasNewsBody(raw))) readTopArticle(raw, query) else null
        val text = digest(raw, article, news, query)
        onDelta(text)
        return text
    }

    /**
     * 并行取结果。
     *
     * ★ 2026-11-09 重做。原来的顺序是「先问 Tavily，等 15 秒，超时再串行
     *   跑搜狗/百度/Bing」，最坏 40 多秒，而且 `cancel(true)` 对
     *   HttpURLConnection 根本无效。现在两路同时起：
     *     · 中文查询先等备用引擎（实测 32~41ms），拿到够用的条目就直接用它；
     *     · 备用引擎不理想就等 Tavily（新闻 12s / 通用 8s）。
     *
     * 新闻给备用引擎 4 秒、通用给 2.5 秒：新闻更看重新鲜度，多等一点换 Tavily
     * 的发布时间；通用查询要的是快，本地引擎一直是这个场景的强项。
     */
    private fun fastSearch(
        query: String,
        tool: LlmTool,
        news: Boolean,
        timeRange: String?
    ): String {
        val searchQuery = if (news) {
            val base = query.trim().replace(Regex("\\s+"), " ")
            if (base.contains(Regex("新闻|要闻|时事|报道|消息"))) "$base 最新报道"
            else "$base 新闻 最新报道"
        } else query
        val args = JSONObject()
            .put("query", searchQuery)
            .put("topic", if (news) "news" else "general")
            .apply { if (timeRange != null) put("time_range", timeRange) }
        Dbg.log("端侧联网搜索 topic=${if (news) "news" else "general"} range=${timeRange ?: "-"} q=$searchQuery")

        val hasCjk = searchQuery.any { it.code in 0x4E00..0x9FFF }
        val fastFuture = if (hasCjk) {
            runCatching { SEARCH_POOL.submit(Callable { FallbackWebSearch.search(searchQuery) }) }.getOrNull()
        } else null
        val tavilyFuture = SEARCH_POOL.submit(Callable { runTool(ToolCall("web_search", args.toString())) })

        var fastResult: FallbackWebSearch.Result? = null
        if (fastFuture != null) {
            val budget = if (news) FAST_NEWS_BUDGET_MS else FAST_GENERAL_BUDGET_MS
            val need = if (news) 2 else 1
            fastResult = runCatching { fastFuture.get(budget, TimeUnit.MILLISECONDS) }.getOrNull()
            val fast = fastResult
            if (fast != null && fast.items.size >= need) {
                Dbg.log("规则搜索走备用引擎 ${fast.engine} news=$news")
                tavilyFuture.cancel(true)
                return FallbackWebSearch.render(fast, searchQuery)
            }
        }
        return try {
            tavilyFuture.get(if (news) 12L else 8L, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            tavilyFuture.cancel(true)
            Dbg.log("端侧联网搜索超时，切到备用搜索")
            // 上面备用引擎已经拿到的条目先用掉，别为同一个查询再跑一遍三引擎
            val quick = fastResult ?: runCatching { FallbackWebSearch.search(searchQuery) }.getOrNull()
            quick?.let { FallbackWebSearch.render(it, searchQuery) }
                ?: "联网搜索超时，备用搜索也没有找到可用结果。"
        } catch (t: Throwable) {
            Dbg.log("端侧联网搜索失败：${t.javaClass.simpleName}")
            "联网搜索失败，暂时没有取得可用结果。"
        }
    }

    /** 移除明确的思考通道标记；普通文本里直接自述规则检查时返回 null，不允许播报。 */
    private fun userVisibleAnswer(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val withoutThink = raw
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .substringBefore("<think>")
            .replace(Regex("</?\\|(?:analysis|final)\\|?>"), "")
            .trim()
        if (withoutThink.isBlank() || looksLikeInternalReasoning(withoutThink)) return null
        return withoutThink
    }

    /** 仅把有明确 final 通道边界的文本视为可直接播报。 */
    private fun explicitFinalAnswer(raw: String): String? {
        val finalChannel = Regex("<\\|final\\|>(.*)$", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()
        if (!finalChannel.isNullOrBlank()) return finalChannel
        val thinkEnd = raw.lastIndexOf("</think>")
        if (thinkEnd >= 0) return raw.substring(thinkEnd + "</think>".length).trim().takeIf { it.isNotBlank() }
        return null
    }

    private fun isNewsRequest(text: String): Boolean = Regex(
        "新闻|要闻|时事|新闻呢|最近发生|最新消息|国内外大事|国际局势|" +
            // ★ 2026-11-09 实测漏判：「张掖最近有什么新鲜事」不含「新闻」，
            //   落到模型手里 11.5 秒换来一句「没有公开信息」。这类口语说法补上。
            "新鲜事|新动态|最近有啥|有什么动静|最近的事"
    ).containsMatchIn(text)

    private fun hasNewsBody(raw: String): Boolean = raw.lineSequence().any { line ->
        val body = line.trim()
        body.length >= 100 && !Regex("跳转到主要内容|首页|登录|注册|版权所有|友情链接").containsMatchIn(body)
    }

    private fun looksLikeInternalReasoning(text: String): Boolean = Regex(
        "^(?:好的[，,]?\\s*)?(?:现在[，,]?\\s*)?(?:我明白了用户|我(?:现在)?(?:要|需要|得)(?:先)?(?:处理|理解|确认|检查|分析|回应|回答|回顾)|用户(?:之前|现在|已经|发来|想要|说|的(?:请求|最新消息|需求))|首先(?:需要|我需要|我要|我得|[，,]我|[，,]?用户)|接下来(?:需要|我|先)|根据(?:提示词|要求|规则)|需要(?:先)?确认用户|让我先(?:检查|确认|回顾)|这个请求需要我|在回答之前|我得先确认)"
    ).containsMatchIn(text.trim())

    /**
     * 打开搜索结果里第一条的正文。
     *
     * 只取 1 个 URL、只读一次：开车场景多等一秒都是成本。
     * 失败就返回 null，退回「标题 + 摘要」。
     */
    private fun readTopArticle(raw: String, query: String): String? {
        val tool = tools.firstOrNull { it.name == "web_extract" } ?: return null
        val url = Regex("https?://[^\\s（）)\"']+").find(raw)?.value ?: return null
        val t0 = System.currentTimeMillis()
        // ★ 有些站（163 / 今日头条）是 JS 渲染，走浏览器内核要 9 秒。
        //   开车等不起：4 秒拿不到就用标题+摘要，正文线程留着跑完自己结束。
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "web-extract").apply { isDaemon = true }
        }
        val future = pool.submit<String?> {
            runCatching {
                tool.execute(org.json.JSONObject().put("urls", org.json.JSONArray().put(url))
                    .put("query", query).toString())
            }.getOrNull()
        }
        val out = runCatching { future.get(4, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrElse { null }
        pool.shutdown()
        Dbg.log("本地正文提取 ${System.currentTimeMillis() - t0}ms chars=${out?.length ?: 0}")
        return cleanArticle(out)?.takeIf { it.length >= 40 }
    }

    /**
     * 清掉正文里的杂质。
     *
     * ★ 实测踩到：有的站正文开头是「来源：https://www.163.com/...」，
     *   直接取前 110 字，念出来就是一串网址（用户听到「查到：来源：https」）。
     */
    private fun cleanArticle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 工具失败时会返回一段很长的说明文字（「网页请求已完成，但没能取到正文…」），
        // 长度足够骗过 40 字门槛，被当正文念出来就荒唐了。
        if (Regex("没能取到正文|未取到正文|浏览器渲染都失败|请勿把它当作已核实").containsMatchIn(raw)) {
            return null
        }
        val junk = Regex("^(来源|作者|编辑|发布时间|时间|责任编辑|原标题|网址|URL)[:：]")
        val body = raw.lines()
            .map { it.trim() }
            // ★ 2026-11-09：markdown 标题标记 `#` / `>` 会被 TTS 念成「井号」。
            //   实测播报「查到：# 明天，甘肃张掖境内有交通管制」—— 一个 # 毁了整句。
            .map { it.replace(Regex("^[#>\\s]+"), "").replace(Regex("^[-*+]\\s+"), "").trim() }
            // 页面控件：字号切换「大 中 小」、打印/收藏/分享。念出来是噪音
            .filterNot { it.matches(Regex("^(?:大|中|小|打印|收藏|分享|关闭|返回)(?:\\s+(?:大|中|小|打印|收藏|分享|关闭|返回))*$")) }
            // 行首残留的括号/编号标点（「) 故宫博物院票务政策」）
            .map { it.replace(Regex("^[)\\]】}、,，。:：;；\\s]+"), "").trim() }
            .filter { it.isNotEmpty() }
            .filterNot { junk.containsMatchIn(it) }
            .filterNot { it.matches(Regex("https?://\\S+")) }
            .joinToString(" ")
            // 网页正文可能含 Markdown 图片/链接；保留可读标题，丢掉标记和目标 URL，
            // 否则朗读会把“感叹号、方括号、图片地址”一并念出来。
            .replace(Regex("!?\\[([^\\]]*)\\](?:\\([^)]*\\)|\\[[^]]*])"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("!?\\[|\\]"), "")
            // ★ 2026-11-09：`*导语*` 这种强调标记会被念成「星号导语星号」，
            //   实测兰州限行那条播报就带着它。
            .replace(Regex("[*_`~]{1,3}"), "")
            .replace(Regex("(导语|摘要|编者按|小编|原标题)[:：]?\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return body.takeIf { it.length >= 40 }
    }

    /**
     * 直出：把搜索结果念出来。
     *
     * ★ 2026-11-09 重做两处：
     *   ① 有正文时不再取开头 110 字，改成按查询词挑最相关的一句（[bestSentence]）——
     *      「门票多少钱」「限行吗」的答案往往在页面中后段，取开头只会念景点简介；
     *   ② 新闻从 2 条扩到 3 条，每条压到 48 字 —— 问「今天有什么新闻」想要的是
     *      几条标题，不是一条报道的开头。
     */
    private fun digest(raw: String, article: String?, news: Boolean, query: String): String {
        val items = parseItems(raw)
        val body = article?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (body.length >= 40) {
            val picked = bestSentence(body, query) ?: body.take(120)
            // 补句号：TTS 靠句末标点断句，没有标点会拖长音
            val sentence = picked.trim().let {
                if (it.isEmpty() || it.last() in "。！？!?.") it else "$it。"
            }
            val title = items.firstOrNull()?.first
            return if (news && !title.isNullOrBlank()) "查到新闻报道《$title》：$sentence" else "查到：$sentence"
        }
        if (items.isEmpty()) return "搜了一下，但没拿到能念的内容。"
        if (news) {
            val withText = items.filter { it.second.isNotBlank() }
            if (withText.isEmpty()) {
                return "找到相关新闻标题《${items.first().first}》，但网页正文暂时读取不到，不能据此复述新闻内容。"
            }
            return withText.take(3).mapIndexed { index, item ->
                val intro = when (index) {
                    0 -> "第一条"
                    1 -> "第二条"
                    else -> "第三条"
                }
                "$intro《${item.first}》：${item.second.take(48)}"
            }.joinToString("。", prefix = "搜到相关新闻：", postfix = "。")
        }
        // ★ 2026-11-09 实测：「今天的油价多少」念出来是
        //   「汽油价格查询 今日油价查询 最新柴油油价实时行情 2025油价调整时间表 92油价…」
        //   —— 那是搜索站的栏目列表，不是内容。优先挑有正文的条目，全是导航页就直说。
        val usable = items.filter { it.second.isNotBlank() && !isAggregatorSnippet(it.second) }
        if (usable.isEmpty() && items.isNotEmpty()) {
            // 全是索引页时至少给一条标题，别只说「换个说法」——用户会觉得没做事
            val title = items.first().first
            return "只搜到索引页，没拿到正文。其中一条是《$title》。"
        }
        val first = usable.firstOrNull() ?: items.first()
        val snippet = first.second.trim()
        // ★ 2026-11-09：有摘要就只念摘要。
        //   原来拼成「查到一条：标题。摘要」，TTS 按句末标点断句，
        //   用户听到的是「查到一条：2026年11月张掖限行规定。」（停一下）再接摘要，
        //   而摘要本身常被 take(60) 切断，听起来像两段不相干的话。
        if (snippet.length >= 16) {
            // ★ 问的是具体数字，来源却只给一段导语（「甘肃今日92号汽油…
            //   最新价格查询。甘肃油价多少钱一升？」），念出来等于没答。
            //   这种情况宁可说没查到，也不要让用户以为这就是答案。
            if (ASKS_NUMBER.containsMatchIn(query) && snippet.none { it.isDigit() }) {
                return "搜到了相关页面，但没给出具体数字。"
            }
            return clip(snippet, 80)
        }
        return "查到一条：${first.first}。"
    }

    /**
     * 聚合页/导航页判定。
     *
     * 搜索站喜欢把「汽油价格查询 今日油价查询 最新柴油油价实时行情 92油价 95油价」
     * 当成摘要返回，看着像内容，其实是栏目名堆砌。两个特征很稳：
     *  - 栏目词密度高（查询/大全/时间表/行情…）
     *  - 空格分段多且没有句末标点
     */
    private fun isAggregatorSnippet(s: String): Boolean {
        val text = s.trim()
        if (text.length < 8) return true
        // 站点自述（「提供全国各地今日油价实时查询…」「本站…」「欢迎访问…」）同样没有信息
        if (Regex("^(提供|本站|我们|欢迎|为您提供|点击|关注|下载|注册|登录|打开)").containsMatchIn(text)) return true
        val columnWords = Regex("查询|大全|时间表|实时行情|行情|走势|官网|下载|APP|客户端|登录|注册|首页|导航|百科|资讯|网")
            .findAll(text).count()
        val segments = text.split(Regex("\\s{2,}")).size
        val hasSentence = text.any { it in "。！？!?" }
        if (columnWords >= 3) return true
        if (!hasSentence && segments >= 4) return true
        return false
    }

    /** 截到标点边界内：直接把句子砍在半截，TTS 断句会怪 */
    private fun clip(text: String, max: Int): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (t.length <= max) return if (t.last() in "。！？!?.") t else "$t。"
        val head = t.take(max)
        val end = head.indexOfLast { it in "。！？!?." }
        if (end >= 20) return head.substring(0, end + 1)
        val comma = head.indexOfLast { it in "，、；,;" }
        if (comma >= 20) return head.substring(0, comma + 1)
        return "$head。"
    }

    /**
     * 从渲染好的搜索结果里取出「标题 + 紧跟着的摘要」。
     *
     * 只认 `- ` 开头的行和紧随其后缩进两格的一行 —— 这是两个渲染器
     * （Tavily / 备用搜索）共同的格式，「备注」「来源有限」这类说明行会被自动跳过。
     */
    private fun parseItems(raw: String): List<Pair<String, String>> {
        val lines = raw.lines().filter { it.isNotBlank() }
        val items = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < lines.size && items.size < 4) {
            val line = lines[i].trim()
            if (line.startsWith("- ")) {
                // 标题常带「- 网易订阅」「| 新浪」这类站点尾巴，念出来很怪
                val title = line.removePrefix("- ").substringBefore("（")
                    .replace(Regex("\\s*[-|｜—–]\\s*[^-|｜—–]{1,12}$"), "")
                    .replace("|", " ").trim()
                val next = lines.getOrNull(i + 1)
                val snippet = next?.takeIf { it.startsWith("  ") && !it.trimStart().startsWith("-") }
                    ?.trim()
                    ?.replace(Regex("^[#>]+\\s*"), "")
                    ?.replace(Regex("\\s+"), " ")
                    ?.takeIf { it.length >= 8 }
                    .orEmpty()
                // ★ 2026-11-09：整条丢掉乱码结果。
                //   实测搜「国内新闻 最新报道」，Tavily 回的中文标题是一串
                //   「һ߽ڣ¡ĸ֡-」—— GBK 站被按别的编码解出来的，念出来是噪音。
                val garbled = looksGarbled(title) || looksGarbled(snippet)
                if (title.isNotBlank() && !garbled) items += title to snippet
                else if (title.isNotBlank()) Dbg.log("跳过乱码结果：「${title.take(20)}」")
            }
            i++
        }
        return items
    }

    /**
     * 判断是不是编码错乱。
     *
     * 中文/英文正常文本里不会出现 U+0080–U+08FF（拉丁补充、西里尔、希伯来、阿拉伯）
     * 这类字符，也不会出现替换符和私用区。出现一定比例就是乱码。
     */
    private fun looksGarbled(s: String): Boolean {
        if (s.isBlank()) return false
        var bad = 0
        for (ch in s) {
            val c = ch.code
            if (c == 0xFFFD || c in 0xE000..0xF8FF || c in 0x0080..0x08FF || c in 0xFB00..0xFB4F) bad++
        }
        return bad >= 3 && bad * 100 / s.length >= 12
    }

    /**
     * 在正文里找最贴近问题的一句。
     *
     * 按句切开，用查询词的二字片段打分。都不沾边（全是介绍性文字）才退回开头。
     */
    private fun bestSentence(body: String, query: String): String? {
        val sentences = body.split('。', '！', '？', '；', '!', '?', ';', '\n')
            .map { it.trim() }
            .filter { it.length in 12..200 }
        if (sentences.isEmpty()) return null
        val grams = queryGrams(query)
        if (grams.isEmpty()) return sentences.first().take(120)
        val best = sentences.maxByOrNull { s -> grams.count { s.contains(it) } } ?: return null
        val score = grams.count { best.contains(it) }
        return if (score == 0) sentences.first().take(120) else best.take(120)
    }

    private fun queryGrams(query: String): List<String> {
        val core = query.replace(Regex("[\\s\\p{Punct}，。、？！：；\"'（）]+"), "")
        if (core.length < 2) return emptyList()
        return core.windowed(2).distinct().take(12)
    }

    /**
     * 这句话要不要联网搜，要的话搜什么。
     *
     * 为什么用规则而不是让模型决定：实测 0.6B 既不按 `<tool_call>` 也不按裸 JSON 输出，
     * 让它判断等于不搜。天气、导航、周边由 NavApp 的拦截层先处理，走不到这里。
     *
     * ★ 2026-11-09 重写触发面。原来只有 13 个命中词，云端 prompt 里明确
     *   「必须搜」的类别（限行、门票、营业时间、路况、评分、活动）本地一个都不认，
     *   全部交给模型凭记忆答。现在按四类判定：
     *     · 新闻类（news=true，走 Tavily 新闻垂类）
     *     · 实时事实类（限行/门票/营业/油价/路况/评分/活动…）
     *     · 明确要求检索（搜索/查一下/网上…）
     *     · 详情追问（「几点」「多少钱」——只在已命中前三类时起作用）
     */
    private fun planSearch(raw: String): SearchPlan? {
        val t = raw.trim()
        if (t.length < 2) return null
        // 导航 / 车控 / 天气：拦截层负责，规则搜索不抢
        if (HANDLED_ELSEWHERE.containsMatchIn(t)) return null
        // 「去张掖西站」这种裸动词短语是导航，不是检索
        if (BARE_NAV.containsMatchIn(t) && !QUESTION_MARK.containsMatchIn(t)) return null
        // 「这新闻真离谱」这类情绪话不搜
        if (CHAT_SIGNAL.containsMatchIn(t)) return null

        val explicit = EXPLICIT_SEARCH.containsMatchIn(t)
        val news = isNewsRequest(t) && !DETAIL_QUESTION.containsMatchIn(t)
        val realtime = REALTIME_WORDS.containsMatchIn(t)

        // ★ 2026-11-09：分类器补漏。
        //   规则只能覆盖「带关键词」的说法；「张掖最近有什么新鲜事」「这阵子有啥新消息」
        //   这类全靠语义，正则穷举不完，交给 2MB 的判别器（几十微秒，纯 Kotlin）。
        //   只在规则全没命中时才问它 —— 规则命中就按规则的来，模型只负责「加」不负责「减」。
        // ★ 知识类问题不交给分类器：「张掖在哪里」实测 p=0.98 会触发搜索，
        //   而搜回来只有索引页（「只搜到索引页…其中一条是《张掖市》」），
        //   比模型自己答「张掖位于甘肃省」还差。这类静态知识不该联网。
        val staticKnowledge = Regex("在哪里|是哪|是什么|为什么|怎么形成|多远|多高|多大|谁|介绍|区别")
            .containsMatchIn(t)
        var byModel = false
        if (!explicit && !news && !realtime && !staticKnowledge) {
            val p = IntentGate.pSearch(t)
            byModel = p >= IntentGate.THRESHOLD
            if (p >= 0.5f) Dbg.log("意图门 p=${"%.2f".format(p)} → ${if (byModel) "搜索" else "不搜"}「$t」")
        }
        if (!explicit && !news && !realtime && !byModel) return null

        val query = buildQuery(t, news)
        val timeRange = timeRangeOf(t)
        // 只说了「今天有什么新闻」：没有主题，先追问，别去搜新闻频道首页
        if (news && query.replace(NEWS_ONLY, "").isBlank()) return SearchPlan("", true, timeRange)
        if (query.isBlank()) return null
        return SearchPlan(query, news, timeRange)
    }

    /**
     * 从口语句子里抽查询词。
     *
     * ★ 2026-11-09 重写：原来用一条大正则把「新闻 / 什么 / 哪些 / 今天」一并删掉，
     *   结果「新闻联播几点开始」变成「联播几点开始」（栏目名被拆），
     *   「这新闻真离谱」变成「这真离谱」（误搜）。现在只删**祈使语和语气词**，
     *   内容词一个不动。
     */
    private fun buildQuery(text: String, news: Boolean): String {
        var q = text.trim()
        q = q.replace(LEADING_FILLER, "")   // 帮我 / 请问 / 我想知道…
        q = q.replace(TRAILING_FILLER, "")  // 吧 / 呢 / 吗 / 一下
        q = q.replace(INLINE_FILLER, "")    // 搜索一下 / 查一下 / 网上
        q = q.replace(TIME_ADVERB, "")      // 今天 / 现在（时间走 time_range）
        q = q.replace(VAGUE_ASK, "")        // 有什么 / 有哪些
        // 「再搜一下今天的国际新闻」删完祈使语会剩下「的国际新闻」，把残留的虚词清掉
        q = q.replace(Regex("^(?:的|了|，|,|、)+"), "")
        q = q.replace(Regex("[\\s，,。.、]+"), " ").trim()
        q = q.trim(' ', '，', ',', '。', '？', '?', '！', '!', '的')
        // 「新鲜事 / 新动态」直接换成「新闻」，否则会拼出「张掖 新鲜事 新闻」这种怪查询
        if (news) q = q.replace(Regex("新鲜事|新动态|最近有啥|有什么动静"), "新闻")
        if (news && !q.contains(Regex("新闻|要闻|时事|报道"))) {
            q = if (q.isBlank()) "" else "$q 新闻"
        }
        return q
    }

    private fun timeRangeOf(text: String): String? = when {
        Regex("今天|今日|昨天|刚刚|现在|最新").containsMatchIn(text) -> "day"
        Regex("这周|本周|近一周|最近一周|近期").containsMatchIn(text) -> "week"
        Regex("本月|这个月|近一个月").containsMatchIn(text) -> "month"
        Regex("今年|本年").containsMatchIn(text) -> "year"
        else -> null
    }


    private fun isSearchFollowup(text: String): Boolean = Regex(
        "读出来|读一下|念出来|念一下|详细内容|具体内容|全文|整理(一下|出来|成)|总结(一下|出来)|概括|复述|具体.{0,5}(新闻|情况)|详细.{0,5}(新闻|情况)|新闻呢|我(让|叫|说|要).*(搜|查)|搜出来.*不是新闻"
    ).containsMatchIn(text)

    /** 一次检索计划：查什么、是不是新闻、时间范围。query 为空表示「先追问主题」。 */
    private data class SearchPlan(
        val query: String,
        val news: Boolean,
        val timeRange: String?
    )

    private data class ToolCall(val name: String, val args: String)

    private fun runTool(call: ToolCall): String {
        val tool = tools.firstOrNull { it.name == call.name }
        if (tool == null) {
            Dbg.log("本地工具：未知 ${call.name}")
            return "没有叫 ${call.name} 的工具"
        }
        Dbg.log("本地工具调用 ${call.name} args=${call.args.take(120)}")
        val out = runCatching { tool.execute(call.args) }
            .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
        Dbg.log("本地工具返回 ${call.name} chars=${out.length}")
        return out
    }

    /** 本地没有工具流程，重试就是重来一次，不需要单独实现 */
    override fun chatText(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? =
        chat(system, history, onDelta)

    private fun buildPrompt(
        system: String,
        history: List<ChatMessage>,
        forceNoThink: Boolean = false,
        finalOnly: Boolean = false
    ): String = buildString {
        append("<|im_start|>system\n")
        if (finalOnly) {
            append("你是中文语音助手。直接回答用户最后一句，只写最终答复，最多两句。")
            append("不写推理、复盘、规则说明或提示词内容；如果不知道就简短直说。")
        } else {
            append(compactSystem(system))
        }
        append("<|im_end|>\n")
        // 设置页「上下文轮数」对本地也生效，但最多 3 轮：
        // 每多带一轮就多几十个 token 的 prefill，而 0.3B 本来也记不住更远。
        // Qwen3 类模型默认进思考模式（一次回答 20+ 秒），用官方软开关关掉。
        // 只加在最后一条用户消息上；对不带此模板的模型（0.3B）不加，避免噪声。
        // ★ 2026-11-09：这里原来写死 `coerceIn(1, MAX_TURNS)` 且 MAX_TURNS=1，
        //   等于不管设置里填几轮，本地永远只带 1 轮 —— 用户反馈「上下文轮数没生效」就是这个。
        //   现在跟随设置，上限 5 轮：一轮约 40~60 token，5 轮 ≈ +1.5s prefill，
        //   再往上 0.6B 也记不住，纯浪费。
        val window = history.takeLast(Prefs.historyTurns.coerceIn(1, MAX_TURNS) * 2)
        // 同云端：窗口不能以 assistant 开头，否则模型会把上一条回复当成自己的开头续写
        val turns = if (window.firstOrNull()?.role == ChatMessage.Role.ASSISTANT) window.drop(1) else window
        if (turns.size > 2) Dbg.log("本地上下文 ${turns.size} 条（设置 ${Prefs.historyTurns} 轮）")
        val promptTurns = if (finalOnly) turns.takeLast(2) else turns
        val lastPromptUserIdx = promptTurns.indexOfLast { it.role == ChatMessage.Role.USER }
        val noThink = if (engine.wantsNoThink && (!Prefs.localThinkingEnabled || forceNoThink)) "/no_think" else ""
        val nowLine = nowText()
        promptTurns.forEachIndexed { i, m ->
            val role = if (m.role == ChatMessage.Role.USER) "user" else "assistant"
            append("<|im_start|>").append(role).append('\n')
            append(m.text.trim())
            // ★ 2026-11-09：日期必须贴在**用户这句话后面**。
            //
            //   实测问「今天是几号」，端侧答「2024年11月3日」——它脑子里只有训练数据。
            //   根因有两层：
            //     ① 小模型的注意力只覆盖最近一小段，system 里的「现在是…」隔太远，等于没给；
            //     ② 思考模式下真正产出答案的是**第二轮**（finalOnly），
            //        而那一轮的 system 只有两行通用指令，**根本没有日期**。
            //   贴在 user 消息后面，两轮都带得上。
            if (i == lastPromptUserIdx && nowLine.isNotEmpty()) {
                append("\n（现在是 ").append(nowLine).append("，问日期、星期、几号一律以此为准）")
            }
            // 换行再跟开关：紧贴中文容易被当成正文的一部分（「价格/no_think」）
            if (i == lastPromptUserIdx && noThink.isNotEmpty()) append("\n").append(noThink)
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /** 「2026年11月9日 星期一 15:49」—— 不给这行，端侧必按训练数据编日期 */
    private fun nowText(): String = runCatching {
        java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern(
                "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA
            )
        )
    }.getOrDefault("")

    /**
     * 裁剪系统提示词。
     *
     * ★ 云端那份有几千字，一半是工具协议、搜索规则、候选确认流程。
     *   本地一条都用不上，但每一条都要 prefill —— 2000 token 在 P40 上要 10 秒。
     *
     * 两个动作：
     *  1) **整句剔除**含 web_search / 工具 / navigate / topic= 的句子。
     *     只按长度截断是不够的：实测截到 260 字时，「先调 web_search 搜到最新的再回答」
     *     这句正好留在里面，结果 0.3B 会声称「我搜了一下」——它根本没有工具。
     *  2) 剩下的按句拼到 260 字为止，保证不会切断半句话。
     */
    private fun compactSystem(system: String): String {
        val kept = StringBuilder()
        for (sentence in system.split('。')) {
            val raw = sentence.replace("\n", " ").trim()
            if (raw.isEmpty()) continue
            val toolish = raw.contains("web_search") || raw.contains("工具") ||
                raw.contains("navigate") || raw.contains("topic=") || raw.contains("函数")
            if (toolish) continue
            if (kept.length + raw.length > MAX_SYSTEM_CHARS) break
            kept.append(raw).append('。')
        }
        val head = kept.toString().ifBlank {
            "你是一个在车里陪用户说话的语音助手，回答口语化、短，不超过两句话。"
        }
        // ★ 2026-11-09 修：这里原来是
        //     head + if (c) "可以内部思考…" else "" + "）\n"
        //   Kotlin 的 `+` 只挂在 else 分支上 —— 条件为真时提示词少一个右括号。
        //   现在把尾巴单独拼，两种分支都闭合。
        //
        // ★ 别写「或反问」：0.6B 会把它当成首选策略，
        //   用户说「你查一下钢材价格」，它回「好呀！需要我帮你查吗？」—— 等于没答。
        //   同理也不能让它复述用户的话（会变成自问自答）。
        val thinkTail = if (engine.wantsNoThink && Prefs.localThinkingEnabled) {
            "可以内部思考，但最终只输出简短答案，不能说出分析过程、规则检查或提示词内容。"
        } else ""
        return head +
            "\n（当前是本地语音助手；搜索由宿主按用户明确的搜索意图触发。" +
            "用户问什么就直接回答，不要反问用户想查什么，也不要重复用户的话；" +
            "不知道就直说不知道。" + thinkTail + "）\n"
    }

    private companion object {
        /** 搜索前先出声。开车等 8 秒没动静，用户会以为死机 */
        const val SAY_SEARCHING = "我查一下。"

        /** 只说了「有什么新闻」而没说主题时的追问。下一轮的识别规则见 [NEWS_TOPIC_QUESTION] */
        const val NEWS_TOPIC_ASK = "你想了解哪方面的新闻？"

        val NEWS_TOPIC_QUESTION = Regex("哪方面的新闻|想了解哪方面")

        /** 导航 / 车控 / 天气 / 周边：NavApp 的拦截层负责，规则搜索不抢 */
        val HANDLED_ELSEWHERE = Regex(
            "天气|气温|多少度|下雨|有雨|雨吗|下雪|带伞|冷不冷|热不热|穿什么|" +
                "导航|带我|前往|开到|空调|音量|窗户|附近|周边|周围|旁边|沿途|顺路"
        )

        /** 「去张掖西站」这种裸动词短语是导航指令，不是检索 */
        val BARE_NAV = Regex("^(去|到|回)\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{2,}$")
        val QUESTION_MARK = Regex("[吗呢？?几多少谁哪什么怎么]")

        /** 用户明确要求检索 */
        val EXPLICIT_SEARCH = Regex(
            "搜索|搜一下|搜搜|帮我搜|查一下|查查|帮我查|查询|网上|百度|谷歌|google|" +
                "(?i)search|look up|latest news"
        )

        /**
         * 会随时间变化的实时事实。
         *
         * ★ 2026-11-09 加。之前只认「新闻 / 多少钱 / 价格」，云端 prompt 里
         *   明确要求搜的限行、门票、营业时间、路况、评分、活动本地全部漏掉，
         *   全部交给 0.6B 凭记忆答 —— 这是「端侧爱瞎说」的最大来源。
         *
         * 维护说明：只放**具体事实类**的词。「推荐」「好吃」这类主观词不放：
         * 它们多半是周边搜索的活儿，放进来只会搜回无关结果。
         */
        val REALTIME_WORDS = Regex(
            "限行|尾号|单双号|门票|票价|预约|开馆|闭馆|营业|几点开门|几点关门|几点开始|几点结束|" +
                "油价|菜价|房价|股价|金价|汇率|涨了|跌了|涨停|跌停|多少钱|什么价|" +
                "路况|堵不堵|拥堵|封路|施工|事故|" +
                "评分|评价|口碑|政策|新规|补贴|社保|公积金|摇号|供暖|停暖|" +
                "上映|排片|演唱会|演出|展览|赛事|马拉松|报名|成绩|放假|" +
                "上市|开售|预售|发布会|怎么回事|发生了什么"
        )

        /** 问题在要「具体某个数」——这类值得多花 4 秒读正文 */
        val DETAIL_WORDS = Regex(
            "多少钱|价格|几点|多少|几个|具体|详细|怎么|为什么|什么时候|多久|哪家|怎么样|限行|门票|营业"
        )

        /** 明确在问一个数字：来源没数字就别硬念 */
        val ASKS_NUMBER = Regex("多少钱|价格|几点|多少|几号|多久|涨了|跌了|一升|一斤")

        /** 带这些词时，「新闻」多半是栏目名或节目名（「新闻联播几点开始」），不走新闻垂类 */
        val DETAIL_QUESTION = Regex("几点|什么时候|多久|多少钱|门票|哪里|哪家|谁|怎么|为什么|多少")

        /** 只剩这些词说明用户没说主题（「今天有什么新闻」），先追问 */
        val NEWS_ONLY = Regex("新闻|要闻|时事|头条|消息|报道|最新|今天|今日|播报|资讯|新鲜事|新动态")

        /** 祈使语：只删这些，内容词一个不动 */
        val LEADING_FILLER = Regex(
            "^(?:请问|麻烦你|麻烦|帮我|帮忙|我想知道|我想了解|我想查|我想看|我想|我要|你能|能不能|可以|给我|那再|再|还|又|那|你)+"
        )
        val TRAILING_FILLER = Regex("(?:一下吧|一下|吧|呢|吗|啊|呀|哦|嘛|谢谢|对不对)+$")
        val INLINE_FILLER = Regex(
            "搜索一下|搜索|搜一下|搜下|搜搜|查一下|查下|查查|查询一下|查询|" +
                "百度一下|百度|谷歌|网上查查|网上|帮我查|帮我搜|告诉我|了解一下|了解|看看|" +
                // 查询词要的是关键词，「多少钱」这种疑问部分搜出来反而更准
                "是多少钱|多少钱|什么价格"
        )
        val TIME_ADVERB = Regex("今天|今日|昨天|前天|现在|目前|当前|刚刚|刚才|最近|最新")
        val VAGUE_ASK = Regex("有什么|有哪些|有啥|是什么|是多少")

        /**
         * 明显是闲聊/情绪表达，不触发搜索。
         *
         * ★ 实测「这新闻真离谱」被「新闻」命中，去搜了「这真离谱」。
         *   这类句子没有疑问或请求的语气，加一张小名单挡掉比调阈值可靠。
         */
        val CHAT_SIGNAL = Regex("真离谱|太离谱|真的假的|无语|服了|好笑|哈哈|太好了|烦死|累死|好累|有点累")

        /**
         * 搜索线程池。
         *
         * 两路并行（本地引擎 + Tavily），还要容得下上一轮超时后仍在跑的废弃任务，
         * 所以给 4 个线程，全部 daemon —— 进程退出不需要等它们。
         * ★ 不要再每次调用新建单线程池：`cancel(true)` 对 HttpURLConnection 无效，
         *   旧池里的请求会一直跑，新建池只会把线程数越堆越多。
         */
        val SEARCH_POOL = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "search-pool").apply { isDaemon = true }
        }

        /** 新闻给备用引擎的等待时间：多等一点，换 Tavily 的发布时间 */
        const val FAST_NEWS_BUDGET_MS = 4_000L

        /** 通用查询给备用引擎的等待时间：实测 32~41ms 就返回，2.5 秒足够 */
        const val FAST_GENERAL_BUDGET_MS = 2_500L

        /** 追问复用上一轮结果的时限 */
        const val SEARCH_CACHE_TTL_MS = 3 * 60 * 1000L

        /** 泛新闻一次念几条。三条标题 ≈ 15 秒，再长开车听不完 */
        const val NEWS_FEED_ITEMS = 3

        /**
         * 本地只带最近 1 轮。
         * 实测：system 462 token 时 prefill 要 3.5 秒，首字延迟已经不可接受；
         * 每多带一轮对话就多几十个 token，而 0.3B 也记不住更远。
         */
        // 和设置里的最大选项（8 轮）对齐，否则选 8 轮实际只带 5 轮，用户看不出来
        const val MAX_TURNS = 8

        /**
         * 系统提示词上限。
         *
         * ★ 云端那份有几千字（工具协议、搜索规则、候选确认），本地一条都用不上，
         *   但每一条都要 prefill。实测 700 字 = 462 token = 3.5 秒首字延迟，
         *   用户等到以为死机。压到 260 字后约 1.5 秒。
         *   本地模式的定位是「离线能聊两句」，不是「功能对齐云端」，
         *   所以这里牺牲人设细节换响应速度。
         */
        const val MAX_SYSTEM_CHARS = 260
    }
}
