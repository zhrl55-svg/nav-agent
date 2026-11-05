package com.navagent.app

import android.content.Context
import com.navagent.core.LlmTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从搜索结果中挑选网页并提取正文，供模型核对细节。
 *
 * ## 两条取正文的路（2026-11-05）
 *
 * 1. **Tavily extract**（首选）：服务端直接抓，快、稳，正文已经是 markdown。
 * 2. **本机 WebView 渲染**（兜底）：Tavily 抓不到正文时，用手机里的浏览器内核
 *    把页面跑起来再取 `innerText` —— 公众号、知乎、各类 SPA 站只有这条路能读。
 *
 * 为什么要兜底：Tavily 的 extract 对「靠 JS 渲染」的页面只会返回空壳，
 * 模型拿到空正文就只能说「没查到细节」，用户体感就是「搜了但没用」。
 */
class WebExtractTool(
    private val context: Context,
    private val apiKey: () -> String
) : LlmTool {

    override val name = "web_extract"
    override val description =
        "读取网页正文。搜索结果里只有标题和摘要；当答案需要**具体数字、日期、人名、完整经过**时" +
            "（比如「涨了多少」「谁当选」「几点结束」「门票多少钱」），" +
            "调用它把正文读出来再回答。只要概括大意就不必调用，省时间。\n" +
            "传 1~2 个刚刚搜索得到的 URL，并尽量提供 query 让内容按问题排序。" +
            "遇到靠 JS 渲染的页面（公众号、知乎、各类 App 内页）会自动用浏览器内核渲染后再取正文。" +
            "不要猜 URL，也不要凭空编造网址去读。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "urls":{"type":"array","items":{"type":"string"},"description":"来自 web_search 结果的网页 URL，最多 2 个"},
          "query":{"type":"string","description":"要从网页中核实的问题"}
        },"required":["urls"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "网页读取参数格式错误，未执行"
        // ★ 模型经常把 urls 写成 url（单数），直接判失败太浪费一轮 ——
        //   实测它搜到新闻后想核对正文，传了 {"url":"https://..."}，
        //   这里拒掉，它只能干巴巴回一句「没查到细节」。两种都收。
        val input = args.optJSONArray("urls")
            ?: args.optString("url").trim().takeIf { it.isNotBlank() }
                ?.let { JSONArray().put(it) }
            ?: return "缺少网页 URL，未执行"
        val urls = (0 until minOf(input.length(), 2)).mapNotNull { input.optString(it).trim().takeIf(String::isNotBlank) }
        if (urls.isEmpty()) return "没有有效网页 URL，未执行"
        if (urls.any { runCatching { URL(it).protocol == "https" }.getOrDefault(false).not() }) {
            return "只允许读取 HTTPS 网页；本次未执行"
        }
        val query = args.optString("query").trim().takeIf { it.isNotBlank() }
        val key = apiKey().trim()

        // 正常顺序：Tavily 优先（快、省电），失败才用本机 WebView 渲染。
        // PREFER_WEBVIEW=true 是调试开关，用来单独验证本机渲染通路。
        if (PREFER_WEBVIEW) {
            val viaWebView = fetchByWebView(urls.first())
            if (viaWebView.isNotBlank()) return viaWebView
            Dbg.log("web_extract: WebView 优先模式下没取到正文，回退 Tavily")
        }

        // ① Tavily extract：有 key 就先试，它更快也更省电
        if (key.isNotBlank()) {
            val viaTavily = fetchByTavily(key, urls, query)
            if (viaTavily.isNotBlank()) return viaTavily
            Dbg.log("web_extract: Tavily 没拿到正文，尝试 WebView 渲染")
        }

        // ② WebView 渲染兜底：只抓第一个 URL（每个都要 3~8 秒，抓两个太慢）
        if (!PREFER_WEBVIEW) {
            val viaWebView = fetchByWebView(urls.first())
            if (viaWebView.isNotBlank()) return viaWebView
        }

        return if (key.isBlank()) {
            "网页读取不可用：未配置 Tavily Key，且浏览器渲染也没取到正文；本次没有读取网页。"
        } else {
            "网页请求已完成，但没能取到正文（Tavily 与浏览器渲染都失败）；" +
                "请勿把它当作已核实的网页内容。"
        }
    }

    /** Tavily 服务端抽取。失败返回空串（不抛异常）。 */
    private fun fetchByTavily(key: String, urls: List<String>, query: String?): String {
        val payload = JSONObject()
            .put("urls", JSONArray(urls))
            .put("format", "markdown")
            .put("extract_depth", "basic")
            .put("timeout", 12)
            .apply { query?.let { put("query", it); put("chunks_per_source", 3) } }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 18_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Dbg.log("web_extract Tavily HTTP $code urls=${urls.size}")
            if (code !in 200..299) {
                Dbg.log("web_extract Tavily 失败：HTTP $code")
                return ""
            }
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: JSONArray()
            val text = buildString {
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val content = r.optString("raw_content").trim()
                    if (content.isBlank()) continue
                    append("来源：").append(r.optString("url")).append('\n')
                    append(content.take(MAX_CHARS)).append("\n\n")
                }
            }.trim()
            text
        } catch (t: Throwable) {
            Dbg.log("web_extract Tavily 异常 ${t.javaClass.simpleName}: ${t.message}")
            ""
        } finally {
            conn?.disconnect()
        }
    }

    /** 本机 WebView 渲染取正文。失败返回空串。 */
    private fun fetchByWebView(url: String): String {
        val text = runCatching { WebViewFetcher.fetch(context, url, WEBVIEW_TIMEOUT_MS) }
            .getOrElse {
                Dbg.log("web_extract WebView 异常 ${it.javaClass.simpleName}: ${it.message}")
                ""
            }
        if (text.isBlank()) return ""
        Dbg.log("web_extract WebView 取到 ${text.length} 字")
        return buildString {
            append("来源：").append(url).append('\n')
            append(text.take(MAX_CHARS_WEBVIEW))
            append("\n（页面由本机浏览器内核渲染后提取）")
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/extract"
        const val MAX_CHARS = 4_000
        const val MAX_CHARS_WEBVIEW = 8_000
        const val WEBVIEW_TIMEOUT_MS = 15_000L

        /** 调试开关：true=WebView 优先，false=Tavily 优先（默认）。 */
        const val PREFER_WEBVIEW = false
    }
}
