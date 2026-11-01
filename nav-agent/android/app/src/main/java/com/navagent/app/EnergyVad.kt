package com.navagent.app

import kotlin.math.sqrt

/**
 * 能量 VAD：自适应噪声底 + 双阈值，把连续音频切成"一句一句"。
 *
 * 为什么不用 silero：silero 要 onnxruntime + 2MB 模型，而车内场景（发动机低频噪音恒定、
 * 人声明显高于底噪）能量法足够，且零依赖、零延迟。先跑通，不够再换。
 *
 * ★ 两个"静默"别搞混（这是最容易写错的地方）：
 *   - 断句阈值 [MIN_SILENCE_MS] 800ms：判断"这句话说完了"，触发识别
 *   - 会话休眠 5s：判断"没人再说话了"，退出连续会话（在 VoiceEngine 里）
 */
class EnergyVad(
    private val onSpeechStart: () -> Unit,
    private val onSegment: (ShortArray) -> Unit
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

    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
    }

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

        val shouldCut = silentMs >= MIN_SILENCE_MS || totalMs >= MAX_SEGMENT_MS
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
    fun flush() {
        if (!inSpeech) return
        inSpeech = false
        val pcm = segment.toShortArray()
        segment.clear()
        if (pcm.isNotEmpty()) onSegment(pcm)
    }

    /**
     * 播报期间的打断检测（barge-in）。
     *
     * 难点是回声：TTS 自己的声音会被麦克风收进来，用正常阈值必然误触发。
     * 这里三条策略叠加：
     *   1. 阈值提高到 [BARGE_IN_FACTOR] 倍 —— 人对着手机说话通常比外放回声响
     *   2. 要连续 [BARGE_IN_FRAMES] 帧（约 400ms）都超阈值 —— 回声随语音起伏，
     *      真人的发声是持续的
     *   3. **不动 noiseFloor** —— 否则 TTS 一播报就把底噪基线拉高，之后再也检测不到
     *
     * @return true 表示确认用户在说话，该停播报了
     */
    fun feedBargeIn(frame: ShortArray, len: Int): Boolean {
        val rms = rms(frame, len)
        val threshold = maxOf(noiseFloor * 2.6, MIN_RMS) * BARGE_IN_FACTOR
        if (rms > threshold) {
            bargeFrames++
            if (bargeFrames >= BARGE_IN_FRAMES) {
                bargeFrames = 0
                return true
            }
        } else {
            // 说话中间有换气停顿，掉一帧别直接清零；连续掉才放弃
            if (bargeFrames > 0) bargeFrames--
        }
        return false
    }

    /** 打断检测的状态清零（会话休眠、开始新一轮时调用） */
    fun resetBargeIn() {
        bargeFrames = 0
    }

    private var bargeFrames = 0

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
        const val MIN_SILENCE_MS = 800
        const val MIN_SPEECH_MS = 300
        const val MAX_SEGMENT_MS = 15_000
        const val PRE_ROLL_FRAMES = 15 // 300ms

        /** 打断检测的阈值倍数。调大=更难打断（少误触），调小=更灵敏（容易把自己声音当用户） */
        const val BARGE_IN_FACTOR = 2.5

        /** 20ms 一帧，20 帧 = 400ms 持续发声才算打断 */
        const val BARGE_IN_FRAMES = 20
    }
}
