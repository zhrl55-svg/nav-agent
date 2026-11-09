package com.navagent.app

import com.navagent.core.ChatMessage
import com.navagent.core.Llm
import com.navagent.core.LlmTool
import org.json.JSONObject

/**
 * 本地大模型适配层：把 [MnnLocalLlm] 接到 [Llm] 接口上。
 *
 * ## 与云端实现（[OpenAiLlm]）的差别
 * - **没有 Function Calling**。导航/搜索/天气三个工具全部不可用，退化成纯聊天。
 *   这是 0.3B 的能力上限，不是没接——模型原生不支持 tools，硬塞只会输出乱码。
 * - **chat 和 chatText 等价**。云端那边 chat 是「判断意图 → 调工具 → 总结」两轮，
 *   chatText 只跑最后一步；本地没有工具流程，两个方法本来就是同一件事。
 *
 * ## 为什么自己拼 prompt
 * MNN 的 native 侧不会自动套 jinja 模板（实测裸 prompt 只吐 1 个 token），
 * 必须显式按 Qwen 的 `<|im_start|>` 格式拼好再喂进去。
 */
class MnnLlm(
    private val engine: MnnLocalLlm,
    /** 可调用工具。只有支持 <tool_call> 协议的模型才会真的用上（见 supportsToolCalls） */
    private val tools: List<LlmTool> = emptyList()
) : Llm {

    override fun chat(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? {
        // ★ 让设置页的「回复长度」在本地也生效。
        //   不接的话，用户切到本地后调这个值毫无反应——比不显示更让人困惑。
        //   上限卡 512：decode 约 57 t/s，512 token 要 9 秒。
        engine.setMaxNewTokens(Prefs.maxTokens.coerceIn(64, 512))

        // ★ 2026-11-09：规则触发的联网搜索。
        //   0.6B 不会按协议调工具（实测一次都没成），但「该不该搜」用关键词判断足够准，
        //   而且比让模型决策更省 —— 不搜的时候一个 token 都不多花。
        //   命中后先搜、再把结果塞进 prompt 让它总结，等于把工具调用拆成「规则 + 总结」两步。
        if (Prefs.toolsEnabled && Prefs.webSearchEnabled()) {
            val lastUser = history.lastOrNull { it.role == ChatMessage.Role.USER }?.text.orEmpty()
            val q = ruleSearchQuery(lastUser)
            if (q != null) {
                val tool = tools.firstOrNull { it.name == "web_search" }
                if (tool != null) {
                    val raw = fastSearch(q, tool)
                    Dbg.log("本地规则搜索 query=$q chars=${raw.length}")
                    // ★ 默认直出：实测 0.6B 4bit 拿到资料也会回「没有查到相关数据」，
                    //   而且它一旦这么回，用户就白等 4 秒。资料就在眼前却用不上，
                    //   这不是提示词能救的（试过放 system、放问题后面，都不行）。
                    //   直出是确定性的：标题 + 摘要，一句话念完。
                    //   想要「像人一样总结」的，打开设置里的「本地工具调用」再试。
                    // ★ 2026-11-09：标题只有一句「XX新闻 - 网易」，念出来没有信息量。
                    //   搜完再把第一条的正文打开，取开头一段念 —— 这才是「搜索结果」该有的样子。
                    // 只在问题要「具体信息」时才去读正文：
                    //   通用新闻（「国内新闻」）念标题就够，为它等 4 秒不值得。
                    val wantsDetail = Regex("多少钱|价格|几点|多少|几个|具体|详细|怎么|为什么|什么时候|多久|哪家|怎么样")
                        .containsMatchIn(q)
                    val article = if (wantsDetail) readTopArticle(raw, q) else null
                    if (!Prefs.localToolsEnabled) return digest(raw, article)
                    val prompt = buildPrompt(system, history, null, searchContext = raw)
                    val out = engine.generate(prompt, keepHistory = false) { onDelta(it) }?.trim()
                    // ★ 4bit 模型看到几百字搜索结果时常直接吐空（decode_len=4）。
                    //   返回空串会让 ChatEngine 判定失败并重试 —— 而重试会把整条流程
                    //   再跑一遍，等于又搜一次（实测白等 10 秒）。
                    //   所以这里必须有兜底：模型不开口就自己念结果。
                    // 有资料却回「找不到」——小模型常见，直接改用直出
                    val gaveUp = out == null || out.isBlank() ||
                        Regex("找不到|没有找到|无法找到|没有相关|没有查到|查不到|没查到|无法提供|不了解")
                            .containsMatchIn(out)
                    if (!gaveUp) return out
                    Dbg.log("本地规则搜索：模型没答上来（${out?.length ?: 0}字），改直出")
                    return digest(raw)
                }
            }
        }

        val active = activeTools()
        if (active.isEmpty()) {
            // trim：Qwen3 常以 "\n\n" 开头，界面上就是一个空行
            return engine.generate(buildPrompt(system, history, null), keepHistory = false) { onDelta(it) }?.trim()
        }

        // 第一轮：带工具清单，模型通常先输出 <tool_call> 而不是答案
        val first = buildPrompt(system, history, active)
        val out1 = engine.generate(first, keepHistory = false) { onDelta(it) } ?: return null
        val call = parseToolCall(out1) ?: return stripToolMarkup(out1)

        // 执行工具，把结果按 Qwen3 的 <tool_response> 格式塞回去再跑一轮
        val result = runTool(call)
        val second = buildString {
            append(first)
            append(out1.trim()).append("<|im_end|>\n")
            append("<|im_start|>user\n<tool_response>\n")
            append(result)
            append("\n</tool_response><|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        return engine.generate(second, keepHistory = false) { onDelta(it) }?.trim()
    }

    /**
     * 本地能用的工具。
     *
     * ★ 只放 `web_search` 一个。本地没有 Function Calling 的框架兜底，
     *   工具 schema 全都要进 prompt，每多一个就多几十 token 的 prefill；
     *   实测只带 1 个工具时首字 1.3s，带 5 个要 3s 以上。
     *   导航本来就走规则（interceptNavigation），不需要模型来调。
     */
    private fun activeTools(): List<LlmTool> {
        if (tools.isEmpty() || !Prefs.toolsEnabled) return emptyList()
        // 默认关：实测 0.6B 不按协议输出，且 prompt 变长把首字拖到 7–18 秒
        if (!Prefs.localToolsEnabled) return emptyList()
        if (!engine.supportsToolCalls) return emptyList()
        return tools.filter { it.name == "web_search" && Prefs.webSearchEnabled() }
    }

    /**
     * 中文查询走备用引擎（搜狗/百度/Bing，实测 32~41ms），
     * Tavily 在手机网络下要 8~11 秒 —— 开车场景等不起。
     */
    private fun fastSearch(query: String, tool: LlmTool): String {
        val hasCjk = query.any { it.code in 0x4E00..0x9FFF }
        if (hasCjk) {
            val fast = runCatching { FallbackWebSearch.search(query) }.getOrNull()
            if (fast != null && fast.items.isNotEmpty()) {
                Dbg.log("规则搜索走备用引擎 ${fast.engine}")
                return FallbackWebSearch.render(fast, query)
            }
        }
        return runTool(ToolCall("web_search", JSONObject().put("query", query).toString()))
    }

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
            .filter { it.isNotEmpty() }
            .filterNot { junk.containsMatchIn(it) }
            .filterNot { it.matches(Regex("https?://\\S+")) }
            .joinToString(" ")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return body.takeIf { it.length >= 40 }
    }

    /**
     * 直出：把搜索结果念出来。
     *
     * 取第一条的标题 + 摘要（截 50 字），有第二条就补一句「还有一条」。
     * 不做摘要改写 —— 0.6B 改写出来的东西比原文更难懂。
     */
    private fun digest(raw: String, article: String? = null): String {
        // 有正文就念正文开头：比标题有信息量，也不用让模型改写
        val body = article?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (body.length >= 40) {
            val head = body.take(110)
            val cut = head.indexOfFirst { it == '。' || it == '！' || it == '？' }
            val sentence = if (cut in 20 until head.length) head.substring(0, cut + 1) else head
            return "查到：$sentence"
        }
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < lines.size && items.size < 2) {
            if (lines[i].startsWith("- ")) {
                // 搜索结果标题常带「- 网易订阅」「| 新浪」这类站点尾巴，
                // TTS 念出来很怪，去掉
                val title = lines[i].removePrefix("- ").substringBefore("（")
                    .replace(Regex("\\s*[-|｜—–]\\s*[^-|｜—–]{1,12}$"), "")
                    .replace("|", " ").trim()
                val snippet = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("- ") }
                    ?.replace(Regex("\\s+"), " ")?.take(50).orEmpty()
                if (title.isNotBlank()) items += title to snippet
            }
            i++
        }
        if (items.isEmpty()) return "搜了一下，但没拿到能念的内容。"
        val first = items[0]
        val sb = StringBuilder("查到一条：").append(first.first)
        if (first.second.isNotBlank()) sb.append("。").append(first.second)
        return sb.toString()
    }

    /**
     * 判断这句话要不要联网搜，要的话给出查询词。
     *
     * 为什么用规则而不是让模型决定：实测 0.6B 既不按 `<tool_call>` 也不按裸 JSON 输出，
     * 让它判断等于不搜。关键词判断在这个场景够用 —— 开车时说的话本来就很短。
     * 天气和导航由 NavApp 的拦截层先处理，走不到这里。
     */
    private fun ruleSearchQuery(text: String): String? {
        val t = text.trim()
        if (t.length < 2) return null
        // 导航/天气/车控意图不抢（正常已被拦截层吃掉，这里是兜底）
        // 天气类先排除：这类交给 NavApp 的天气规则，别抢
        if (Regex("天气|气温|多少度|下雨|有雨|雨吗|下雪|带伞|冷不冷|热不热|穿什么|" +
                "导航|带我|前往|开到|^(去|到|回)|空调|音量|窗户").containsMatchIn(t)) return null

        val hit = Regex(
            "搜索|搜一下|搜搜|帮我搜|查一下|查查|帮我查|网上|最新|新闻|多少钱|价格|怎么回事|发生了什么|" +
                "(?i)^search|look up|latest news"
        ).containsMatchIn(t)
        if (!hit) return null

        // 去掉祈使词，留下查询内容
        val q = t.replace(Regex("帮我|请|你|能不能|可以|搜索|搜一下|搜搜|查一下|查查|一下|网上|告诉我|看看"), "")
            .replace(Regex("(?i)^search|look up|for "), "")
            .trim(' ', '，', ',', '。', '？', '?', '的')
        return q.ifBlank { t }
    }

    private data class ToolCall(val name: String, val args: String)

    /**
     * 解析工具调用。两种格式都收：
     *  1. 官方 `<tool_call>{...}</tool_call>`
     *  2. 裸的一行 JSON（当前提示词用的这个 —— 特殊 token 会被解码器丢掉）
     */
    private fun parseToolCall(text: String): ToolCall? {
        val tagged = Regex("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        val json = tagged ?: Regex("\\{[^{}]*\"name\"\\s*:\\s*\"[^\"]+\"[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return null
        val args = obj.opt("arguments") ?: obj.opt("args") ?: return ToolCall(name, "{}")
        return ToolCall(name, args.toString())
    }

    private fun stripToolMarkup(text: String): String =
        text.replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\{[^{}]*\"name\"\\s*:\\s*\"[^\"]+\"[^{}]*\\}", RegexOption.DOT_MATCHES_ALL), "")
            .trim()

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
        toolList: List<LlmTool>?,
        searchContext: String? = null
    ): String = buildString {
        append("<|im_start|>system\n")
        append(compactSystem(system, hasTools = !toolList.isNullOrEmpty()))
        if (!toolList.isNullOrEmpty()) append(toolsBlock(toolList))
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
        val lastUserIdx = turns.indexOfLast { it.role == ChatMessage.Role.USER }
        val noThink = if (engine.wantsNoThink) "/no_think" else ""
        turns.forEachIndexed { i, m ->
            val role = if (m.role == ChatMessage.Role.USER) "user" else "assistant"
            append("<|im_start|>").append(role).append('\n')
            append(m.text.trim())
            // ★ 资料必须贴在问题后面，不能放 system。
            //   实测放 system 时模型当没看见，回「找不到相关的搜索内容」；
            //   小模型的注意力基本只覆盖最近一小段，隔一整个 system 就丢了。
            if (i == lastUserIdx && !searchContext.isNullOrBlank()) {
                append("\n\n【参考资料】\n")
                append(searchContext.take(500))
                append("\n\n请只根据上面的资料，用一两句口语回答。资料没提到就说没查到，不要编。")
            }
            // 换行再跟开关：紧贴中文容易被当成正文的一部分（「价格/no_think」）
            if (i == lastUserIdx && noThink.isNotEmpty()) append("\n").append(noThink)
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /**
     * 按 Qwen3 官方模板拼工具段。
     *
     * 模板原文（llm_config.json 的 jinja）就是这套格式，这里照抄：
     * 少了 `<tools>` 外壳或 `<tool_call>` 示例，模型会退回普通聊天，不会调工具。
     */
    private fun toolsBlock(list: List<LlmTool>): String = buildString {
        append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags://n<tools>")
        for (t in list) {
            append("\n{\"type\":\"function\",\"function\":{\"name\":\"")
            append(t.name)
            append("\",\"description\":\"")
            append(t.description.replace("\"", "'").replace("\n", " "))
            append("\",\"parameters\":")
            append(t.parametersJson())
            append("}}")
        }
        append("\n</tools>\n\n")
        // ★ 不用官方的 <tool_call> 标签：它在 Qwen3 词表里是**特殊 token**，
        //   MNN 解码时会把特殊 token 丢掉，模型明明调了工具，我们这边只拿到空字符串
        //   （实测 decode_len=4、文本为空）。改成让它直接吐一行 JSON，纯文本可解析。
        append("When you need to call a function, reply with ONLY one line of JSON, no other text://n")
        append("{\"name\": \"<function-name>\", \"arguments\": {<args-json-object>}}")
    }

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
    private fun compactSystem(system: String, hasTools: Boolean = false): String {
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
        // ★ 这里必须和「有没有给工具」一致。
        //   之前无论有没有工具都写「没有联网和导航工具」，结果工具块刚加进去，
        //   模型被这句劝退：prompt 里 712 token 带着 <tools>，输出却是一句
        //   「没有最新消息的内容」——一次工具都没调。
        return head +
            if (hasTools) {
                "\n（当前是离线本地模式，只有 web_search 能联网查资料；" +
                    "问题涉及最新消息、实时数据时先调用它，不要凭记忆编。" +
                    "不要重复用户说的话。）\n"
            } else {
                // ★ 别写「或反问」：0.6B 会把它当成首选策略，
                //   用户说「你查一下钢材价格」，它回「好呀！需要我帮你查吗？」——
                //   等于没答。同理也不能让它复述用户的话（会变成自问自答）。
                "\n（当前是离线本地模式，没有联网和导航工具，不要声称自己能查东西。" +
                    "用户问什么就直接回答，不要反问用户想查什么，也不要重复用户的话；" +
                    "不知道就直说不知道。）\n"
            }
    }

    private companion object {
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
