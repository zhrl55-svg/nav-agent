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
 *   第 2 轮不带 tools 再流式跑，接着播报答案
 *   最多 [MAX_TOOL_ROUNDS] 轮，防止模型自己跟自己绕圈
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
            val outcome = request(messages, allowTools = round == 0, onDelta)

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
            answer.append(r.text)

            if (r.calls.isEmpty() || round >= MAX_TOOL_ROUNDS) {
                lastDigest = null
                return answer.toString().trim().ifEmpty { null }
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
                val out = runCatching { task.get(8, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrElse { "工具超时或失败：${it.javaClass.simpleName}" }
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
        val withTools = allowTools && active.isNotEmpty() && Prefs.toolsEnabled
        if (withTools) {
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
            payload.put("tool_choice", "auto")
        }

        val base = Prefs.chatBaseUrl().trimEnd('/')
        val apiKey = Prefs.chatApiKey()

        Dbg.log(
            "LLM 请求 $base model=${Prefs.chatModel} tokens=${Prefs.maxTokens} " +
                "turns=${Prefs.historyTurns} tools=${if (withTools) active.size else 0} " +
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
                val filter = StreamFilter()
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
                if (filter.toolText.isNotBlank()) {
                    val n = parseTextToolCalls(filter.toolText.toString(), calls)
                    Dbg.log("文本形式的工具调用：解析到 $n 个")
                }
            }

            Dbg.log("LLM 完成 ${sb.length}字（原始 ${rawChars}字）toolCalls=${calls.size}")
            return Attempt.Ok(Round(sb.toString(), calls.values.toList()))
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
        Dbg.log("工具调用 ${c.name}(${c.args.toString().take(200)})")
        val out = runCatching { tool.execute(c.args.toString()) }
            .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
        Dbg.log("工具返回 ${out.take(200)}")
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
            calls[TEXT_CALL_BASE + n] = c
            n++
        }
        return n
    }

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
    private class StreamFilter {

        /** 还没判断完的尾巴：可能是半个开标签，也可能是正在吞的内容 */
        private val pending = StringBuilder()
        private var mode = MODE_TEXT

        /** 被吞掉的工具调用正文，上层解析 */
        val toolText = StringBuilder()

        /** @return 这一段里可以安全上屏/朗读的部分 */
        fun feed(piece: String): String {
            pending.append(piece)
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
                    val idx = pending.indexOf(CLOSE_TOOL)
                    if (idx < 0) {
                        val keep = suffixLen(pending, CLOSE_TOOL)
                        toolText.append(pending, 0, pending.length - keep)
                        pending.delete(0, pending.length - keep)
                        break
                    }
                    toolText.append(pending, 0, idx)
                    pending.delete(0, idx + CLOSE_TOOL.length)
                    mode = MODE_TEXT
                    continue
                }

                // 正文：找下一个开标签，谁先出现就进哪个模式
                val iThink = pending.indexOf(OPEN_THINK)
                val iTool = pending.indexOf(OPEN_TOOL)
                val idx: Int
                val tag: String
                if (iThink >= 0 && (iTool < 0 || iThink < iTool)) {
                    idx = iThink; tag = OPEN_THINK
                } else if (iTool >= 0) {
                    idx = iTool; tag = OPEN_TOOL
                } else {
                    // 没有完整开标签，但末尾可能是 `<thi` 这种半截
                    val keep = maxOf(suffixLen(pending, OPEN_THINK), suffixLen(pending, OPEN_TOOL))
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    break
                }
                out.append(pending, 0, idx)
                pending.delete(0, idx + tag.length)
                mode = if (tag == OPEN_THINK) MODE_THINK else MODE_TOOL
            }
            return stripSpecial(out.toString())
        }

        /** 流结束时把没吐出来的最后一点交回去（思考/工具内容丢弃） */
        fun flush(): String {
            var rest = if (mode == MODE_TEXT) pending.toString() else ""
            if (rest.isNotEmpty()) {
                // 末尾残留的半个标签别交出去 —— 交出去会被朗读成「小于 t h i」
                val keep = maxOf(suffixLenText(rest, OPEN_THINK), suffixLenText(rest, OPEN_TOOL))
                if (keep > 0) rest = rest.dropLast(keep)
            }
            pending.setLength(0)
            mode = MODE_TEXT
            return stripSpecial(rest)
        }

        /** 去掉 chat template 的保留 token */
        private fun stripSpecial(s: String): String =
            if (s.indexOf('|') < 0) s else SPECIAL_TOKEN.replace(s, "")

        private fun suffixLen(sb: StringBuilder, tag: String): Int =
            suffixLenText(sb.toString(), tag)

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

            const val OPEN_THINK = "<think>"
            const val CLOSE_THINK = "</think>"
            const val OPEN_TOOL = "<tool_call>"
            const val CLOSE_TOOL = "</tool_call>"

            /** `<|im_end|>`、`<|minimax|>` 这类保留 token，最长也就二十来个字符 */
            val SPECIAL_TOKEN = Regex("<\\|[^<>|]{0,24}\\|>")
        }
    }

    private companion object {
        /** 最多查两轮工具。再多基本是模型在绕圈，白烧钱 */
        const val MAX_TOOL_ROUNDS = 2

        /** 重试摘要里单个工具结果的上限 */
        const val DIGEST_PER_TOOL = 1_200

        /** 重试摘要总量上限。重试就是要小，大了必然又超时 */
        const val DIGEST_TOTAL = 2_400

        /** 文本工具调用在 calls 里的起始下标，避开原生分片的 0/1/2 */
        const val TEXT_CALL_BASE = 100
    }
}
