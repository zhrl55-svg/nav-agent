package com.navagent.app

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.navagent.core.Asr
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 端侧语音识别：sherpa-onnx + SenseVoice-Small（int8）。
 *
 * 和 [MinimaxAsr] 的关系：同一个 [Asr] 接口的两套实现，靠 [AsrRouter] 路由。
 * 这个类不认识网络，也不做重试——它的失败只有两种：模型没就绪、解码异常。
 *
 * ## 为什么用离线识别器而不是流式
 * 现有链路是「VAD 切好整段 → transcribe(整段 PCM)」，接口给的就是一整块音频。
 * 用 OfflineRecognizer 正好对上，不用改 VoiceEngine 的状态机。
 * 真要「边说边出字」，得把接口改成回调和部分结果，那是另一件事。
 *
 * ## 模型
 * 期望目录：`filesDir/models/asr-sensevoice/`
 *   - model.int8.onnx   (SenseVoice-Small int8，约 230MB)
 *   - tokens.txt
 * 不打包进 APK：230MB 会让 APK 从 115MB 涨到 345MB。
 *
 * ## ITN 必须关
 * SenseVoice 的 `useInverseTextNormalization` 会把口语数字转成阿拉伯数字。
 * 对导航是灾难——「三里屯」可能变成「3里屯」，「五道口」变成「5道口」。
 * 地名里的数字是专名的一部分，不能规范化。所以恒为 false。
 */
class LocalAsr(private val context: Context) : Asr {

    private val lock = ReentrantLock()
    private var recognizer: OfflineRecognizer? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastLatencyMs: Long = -1
        private set

    @Volatile
    var lastModelLoadMs: Long = -1
        private set

    private val recentLatency = ArrayDeque<Long>()
    private var okCount = 0
    private var failCount = 0

    /** 模型目录。有 int8 就用 int8，没就退回 fp32 命名 */
    private fun modelDir(): File = File(context.filesDir, "models/asr-sensevoice")

    @Volatile private var downloading = false

    /** 首次运行下载模型。只启动一次，失败不重试（下次冷启动会再来）。 */
    private fun startModelDownloadOnce() {
        if (downloading) return
        downloading = true
        Thread {
            val ok = runCatching {
                ModelStore.ensureLocalAsr(context.filesDir, context.getExternalFilesDir(null)) { msg -> Dbg.log("本地ASR: $msg") }
            }.getOrDefault(false)
            downloading = false
            if (ok) {
                // 装好了清掉失败标记，下一次识别就能用上
                initFailed = false
                initTried = false
                Dbg.log("本地ASR: 模型已就绪，下次识别生效")
            } else {
                Dbg.log("本地ASR: 模型下载失败，继续走云端")
            }
        }.apply { name = "asr-download"; isDaemon = true; start() }
    }

    /**
     * 选模型文件。
     *
     * ★ 2026-11-08：支持「模型可选」——除了约定名 model.int8.onnx，
     *   目录里放任何 sherpa-onnx 兼容的 .onnx（SenseVoice 其它精度、
     *   paraformer、whisper 小模型…）都能被用上，不用改代码。
     *   排序取第一个，名字里带 int8 的优先（体积小、手机上更快）。
     */
    private fun modelFile(dir: File): File {
        File(dir, "model.int8.onnx").takeIf { it.exists() }?.let { return it }
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
            ?.sortedBy { if (it.name.contains("int8")) 0 else 1 }
            ?.firstOrNull()
        return onnx ?: File(dir, "model.onnx")
    }

