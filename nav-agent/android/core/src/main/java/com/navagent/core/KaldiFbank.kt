package com.navagent.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kaldi fbank 特征（纯 Kotlin，无依赖）。
 *
 * **必须和 torchaudio.compliance.kaldi.fbank 逐位对齐** —— WeSpeaker 那个模型是按这套特征
 * 训练的，特征偏一点 embedding 就不可比，声纹判断直接失效。所以这里不是"大概实现一下"，
 * 而是照着 torchaudio 的 `kaldi.py` 和 Kaldi 的 `feature-window.cc` / `mel-computations.cc`
 * 一行行抄过来的。
 *
 * 参数（桌面 voiceprint.py 用的那组，一个都不许改）：
 * ```
 * num_mel_bins 80 / frame_length 25ms / frame_shift 10ms
 * window_type hamming(periodic=False, alpha=0.54, beta=0.46)
 * dither 0.0 / use_energy false / use_power true / use_log_fbank true
 * low_freq 20 / high_freq 0(=Nyquist) / preemphasis 0.97
 * remove_dc_offset true / round_to_power_of_two true / snip_edges true
 * ```
 *
 * 输出后还要做 CMN（`feat - feat.mean(0)`），在 [compute] 里一并做了。
 */
object KaldiFbank {

    const val SAMPLE_RATE = 16_000
    const val NUM_MEL_BINS = 80

    private const val FRAME_LENGTH_MS = 25.0
    private const val FRAME_SHIFT_MS = 10.0
    private const val LOW_FREQ = 20.0
    private const val HIGH_FREQ = 0.0
    private const val PREEMPH = 0.97

    /** torchaudio `_get_epsilon(float32)` —— log 之前的下限 */
    private const val EPS = 1.1920929E-7f

    private val frameLength = (FRAME_LENGTH_MS * SAMPLE_RATE / 1000).toInt()   // 400
    private val frameShift = (FRAME_SHIFT_MS * SAMPLE_RATE / 1000).toInt()     // 160
    private val paddedLength = nextPow2(frameLength)                            // 512
    private val numFftBins = paddedLength / 2                                   // 256（不含 Nyquist）
    private val fftBinWidth = SAMPLE_RATE.toDouble() / paddedLength              // 31.25 Hz

    private val window = FloatArray(frameLength) { n ->
        (0.54 - 0.46 * cos(2.0 * PI * n / (frameLength - 1))).toFloat()
    }

    /** 80 × 256 的三角滤波器权重，构造一次复用 */
    private val melBanks: Array<FloatArray> by lazy { buildMelBanks() }

    /**
     * @param pcm 16k 单声道，范围 [-1, 1]
     * @return (帧数, 80)，已做 CMN。音频太短（不足一帧）返回空数组
     */
    fun compute(pcm: FloatArray): Array<FloatArray> {
        if (pcm.size < frameLength) return emptyArray()

        // snip_edges=true：只保留完整帧
        val numFrames = 1 + (pcm.size - frameLength) / frameShift
        if (numFrames <= 0) return emptyArray()

        val feats = Array(numFrames) { FloatArray(NUM_MEL_BINS) }
        val re = FloatArray(paddedLength)
        val im = FloatArray(paddedLength)
        val power = FloatArray(numFftBins)

        for (f in 0 until numFrames) {
            val start = f * frameShift

            // 1) 减掉本帧均值（remove_dc_offset）
            var mean = 0.0
            for (i in 0 until frameLength) mean += pcm[start + i]
            mean /= frameLength

            // 2) 预加重：y[i] = x[i] - 0.97*x[i-1]
            //    ★ 第 0 个样本这里有个坑：torchaudio 用 `pad(replicate)` 做边界，
            //      所以 y[0] = x[0] - 0.97*x[0] = 0.03*x[0]，**不是** y[0]=x[0]。
            //      Kaldi 的 C++ 实现（feature-window.cc）反过来，它不动 x[0]。
            //      桌面 voiceprint.py 用的是 torchaudio，所以这里跟 torchaudio 对齐 ——
            //      实测差异只在这一维，但会让整帧特征偏掉，embedding 直接不可比。
            //    倒着算，避免用到已经被改过的 x[i-1]
            val first = (pcm[start] - mean).toFloat()
            re[0] = first - (PREEMPH * first).toFloat()
            var prev = first
            for (i in 1 until frameLength) {
                val cur = (pcm[start + i] - mean).toFloat()
                re[i] = cur - (PREEMPH * prev).toFloat()
                prev = cur
            }

            // 3) 加 hamming 窗
            for (i in 0 until frameLength) re[i] *= window[i]
            // 4) 补零到 2 的幂
            for (i in frameLength until paddedLength) re[i] = 0f
            for (i in 0 until paddedLength) im[i] = 0f

            // 5) FFT
            fft(re, im)

            // 6) 功率谱（use_power=true）。Kaldi 和 torch 的 rfft 都不做 1/N 归一化
            for (i in 0 until numFftBins) power[i] = re[i] * re[i] + im[i] * im[i]

            // 7) mel 滤波 + log
            for (b in 0 until NUM_MEL_BINS) {
                var sum = 0.0
                val w = melBanks[b]
                for (i in 0 until numFftBins) sum += w[i] * power[i]
                feats[f][b] = ln(max(sum.toFloat(), EPS).toDouble()).toFloat()
            }
        }

        // 8) CMN：减掉每一维的均值（torchaudio 里是 feat - feat.mean(0)）
        for (b in 0 until NUM_MEL_BINS) {
            var sum = 0.0
            for (f in 0 until numFrames) sum += feats[f][b]
            val m = (sum / numFrames).toFloat()
            for (f in 0 until numFrames) feats[f][b] -= m
        }
        return feats
    }

    // ------------------------------------------------------------ 内部

    /**
     * 照抄 torchaudio `get_mel_banks`（htk_compat=False、vtln=1.0 分支）：
     * ```
     * up   = (mel - left) / (center - left)
     * down = (right - mel) / (right - center)
     * w    = max(0, min(up, down))
     * ```
     * 注意 `num_fft_bins = padded/2`，**不含 Nyquist bin**。
     */
    private fun buildMelBanks(): Array<FloatArray> {
        val nyquist = 0.5 * SAMPLE_RATE
        val highFreq = if (HIGH_FREQ <= 0.0) HIGH_FREQ + nyquist else HIGH_FREQ
        val melLow = melScale(LOW_FREQ)
        val melHigh = melScale(highFreq)
        val delta = (melHigh - melLow) / (NUM_MEL_BINS + 1)

        return Array(NUM_MEL_BINS) { b ->
            val left = melLow + b * delta
            val center = melLow + (b + 1) * delta
            val right = melLow + (b + 2) * delta

            FloatArray(numFftBins) { i ->
                val mel = melScale(i * fftBinWidth)
                val up = (mel - left) / (center - left)
                val down = (right - mel) / (right - center)
                max(0.0, min(up, down)).toFloat()
            }
        }
    }

    private fun melScale(freq: Double): Double = 1127.0 * ln(1.0 + freq / 700.0)

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** 就地 radix-2 Cooley-Tukey。长度必须是 2 的幂 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size

        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    val uR = re[a].toDouble()
                    val uI = im[a].toDouble()
                    re[a] = (uR + vR).toFloat()
                    im[a] = (uI + vI).toFloat()
                    re[b] = (uR - vR).toFloat()
                    im[b] = (uI - vI).toFloat()
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
