package com.navagent.app

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
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

    /**
     * 已就绪且可被 Sherpa 加载的声纹模型；没有返回 null。
     *
     * WeSpeaker 发布的原始 ONNX 不带 Sherpa 需要的 metadata。直接把原始文件
     * 交给 SpeakerEmbeddingExtractor 会在 dim() 时触发 native abort。这里首次
     * 使用时生成带 metadata 的兼容副本，原文件保持不变，便于校验和重新生成。
     */
    fun voiceprintModel(filesDir: File): File? {
        val source = voiceprintSource(filesDir) ?: return null
        return ensureSherpaMetadata(source)
    }

    /**
     * 确保声纹模型存在。阻塞调用，必须在后台线程。
     * @return 模型文件，失败返回 null
     */
    fun ensureVoiceprint(filesDir: File, onProgress: (String) -> Unit = {}): File? {
        voiceprintModel(filesDir)?.let { return it }

        val target = voiceprintSourceFile(filesDir)
        target.parentFile?.mkdirs()

        try {
            onProgress("正在下载声纹模型（约 26MB）…")
            if (download(WP_URL, target, onProgress) && target.length() > WP_MIN_BYTES) {
                val compatible = ensureSherpaMetadata(target)
                if (compatible != null) {
                    onProgress("声纹模型就绪")
                    return compatible
                }
            }
        } catch (t: Throwable) {
            Dbg.log("声纹模型下载失败: ${t.javaClass.simpleName} ${t.message}")
        }
        runCatching { target.delete() }
        Dbg.log("声纹模型下载失败")
        return null
    }

    private fun voiceprintSourceFile(filesDir: File): File =
        File(filesDir, "models/$WP_DIR/$WP_FILE")

    private fun voiceprintSource(filesDir: File): File? =
        voiceprintSourceFile(filesDir).takeIf {
            it.exists() && it.length() > WP_MIN_BYTES
        }

    /**
     * 在 ONNX ModelProto 末尾追加 metadata_props（field 14）。
     *
     * protobuf 允许已知字段乱序出现，因此无需引入 ONNX/protobuf 依赖来重写
     * 26MB 图结构。七个值取自 sherpa-onnx 官方 add_meta_data.py；该 WeSpeaker
     * CN-Celeb 模型的输出维度是 256，且输入按 [-32768, 32767] 归一化。
     */
    @Synchronized
    private fun ensureSherpaMetadata(source: File): File? {
        val metadataVersion = "v1"
        val target = File(
            source.parentFile,
            source.nameWithoutExtension + ".sherpa-$metadataVersion.onnx"
        )
        val metadata = encodeSherpaMetadata()
        val expectedSize = source.length() + metadata.size
        if (target.exists() && target.length() == expectedSize) return target

        val temp = File(target.parentFile, target.name + ".tmp")
        return try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            RandomAccessFile(temp, "rw").use { out ->
                out.seek(out.length())
                out.write(metadata)
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("无法替换旧的声纹兼容模型")
            }
            if (!temp.renameTo(target)) {
                throw IllegalStateException("无法保存声纹兼容模型")
            }
            Dbg.log("已生成 Sherpa 声纹兼容模型 ${target.length() / 1024 / 1024}MB")
            target
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            Dbg.log("生成 Sherpa 声纹兼容模型失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun encodeSherpaMetadata(): ByteArray {
        val entries = linkedMapOf(
            "framework" to "wespeaker",
            "language" to "Chinese",
            "url" to "https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md",
            "comment" to "no comment",
            "sample_rate" to "16000",
            "output_dim" to "256",
            "normalize_samples" to "0"
        )
        return ByteArrayOutputStream().use { model ->
            for ((key, value) in entries) {
                val entry = ByteArrayOutputStream().use { bytes ->
                    writeBytes(bytes, field = 1, key.toByteArray(Charsets.UTF_8))
                    writeBytes(bytes, field = 2, value.toByteArray(Charsets.UTF_8))
                    bytes.toByteArray()
                }
                // ModelProto.metadata_props = 14
                writeBytes(model, field = 14, entry)
            }
            model.toByteArray()
        }
    }

    private fun writeBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        writeVarint(out, (field shl 3) or 2)
        writeVarint(out, value.size)
        out.write(value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining and 0x7f.inv() != 0) {
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
    }

    /** 已就绪的模型目录；没有返回 null */
    // ---------------------------------------------------------------- 端侧识别（SenseVoice）
    //
    // ★ 2026-11-08：模型不进 APK（239MB 会把包体翻倍），改为首次运行下载。
    //   下载失败不影响可用性 —— AsrRouter 会自动降级云端，只是没收益。

    private const val ASR_DIR = "asr-sensevoice"
    private const val ASR_MODEL = "model.int8.onnx"
    private const val ASR_TOKENS = "tokens.txt"
    private const val ASR_BASE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    /** 模型文件最小体积。下到一半断网的残file靠它识别出来，避免拿坏模型去 init（会 native abort） */
    private const val ASR_MODEL_MIN_BYTES = 200_000_000L
    private const val ASR_TOKENS_MIN_BYTES = 200_000L

    fun localAsrDir(filesDir: File): File = File(filesDir, "models/$ASR_DIR")

    fun localAsrReady(filesDir: File): Boolean {
        val d = localAsrDir(filesDir)
        return File(d, ASR_MODEL).length() > ASR_MODEL_MIN_BYTES &&
            File(d, ASR_TOKENS).length() > ASR_TOKENS_MIN_BYTES
    }

    /**
     * 确保端侧识别模型存在。阻塞调用，必须放后台线程。
     * @return 是否就绪
     */
    fun ensureLocalAsr(
        filesDir: File,
        externalDir: File? = null,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        if (localAsrReady(filesDir)) return true
        val dir = localAsrDir(filesDir)
        dir.mkdirs()

        // ① 先看外部私有目录（Android/data/com.navagent/files/...）有没有人放好的模型。
        //    ★ 这是手动导入的主路径：用户用数据线连着电脑、或者用文件管理器，
        //      只能碰到这个目录，碰不到 filesDir。放这里 App 自动搬进来。
        if (externalDir != null) {
            val src = File(externalDir, "models/$ASR_DIR")
            if (localAsrReady(src)) {
                onProgress("发现外部目录里的模型，正在导入…")
                listOf(ASR_MODEL, ASR_TOKENS).forEach { name ->
                    runCatching {
                        File(src, name).copyTo(File(dir, name), overwrite = true)
                    }.onFailure { Dbg.log("导入 $name 失败：${it.message}") }
                }
                if (localAsrReady(filesDir)) {
                    onProgress("端侧识别模型导入完成")
                    return true
                }
                onProgress("导入失败（文件不完整）")
            }
        }

        val model = File(dir, ASR_MODEL)
        if (model.length() <= ASR_MODEL_MIN_BYTES) {
            onProgress("正在下载端侧识别模型（约 239MB，仅首次）…")
            if (!download("$ASR_BASE/$ASR_MODEL", model, onProgress)) {
                Dbg.log("端侧识别模型下载失败")
                return false
            }
        }
        val tokens = File(dir, ASR_TOKENS)
        if (tokens.length() <= ASR_TOKENS_MIN_BYTES) {
            onProgress("正在下载词表…")
            if (!download("$ASR_BASE/$ASR_TOKENS", tokens, onProgress)) {
                Dbg.log("端侧识别词表下载失败")
                return false
            }
        }
        val ok = localAsrReady(filesDir)
        onProgress(if (ok) "端侧识别模型就绪" else "端侧识别模型校验失败")
        return ok
    }

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
            val root = target.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val normalizedName = e.name.replace('\\', '/')
                    val name = normalizedName.substringAfter('/', normalizedName).trimEnd('/')
                    if (name.isEmpty()) continue
                    val out = File(root, name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw SecurityException("ZIP entry escapes target directory")
                    }
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
            target.deleteRecursively()
            Dbg.log("解压失败 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
