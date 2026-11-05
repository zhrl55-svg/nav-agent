package com.navagent.app

import com.navagent.core.ChatMessage
import com.navagent.core.Llm
import com.navagent.core.LlmTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * 对话大脑：OpenAI 兼容的 `/chat/completions`，SSE 流式 + Function Calling。
 *
 * ★ 2026-11-02 从 `MinimaxLlm` 改名并解耦。
 *
 *   原来只认 MiniMax，地址和 Key 跟语音识别/合成共用一套。接本地模型（Ollama、
 *   LM Studio、vLLM、llama.cpp）就会互相打坏：改对话地址 = 语音也一起废掉。
 *   现在地址/Key 全部走 `Prefs.chatBaseUrl()` / `Prefs.chatApiKey()`，跟语音分开。
 *
 *   协议本身本来就是 OpenAI 那套，MiniMax 只是其中一个实现 —— 只要对方支持
 *   `POST {base}/chat/completions` + `stream:true` + `tools`，这里就能直接跑。
 *   本地小模型常常不支持 function calling，那种情况在设置里把「工具调用」关掉，
 *   退化成纯聊天，至少不会得到一堆"我这就帮你导航"的空话。
 *
 * 关于工具调用：
 *   MiniMax 的 API **没有**内置联网 —— `MiniMax-M2.5-Online` / `M2.5-Search` 这些
 *   模型名实测全是 400，`tools:[{"type":"web_search"}]` 也被拒（要求 function 定义）。
 *   但标准 OpenAI 格式的 `tools` / `tool_calls` 是支持的。
 *   所以联网只能自己接：模型判断该查 → 我们执行工具 → 结果回填 → 模型总结。
 *
 * 两轮请求的顺序：
 *   第 1 轮带 tools 流式跑，边流边播（模型通常先说"我帮你查一下"）
 *   有 tool_calls 就执行，把结果作为 role=tool 回填
 *   第 2 轮仍带 tools 但设置 tool_choice=none，接着播报答案
 *   工具只允许在第一轮调用；拿到结果后只做一次自然语言总结，防止模型绕圈。
 */
