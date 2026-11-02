package com.navagent.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.navagent.core.CloudTts
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * MiniMax 云端语音合成。
 *
 * 为什么它作为「可选项」而不是默认：本地 TTS（华为 hiai / 讯飞）零延迟、离线可用，
 * 导航播报这种高频短句走本地最合适。云端音色更自然，但要每句多几百毫秒网络往返，
 * 而且没网就哑了。所以做成开关，让用户自己权衡。
 *
 * ★ 接口细节（实测，别凭印象改）：
 *   POST {base}/t2a_v2
 *   → `data.audio` 是 **hex 字符串**（不是 base64！`494433` 就是 "ID3" 的 ASCII 码）
 *   解码后是 MP3，用 MediaPlayer 播
 */
class MinimaxTts(private val context: Context) : CloudTts {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentFile: File? = null

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        Thread {
            val t0 = System.currentTimeMillis()
            val bytes = synth(text)
            if (bytes == null || bytes.isEmpty()) {
                Dbg.log("MiniMax TTS 合成失败")
                main.post { onError("合成失败") }
                return@Thread
            }
            Dbg.log("MiniMax TTS ${bytes.size}B ${System.currentTimeMillis() - t0}ms")
            main.post { play(bytes, onDone, onError) }
        }.apply { isDaemon = true; name = "mm-tts" }.start()
    }

    override fun stop() {
        main.post { stopInternal() }
    }

    // ------------------------------------------------------------ 播放

    private fun play(bytes: ByteArray, onDone: () -> Unit, onError: (String) -> Unit) {
        stopInternal()
        runCatching {
            val f = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            f.writeBytes(bytes)
            currentFile = f

            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                stopInternal()
                onDone()
            }
            mp.setOnErrorListener { _, what, extra ->
                Dbg.log("MediaPlayer 错误 what=$what extra=$extra")
                stopInternal()
                onError("播放失败")
                true
            }
            mp.prepare()
            mp.start()
        }.onFailure {
            Dbg.log("播放异常 ${it.javaClass.simpleName}: ${it.message}")
            stopInternal()
            onError("播放异常")
        }
    }

    /** 必须停在主线程：MediaPlayer 的 release 在别的线程容易崩 */
    private fun stopInternal() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
    }

    // ------------------------------------------------------------ 合成

    private fun synth(text: String): ByteArray? {
        // 每次合成都从 Prefs 读：设置页换 Key 后立刻生效
        val apiKey = Prefs.voiceApiKey()
        if (apiKey.isBlank()) {
            Dbg.log("TTS：没有配置 API Key（设置页没填，编译期也没注入）")
            return null
        }
        val payload = JSONObject()
            .put("model", Prefs.ttsCloudModel)
            .put("text", text)
            .put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", Prefs.ttsVoiceId)
                    .put("speed", Prefs.ttsRate.toDouble())
                    .put("vol", 1.0)
                    .put("pitch", 0)
            )
            .put(
                "audio_setting",
                JSONObject()
                    .put("sample_rate", 32000)
                    .put("bitrate", 128_000)
                    .put("format", "mp3")
            )
            .toString()

        var conn: HttpURLConnection? = null
        return try {
            val base = Prefs.voiceBaseUrl().trimEnd('/')
            conn = (URL("$base/t2a_v2").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                Dbg.log("TTS HTTP $code ${body.take(200)}")
                return null
            }

            val json = JSONObject(body)
            val bizCode = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
            if (bizCode != 0) {
                Dbg.log("TTS 业务错误 $bizCode: ${json.optJSONObject("base_resp")?.optString("status_msg")}")
                return null
            }

            val hex = json.optJSONObject("data")?.optString("audio").orEmpty()
            if (hex.isBlank()) {
                Dbg.log("TTS 返回没有 audio 字段")
                return null
            }
            hexToBytes(hex)
        } catch (t: Throwable) {
            Dbg.log("TTS 异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 返回的是十六进制串，不是 base64 */
    private fun hexToBytes(s: String): ByteArray? {
        val len = s.length / 2
        if (len == 0) return null
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
