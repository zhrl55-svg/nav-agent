package com.navagent.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 「这句话该不该联网搜」的判别器。
 *
 * ## 为什么不是模型
 * 让 0.6B 生成式模型判断「该不该搜」，实测一次都没成——它既不会按 `<tool_call>`
 * 输出，也不会按裸 JSON 输出。而这件事本质是个二分类，一个 10 万参数的小模型就能做。
 *
 * ## 模型
 * 字符 1/2/3-gram 哈希到 32768 个桶 → 查一张 32768×32 的表 → 取均值 → 两个 logit。
 * 权重 2.1MB（fp16），推理是查表 + 加权平均，**几十微秒**，纯 Kotlin 无依赖。
 *
 * ## 边界
 * - 只用来**加**搜索触发，不用来否掉已有规则（规则说搜就搜）。
 * - 阈值 0.9：实测真搜索都 ≥0.91，误触发最高 0.84。
 * - 只解决 search vs 其它；天气/周边/导航/休眠由 NavApp 的拦截层先处理，到不了这里。
 */
object IntentGate {

    private const val ASSET_BIN = "intent_bin.bin"
    private const val SEARCH_INDEX = 1

    @Volatile
    private var ready = false

    private var buckets = 0
    private var dim = 0
    private var classes = 0
    private var emb: FloatArray = FloatArray(0)   // buckets × dim，fp16 已转 float
    private var fcW: FloatArray = FloatArray(0)   // classes × dim
    private var fcB: FloatArray = FloatArray(0)   // classes

    /** 低于这个分数不触发。0.84 是实测误触发的最高值。 */
    const val THRESHOLD = 0.90f

    fun load(context: Context) {
        if (ready) return
        runCatching {
            val bytes = context.assets.open(ASSET_BIN).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buckets = buf.int
            dim = buf.int
            classes = buf.int
            require(buckets > 0 && dim > 0 && classes >= 2) { "头部异常" }

            val embCount = buckets * dim
            emb = FloatArray(embCount)
            for (i in 0 until embCount) emb[i] = halfToFloat(buf.short)

            fcW = FloatArray(classes * dim)
            for (i in fcW.indices) fcW[i] = buf.float

            fcB = FloatArray(classes)
            for (i in fcB.indices) fcB[i] = buf.float

            ready = true
            Dbg.log("意图门已加载 buckets=$buckets dim=$dim classes=$classes")
        }.onFailure { Dbg.log("意图门加载失败：${it.message}") }
    }

    /** 这句话像不像「该联网查一下」。加载失败时返回 0（等于不干预）。 */
    fun pSearch(text: String): Float {
        if (!ready) return 0f
        val t = normalize(text)
        if (t.isEmpty()) return 0f

        val acc = FloatArray(dim)
        var n = 0
        for (ng in 1..3) {
            if (t.length < ng) continue
            for (i in 0..t.length - ng) {
                val h = (fnv1a(t.substring(i, i + ng)) % buckets + buckets) % buckets
                val base = h * dim
                for (d in 0 until dim) acc[d] += emb[base + d]
                n++
            }
        }
        if (n == 0) return 0f
        for (d in 0 until dim) acc[d] /= n

        // 两类的 logits 与 softmax（只关心 search 这类）
        var maxL = Float.NEGATIVE_INFINITY
        val logits = FloatArray(classes)
        for (c in 0 until classes) {
            var s = fcB[c]
            val base = c * dim
            for (d in 0 until dim) s += fcW[base + d] * acc[d]
            logits[c] = s
            if (s > maxL) maxL = s
        }
        var sum = 0f
        for (c in 0 until classes) {
            logits[c] = Math.exp((logits[c] - maxL).toDouble()).toFloat()
            sum += logits[c]
        }
        return if (sum <= 0f) 0f else logits[SEARCH_INDEX] / sum
    }

    fun shouldSearch(text: String): Boolean = pSearch(text) >= THRESHOLD

    // ---------------------------------------------------------------- 内部

    /** 和训练脚本完全一致：只留字母/数字/汉字，转小写 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * FNV-1a over UTF-8 字节。
     *
     * ★ 必须和训练脚本逐位一致：Python 那边是 `h = (h * 16777619) & 0xFFFFFFFF`，
     *   Kotlin 的 Int 是有符号的，所以这里用 Long 再取低 32 位，最后转回 Int。
     */
    private fun fnv1a(s: String): Int {
        var h = 2166136261L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 16777619L) and 0xFFFFFFFFL
        }
        return h.toInt()
    }

    /** IEEE 754 half → float（Android 没有 Float.float16ToFloat） */
    private fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = bits and 0x8000
        val exp = bits and 0x7C00
        val mant = bits and 0x03FF
        val f: Int = when {
            exp == 0x7C00 -> (sign shl 16) or 0x7F800000 or (mant shl 13)
            exp == 0 -> if (mant == 0) (sign shl 16) else {
                var e = -14
                var m = mant
                while (m and 0x0400 == 0) {
                    m = m shl 1
                    e--
                }
                m = m and 0x03FF
                (sign shl 16) or ((e + 127) shl 23) or (m shl 13)
            }
            else -> (sign shl 16) or (((exp shr 10) - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(f)
    }
}