class OpenAiLlm(
    private val tools: List<LlmTool> = emptyList()
) : Llm {

    /**
     * 上一轮工具查到的结果。
     *
     * ★ 失败重试时还要用它：搜索成功了、总结那一轮被拒，结果不能白搜一次。
     *   [chatText] 会把它拼进 system prompt，让重试能真的回答问题。
     */
    @Volatile
    private var lastDigest: String? = null

    override fun chat(
        system: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String? {
        // 每轮开头清掉，避免上一轮的搜索残留到这一轮
        lastDigest = null

        // 每次请求都从 Prefs 读：设置页改完立刻生效，不用重启 App。
        val apiKey = Prefs.chatApiKey()
        if (apiKey.isBlank() && !Prefs.chatEndpointIsCustom()) {
            Dbg.log("LLM: 没有配置 API Key（设置页没填，编译期也没注入）")
            return "还没配置对话模型，去设置页填一个 Key，或者接一个本地模型"
        }

        val messages = buildMessages(system, history)
        val answer = StringBuilder()
        var round = 0
        /** 工具结果是否已经精简过一次（避免反复重试） */
        var slimmed = false

        while (true) {
            // ★ 2026-11-05：允许**多轮**工具调用。
            //   原来只有第 1 轮能调工具（第 2 轮起 tool_choice=none），模型最多搜一次，
            //   「搜 → 读正文 → 发现不对 → 换个词再搜」这套真正的调研做不了。
            //   现在前 MAX_TOOL_ROUNDS 轮都能调工具，到上限后强制总结，防止绕圈。
            //
            // ★ 播报策略：只有**第 1 轮**实时播报（模型通常先说「我帮你查一下」，
            //   是有用的过渡语）；中间轮静音执行，否则模型每轮都说一句「我再查一下」，
            //   语音会重复播报好几次。
            //   最后一轮（没有 tool_calls 的那轮）若处于静音状态，把整段文本一次性播出来
            //   —— 宁可晚一点，不要丢答案。
            val speakThisRound = (round == 0)
            val outcome = request(
                messages,
                allowTools = round < MAX_TOOL_ROUNDS,
                onDelta = if (speakThisRound) onDelta else ({ _: String -> })
            )

            if (outcome is Attempt.Err) {
                // ① 第二轮被内容审核拒（422）：触发点几乎都在工具结果的正文里。
                //    把正文丢掉、只留标题和来源，再要一次 —— 比整轮重来省一次搜索，
                //    也比直接失败多一次机会。
                if (outcome.code == 422 && round > 0 && !slimmed) {
                    Dbg.log("LLM 第二轮 422，改用精简工具结果重试")
                    slimToolMessages(messages)
                    slimmed = true
                    continue
                }
                // ② 其它失败：交给上层。
                //    ★ 这里必须返回 null，不能把前面几轮的半截话当成功返回。
                //    以前返回 `answer.toString()`，于是「我帮你查一下」被当成完整回复，
                //    上层既不重试也不提示 —— 用户听到一句话之后就是一片死寂，
                //    这就是"新闻搜索经常失败"最直接的体感来源。
                Dbg.log("LLM 失败 code=${outcome.code} round=$round（${outcome.detail.take(120)}）")
                return null
            }

            val r = (outcome as Attempt.Ok).round
            // ★ 只有会播报的轮次（第 1 轮）和最终答案轮才进 answer。
            //   中间轮模型说的是「我再查一下」这类过渡语，混进最终回答会被 TTS 念出来。
            //   （最后一轮若静音，会在上面统一补播。）
            if (speakThisRound || r.calls.isEmpty()) answer.append(r.text)

            // 没有 tool_calls = 模型在出答案，这一轮就是终局。
            // ★ 2026-11-05：原来这里还有 `|| round > 0`，即第 2 轮起无条件收尾。
            //   现在允许多轮，只要模型还要工具就继续；到 MAX_TOOL_ROUNDS 上限时
            //   request() 会用 tool_choice=none 逼它总结，tool_calls 自然为空。
            if (r.calls.isEmpty()) {
                // 静音轮（中间轮）走到这里，说明它其实是在出最终答案：
                // 把这一段一次性播出去，别让用户只听到半句。
                if (!speakThisRound && r.text.isNotBlank()) onDelta(r.text)
                val text = answer.toString().trim()
                // 总结为空时保留工具结果，让 ChatEngine 的重试可以通过
                // chatText() 直接基于已经搜到的资料回答，避免白搜一次。
                if (text.isNotEmpty()) lastDigest = null
                return text.ifEmpty { null }
            }

            // 这里故意不补「我查一下」之类的过渡语 —— 用户嫌啰嗦，宁可静默等结果。

            // 1) 把模型的调用意图回填成 assistant 消息
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", r.text)
                    .put(
                        "tool_calls",
                        JSONArray().apply {
                            r.calls.forEach { c ->
                                put(
                                    JSONObject()
                                        .put("id", c.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", c.name)
                                                .put("arguments", c.args.toString())
                                        )
                                )
                            }
                        }
                    )
            )
            // 2) 每个工具的结果作为 role=tool 回填
            //    并发执行：模型一次调好几个是常态（实测「周围有什么好吃的」会同时发
            //    「餐厅」和「美食」两条），串行要 450ms，并发只要 230ms。
            //    结果必须按原顺序回填，tool_call_id 才对得上。
            val done = r.calls.map { c ->
                val task = java.util.concurrent.FutureTask<String> { runTool(c) }
                Thread(task, "tool-${c.name}").apply { isDaemon = true }.start()
                c to task
            }
            val results = ArrayList<Pair<Call, String>>(done.size)
            for ((c, task) in done) {
                val out = runCatching {
                    task.get(TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                }.getOrElse {
                    // 超时后必须取消任务，否则工具线程可能继续算路并在模型已收到
                    // 「工具超时」之后突然启动导航。
                    task.cancel(true)
                    "工具超时或失败：${it.javaClass.simpleName}"
                }
                results += c to out
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", c.id)
                        .put("content", out)
                )
            }
            // 记下来，万一下一轮失败还能靠 chatText 把结果用上
            lastDigest = digestOf(results)
            round++
        }
    }

    /**
     * 只跑最后一段「把结果说成人话」的请求，不判断意图、不调工具。
     *
     * 用于失败重试。如果这一轮之前工具已经跑过（[lastDigest] 有值），
     * 就把它拼进 system prompt —— 重试的目的是把已经查到的东西说出来，
     * 不是重新查一遍。
     */
    override fun chatText(
        system: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String? {
        val digest = lastDigest
        lastDigest = null

        val apiKey = Prefs.chatApiKey()
        if (apiKey.isBlank() && !Prefs.chatEndpointIsCustom()) return null

        val sys = if (digest.isNullOrBlank()) system else buildString {
            append(system)
            append("\n\n【已经查到的资料】下面这些是刚才已经拿到的结果，")
            append("直接据此回答，不要再查：\n")
            append(digest)
            append("\n（注意：上面说的「没查到就说没查到」在这里不适用，资料已经在这儿了。）")
        }

        val outcome = request(buildMessages(sys, history), allowTools = false, onDelta)
        return (outcome as? Attempt.Ok)?.round?.text?.trim()?.ifEmpty { null }
    }

    // ------------------------------------------------------------ 请求组装

    private fun buildMessages(system: String, history: List<ChatMessage>): JSONArray =
        JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            // 只带最近 N 轮，控制 token；开车闲聊不需要长上下文。
            // N 在设置页可调：对话模型记性差就调大，嫌慢就调小。
            history.takeLast(Prefs.historyTurns * 2).forEach {
                put(
                    JSONObject()
                        .put("role", if (it.role == ChatMessage.Role.USER) "user" else "assistant")
                        .put("content", it.text)
                )
            }
        }

    /**
     * 把 role=tool 的内容瘦身：只留标题行，丢掉正文。
     *
     * 第二轮被审核拒绝时用。实测触发点是正文里的具体词句，标题和来源一般安全；
     * 而且请求体一下子小掉一大半，超时概率也跟着降。
     */
    private fun slimToolMessages(messages: JSONArray) {
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "tool") continue
            val slim = m.optString("content").lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("  ") }
                .take(6)
                .joinToString("\n")
                .take(400)
            m.put("content", slim.ifBlank { "（搜索结果的正文被丢弃，只剩来源）" })
        }
    }

    /** 工具结果的摘要，给重试用。单个工具截断，总量也截断 —— 重试的请求必须小 */
    private fun digestOf(results: List<Pair<Call, String>>): String? {
        if (results.isEmpty()) return null
        val sb = StringBuilder()
        for ((c, out) in results) {
            if (sb.length >= DIGEST_TOTAL) break
            sb.append("[").append(c.name).append("]\n")
            sb.append(out.take(DIGEST_PER_TOOL)).append("\n\n")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    // ------------------------------------------------------------ 单轮请求

    private fun request(
        messages: JSONArray,
        allowTools: Boolean,
        onDelta: (String) -> Unit
    ): Attempt {
        val payload = JSONObject()
            .put("model", Prefs.chatModel)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", Prefs.maxTokens)

        // 工具调用总开关：本地小模型不会 function calling 时关掉，退化成纯聊天
        val active = activeTools()
        val toolsAvailable = active.isNotEmpty() && Prefs.toolsEnabled
        if (toolsAvailable) {
            payload.put(
                "tools",
                JSONArray().apply {
                    active.forEach { t ->
                        put(
                            JSONObject()
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", t.name)
                                        .put("description", t.description)
                                        .put("parameters", JSONObject(t.parametersJson()))
                                )
                        )
                    }
                }
            )
            // 工具定义在总结轮仍要保留，否则部分 OpenAI 兼容服务会退回原始
            // chat template，输出 `]<]minimax[>[<tool_call>` 这类协议碎片。
            // tool_choice=none 明确告诉模型：可以看见工具，但本轮禁止调用。
            payload.put("tool_choice", if (allowTools) "auto" else "none")
        }

        val base = Prefs.chatBaseUrl().trimEnd('/')
        val apiKey = Prefs.chatApiKey()

        Dbg.log(
            "LLM 请求 $base model=${Prefs.chatModel} tokens=${Prefs.maxTokens} " +
                "turns=${Prefs.historyTurns} tools=${if (toolsAvailable) active.size else 0} " +
                "auth=${if (apiKey.isBlank()) "无" else "有"}"
        )

        var conn: HttpURLConnection? = null
        val sb = StringBuilder()
        val calls = TreeMap<Int, Call>()
        /** 模型原始输出的字符数，用来判断 ThinkFilter 有没有吞掉东西 */
        var rawChars = 0

        try {
            val t0 = System.currentTimeMillis()
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                // 本地模型要连局域网，6 秒够；连不上就是地址/防火墙错了，早点报
                connectTimeout = 6_000
                // ★ 首轮输入小（只有历史 + 工具定义），25 秒兜住；
                //   带搜索结果的那一轮输入能到 6000 字，实测出现过 38 秒 SocketTimeout，
                //   45 秒兜住，再长就是真挂了。
                readTimeout = if (allowTools) 25_000 else 45_000
                doOutput = true
                // 本地服务通常不校验 Key，留空就别发一个假的
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                // Minis 等网关可据此启用服务端记忆；普通 OpenAI 兼容接口会忽略未知头。
                // 真正跨服务商不丢上下文依赖 App 本地 SQLite，而不是这两个头。
                setRequestProperty("X-User-Id", Prefs.memoryUserId())
                setRequestProperty("X-Conversation-Id", Prefs.conversationId())
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Dbg.log("LLM HTTP $code: ${err.take(300)}")
                return Attempt.Err(code, err)
            }

            conn.inputStream.bufferedReader().use { reader ->
                var firstToken = true
                val filter = StreamFilter(active.map { it.name }.toSet())
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    val delta = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                    }.getOrNull() ?: continue

                    // ① 工具调用分片：首片带 id/name，后续只追加 arguments 的字符
                    delta.optJSONArray("tool_calls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val tc = arr.optJSONObject(i) ?: continue
                            val acc = calls.getOrPut(tc.optInt("index", 0)) { Call() }
                            tc.optString("id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                            tc.optJSONObject("function")?.let { f ->
                                f.optString("name").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                f.optString("arguments").takeIf { it.isNotEmpty() }
                                    ?.let { acc.args.append(it) }
                            }
                        }
                    }

                    // ② 正文
                    val text = delta.optString("content").orEmpty()
                    if (text.isEmpty()) continue
                    rawChars += text.length
                    // ★ 必须在这里剥 think，不能等收尾。
                    // ChatEngine 是边流边念的（攒到句号就交给 TTS），等整段回来再剥，
                    // 思考内容早就朗读出去了 —— 用户实测就是听到英文 thinking。
                    val safe = filter.feed(text)
                    if (safe.isEmpty()) continue

                    if (firstToken) {
                        Dbg.log("LLM 首字 ${System.currentTimeMillis() - t0}ms")
                        firstToken = false
                    }
                    sb.append(safe)
                    onDelta(safe)
                }
                // 把过滤器里可能残留的最后半句吐出来
                val tail = filter.flush()
                if (tail.isNotEmpty()) {
                    sb.append(tail)
                    onDelta(tail)
                }
                // ③ 文本形式的工具调用（本地模型常见）
                if (allowTools && toolsAvailable && filter.toolText.isNotBlank()) {
                    // ★ 工具调用正文里也可能夹着 MiniMax 标记，清掉再解析。
                    //   实测真机原文：`]<]minimax[>[<tool_call>{...}<tool_call>
                    //   标记在标签外面，不影响 JSON 括号配对，但留着会污染 name 字段。
                    val cleanToolText = MINIMAX_MARK_RE_OUTER.replace(filter.toolText.toString(), "")
                    val n = parseTextToolCalls(cleanToolText, calls)
                    Dbg.log("文本形式的工具调用：解析到 $n 个")
                }
            }

            val acceptedCalls = if (allowTools && toolsAvailable) {
                calls.values.toList()
            } else {
                emptyList()
            }
            Dbg.log("LLM 完成 ${sb.length}字（原始 ${rawChars}字）toolCalls=${acceptedCalls.size}")
            return Attempt.Ok(Round(sb.toString(), acceptedCalls))
        } catch (t: Throwable) {
            Dbg.log("LLM 异常 ${t.javaClass.simpleName}: ${t.message}")
            return Attempt.Err(0, t.message.orEmpty())
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------ 工具

    /**
     * 这一次请求真正交给模型的工具。
     *
     * 搜索工具会被 [Prefs.webSearchEnabled] 过滤掉：接了本地接口时默认不给 ——
     * 本地那套服务自带检索，模型直接在服务端查完再答；再塞一个 Tavily 进去
     * 等于让它二选一，而 Tavily 那份更慢（5 秒）、更差（中文新闻常是短讯）。
     */
    private fun activeTools(): List<LlmTool> {
        if (tools.isEmpty()) return emptyList()
        if (Prefs.webSearchEnabled()) return tools
        return tools.filter { it.name != "web_search" && it.name != "web_extract" }
    }

    private fun runTool(c: Call): String {
        val tool = tools.firstOrNull { it.name == c.name }
        if (tool == null) {
            Dbg.log("未知工具 ${c.name}")
            return "没有叫 ${c.name} 的工具"
        }
        Dbg.log("工具调用 ${c.name} argsChars=${c.args.length}")
        val out = runCatching { tool.execute(c.args.toString()) }
            .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
        Dbg.log("工具返回 ${c.name} chars=${out.length}")
        return out
    }

    private class Call {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private class Round(val text: String, val calls: List<Call>)

    /** 单轮请求的结果。code=0 表示网络异常 / 解析失败 */
    private sealed class Attempt {
        class Ok(val round: Round) : Attempt()
        class Err(val code: Int, val detail: String) : Attempt()
    }

    // ------------------------------------------------------------ 文本工具调用

    /**
     * 从文本里抠出工具调用并塞进 [calls]。
     *
     * 为什么需要：`tool_calls` 是 OpenAI 的原生字段，但**不是所有模型都会用**。
     * 实测本地跑的模型（以及一部分国产模型）会把调用写成正文文本：
     *     <tool_call>{"name":"web_search","arguments":{"query":"今天新闻"}}</tool_call>
     * 原生解析器只看 `delta.tool_calls`，于是这段 XML 被当成正文念了出来 ——
     * 用户看到聊天框里一大坨 `&lt;tool_call&gt;`，导航却一点没动。
     *
     * 这里兼容三种写法：arguments 是对象 / arguments 是字符串 / 字段名用 parameters。
     *
     * @return 解析成功的个数
     */
    private fun parseTextToolCalls(text: String, calls: TreeMap<Int, Call>): Int {
        var n = 0
        for (json in extractJsonObjects(text)) {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val name = obj.optString("name").ifBlank { obj.optString("tool") }.trim()
            if (name.isEmpty()) continue
            val rawArgs = obj.opt("arguments") ?: obj.opt("parameters") ?: continue
            val argsText = when (rawArgs) {
                is JSONObject -> rawArgs.toString()
                is String -> rawArgs
                else -> continue
            }
            // 只认真的注册过、而且这一次真的开着的工具，避免把普通 JSON 误当成调用
            if (activeTools().none { it.name == name }) continue
            val c = Call()
            // 文本调用没有 id，自己编一个，回填 tool 消息时用得上
            c.id = "text-${calls.size}"
            c.name = name
            c.args.append(argsText)
            calls[nextTextCallIndex(calls)] = c
            n++
        }
        return n + parseXmlToolCalls(text, calls)
    }

    /**
     * MiniMax 的 M2/M3 系列在部分兼容接口上会退回 XML 工具协议：
     *
     *   <invoke name="web_search">
     *     <topic>news</topic>
     *     <time_range>day</time_range>
     *     <query>今天的国际新闻</query>
     *   </invoke>
     *
     * 原生 tool_calls 为空时，这段以前只会被当成正文。这里把它转换成与
     * JSON 文本调用相同的 [Call]，让首轮仍能真正执行搜索。
     */
    private fun parseXmlToolCalls(text: String, calls: TreeMap<Int, Call>): Int {
        if (!text.contains("<invoke", ignoreCase = true)) return 0

        val invokeRegex = Regex(
            """<invoke\s+name\s*=\s*["']([^"']+)["'][^>]*>(.*?)</invoke>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val parameterRegex = Regex(
            """<([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*)>(.*?)</\1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val nameAttrRegex = Regex(
            """name\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        var count = 0
        for (invoke in invokeRegex.findAll(text)) {
            val name = invoke.groupValues[1].trim()
            if (activeTools().none { it.name == name }) continue

            val args = JSONObject()
            for (parameter in parameterRegex.findAll(invoke.groupValues[2])) {
                val tagName = parameter.groupValues[1]
                val key = if (tagName.equals("parameter", ignoreCase = true)) {
                    nameAttrRegex.find(parameter.groupValues[2])
                        ?.groupValues?.get(1)
                        ?.trim()
                        .orEmpty()
                } else {
                    tagName
                }
                if (key.isEmpty()) continue
                args.put(key, xmlUnescape(parameter.groupValues[3].trim()))
            }

            val argsText = if (args.length() > 0) {
                args.toString()
            } else {
                invoke.groupValues[2].trim().takeIf { it.startsWith("{") } ?: continue
            }
            val call = Call().apply {
                id = "xml-${calls.size}"
                this.name = name
                this.args.append(argsText)
            }
            calls[nextTextCallIndex(calls)] = call
            count++
        }
        return count
    }

    private fun nextTextCallIndex(calls: TreeMap<Int, Call>): Int {
        var index = TEXT_CALL_BASE
        while (calls.containsKey(index)) index++
        return index
    }

    private fun xmlUnescape(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    /**
     * 从任意文本里切出完整的 JSON 对象。
     *
     * 不能直接用正则：`arguments` 里面还有一层花括号，正则的非贪婪匹配会在内层
     * 的 `}` 处截断。这里做括号配对，并且跳过字符串里的 `{}` 和转义引号。
     */
    private fun extractJsonObjects(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '{') { i++; continue }
            var depth = 0
            var inStr = false
            var esc = false
            var j = i
            var closed = false
            while (j < s.length) {
                val c = s[j]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) { closed = true }
                        }
                    }
                }
                if (closed) break
                j++
            }
            if (closed) {
                out.add(s.substring(i, j + 1))
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    /**
     * 流式过滤器：把不该给用户看的东西在流里就剥掉。
     *
     * 三件事：
     *  1. `<think>…</think>` —— 思考内容。m3.1-flash 走独立的 reasoning_content 字段，
     *     但 M2.7 和本地模型（DeepSeek-R1 蒸馏、QwQ）是内联在 content 里的，必须拦。
     *  2. `<tool_call>…</tool_call>` —— 文本形式的工具调用。剥掉的同时把内容攒进
     *     [toolText]，交给 [parseTextToolCalls] 去执行。
     *  3. `<|…|>` 这类 chat template 的保留 token —— 念出来是「竖线 minimax 竖线」。
     *
     * 为什么必须是状态机而不是 replace：SSE 的 delta 是任意切的，
     * 实测能看到 `<thi` + `nk>` 把标签拆两帧，`<tool_c` + `all>` 同理。
     */
    private class StreamFilter(private val toolNames: Set<String>) {

        /** 还没判断完的尾巴：可能是半个开标签，也可能是正在吞的内容 */
        private val pending = StringBuilder()
        private var mode = MODE_TEXT

        /** 被吞掉的工具调用正文，上层解析 */
        val toolText = StringBuilder()

        /**
         * 有些兼容接口会丢掉 `<tool_call><invoke ` 开头，只从参数体开始返回：
         *   name="web_search"><topic>news</topic>...
         * 这些片段也必须进入工具正文模式，不能进入界面或 TTS。
         */
        private val orphanToolMarkers: List<String> = buildList {
            for (name in toolNames) {
                add("name=\"$name\"")
                add("name='$name'")
                add("\"name\":\"$name\"")
                add("\"name\": \"$name\"")
            }
            addAll(ORPHAN_TOOL_HINTS)
        }

        /** @return 这一段里可以安全上屏/朗读的部分 */
        fun feed(piece: String): String {
            pending.append(piece)

            // ★★ 第一步：先在 pending 上整体剥离 MiniMax 标记。
            //
            //   为什么必须在 pending 这一层做，而不是在「吐出去的片段」上做：
            //   标记会被 SSE 切成任意几帧（实测真机：
            //     帧1: ]<]minimax[>[
            //     帧2: <tool_call>...
            //     帧3: ]minimax[>[
            //   ）。如果只在单个片段上 replace，
            //   跨帧的那部分永远匹配不上 → 标记漏进 TTS，
            //   用户听到的就是「竖线右方括号 小猪 左方括号」。
            //
            //   在 pending 上做的话，只要标记凑齐就整段消失，
            //   跨多少帧都无所谓。
            stripMinimaxMarks()

            val out = StringBuilder()

            while (true) {
                if (mode == MODE_THINK) {
                    val idx = pending.indexOf(CLOSE_THINK)
                    if (idx < 0) {
                        // 思考还没结束：只留可能是 </think> 前缀的尾巴
                        trimToSuffix(pending, CLOSE_THINK)
                        break
                    }
                    pending.delete(0, idx + CLOSE_THINK.length)
                    mode = MODE_TEXT
                    continue
                }
                if (mode == MODE_TOOL) {
                    // 不同服务商的文本工具协议并不完全一致。除了 </tool_call>，
                    // MiniMax 的 XML 模板还会用 </invoke> 收尾；只认前者会让
                    // 后面的 </invoke> 漏进正文。
                    val close = earliestToken(pending, TOOL_CLOSE_TAGS)
                    if (close == null) {
                        val keep = maxOf(
                            maxSuffixLen(pending.toString(), TOOL_CLOSE_TAGS),
                            minimaxPrefixLen(pending.toString())
                        )
                        toolText.append(pending, 0, pending.length - keep)
                        pending.delete(0, pending.length - keep)
                        break
                    }
                    // 保留闭标签，XML 工具解析需要完整的 <invoke>…</invoke>。
                    toolText.append(pending, 0, close.first + close.second.length)
                    pending.delete(0, close.first + close.second.length)
                    mode = MODE_TEXT
                    continue
                }

                // 正文：找下一个协议片段。除了正常开标签，还要处理服务端
                // 丢掉开标签后留下的 name="web_search"、<topic> 等 XML 残片。
                val iThink = pending.indexOf(OPEN_THINK)
                val toolOpen = earliestToken(pending, TOOL_OPEN_PREFIXES)
                val strayClose = earliestToken(pending, TOOL_CLOSE_TAGS)
                val orphanTool = earliestToken(pending, orphanToolMarkers)
                val events = ArrayList<Triple<Int, Int, String>>(4)
                if (iThink >= 0) events += Triple(iThink, EVENT_THINK, OPEN_THINK)
                toolOpen?.let { events += Triple(it.first, EVENT_TOOL_OPEN, it.second) }
                strayClose?.let { events += Triple(it.first, EVENT_STRAY_CLOSE, it.second) }
                orphanTool?.let { events += Triple(it.first, EVENT_ORPHAN_TOOL, it.second) }

                val event = events.minByOrNull { it.first }
                if (event == null) {
                    // 没有完整开标签，但末尾可能是 `<thi` 这种半截
                    var keep = maxOf(
                        suffixLen(pending, OPEN_THINK),
                        maxSuffixLen(pending.toString(), TOOL_OPEN_PREFIXES),
                        maxSuffixLen(pending.toString(), TOOL_CLOSE_TAGS),
                        maxSuffixLen(pending.toString(), orphanToolMarkers)
                    )
                    // ★ 再加上 MiniMax 标记的前缀尾巴（实测它被切成 5 帧）
                    keep = maxOf(keep, minimaxPrefixLen(pending.toString()))
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    break
                }

                val idx = event.first
                out.append(pending, 0, idx)
                when (event.second) {
                    EVENT_THINK -> {
                        pending.delete(0, idx + event.third.length)
                        mode = MODE_THINK
                    }
                    EVENT_TOOL_OPEN -> {
                        // 开标签可能带属性，例如 <invoke name="web_search">。
                        // 等完整的 '>' 到齐后再进入工具模式。
                        val end = pending.indexOf(">", idx + event.third.length)
                        if (end < 0) {
                            pending.delete(0, idx)
                            break
                        }
                        pending.delete(0, end + 1)
                        mode = MODE_TOOL
                    }
                    EVENT_STRAY_CLOSE -> {
                        pending.delete(0, idx + event.third.length)
                    }
                    EVENT_ORPHAN_TOOL -> {
                        pending.delete(0, idx)
                        mode = MODE_TOOL
                    }
                }
            }
            return stripSpecial(out.toString())
        }

        /** 流结束时把没吐出来的最后一点交回去（思考/工具内容丢弃） */
        fun flush(): String {
            var rest = if (mode == MODE_TEXT) pending.toString() else ""
            if (rest.isNotEmpty()) {
                // 末尾残留的半个标签或 MiniMax 标记别交出去。
                val keep = maxOf(
                    maxSuffixLen(rest, TOOL_OPEN_PREFIXES),
                    maxSuffixLen(rest, TOOL_CLOSE_TAGS),
                    maxSuffixLen(rest, orphanToolMarkers),
                    suffixLenText(rest, OPEN_THINK),
                    minimaxPrefixLen(rest)
                )
                if (keep > 0) rest = rest.dropLast(keep)
            }
            pending.setLength(0)
            mode = MODE_TEXT
            return stripSpecial(rest)
        }

        /** 去掉 chat template 的保留 token */
        /**
         * 去掉不该被朗读的标记。
         *
         * 三类：
         *  1. chat template 保留 token —— `<|im_end|>`、`<|minimax|>` 这类
         *  2. MiniMax 私有分段标记 —— `]<]minimax[>[` / `]minimax[>`
         *  3. 裸的 `minimax` 单词 —— 标记被切碎后可能只剩这个词
         *
         * ★ 为什么必须跨帧处理：SSE 的 delta 是任意切的，实测真机日志里
         *   MiniMax 的标记被切成 5 帧：
         *     帧1: ]<]minimax[>[<tool_c
         *     帧2: all>]minimax[>]<]mi
         *     帧3: nimax[>[<tool_call>\n
         *     帧4: <]minimax[>[<tool_ca
         *     帧5: ll>\n]<]minimax[>[</f
         *   单看任何一帧都匹配不上完整标记 —— 逐帧 replace 等于没做。
         *   所以这里用「跨帧缓冲」：只有确认不是标记前缀的尾巴才吐出去。
         *
         * 还有一个坑：即使一帧里恰好有完整标记，它也可能是**别的标记被切断后的残片**，
         *   所以最后还要再扫一遍裸的 `minimax` 单词兜底。
         */
        private fun stripSpecial(s: String): String {
            if (s.isEmpty()) return s
            var out = s
            if (out.indexOf('|') >= 0) out = SPECIAL_TOKEN.replace(out, "")
            out = MINIMAX_MARK_RE.replace(out, "")
            return out
        }
        private fun suffixLen(sb: StringBuilder, tag: String): Int =
            suffixLenText(sb.toString(), tag)

        private fun maxSuffixLen(s: String, tags: Collection<String>): Int =
            tags.maxOfOrNull { suffixLenText(s, it) } ?: 0

        /**
         * 返回最先出现的 token 及其下标。只比较完整标签；
         * 半截标签由 maxSuffixLen 留在 pending 中等待下一帧。
         */
        private fun earliestToken(sb: StringBuilder, tags: Collection<String>): Pair<Int, String>? {
            var bestIndex = -1
            var bestTag = ""
            for (tag in tags) {
                val index = sb.indexOf(tag)
                if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
                    bestIndex = index
                    bestTag = tag
                }
            }
            return if (bestIndex >= 0) bestIndex to bestTag else null
        }

        /**
         * 末尾有多少个字符**可能**是某个 MiniMax 标记的前缀。
         *
         * ★ 为什么要留尾巴：SSE 的 delta 任意切，实测 MiniMax 标记被切成 5 帧：
         *     帧1: ]<]minimax[>[<tool_c
         *     帧2: all>]minimax[>]<]mi
         *     帧3: nimax[>[<tool_call>\n
         *   如果每帧都直接吐出去，第一帧的 `]<]minimax[>[` 会被切成
         *   `]<]mi` + `nimax[>[` 两半 —— 哪一半都不匹配正则，标记就漏了。
         *
         *   所以吐出去之前先看看末尾：要是正好停在某个标记的前缀上，
         *   就把它留在 pending 里等下一帧。
         *
         * @param s 待检查的文本
         * @return 需要留在缓冲区里的字符数（0 = 全部可以吐）
         */
        /**
         * 在 pending 上剥离 MiniMax 标记。
         *
         * ★ 2026-11-04 真机 raw hex 实测，MiniMax 的输出里这些标记有多种形态：
         *
         *   ① ]<]minimax[>[        —— 独立出现的分段标记（最常见）
         *   ② ]minimax[>            —— 它的闭标记
         *   ③ <tool_call>…</tool_call>
         *   ④ <query>…</query>      —— XML 风格的参数（不是 JSON）
         *
         *   而且它们会被 SSE 切到任意位置，实测同一句话里
         *   标记就横跨 3 帧：`]<]minimax[>[` / `<tool_call>...` / `]minimax[>[`
         *
         * 做法：用一个宽松的正则，把「标记 + 附近的分隔符」整段吃掉。
         * 宁可多吃几个字符，也不能让标记漏进 TTS。
         */
        private fun stripMinimaxMarks() {
            if (pending.length == 0) return
            if (pending.indexOf("minimax") < 0) return

            // 只删除包装标记本身。旧正则会把标记后面的 `<` 一起吃掉，
            // 导致跨帧的 `<tool_c` + `all>` 永远拼不回 `<tool_call>`。
            val s = MINIMAX_MARK_RE.replace(pending.toString(), "")
            if (s == pending.toString()) return
            pending.setLength(0)
            pending.append(s)
        }
        private fun minimaxPrefixLen(s: String): Int {
            if (s.isEmpty()) return 0
            // 标记里可能出现的非字母字符：] < [ > 和零宽空格
            var best = 0
            for (marker in MINIMAX_MARK_VARIANTS) {
                // 找出所有可能是 marker 前缀的尾巴
                val maxLen = minOf(s.length, marker.length - 1)
                for (n in maxLen downTo 1) {
                    if (s.regionMatches(s.length - n, marker, 0, n)) {
                        if (n > best) best = n
                        break
                    }
                }
            }
            return best
        }
        private fun suffixLenText(s: String, tag: String): Int {
            val max = minOf(s.length, tag.length - 1)
            for (n in max downTo 1) {
                if (s.regionMatches(s.length - n, tag, 0, n)) return n
            }
            return 0
        }

        private fun trimToSuffix(sb: StringBuilder, tag: String) {
            val n = suffixLen(sb, tag)
            sb.delete(0, sb.length - n)
        }

        private companion object {
            const val MODE_TEXT = 0
            const val MODE_THINK = 1
            const val MODE_TOOL = 2

            const val EVENT_THINK = 0
            const val EVENT_TOOL_OPEN = 1
            const val EVENT_STRAY_CLOSE = 2
            const val EVENT_ORPHAN_TOOL = 3

            const val OPEN_THINK = "<think>"
            const val CLOSE_THINK = "</think>"
            const val CLOSE_TOOL = "</tool_call>"
            const val CLOSE_INVOKE = "</invoke>"
            const val CLOSE_MINIMAX_TOOL = "</minimax:tool_call>"
            val TOOL_OPEN_PREFIXES = listOf(
                "<minimax:tool_call",
                "<tool_call",
                "<invoke"
            )
            val TOOL_CLOSE_TAGS = listOf(CLOSE_TOOL, CLOSE_INVOKE, CLOSE_MINIMAX_TOOL)
            val ORPHAN_TOOL_HINTS = listOf(
                "<topic>",
                "<time_range>",
                "<query>",
                "topic>",
                "time_range>",
                "query>",
                "\"arguments\":",
                "\"parameters\":"
            )

            /**
             * MiniMax 的分段标记。它们是聊天模板的控制符，不是正文。
             *
             * ★ 这是 2026-11-04 真机抓到的 raw hex 原文（逐码位还原，纯 ASCII，无零宽字符）：
             *   ]<]minimax[>[<tool_call>]minimax[>]<]minimax[>[<tool_call>
             *   <]minimax[>[<tool_call>
             *   ]<]minimax[>[</f
             *
             *   拼起来就是 MiniMax 发给客户端的**文本形式工具调用**：
             *     ]<]minimax[>[<tool_call>{json}<tool_call>
             *   这个前缀标记是 MiniMax 私有的，不是 chat template 的保留 token，
             *   原来的 SPECIAL_TOKEN 正则 `<\|...\|>` 完全匹配不到它 →
             *   它就跟着工具调用正文一起漏到 TTS 里，
             *   用户听到的就是「竖线右方括号 小猪 左方括号」这种乱码。
             *
             *   实测症状：模型最终播报只剩 13 个字符 —— 正好是 `]<]minimax[>[` 的长度。
             */
            /** `<|im_end|>`、`<|minimax|>` 这类保留 token，最长也就二十来个字符 */
            val SPECIAL_TOKEN = Regex("<\\|[^<>|]{0,24}\\|>")

            /**
             * 精确匹配 MiniMax 包装标记，避免吞掉紧随其后的 `<tool_call>`。
             * 前缀可能是 `]<]`、`<]`、`]` 或没有；后缀可能多一个 `[`/`]`。
             */
            val MINIMAX_MARK_RE = Regex("(?:]<]|<]|])?minimax\\[>(?:\\[|])?")
            val MINIMAX_MARK_VARIANTS = listOf(
                "]<]minimax[>[",
                "<]minimax[>[",
                "]minimax[>[",
                "minimax[>[",
                "]<]minimax[>",
                "<]minimax[>",
                "]minimax[>",
                "minimax[>"
            )
        }
    }

    /**
     * MiniMax 私有标记的总正则。
     *
     * ★ 2026-11-04 真机 raw hex 实测抓到的原文：
     *   ]<]minimax[>[<tool_call>]minimax[>]<]minimax[>[<tool_call><LF>
     *   <]minimax[>[<tool_call><LF>]<]minimax[>[</f
     *
     *   形态不统一：开标记有 `]<>]minimax[>[` 和 `<]minimax[>[` 两种（括号数不同），
     *   所以用宽松字符类而不是字面量。
     */

    /** 外层用的 MiniMax 标记正则（与 StreamFilter 内部同款） */
    private val MINIMAX_MARK_RE_OUTER =
        Regex("(?:]<]|<]|])?minimax\\[>(?:\\[|])?")

    private companion object {
        /** 重试摘要里单个工具结果的上限 */
        const val DIGEST_PER_TOOL = 1_200

        /** 重试摘要总量上限。重试就是要小，大了必然又超时 */
        const val DIGEST_TOTAL = 2_400

        /** 文本工具调用在 calls 里的起始下标，避开原生分片的 0/1/2 */
        const val TEXT_CALL_BASE = 100

        /** 覆盖地点搜索 + 城市等待 + 高德算路的最长链路 */
        const val TOOL_TIMEOUT_SECONDS = 30L

        /**
         * 一次对话里最多允许几轮工具调用。
         *
         * ★ 2026-11-05 新增。原来只有 1 轮（搜一次就总结），做不了真正的调研。
         *   3 轮是平衡点：够完成「搜 → 读正文 → 换个词再搜」，
         *   又不至于让语音助手的等待时间失控（每轮都要一次 LLM 往返）。
         *   到上限后 request() 用 tool_choice=none 强制总结，不会无限绕圈。
         */
        const val MAX_TOOL_ROUNDS = 3
    }
}
