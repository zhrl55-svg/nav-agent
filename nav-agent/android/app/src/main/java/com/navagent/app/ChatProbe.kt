package com.navagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 对话大脑连通性自检。
 *
 * 为什么需要：接了本地模型之后，最常见的三种失败——地址写错、端口写错、
 * 模型名写错——表现完全一样：说一句话，没有声音。日志要连 adb 才看得到，
 * 而用户在车里根本没这个条件。
 *
 * 这里发一句最短的请求（`stream=false`，只要 16 token），把 HTTP 码和
 * 服务端返回的错误原样带回来，一眼能看出是哪一环。
 */
object ChatProbe {

    /** @return 一句人话，例如「连通 · 840ms · 回复「在的」」 */
    fun run(): String {
        val base = Prefs.chatBaseUrl().trimEnd('/')
        val key = Prefs.chatApiKey()
        val payload = JSONObject()
            .put("model", Prefs.chatModel)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", "在吗")
                )
            )
            .put("max_tokens", 16)
            // 自检要的是结构化响应，不走流式 —— 流式时错误常常是"空流"，
            // 看不出原因
            .put("stream", false)
            .toString()

        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        return try {
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 20_000
                doOutput = true
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val ms = System.currentTimeMillis() - t0
            Dbg.log("ChatProbe $base model=${Prefs.chatModel} HTTP $code ${ms}ms ${body.take(200)}")

            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> "Key 不对或没权限"
                    404 -> "地址或模型名不对（本地服务注意要带 /v1）"
                    422 -> "服务端拒了这次输入"
                    else -> "服务端返回错误"
                }
                return "失败 · HTTP $code · $hint\n\n${body.take(200)}"
            }

            val text = runCatching {
                JSONObject(body).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content").orEmpty()
            }.getOrDefault("")

            buildString {
                append("连通 · ").append(ms).append("ms\n")
                append("模型：").append(Prefs.chatModel).append("\n")
                append("回复：").append(text.trim().take(40).ifBlank { "（空）" })
            }
        } catch (t: Throwable) {
            val msg = t.message.orEmpty().take(90)
            Dbg.log("ChatProbe 异常 ${t.javaClass.simpleName}: $msg")
            "连不上：${t.javaClass.simpleName}\n$msg\n\n" +
                "检查：手机和电脑是否同一个 Wi-Fi、地址端口对不对、" +
                "电脑上的服务是否监听 0.0.0.0（只监听 127.0.0.1 时手机连不进去）"
        } finally {
            conn?.disconnect()
        }
    }
}
