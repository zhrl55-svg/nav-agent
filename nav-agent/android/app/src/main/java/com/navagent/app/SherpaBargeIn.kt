package com.navagent.app

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 播报期间的关键词识别（sherpa-onnx zipformer KWS）。
 *
 * ★ 2026-11-07 重做：词表不再是写死的「小猪」。
 *
 *   老版本把 `KEYWORD_LINE = "x iǎo zh ū @小猪"` 和 `keyword.contains("小猪")`
 *   都写死在代码里，用户把唤醒词改成「乐乐」之后这条链路就整条失效，
 *   而且加一个打断词就得手拼带声调的拼音。
 *
 *   现在词表由宿主传进来（唤醒词 + 停止口令 + 序号词，全部来自设置），
 *   拼音由 [KwsKeywords] 用 Android 自带的 ICU 转写器生成，不需要内置拼音表。
 *   改词 = 改设置，不用改代码、不用换模型。
 *
 * 这里使用 zipformer KWS，而不是让 Vosk 自由解码 TTS 与麦克风的混合音频。
 * Vosk 在长播报中经常只吐出“测试”“直播”等碎片，完整唤醒词根本没有进入
 * 候选；KWS 把解码约束在目标拼音路径上，命中后仍交给声纹做主人确认。
 */
class SherpaBargeIn(private val context: Context) {

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private val samples = FloatArray(AudioCapture.FRAME_SAMPLES)
    private var fedFrames = 0

    /** 当前生效的关键词（中文原文，也是 [feed] 返回的 label） */
    @Volatile
    var labels: List<String> = emptyList()
        private set

    private var loadedWith: List<String> = emptyList()

    /** 模型认得的 token（tokens.txt 第一列）。用来提前筛掉拼不出来的词 */
    private val vocab: Set<String> by lazy {
        runCatching {
            context.assets.open("$ASSET_DIR/tokens.txt").bufferedReader().useLines { seq ->
                seq.mapNotNull { line ->
                    line.substringBefore(' ').trim().takeIf { it.isNotEmpty() }
                }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    /** 取一行关键词的拼音部分（@ 之前）拆成 token */
    private fun tokensOf(line: String): List<String> =
        line.substringBefore('@').trim().split(' ').filter { it.isNotEmpty() }

    /**
     * 只在 voice-worker 上预加载，避免第一段播报时才在采集线程初始化。
     *
     * 词表变了（唤醒词/打断词改了）要带着新词表重新调用 —— 内容和上次一样就直接返回。
     */
    @Synchronized
    fun load(words: List<String>): Boolean {
        val wanted = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ready && wanted == loadedWith) return true
        if (failed) return false
        val generated = KwsKeywords.lines(wanted)
        // ★ 关键：ICU 转出来的音节不一定都在模型词表里（比如 ü 系列），
        //   只要有一行含未知 token，createStream 就会抛
        //   "Failed to create native OnlineStream"，整条链路直接哑掉。
        //   所以逐行校验，坏的挑出来丢掉并打日志，别让一行坏词拖垮全部。
        val keywordLines = generated.filter { line -> tokensOf(line).all { it in vocab } }
        val dropped = generated - keywordLines.toSet()
        if (dropped.isNotEmpty()) {
            Dbg.log("Sherpa KWS 丢弃含未知音节的词：$dropped")
        }
        Dbg.log("Sherpa KWS 词表行：$keywordLines")
        if (keywordLines.isEmpty()) {
            lastError = "关键词转拼音失败（ICU 不可用或词表为空）"
            Dbg.log("Sherpa KWS 跳过：$lastError")
            return false
        }
        // 词表换了要重建 stream
        if (ready) closeInternal()
        return try {
            val t0 = System.currentTimeMillis()
            val transducer = OnlineTransducerModelConfig(
                encoder = "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$ASSET_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                joiner = "$ASSET_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            )
            val model = OnlineModelConfig(
                transducer = transducer,
                tokens = "$ASSET_DIR/tokens.txt",
                numThreads = 1,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(
                    sampleRate = AudioCapture.SAMPLE_RATE,
                    featureDim = FEATURE_DIM,
                    dither = 0f
                ),
                modelConfig = model,
                maxActivePaths = 8,
                keywordsFile = "$ASSET_DIR/keywords.txt",
                keywordsScore = KEYWORD_SCORE,
                keywordsThreshold = KEYWORD_THRESHOLD,
                numTrailingBlanks = 1
            )
            val next = KeywordSpotter(context.assets, config)
            spotter = next
            // ★ createStream 可以直接吃多行词表，覆盖 keywordsFile 的内容
            stream = next.createStream(keywordLines.joinToString("\n"))
            labels = wanted
            loadedWith = wanted
            ready = true
            Dbg.log(
                "Sherpa KWS 就绪 keywords=${wanted.size} $wanted " +
                    "${System.currentTimeMillis() - t0}ms"
            )
            true
        } catch (t: Throwable) {
            failed = true
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            closeInternal()
            Dbg.log("Sherpa KWS 初始化失败 $lastError")
            false
        }
    }

    /**
     * 输入一帧 16k PCM16。命中时返回命中的关键词（中文原文），并立即 reset。
     *
     * 返回 null = 这一帧没命中。调用方仍在 Vosk 严格模式上保留后备检测。
     */
    @Synchronized
    fun feed(frame: ShortArray, len: Int): String? {
        if (!ready) return null
        val kws = spotter ?: return null
        val input = stream ?: return null
        val n = len.coerceAtMost(frame.size).coerceAtMost(samples.size)
        if (n <= 0) return null

        return try {
            for (i in 0 until n) samples[i] = frame[i] / 32768f
            val chunk = if (n == samples.size) samples else samples.copyOf(n)
            input.acceptWaveform(chunk, AudioCapture.SAMPLE_RATE)

            // 心跳：确认这条链路真的在吃音频。
            // 真机日志里一次 `Sherpa KWS 命中` 都没有，分不清是模型不认这个词
            // 还是这行代码压根没被调用 —— 有这个心跳就能一眼看出来。
            fedFrames++
            if (fedFrames % HEARTBEAT_FRAMES == 0L) {
                Dbg.log(
                    "Sherpa KWS 已喂 ${fedFrames * AudioCapture.FRAME_MS / 1000}s 音频，仍未命中"
                )
            }

            var hit: String? = null
            while (kws.isReady(input)) {
                kws.decode(input)
                val keyword = kws.getResult(input).keyword
                if (keyword.isBlank()) continue
                // keyword 就是词表里 @ 后面那串中文
                val label = labels.firstOrNull { keyword.contains(it) } ?: keyword
                Dbg.log("Sherpa KWS 命中「$label」")
                hit = label
                break
            }
            if (hit != null) kws.reset(input)
            hit
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            ready = false
            failed = true
            closeInternal()
            Dbg.log("Sherpa KWS 识别失败 $lastError")
            null
        }
    }

    @Synchronized
    fun reset() {
        if (!ready) return
        val kws = spotter ?: return
        val input = stream ?: return
        runCatching { kws.reset(input) }
            .onFailure { Dbg.log("Sherpa KWS reset 失败：${it.message}") }
    }

    @Synchronized
    fun close() {
        failed = true
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
        ready = false
    }

    private companion object {
        const val ASSET_DIR = "sherpa-kws"
        const val FEATURE_DIM = 80
        const val KEYWORD_SCORE = 2.0f
        const val KEYWORD_THRESHOLD = 0.12f

        /** 100 帧 = 2 秒，打一次心跳（太密会把 nav.log 淹掉） */
        const val HEARTBEAT_FRAMES = 100L
    }
}
