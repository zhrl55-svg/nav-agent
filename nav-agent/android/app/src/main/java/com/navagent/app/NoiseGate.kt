package com.navagent.app

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 上传 ASR 之前的噪声门（实验，2026-11-07，默认关）。
 *
 * 包络参数照 D:/AI/app/hf-realtime-voice/worklets/mic-capture.js：
 *   attack 5ms（快开，别吃掉字头）/ hold 250ms（字间停顿继续放行）/
 *   release 80ms（慢关，尾巴不咔哒）。
 * 阈值按 20ms 一帧的 RMS 判定，和采集帧率对齐。
 *
 * ★ 和能量 VAD 的分工：VAD 决定「哪一段是一句话」，门只改这一句内部的
 *   幅度，不改变切句边界。调用方（VoiceEngine.onSegment）只把处理后的
 *   **副本**送去 ASR —— VAD / 唤醒 / 声纹仍用原始 PCM。否则门把噪声底
 *   压成 0 后，EnergyVad 的自适应噪声底会一路掉到下限，反而更容易误触发。
 *
 * ★ 每次 apply 独立计算、不跨句保留状态，所以设置页改阈值下一句就生效。
 */
class NoiseGate {

    /**
     * @param pcm 16bit 单声道整句音频
     * @param thresholdDb 门限（dBFS），越小越容易放行，默认 -45
     * @return 新的 ShortArray；原数组不变
     */
    fun apply(
        pcm: ShortArray,
        thresholdDb: Float = -45f,
        sampleRate: Int = AudioCapture.SAMPLE_RATE
    ): ShortArray {
        if (pcm.isEmpty()) return pcm
        val threshold = 10.0.pow(thresholdDb.toDouble() / 20.0)
        val out = ShortArray(pcm.size)
        val attackCoef = exp(-1.0 / ((ATTACK_MS / 1000.0) * sampleRate))
        val releaseCoef = exp(-1.0 / ((RELEASE_MS / 1000.0) * sampleRate))
        val holdSamples = (HOLD_MS / 1000.0 * sampleRate).toInt()
        val frameSamples = AudioCapture.FRAME_SAMPLES // 20ms

        var gain = 1.0
        // 开头先给一个保持窗：VAD 带 300ms pre-roll，句首那一段即使安静也
        // 不该被渐弱，否则第一个字会被削掉一截。
        var hold = holdSamples
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frameSamples, pcm.size)
            var sumSq = 0.0
            for (j in i until end) {
                val s = pcm[j].toDouble() / 32768.0
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (end - i))
            val target = when {
                rms >= threshold -> {
                    hold = holdSamples
                    1.0
                }
                hold > 0 -> {
                    hold -= (end - i)
                    1.0
                }
                else -> 0.0
            }
            for (j in i until end) {
                val coef = if (target > gain) attackCoef else releaseCoef
                gain = target + (gain - target) * coef
                val s = (pcm[j].toDouble() / 32768.0) * gain
                out[j] = (s.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
            }
            i = end
        }
        return out
    }

    private companion object {
        const val ATTACK_MS = 5.0
        const val HOLD_MS = 250.0
        const val RELEASE_MS = 80.0
    }
}
