package com.navagent.app

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.sqrt

/**
 * WeSpeaker 声纹编码器：一段语音 → 256 维 embedding。
 *
 * 使用 Sherpa 的 SpeakerEmbeddingExtractor，因为它与播报打断 KWS 共用
 * 完整版 AAR 自带的 ONNX Runtime。此前 Microsoft ORT 与 Sherpa 的 native
 * 运行时版本不一致，两个同名 libonnxruntime.so 无法同时可靠加载。
 */
class SpeakerEncoder(private val modelFile: File) {

    private var extractor: SpeakerEmbeddingExtractor? = null

    @Volatile
    var ready = false
        private set

    /** 懒加载。失败会置 broken，之后不再重试（避免每句话都卡一次） */
    @Volatile
    private var broken = false

    @Synchronized
    fun load(): Boolean {
        if (ready) return true
        if (broken) return false
        if (!modelFile.exists() || modelFile.length() < 1_000_000) {
            Dbg.log("SpeakerEncoder：模型不存在或太小 ${modelFile.length()}B")
            broken = true
            return false
        }
        return try {
            val t0 = System.currentTimeMillis()
            val next = SpeakerEmbeddingExtractor(
                // 声纹模型运行后才下载。传 null 让 Sherpa 走 newFromFile；
                // 传 AssetManager 去读绝对路径时，该版本 native 层会直接 abort。
                null,
                SpeakerEmbeddingExtractorConfig(
                    model = modelFile.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            extractor = next
            ready = true
            Dbg.log(
                "SpeakerEncoder 就绪 ${modelFile.length() / 1024 / 1024}MB " +
                    "${System.currentTimeMillis() - t0}ms dim=${next.dim()}"
            )
            true
        } catch (t: Throwable) {
            Dbg.log("SpeakerEncoder 加载失败 ${t.javaClass.simpleName}: ${t.message}")
            broken = true
            false
        }
    }

    /**
     * @param pcm 16k 单声道 PCM16
     * @return L2 归一化后的 embedding；失败 null（调用方应放行）
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (!load()) return null
        val model = extractor ?: return null

        // 限长：超过 15 秒只取前面一段，够判定又省算力（和桌面 prepare() 一致）
        val maxSamples = MAX_SECONDS * AudioCapture.SAMPLE_RATE
        val use = if (pcm.size > maxSamples) pcm.copyOf(maxSamples) else pcm
        if (use.size < MIN_SAMPLES) return null

        val f32 = FloatArray(use.size) { use[it] / 32768f }
        val stream = runCatching { model.createStream() }.getOrElse {
            Dbg.log("SpeakerEncoder createStream 失败：${it.message}")
            return null
        }
        return try {
            stream.acceptWaveform(f32, AudioCapture.SAMPLE_RATE)
            val out = model.compute(stream)
            if (out.isEmpty()) {
                Dbg.log("embed 输出为空")
                return null
            }
            val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
            if (norm <= 0f) null else FloatArray(out.size) { out[it] / norm }
        } catch (t: Throwable) {
            Dbg.log("embed 失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    @Synchronized
    fun close() {
        runCatching { extractor?.release() }
        extractor = null
        ready = false
    }

    private companion object {
        /** 桌面 voiceprint.py 的 S2S_VOICEPRINT_MAX_SECONDS */
        const val MAX_SECONDS = 15

        /** 比 0.4s 还短就不算了（桌面 _MIN_AUDIO_S） */
        val MIN_SAMPLES = (0.4 * AudioCapture.SAMPLE_RATE).toInt()
    }
}
