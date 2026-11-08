package com.navagent.app

import android.content.Context
import com.navagent.core.Asr
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 识别引擎路由：云端 / 端侧 / 影子三档，设置页可切。
 *
 * 三档的行为约定：
 *
 * | 档位     | 返回什么            | 端侧做什么       | 用途                     |
 * |----------|---------------------|------------------|--------------------------|
 * | `cloud`  | 云端结果（旧行为）  | 不加载           | 默认值，出问题可一键回滚 |
 * | `local`  | 端侧优先，失败降级  | 真正参与决策     | 正式切主链路             |
 * | `shadow` | **云端结果**        | 后台跑，只记日志 | 灰度期收数据，零风险     |
 *
 * 为什么要 `shadow` 这一档：端侧识别在隧道、地库、嘈杂车内的真实表现，
 * 光靠标定集测不出来。影子模式让端侧跟着跑一周但不参与决策，拿到
 * 「和云端的一致率」再切，比拍脑袋上线安全得多。
 *
 * ★ 切到 `local` 之前，必须先确认 `filesDir/models/asr-sensevoice/` 里有模型。
 *   模型缺失时 [LocalAsr] 返回 null，这里会自动降级云端——不会哑掉，但也没收益。
 */
class AsrRouter(private val context: Context) : Asr {

    private val cloud = MinimaxAsr()
    private val local = LocalAsr(context)

    /** 影子对比串行执行：识别器不是线程安全的，也避免堆积 */
    private val shadowExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-shadow").apply { isDaemon = true }
    }
    private val shadowBusy = AtomicBoolean(false)

    @Volatile
    private var shadowSamples = 0
    @Volatile
    private var shadowSame = 0

    init {
        // 预热：模型加载 1~2 秒，不能等到用户说第一句时才付这个时间
        if (engine() != ENGINE_CLOUD) {
            Thread {
                local.ensureReady()
            }.apply {
                name = "asr-warmup"
                isDaemon = true
                start()
            }
        }
    }

    private fun engine(): String = Prefs.asrEngine

    val localReady: Boolean get() = local.ready

    override fun transcribe(pcm: ShortArray, sampleRate: Int): String? =
        when (engine()) {
            ENGINE_LOCAL -> localFirst(pcm, sampleRate)
            ENGINE_SHADOW -> shadow(pcm, sampleRate)
            else -> cloud.transcribe(pcm, sampleRate)
        }

    // ---------------------------------------------------------------- 各档实现

    /** 端侧优先。端侧不可用（模型缺失/解码异常）才降级云端。 */
    private fun localFirst(pcm: ShortArray, sampleRate: Int): String? {
        val text = local.transcribe(pcm, sampleRate)
        if (text != null) {
            // null 才是「本地不可用」；空串是「确实没说话」，不要拿它去浪费一次云端请求
            return text
        }
        Dbg.log("ASR: 端侧不可用，降级云端")
        return cloud.transcribe(pcm, sampleRate)
    }

    /**
     * 影子模式：返回值和 `cloud` 档**完全一致**，端侧结果只落日志。
     * 这样灰度期间用户体验没有任何变化，风险为零。
     */
    private fun shadow(pcm: ShortArray, sampleRate: Int): String? {
        val primary = cloud.transcribe(pcm, sampleRate)

        // 前一次对比还没跑完就跳过，宁可少样本也不要拖慢主链路
        if (shadowBusy.compareAndSet(false, true)) {
            // pcm 会被调用方复用，必须拷贝
            val copy = pcm.copyOf()
            shadowExec.execute {
                try {
                    val t = local.transcribe(copy, sampleRate)
                    logCompare(primary, t)
                } catch (t: Throwable) {
                    Dbg.log("影子对比 异常 ${t.javaClass.simpleName}")
                } finally {
                    shadowBusy.set(false)
                }
            }
        }
        return primary
    }

    private fun logCompare(cloudText: String?, localText: String?) {
        if (cloudText.isNullOrBlank() || localText.isNullOrBlank()) {
            Dbg.log("影子对比 云端「${cloudText ?: "null"}」 本地「${localText ?: "null"}」 跳过")
            return
        }
        shadowSamples++
        val same = cloudText == localText
        if (same) shadowSame++
        Dbg.log(
            "影子对比 云端「$cloudText」 本地「$localText」 " +
                "差异=${diffPercent(cloudText, localText)}% " +
                "一致率=${if (shadowSamples == 0) 0 else shadowSame * 100 / shadowSamples}%" +
                " (n=$shadowSamples)"
        )
    }

    // ---------------------------------------------------------------- 统计

    /** 设置页「诊断」那一行用的就是它，格式沿用 [MinimaxAsr.statsText] */
    fun statsText(): String = when (engine()) {
        ENGINE_LOCAL -> "端侧 ${local.statsText()}｜云端备用 ${cloud.statsText()}"
        ENGINE_SHADOW -> "影子 云端 ${cloud.statsText()}｜端侧 ${local.statsText()}"
        else -> cloud.statsText()
    }

    fun release() {
        local.release()
        shadowExec.shutdownNow()
    }

    private companion object {
        const val ENGINE_CLOUD = "cloud"
        const val ENGINE_LOCAL = "local"
        const val ENGINE_SHADOW = "shadow"

        /**
         * 字级编辑距离占比，用来量化两套引擎的差异。
         * 这里没有标准答案可对，所以只能算「互相差多少」，不是真正的 CER。
         * 一致率高 + 差异集中在同音字/断句，说明端侧够用。
         */
        fun diffPercent(a: String, b: String): Int {
            if (a == b) return 0
            val n = a.length
            val m = b.length
            if (n == 0 || m == 0) return 100
            var prev = IntArray(m + 1) { it }
            var cur = IntArray(m + 1)
            for (i in 1..n) {
                cur[0] = i
                for (j in 1..m) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return prev[m] * 100 / maxOf(n, m)
        }
    }
}
