package com.navagent.app

import com.navagent.core.SpeakerGate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * 声纹门禁（照抄桌面 `D:/AI/app/voiceprint.py` 的策略）。
 *
 * 规则：
 *  1. 还没模板时，**第一句 ≥[MIN_ENROLL_SECONDS] 秒的有效语音**自动注册成主人声纹
 *  2. 之后每句算余弦：≥ 阈值放行，< 阈值直接丢弃（等于"没听见"）
 *  3. 主人的声纹缓慢自适应（只在分数 ≥[ADAPT_MIN] 时 EMA 更新），
 *     感冒 / 座椅位置变了 / 小声说话不会把自己锁在门外
 *
 * ★ 失败一律放行。它是**防误触发**（乘客说话、导航串音），不是身份认证，别当锁用。
 */
class VoiceprintGate(
    private val encoder: SpeakerEncoder,
    private val storeFile: File,
    /** 阈值从 Prefs 读，默认 0.35（同人实测 0.56~0.77，异人 -0.09~0.28） */
    private val threshold: () -> Float = { 0.35f }
) : SpeakerGate {

    @Volatile
    private var template: FloatArray? = loadFromDisk()

    @Volatile
    private var updates = 0

    @Volatile
    private var lastSaveMs = 0L

    override val enrolled: Boolean get() = template != null

    /** 最近一次判定分数，给设置页/日志看 */
    @Volatile
    var lastScore: Float? = null
        private set

    @Synchronized
    override fun enroll(pcm: ShortArray, sampleRate: Int): Boolean {
        val seconds = pcm.size.toDouble() / sampleRate
        if (seconds < MIN_ENROLL_SECONDS) {
            Dbg.log("声纹：音频 ${"%.2f".format(seconds)}s 太短，不注册")
            return false
        }
        val emb = encoder.embed(pcm) ?: return false
        template = emb
        updates = 0
        saveToDisk(emb, force = true)
        Dbg.log("声纹已注册（${"%.2f".format(seconds)}s）—— 之后非本人语音不再回应")
        return true
    }

    @Synchronized
    override fun score(pcm: ShortArray, sampleRate: Int): Float? {
        return scoreInternal(pcm, sampleRate, adapt = true)
    }

    /**
     * 唤醒打断专用评分：不更新主人声纹模板。
     *
     * 播报中候选音频可能同时包含 TTS 和主人说话。一个勉强过线的混合
     * embedding 不能拿去做 EMA，否则模板会慢慢被播报声污染。
     *
     * ★ 2026-11-07 用户换了女声之后反馈"声纹没过滤掉播报声"，顺着查到的
     *   根源就在这条 EMA 上：以前普通识别路径（[allow] → [score] adapt=true）
     *   对**每一句**都自适应，包括播报混音里的句子。主人声音 0.6~0.7 过线，
     *   模板就被 TTS 成分一点点拉过去；用得越久，模板越像"主人 + 女声"的混合，
     *   于是播报声的分数越来越高，最后就"过滤不掉"了。
     *   现在只要这段音频和播报有重叠，一律走这里（不自适应 + 更高阈值）。
     */
    @Synchronized
    fun scoreForCheck(pcm: ShortArray, sampleRate: Int): Float? {
        return scoreInternal(pcm, sampleRate, adapt = false)
    }

    private fun scoreInternal(pcm: ShortArray, sampleRate: Int, adapt: Boolean): Float? {
        val tpl = template ?: return null
        val emb = encoder.embed(pcm) ?: return null
        if (emb.size != tpl.size) {
            Dbg.log("声纹维度不一致 emb=${emb.size} tpl=${tpl.size}")
            return null
        }
        var dot = 0f
        for (i in emb.indices) dot += emb[i] * tpl[i]
        lastScore = dot
        if (adapt && dot >= ADAPT_MIN) adapt(emb)
        return dot
    }

    /**
     * 是否放行。没注册 / 算不出 / 太短 —— 全部放行。
     *
     * @param margin 额外收紧量。播报混音里主人自己的声音只有 0.6 左右，
     *   而 TTS 回声偶尔能蹭到 0.3+，所以这段音频要更严。
     * @param adaptAllowed 是否允许用这段音频更新模板。
     *   ★ 和播报重叠的音频**必须**传 false —— 见 [adapt] 的注释。
     */
    fun allow(
        pcm: ShortArray,
        sampleRate: Int = 16_000,
        margin: Float = 0f,
        adaptAllowed: Boolean = true
    ): Boolean {
        val tpl = template ?: return true
        val seconds = pcm.size.toDouble() / sampleRate
        if (seconds < MIN_CHECK_SECONDS) {
            Dbg.log("声纹：${"%.2f".format(seconds)}s 太短，跳过判定直接放行")
            return true
        }
        val s = if (adaptAllowed) score(pcm, sampleRate) else scoreForCheck(pcm, sampleRate)
        if (s == null) return true
        val th = threshold() + margin
        val ok = s >= th
        Dbg.log(
            "声纹 ${"%.3f".format(s)} vs 阈值 ${"%.2f".format(th)}" +
                (if (margin > 0f) "(+${"%.2f".format(margin)} 播报重叠)" else "") +
                " → ${if (ok) "放行" else "丢弃"}"
        )
        return ok
    }

    /** 清掉模板（设置页「重新注册声纹」） */
    @Synchronized
    fun clear() {
        template = null
        updates = 0
        lastScore = null
        runCatching { storeFile.delete() }
        Dbg.log("声纹已清除")
    }

    /** 后台预热模型，别让第一句话等初始化 */
    fun warmup() {
        Thread {
            encoder.load()
        }.apply { isDaemon = true; name = "voiceprint-warmup" }.start()
    }

    fun statusText(): String = when {
        !encoder.ready -> "模型未加载"
        !enrolled -> "未注册（下次说话自动注册）"
        else -> "已注册 · 自适应 ${updates} 次"
    }

    // ------------------------------------------------------------ 自适应

    @Synchronized
    private fun adapt(emb: FloatArray) {
        val tpl = template ?: return
        val merged = FloatArray(tpl.size) {
            (1f - ADAPT_RATE) * tpl[it] + ADAPT_RATE * emb[it]
        }
        val norm = sqrt(merged.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 0f) return
        val next = FloatArray(merged.size) { merged[it] / norm }
        template = next
        updates++
        saveToDisk(next, force = false)
    }

    // ------------------------------------------------------------ 持久化

    private fun loadFromDisk(): FloatArray? {
        if (!storeFile.exists()) return null
        return runCatching {
            DataInputStream(storeFile.inputStream().buffered()).use { input ->
                val n = input.readInt()
                if (n <= 0 || n > 4096) return@use null
                val arr = FloatArray(n) { input.readFloat() }
                val norm = sqrt(arr.sumOf { (it * it).toDouble() }).toFloat()
                if (norm <= 0f) null else FloatArray(n) { arr[it] / norm }
            }
        }.onFailure { Dbg.log("声纹读取失败 ${it.message}") }.getOrNull()
            ?.also { Dbg.log("声纹已从文件载入（${it.size} 维）") }
    }

    @Synchronized
    private fun saveToDisk(emb: FloatArray, force: Boolean) {
        val now = System.currentTimeMillis()
        // 自适应更新很频繁，限流到 10 秒一次，别把闪存写坏
        if (!force && now - lastSaveMs < 10_000) return
        lastSaveMs = now
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.absolutePath + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(emb.size)
                emb.forEach { out.writeFloat(it) }
            }
            tmp.renameTo(storeFile)
        }.onFailure { Dbg.log("声纹保存失败 ${it.message}") }
    }

    private companion object {
        /** 第一句多长才拿来注册 */
        const val MIN_ENROLL_SECONDS = 1.0

        /** 比这还短的句子不判定，直接放行（"嗯""对"这种） */
        const val MIN_CHECK_SECONDS = 0.5

        /** 分数高于这个才更新模板 */
        const val ADAPT_MIN = 0.50f

        /** EMA 步长 */
        const val ADAPT_RATE = 0.15f
    }
}
