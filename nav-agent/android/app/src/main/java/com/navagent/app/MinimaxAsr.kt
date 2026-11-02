package com.navagent.app

import com.navagent.core.Asr
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * MiniMax 语音识别：整段上传 + 一次性返回。
 *
 * 接口细节来自桌面 D:/AI/app/asr_minimax.py（那边已实测跑通），不是猜的：
 *   POST {base}/speech_to_text
 *   multipart 字段  model / response_format=json / stream=false / file=audio.wav
 *   语种走 **HTTP header** `language: zh`，不是 form 字段
 *   返回 {"text": "...", "duration": 1.2, "trace_id": "..."}
 *
 * 为什么不支持流式：官方这个接口就是整段的。桌面实测 2s 音频 650~855ms，
 * 4s 音频 657~737ms —— 延迟几乎与音频长度无关，瓶颈在网络往返。
 */
class MinimaxAsr : Asr {

    @Volatile
    var lastLatencyMs: Long = -1
        private set

    override fun transcribe(pcm: ShortArray, sampleRate: Int): String? {
        // 每次调用都从 Prefs 读：设置页换 Key 后立刻生效
        val apiKey = Prefs.voiceApiKey()
        if (apiKey.isBlank()) {
            Dbg.log("ASR: 没有配置 API Key（设置页没填，编译期也没注入）")
            return null
        }
        val seconds = pcm.size.toDouble() / sampleRate
        if (seconds < MIN_SECONDS) return ""

        val wav = toWav(pcm, sampleRate)
        val boundary = "----navagent" + UUID.randomUUID().toString().replace("-", "")
        val body = buildMultipart(boundary, wav)

        val base = Prefs.voiceBaseUrl().trimEnd('/')
        val url = URL("$base/speech_to_text")

        var attempt = 0
        while (attempt <= RETRY) {
            val t0 = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6_000
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("language", "zh")
                }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    Dbg.log("ASR HTTP $code: ${raw.take(200)}")
                    attempt++
                    continue
                }
                val json = JSONObject(raw)
                val bizCode = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
                if (bizCode != 0) {
                    Dbg.log("ASR 业务错误 $bizCode: ${json.optJSONObject("base_resp")?.optString("status_msg")}")
                    attempt++
                    continue
                }
                lastLatencyMs = System.currentTimeMillis() - t0
                val text = json.optString("text").trim()
                Dbg.log("ASR ${lastLatencyMs}ms ${"%.2f".format(seconds)}s音频 → 「$text」")
                return text
            } catch (t: Throwable) {
                Dbg.log("ASR 异常 attempt=$attempt ${t.javaClass.simpleName}: ${t.message}")
                attempt++
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun buildMultipart(boundary: String, wav: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray())
            out.write("\r\n".toByteArray())
        }
        field("model", Prefs.asrModel)
        field("response_format", "json")
        field("stream", "false")

        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray()
        )
        out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    /** PCM16 单声道 → WAV 字节（44 字节标准头） */
    private fun toWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun le32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun le16(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataLen)
        val bytes = ByteArray(dataLen)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            bytes[i * 2] = (s and 0xff).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        out.write(bytes)
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val RETRY = 1
        const val MIN_SECONDS = 0.3
    }
}
