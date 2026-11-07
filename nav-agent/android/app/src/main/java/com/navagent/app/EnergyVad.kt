package com.navagent.app

import kotlin.math.sqrt

/**
 * 能量 VAD：自适应噪声底 + 双阈值，把连续音频切成"一句一句"。
 *
 * 为什么不用 silero：silero 要 onnxruntime + 2MB 模型，而车内场景（发动机低频噪音恒定、
 * 人声明显高于底噪）能量法足够，且零依赖、零延迟。先跑通，不够再换。
 *
 * ★ 两个"静默"别搞混（这是最容易写错的地方）：
 *   - 断句阈值 [minSilenceMs]（默认 800ms）：判断"这句话说完了"，触发识别
 *   - 会话休眠 5s：判断"没人再说话了"，退出连续会话（在 VoiceEngine 里）
 */
class EnergyVad(
    private val onSpeechStart: () -> Unit,
    private val onSegment: (ShortArray) -> Unit,
    /**
     * 断句静默时长（ms），每次切句时现取，设置页改完下一句就生效。
     *
     * ★ 2026-11-07 从写死的 800 改成可配。借鉴 D:/AI 的做法：那边把
     *   MIN_SILENCE_MS 放在启动脚本里，文档专门写了「抢答就调大」。
     *   车里噪音差异大，安静地库 600ms 够，高速上 1200ms 才不抢话。
     */
    private val minSilenceMs: () -> Int = { DEFAULT_SILENCE_MS }
) {

    /** 自适应噪声底，初始给个保守值 */
    private var noiseFloor = 120.0

    /** 连续多少帧超过阈值才算"开口"（3 帧 = 60ms，挡住咔哒声） */
    private var voicedFrames = 0

    /** 连续多少帧低于阈值才算"说完" */
    private var silentFrames = 0

    private val preRoll = ArrayDeque<ShortArray>()
    private val segment = ArrayList<Short>()
    private var inSpeech = false

    @Synchronized
    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
    }

    @Synchronized
    fun feed(frame: ShortArray, len: Int) {
        val rms = rms(frame, len)

        // 没在说话时缓慢跟踪噪声底：升得慢、降得快，避免被一次咳嗽带跑
        if (!inSpeech) {
            noiseFloor = if (rms < noiseFloor) {
                noiseFloor * 0.90 + rms * 0.10
            } else {
                noiseFloor * 0.995 + rms * 0.005
            }.coerceIn(40.0, 3000.0)
        }

        val threshold = maxOf(noiseFloor * 2.6, MIN_RMS)

        if (!inSpeech) {
            preRoll.addLast(frame.copyOf(len))
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

            if (rms > threshold) {
                voicedFrames++
                if (voicedFrames >= 3) {
                    inSpeech = true
                    silentFrames = 0
                    segment.clear()
                    // 带上开口前的 300ms，别把首字吃掉
                    preRoll.forEach { segment.addAll(it.toList()) }
                    preRoll.clear()
                    onSpeechStart()
                }
            } else {
                voicedFrames = 0
            }
            return
        }

        segment.addAll(frame.take(len))

        if (rms > threshold) {
            silentFrames = 0
        } else {
            silentFrames++
        }

        val silentMs = silentFrames * AudioCapture.FRAME_MS
        val totalMs = segment.size * 1000 / AudioCapture.SAMPLE_RATE

        val shouldCut = silentMs >= minSilenceMs() || totalMs >= MAX_SEGMENT_MS
        if (!shouldCut) return

        inSpeech = false
        voicedFrames = 0
        silentFrames = 0
        val pcm = segment.toShortArray()
        segment.clear()

        // 太短的是噪音（关门声、雨刷），直接丢
        if (pcm.size * 1000 / AudioCapture.SAMPLE_RATE >= MIN_SPEECH_MS) {
            Dbg.log("VAD 切出一句 ${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms")
            onSegment(pcm)
        } else {
            Dbg.log("VAD 丢弃过短片段 ${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms")
        }
    }

    /** 外部强制收尾（比如手动点"说完了"） */
    @Synchronized
    fun flush() {
        if (!inSpeech) return
        inSpeech = false
        val pcm = segment.toShortArray()
        segment.clear()
        if (pcm.isNotEmpty()) onSegment(pcm)
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    private companion object {
        const val MIN_RMS = 260.0
        /** 出厂断句时长（Prefs 默认值也是 800，两处别改岔了） */
        const val DEFAULT_SILENCE_MS = 800
        const val MIN_SPEECH_MS = 300
        const val MAX_SEGMENT_MS = 15_000
        const val PRE_ROLL_FRAMES = 15 // 300ms

    }
}