    /**
     * 加载模型。阻塞，调用方负责放到后台线程。
     * 幂等：成功过一次就直接返回 true，失败过的也不反复重试（避免每次说话卡 2 秒）。
     */
    fun ensureReady(): Boolean {
        if (ready) return true
        lock.withLock {
            if (ready) return true
            if (initTried && initFailed) return false
            initTried = true

            val dir = modelDir()
            val model = modelFile(dir)
            val tokens = File(dir, "tokens.txt")
            if (!model.exists() || !tokens.exists()) {
                initFailed = true
                Dbg.log("本地ASR: 模型缺失 ${model.absolutePath}（tokens.exists=${tokens.exists()}）")
                // ★ 2026-11-08：模型不进 APK，首次运行自动下载（239MB）。
                //   下载期间本次识别走云端（AsrRouter 的降级路径），装好下次生效。
                startModelDownloadOnce()
                return false
            }
            // 有文件但体积不对（下到一半断了）也要重下，否则 native 加载会 abort
            if (!ModelStore.localAsrReady(context.filesDir)) {
                initFailed = true
                Dbg.log("本地ASR: 模型体积不对，判为残缺，重新下载")
                startModelDownloadOnce()
                return false
            }

            val t0 = System.currentTimeMillis()
            try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "zh",
                            // ★ 见类注释：地名里的数字是专名，不能规范化
                            useInverseTextNormalization = false
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = NUM_THREADS,
                        provider = "cpu",
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                // ★ assetManager 必须传 null。
                //   传 context.assets 会让 sherpa 走 AssetManager 读文件（只认 assets 里的
                //   相对路径），而我们给的是 filesDir 的绝对路径，两者对不上：
                //   sherpa 先 WARN 一句 "assetManager is NOT set to null"，然后
                //   FATAL "Load tokens.txt failed" 直接 abort —— 是 native abort，
                //   Kotlin 的 try/catch 抓不到，整个进程静默消失。
                //   2026-11-08 在 P40 上踩过一次，排查成本很高。
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                lastModelLoadMs = System.currentTimeMillis() - t0
                ready = true
                Dbg.log("本地ASR: 就绪，加载 ${lastModelLoadMs}ms，${model.name}")
                return true
            } catch (t: Throwable) {
                initFailed = true
                recognizer = null
                Dbg.log("本地ASR: 初始化失败 ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        }
    }

    /**
     * 整段识别。
     * 返回 null = 本地不可用（模型缺失/解码异常），由 [AsrRouter] 决定要不要降级云端。
     * 返回 "" = 确实没识别出内容。
     */
    override fun transcribe(pcm: ShortArray, sampleRate: Int): String? {
        if (!ready && !ensureReady()) return null
        // SenseVoice 只吃 16k；采样率不对说明上游改了音频管线，宁可报错也别喂错数据
        if (sampleRate != SAMPLE_RATE) {
            Dbg.log("本地ASR: 采样率 $sampleRate != $SAMPLE_RATE，放弃")
            return null
        }

        val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        val t0 = System.currentTimeMillis()
        // OfflineRecognizer / Stream 都不是线程安全的，和 ensureReady 共用一把锁
        lock.withLock {
            val r = recognizer ?: return null
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                stream = r.createStream()
                stream.acceptWaveform(samples, sampleRate)
                r.decode(stream)
                val text = r.getResult(stream).text.trim()
                val dt = System.currentTimeMillis() - t0
                recordOk(dt)
                Dbg.log(
                    "本地ASR ${dt}ms ${"%.2f".format(pcm.size.toDouble() / sampleRate)}s音频" +
                        " → 「$text」 · ${statsText()}"
                )
                return text
            } catch (t: Throwable) {
                recordFail()
                Dbg.log("本地ASR 异常 ${t.javaClass.simpleName}: ${t.message}")
                return null
            } finally {
                stream?.release()
            }
        }
    }

    @Synchronized
    private fun recordOk(ms: Long) {
        lastLatencyMs = ms
        okCount++
        recentLatency.addLast(ms)
        while (recentLatency.size > 50) recentLatency.removeFirst()
    }

    @Synchronized
    private fun recordFail() {
        failCount++
    }

    /** 和 [MinimaxAsr.statsText] 同样的格式，便于日志里直接对比 */
    @Synchronized
    fun statsText(): String {
        if (recentLatency.isEmpty()) return "ok=$okCount fail=$failCount n=0"
        val sorted = recentLatency.sorted()
        val p50 = sorted[sorted.size / 2]
        val p90 = sorted[minOf(sorted.size - 1, (sorted.size * 0.9).toInt())]
        return "p50=${p50}ms p90=${p90}ms ok=$okCount fail=$failCount n=${sorted.size}"
    }

    fun release() {
        lock.withLock {
            try {
                recognizer?.release()
            } catch (_: Throwable) {
            }
            recognizer = null
            ready = false
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000

        /**
         * P40（麒麟 990）实测：2 线程比 1 线程快约 35%，4 线程只再快 8% 且明显发热。
         * 车载场景手机本来就烫，取 2。
         */
        const val NUM_THREADS = 2
    }
}
