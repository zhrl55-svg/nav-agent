package com.navagent.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.navagent.core.KaldiFbank
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * WeSpeaker 声纹编码器：一段语音 → 256 维 embedding。
 *
 * 模型 `cnceleb_resnet34_LM.onnx`（26MB，CN-Celeb 中文说话人集上训的），
 * CPU 2 线程跑，3 秒音频约 0.1s —— 跟 ASR 并行不抢资源。
 *
 * 输入必须是 **Kaldi fbank**（见 [KaldiFbank]），跟训练时一致，差一点 embedding 就不可比。
 */
class SpeakerEncoder(private val modelFile: File) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = ""
    private var outputName: String = ""

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
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            env = e
            session = s
            inputName = s.inputNames.first()
            outputName = s.outputNames.first()
            ready = true
            Dbg.log(
                "SpeakerEncoder 就绪 ${modelFile.length() / 1024 / 1024}MB " +
                    "${System.currentTimeMillis() - t0}ms in=$inputName out=$outputName"
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
        val s = session ?: return null
        val e = env ?: return null

        // 限长：超过 15 秒只取前面一段，够判定又省算力（和桌面 prepare() 一致）
        val maxSamples = MAX_SECONDS * KaldiFbank.SAMPLE_RATE
        val use = if (pcm.size > maxSamples) pcm.copyOf(maxSamples) else pcm
        if (use.size < MIN_SAMPLES) return null

        val f32 = FloatArray(use.size) { use[it] / 32768f }
        val feat = KaldiFbank.compute(f32)
        if (feat.isEmpty()) return null

        val t = feat.size
        val bins = KaldiFbank.NUM_MEL_BINS
        val flat = FloatArray(t * bins)
        for (i in 0 until t) {
            System.arraycopy(feat[i], 0, flat, i * bins, bins)
        }

        var input: OnnxTensor? = null
        return try {
            input = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(flat),
                longArrayOf(1L, t.toLong(), bins.toLong())
            )
            val out = s.run(mapOf(inputName to input)).use { result ->
                reshape(result.get(0).value)
            }
            if (out == null || out.isEmpty()) {
                Dbg.log("embed 输出形状异常")
                return null
            }
            val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
            if (norm <= 0f) null else FloatArray(out.size) { out[it] / norm }
        } catch (t2: Throwable) {
            Dbg.log("embed 失败 ${t2.javaClass.simpleName}: ${t2.message}")
            null
        } finally {
            runCatching { input?.close() }
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        ready = false
    }

    /** ONNX 的输出可能是 FloatArray / Array<FloatArray> / Array<Array<FloatArray>>，统统拍平 */
    private fun reshape(v: Any?): FloatArray? = when (v) {
        is FloatArray -> v
        is Array<*> -> {
            val acc = ArrayList<Float>(512)
            fun walk(x: Any?) {
                when (x) {
                    is FloatArray -> x.forEach { acc.add(it) }
                    is Array<*> -> x.forEach { walk(it) }
                }
            }
            walk(v)
            if (acc.isEmpty()) null else acc.toFloatArray()
        }
        else -> null
    }

    private companion object {
        /** 桌面 voiceprint.py 的 S2S_VOICEPRINT_MAX_SECONDS */
        const val MAX_SECONDS = 15

        /** 比 0.4s 还短就不算了（桌面 _MIN_AUDIO_S） */
        const val MIN_SAMPLES = (0.4 * KaldiFbank.SAMPLE_RATE).toInt()
    }
}
