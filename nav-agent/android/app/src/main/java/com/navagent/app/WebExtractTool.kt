package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 从搜索结果中挑选网页并提取正文，供模型核对细节。 */
class WebExtractTool(private val apiKey: () -> String) : LlmTool {

    override val name = "web_extract"
    override val description =
        "读取指定网页正文。仅在 web_search 返回的来源链接需要核对细节、引用或长文内容时调用；" +
            "最多传 2 个刚刚搜索得到的 URL，并尽量提供 query 让内容按问题排序。不要猜 URL，也不要读取用户未提供且未搜索到的网页。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "urls":{"type":"array","items":{"type":"string"},"description":"来自 web_search 结果的网页 URL，最多 2 个"},
          "query":{"type":"string","description":"要从网页中核实的问题"}
        },"required":["urls"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "网页读取参数格式错误，未执行"
        val input = args.optJSONArray("urls") ?: return "缺少网页 URL，未执行"
        val urls = (0 until minOf(input.length(), 2)).mapNotNull { input.optString(it).trim().takeIf(String::isNotBlank) }
        if (urls.isEmpty()) return "没有有效网页 URL，未执行"
        if (urls.any { runCatching { URL(it).protocol == "https" }.getOrDefault(false).not() }) {
            return "只允许读取 HTTPS 网页；本次未执行"
        }
        val key = apiKey().trim()
        if (key.isBlank()) return "网页正文读取不可用：未配置 Tavily Key，本次没有读取网页。"

        val payload = JSONObject()
            .put("urls", JSONArray(urls))
            .put("format", "markdown")
            .put("extract_depth", "basic")
            .put("timeout", 12)
            .apply { args.optString("query").trim().takeIf(String::isNotBlank)?.let { put("query", it); put("chunks_per_source", 3) } }

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
            Dbg.log("web_extract HTTP $code urls=${urls.size}")
            if (code !in 200..299) return when (code) {
                401, 403 -> "网页读取失败：Tavily Key 无效或没有权限；未读取网页。"
                429 -> "网页读取失败：Tavily 额度不足；未读取网页。"
                else -> "网页读取失败（HTTP $code）；未读取网页，请依据已取得的搜索摘要并说明限制。"
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
            if (text.isNotBlank()) text else "网页请求已完成，但没有成功提取正文；请勿把它当作已核实的网页内容。"
        } catch (t: Throwable) {
            Dbg.log("web_extract 异常 ${t.javaClass.simpleName}: ${t.message}")
            "网页读取出错（${t.javaClass.simpleName}）；本次没有取得网页正文。"
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/extract"
        const val MAX_CHARS = 4_000
    }
}
