package com.navagent.app

import com.navagent.app.BuildConfig.MINIMAX_API_KEY
import com.navagent.app.BuildConfig.MINIMAX_BASE_URL
import com.navagent.core.ChatMessage
import com.navagent.core.Llm
import com.navagent.core.LlmTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * MiniMax 聊天：SSE 流式 + Function Calling。
 *
 * 关于工具调用（2026-11-01 加）：
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
class MinimaxLlm(
    private val tools: List<LlmTool> = emptyList()
) : Llm {

    override fun chat(
        system: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String? {
        if (MINIMAX_API_KEY.isBlank()) {
            Dbg.log("LLM: 没有配置 MINIMAX_API_KEY")
            return "还没配置 MiniMax Key，聊天用不了"
        }

        val messages = JSONArray().apply {
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

        val answer = StringBuilder()
        var round = 0
        while (true) {
            val r = request(messages, allowTools = round == 0, onDelta)
                ?: return answer.toString().trim().ifEmpty { null }

            answer.append(r.text)

            if (r.calls.isEmpty() || round >= MAX_TOOL_ROUNDS) {
                return answer.toString().trim()
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
            for ((c, task) in done) {
                val out = runCatching { task.get(8, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrElse { "工具超时或失败：${it.javaClass.simpleName}" }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", c.id)
                        .put("content", out)
                )
            }
            round++
        }
    }

    // ------------------------------------------------------------ 单轮请求

    private fun request(
        messages: JSONArray,
        allowTools: Boolean,
        onDelta: (String) -> Unit
    ): Round? {
        val payload = JSONObject()
            .put("model", Prefs.chatModel)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", Prefs.maxTokens)

        val withTools = allowTools && tools.isNotEmpty()
        if (withTools) {
            payload.put(
                "tools",
                JSONArray().apply {
                    tools.forEach { t ->
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

        Dbg.log(
            "LLM 请求 model=${Prefs.chatModel} tokens=${Prefs.maxTokens} " +
                "turns=${Prefs.historyTurns} tools=${if (withTools) tools.size else 0}"
        )

        val base = MINIMAX_BASE_URL.trimEnd('/')
        var conn: HttpURLConnection? = null
        val sb = StringBuilder()
        val calls = TreeMap<Int, Call>()
        /** 模型原始输出的字符数，用来判断 ThinkFilter 有没有吞掉东西 */
        var rawChars = 0

        try {
            val t0 = System.currentTimeMillis()
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $MINIMAX_API_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Dbg.log("LLM HTTP $code: ${err.take(300)}")
                return null
            }

            conn.inputStream.bufferedReader().use { reader ->
                var firstToken = true
                val filter = ThinkFilter()
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
            }

            Dbg.log("LLM 完成 ${sb.length}字（原始 ${rawChars}字）toolCalls=${calls.size}")
            return Round(sb.toString(), calls.values.toList())
        } catch (t: Throwable) {
            Dbg.log("LLM 异常 ${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------ 工具

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

    /**
     * 流式剥 `<think>…</think>`。
     *
     * 为什么需要状态机而不是简单 replace：SSE 的 delta 是任意切的，
     * 实测能看到 `<thi` + `nk>` 这种把标签拆两帧的情况，
     * 而且思考内容本身跨很多帧。
     *
     * m3.1-flash 的思考走独立的 `reasoning_content` 字段（我们本来就不读），
     * 所以以前没暴露这个问题；M2.7 是内联在 `content` 里的，必须拦。
     */
    private class ThinkFilter {

        /** 还没判断完的尾巴：可能是半个开标签，也可能是正在吞的思考内容 */
        private val pending = StringBuilder()
        private var inThink = false

        /** @return 这一段里可以安全上屏/朗读的部分 */
        fun feed(piece: String): String {
            pending.append(piece)
            val out = StringBuilder()
            while (true) {
                if (inThink) {
                    val idx = pending.indexOf(CLOSE)
                    if (idx < 0) {
                        // 思考还没结束：只留可能是 </think> 前缀的尾巴
                        trimToSuffix(pending, CLOSE)
                        break
                    }
                    pending.delete(0, idx + CLOSE.length)
                    inThink = false
                } else {
                    val idx = pending.indexOf(OPEN)
                    if (idx >= 0) {
                        out.append(pending, 0, idx)
                        pending.delete(0, idx + OPEN.length)
                        inThink = true
                        continue
                    }
                    // 没有完整开标签，但末尾可能是 `<thi` 这种半截
                    val keep = suffixLen(pending, OPEN)
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    break
                }
            }
            return out.toString()
        }

        /** 流结束时把没吐出来的最后一点交回去（思考内容丢弃） */
        fun flush(): String {
            var rest = if (inThink) "" else pending.toString()
            if (rest.isNotEmpty()) {
                // 末尾残留的半个标签别交出去 —— 交出去会被朗读成「小于 t h i」
                val keep = maxOf(suffixLenText(rest, OPEN), suffixLenText(rest, CLOSE))
                if (keep > 0) rest = rest.dropLast(keep)
            }
            pending.setLength(0)
            inThink = false
            return rest
        }

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
            const val OPEN = "<think>"
            const val CLOSE = "</think>"
        }
    }

    private companion object {
        /** 最多查两轮工具。再多基本是模型在绕圈，白烧钱 */
        const val MAX_TOOL_ROUNDS = 2
    }
}
