package com.navagent.app

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 播报期间的固定关键词识别，只负责识别「小猪」。
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

    /** 只在 voice-worker 上预加载，避免第一段播报时才在采集线程初始化。 */
    @Synchronized
    fun load(): Boolean {
        if (ready) return true
        if (failed) return false
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
            stream = next.createStream(KEYWORD_LINE)
            ready = true
            Dbg.log(
                "Sherpa KWS 就绪 keyword=小猪 model=$ASSET_DIR " +
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
     * 输入一帧 16k PCM16。命中时返回 true，并立即 reset 防止重复触发。
     * 调用方仍在 Vosk 严格模式上保留后备检测。
     */
    @Synchronized
    fun feed(frame: ShortArray, len: Int): Boolean {
        if (!ready && !load()) return false
        val kws = spotter ?: return false
        val input = stream ?: return false
        val n = len.coerceAtMost(frame.size).coerceAtMost(samples.size)
        if (n <= 0) return false

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

            var hit = false
            while (kws.isReady(input)) {
                kws.decode(input)
                val keyword = kws.getResult(input).keyword
                if (keyword.isNotBlank() && keyword.contains(KEYWORD)) {
                    Dbg.log("Sherpa KWS 命中「$keyword」")
                    hit = true
                    break
                }
            }
            if (hit) kws.reset(input)
            hit
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            ready = false
            failed = true
            closeInternal()
            Dbg.log("Sherpa KWS 识别失败 $lastError")
            false
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
        const val KEYWORD = "小猪"
        const val KEYWORD_LINE = "x iǎo zh ū @小猪"
        const val KEYWORD_SCORE = 2.0f
        const val KEYWORD_THRESHOLD = 0.12f

        /** 100 帧 = 2 秒，打一次心跳（太密会把 nav.log 淹掉） */
        const val HEARTBEAT_FRAMES = 100L
    }
}
