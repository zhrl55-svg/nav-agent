package com.navagent.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 模型下载与校验。
 *
 * 策略：**不打包进 APK**，首次运行下载到 getFilesDir()/models/。
 * 原因：vosk 中文小模型 43.9MB，打进包会让 APK 从 90MB 涨到 134MB，
 * 而它只在第一次用得上。手机自己的网络比开发机靠谱（实测开发机走代理才通）。
 *
 * 源：alphacephei 官方（实测直连 200，43.9MB）。
 */
object ModelStore {

    private const val URL_PRIMARY =
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private const val URL_FALLBACK =
        "https://hf-mirror.com/alphacep/vosk-model-small-cn/resolve/main/vosk-model-small-cn-0.22.zip"

    private const val DIR_NAME = "vosk-cn"

    // ---------------------------------------------------------------- 声纹模型

    /**
     * WeSpeaker 声纹模型（26MB ONNX，256 维 embedding）。
     * 官方 huggingface.co 国内直连不通（实测 000），走 hf-mirror。
     */
    private const val WP_URL = "https://hf-mirror.com/Wespeaker/wespeaker-cnceleb-resnet34-LM/resolve/main/cnceleb_resnet34_LM.onnx"
    private const val WP_DIR = "voiceprint"
    private const val WP_FILE = "cnceleb_resnet34_LM.onnx"
    private const val WP_MIN_BYTES = 20_000_000L

    /** 已就绪的声纹模型；没有返回 null */
    fun voiceprintModel(filesDir: File): File? {
        val f = File(filesDir, "models/$WP_DIR/$WP_FILE")
        return if (f.exists() && f.length() > WP_MIN_BYTES) f else null
    }

    /**
     * 确保声纹模型存在。阻塞调用，必须在后台线程。
     * @return 模型文件，失败返回 null
     */
    fun ensureVoiceprint(filesDir: File, onProgress: (String) -> Unit = {}): File? {
        voiceprintModel(filesDir)?.let { return it }

        val target = File(filesDir, "models/$WP_DIR/$WP_FILE")
        target.parentFile?.mkdirs()

        try {
            onProgress("正在下载声纹模型（约 26MB）…")
            if (download(WP_URL, target, onProgress) && target.length() > WP_MIN_BYTES) {
                onProgress("声纹模型就绪")
                return target
            }
        } catch (t: Throwable) {
            Dbg.log("声纹模型下载失败: ${t.javaClass.simpleName} ${t.message}")
        }
        runCatching { target.delete() }
        Dbg.log("声纹模型下载失败")
        return null
    }

    /** 已就绪的模型目录；没有返回 null */
    fun voskDir(filesDir: File): File? {
        val d = File(filesDir, "models/$DIR_NAME")
        return if (isValid(d)) d else null
    }

    private fun isValid(dir: File): Boolean =
        File(dir, "am/final.mdl").exists() && File(dir, "conf/mfcc.conf").exists()

    /**
     * 确保模型存在。阻塞调用，必须在后台线程。
     * @return 模型目录，失败返回 null
     */
    fun ensureVosk(filesDir: File, onProgress: (String) -> Unit = {}): File? {
        val target = File(filesDir, "models/$DIR_NAME")
        if (isValid(target)) return target

        val zip = File(filesDir, "models/vosk-cn.zip")
        zip.parentFile?.mkdirs()

        for ((i, url) in listOf(URL_PRIMARY, URL_FALLBACK).withIndex()) {
            try {
                onProgress("正在下载语音模型（${i + 1}/2，约 44MB）…")
                if (download(url, zip, onProgress)) {
                    onProgress("正在解压…")
                    if (unzip(zip, target) && isValid(target)) {
                        zip.delete()
                        onProgress("语音模型就绪")
                        return target
                    }
                }
            } catch (t: Throwable) {
                Dbg.log("模型下载失败 $url: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        Dbg.log("模型下载全部失败")
        return null
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                Dbg.log("模型下载 HTTP ${conn.responseCode}")
                return false
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -10
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= lastPct + 10) {
                                lastPct = pct
                                onProgress("下载语音模型 $pct%")
                            }
                        }
                    }
                }
            }
            return dest.length() > 1_000_000
        } catch (t: Throwable) {
            Dbg.log("下载异常 ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /** 解压，剥掉压缩包里的顶层目录 */
    private fun unzip(zip: File, target: File): Boolean {
        return try {
            target.deleteRecursively()
            target.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name.substringAfter('/', e.name).trimEnd('/')
                    if (name.isEmpty()) continue
                    val out = File(target, name)
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }
            true
        } catch (t: Throwable) {
            Dbg.log("解压失败 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
