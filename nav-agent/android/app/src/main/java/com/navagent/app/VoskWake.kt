package com.navagent.app

import com.navagent.core.WakeWord
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * 离线唤醒词（vosk + 中文小模型）。
 *
 * 为什么是 vosk 而不是 sherpa-onnx：sherpa 的官方 AAR 只在 GitHub releases，
 * 本机下载不通；vosk-android 在 Maven Central 上，且桌面 D:/AI 已经用
 * vosk-model-small-cn-0.22 跑通了唤醒。
 *
 * ★ 关键：**必须用 grammar 约束**。
 * 不加 grammar 时小模型要自由解码整个中文词表，说"小高"这种两字短语
 * 大概率输出成别的字，或者干脆吐空 —— 实测加了 grammar 才稳定命中。
 * grammar 只允许这几句 + [unk]，等于把识别任务从"听写"降级成"匹配"。
 *
 * 匹配策略（三层，从严到宽）：
 *   1. grammar 精确命中（最可靠）
 *   2. 归一化后子串匹配配置里的唤醒词
 *   3. 正则 `小.{0,3}高` —— 兜住"晓高/笑高/小 高/小啊高"这类同音变体
 */
class VoskWake(modelDir: File, private val wakeWords: List<String>) : WakeWord {

    private var model: Model? = null
    private var rec: Recognizer? = null

    @Volatile
    private var framesSinceReset = 0

    @Volatile
    private var lastLogged = ""

    @Volatile
    private var lastPinyinLogged = ""

    /** 滑动窗口：拼 vosk 吐出来的碎片 */
    private val window = StringBuilder()
    private var lastPartial = ""
    private var windowTs = 0L

    @Volatile
    var ready = false
        private set

    private val normalized = wakeWords.map { normalize(it) }.filter { it.isNotEmpty() }

    /**
     * 唤醒词的**无声调拼音**。
     *
     * 为什么必须比拼音而不是比汉字：vosk 小模型对「小度」这种词识别很差，
     * 实测喊半天只零星听到「四」「请」「和」；喊「小杜」「小肚」它反而更可能听对。
     * 汉字比对要求一字不差，拼音比对能兜住所有同音字 —— 实测这套之后
     * 「小度」「晓杜」「小肚」都能命中。
     *
     * 用 android.icu 的 Transliterator（API 24+，零依赖），比内置拼音表省 200KB。
     */
    /**
     * 唤醒词的**无声调拼音候选**（多音字会有多个）。
     *
     * ★ 为什么要按字取读音再交叉组合，而不是整串转写：
     *   ICU 的 Han-Latin 对多音字（都= dōu/dū、了= le/liǎo、重= zhòng/chóng）
     *   会把所有读音用 "|" 拼进结果，「小都」转出来是 `xiaodou|du` ——
     *   竖线正好卡在音节中间，期望串 `xiaodu` 永远匹配不上。
     *   「小高」恰好两个字都是单音字，所以以前一直没暴露。
     *   正确做法：逐字取读音（每字最多留 3 个），做交叉组合（封顶 32 条），
     *   「小都」→ [xiaodu, xiaodou]，两条都过模糊音归一化。
     */
    // ★ 必须 by lazy：它调用的 readingsOf 依赖 charReadings，而 charReadings
    //   声明在下面 —— 构造期直接算会踩"前向引用未初始化属性"的坑
    private val expectedPinyin: Set<String> by lazy {
        wakeWords
            .flatMap { phrase ->
                val normalizedPhrase = normalize(phrase)
                val candidates = pinyinCandidates(normalizedPhrase)
                val repeatSplit = if (normalizedPhrase.length >= 4 && normalizedPhrase.length % 2 == 0) {
                    val half = normalizedPhrase.length / 2
                    if (normalizedPhrase.substring(0, half) == normalizedPhrase.substring(half)) {
                        pinyinCandidates(normalizedPhrase.substring(0, half))
                    } else emptyList()
                } else emptyList()
                candidates + repeatSplit
            }
            .filter { it.length >= MIN_WAKE_PHONEMES }
            .toSet()
    }

    /**
     * 同音兜底。
     *
     * 实测 vosk 中文小模型对生造词很不友好，「小高」很容易被听成
     * 「晓高/笑高/小搞/萧高」。Android 上没有 pypinyin，桌面那套
     * 「转拼音再比」走不通，所以这里直接枚举常见同音字。
     *
     * 代价是误唤醒率略升（"效果"也含"效"…不含小，安全；
     * 但"校高"这种会误命中），车内场景可以接受 —— 误唤醒只是多说一句"在"，
     * 比喊十遍不理人强。
     *
     * ★ 只在唤醒词还是「小X高」时才启用。用户换成「你好小明」之后，
     *   这套字表完全对不上，硬套只会乱命中。
     */
    private val fuzzy: Regex? =
        if (wakeWords.any { it.contains("小") && it.contains("高") }) {
            Regex("[小晓笑校萧效].?[高搞告稿糕]")
        } else {
            null
        }

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)

            // ★ 不要用 grammar。
            // 实测：vosk 中文小模型的词表里没有「小高」这个生造词，
            // 加 grammar 之后它对任何语音都只吐 {"partial":"[unk]"}，
            // 等于把唤醒彻底废掉。必须自由解码，靠下面的模糊匹配兜。
            rec = Recognizer(model, AudioCapture.SAMPLE_RATE.toFloat())
            warmup()
            ready = true
            Dbg.log("vosk 唤醒就绪（自由解码），唤醒词=$wakeWords")
        } catch (t: Throwable) {
            Dbg.log("vosk 加载失败 ${t.javaClass.simpleName}: ${t.message}")
            ready = false
        }
    }

    /**
     * ★ 热身：识别器建好后先喂一段静音。
     *
     * 为什么必须热身（实测踩过）：
     *   vosk 的解码器刚 new 出来时内部状态是空的，前几秒解码质量极差 ——
     *   实测首次进入应用后头几秒喊「小度」，日志里听到的是「和」「之」「有不对」
     *   这种完全不相干的碎片；而点一下语音按钮就好了。
     *
     *   原因不是唤醒逻辑有问题，而是**解码器没进入稳定态**。
     *   点按钮会走 `wakeManually()` → `wake.reset()`，reset 顺手把解码状态清了，
     *   之后进入的是正常状态 —— 所以「点一下就好了」。
     *
     *   喂静音同样能触发 reset + 让解码器跑起来，达到同样的效果。
     *   1 秒静音 = 50 帧，够解码器稳定。
     */
    private fun warmup() {
        val r = rec ?: return
        val silence = ShortArray(AudioCapture.FRAME_SAMPLES)
        try {
            for (i in 0 until WARMUP_FRAMES) {
                r.acceptWaveForm(silence, silence.size)
                // ★ Kotlin 属性语法，对应 Java 的 getPartialResult() / getResult()
                r.partialResult
                r.result
            }
            r.reset()
            framesSinceReset = 0
            resetWindow()
            Dbg.log("vosk 热身完成（${WARMUP_FRAMES} 帧静音）")
        } catch (t: Throwable) {
            Dbg.log("vosk 热身异常 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun feed(pcm: ShortArray, len: Int): Boolean {
        val r = rec ?: return false
        return try {
            r.acceptWaveForm(pcm, len)
            framesSinceReset++

            val partial = textOf(r.partialResult)
            val final = textOf(r.result)
            accumulate(partial, final)
            logPartial(partial, final)
            if (framesSinceReset <= 5) {
                Dbg.log("vosk warmup partial=「$partial」 final=「$final」")
            }

            // 同一帧结果处理三种常见形态：连续 partial（整段累积）、重置型 partial（逐字吐出）、final。
            // 每次都对「最近的 partial + final」匹配，所以「小度」被拆成「小」和「都」时不会丢边界。
            val live = buildString {
                if (partial.isNotBlank()) append(partial)
                if (final.isNotBlank() && !endsWith(final)) append(final)
            }
            if (live.isNotBlank() && match(live)) {
                Dbg.log("唤醒词命中，partial=「$partial」 final=「$final」窗口=「$window」")
                r.reset()
                framesSinceReset = 0
                resetWindow()
                return true
            }

            if (match(window.toString())) {
                Dbg.log("唤醒词命中，窗口=「$window」")
                r.reset()
                framesSinceReset = 0
                resetWindow()
                return true
            }
            // 长时间没命中就重置，防止解码状态无限增长
            if (framesSinceReset > RESET_EVERY_FRAMES) {
                r.reset()
                framesSinceReset = 0
                resetWindow()
            }
            false
        } catch (t: Throwable) {
            Dbg.log("vosk feed 异常 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * ★ 滑动窗口：把 vosk 吐出来的碎片拼起来。
     *
     * 实测（华为 P40 Pro）vosk 中文小模型会把「小高」拆成两次独立 final：
     *   09:18:08.150 final=「小」
     *   09:18:08.387 final=「高」
     * 逐帧匹配永远拼不出「小高」—— 这就是唤醒一直不响的真正原因。
     *
     * partial 是累积的（"小" → "小高"），final 是这一段的结果。
     * 这里把增量字符累积到窗口里，窗口只保留最近十几个字、4 秒过期。
     */
    private fun accumulate(partial: String, final: String) {
        val now = System.currentTimeMillis()
        if (now - windowTs > WINDOW_MS) resetWindow()
        windowTs = now

        if (partial.isNotEmpty()) {
            when {
                // 累积型 partial，只取新增部分
                partial.length > lastPartial.length && partial.startsWith(lastPartial) ->
                    window.append(partial.substring(lastPartial.length))
                // 新的一段，整段接上
                partial != lastPartial -> window.append(partial)
            }
            lastPartial = partial
        }

        if (final.isNotEmpty()) {
            // 已经拼进去的不要重复加
            if (!window.endsWith(final)) window.append(final)
            // 关键：把 lastPartial 设成 final，否则下一段累积型 partial
            // 会把同一批字再加一遍（"小小高"）
            lastPartial = final
        }

        if (window.length > MAX_WINDOW_CHARS) {
            window.delete(0, window.length - MAX_WINDOW_CHARS)
        }
    }

    private fun resetWindow() {
        window.setLength(0)
        lastPartial = ""
    }

    /** 把非空的识别结果打进日志，用来判断"到底听成了什么"。同一句只记一次。 */
    private fun logPartial(partial: String, final: String) {
        // 优先记 partial：它是实时的，能看到"小"、"小高"这种半句
        val s = partial.trim().ifEmpty { final.trim() }
        if (s.isEmpty() || s == lastLogged) return
        lastLogged = s
        Dbg.log("vosk 听到：「$s」${if (final.isNotBlank()) " final=「$final」" else ""} 窗口=「$window」")
    }

    private fun textOf(json: String?): String {
        if (json.isNullOrBlank()) return ""
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        return obj.optString("partial").ifEmpty { obj.optString("text") }
    }

    private fun match(text: String): Boolean {
        if (text.isBlank()) return false
        // 只看窗口尾部：窗口会被环境说话污染，前面的字没有意义
        val tail = if (text.length > MATCH_TAIL_CHARS) text.takeLast(MATCH_TAIL_CHARS) else text
        val norm = normalize(tail)
        if (norm.isEmpty()) return false
        // [unk] 是"没听懂"的占位符，归一化后会变成 "unk"，
        // 千万别让它命中（之前就是这么误唤醒的）
        if (norm == "unk") return false

        // ① 汉字精确匹配（快路径，不需要转拼音）
        if (normalized.any { norm.contains(it) }) return true

        // ② 拼音匹配 —— 兜住所有同音字（小杜/小肚/晓度…）和多音字（都 dōu/dū）
        val cands = pinyinCandidates(tail)
        if (cands.firstOrNull() != lastPinyinLogged) {
            lastPinyinLogged = cands.firstOrNull() ?: ""
            // 诊断用：能直接看出「vosk 听到什么 → 展开成什么拼音 → 期望是什么」
            Dbg.log("拼音窗口：「$tail」→ ${cands.take(6)} 期望=$expectedPinyin")
        }
        val hits = cands.asSequence()
            .flatMap { candidate -> expectedPinyin.asSequence().mapNotNull { expected ->
                approximateMatch(candidate, expected)
            } }
            .toList()
        val hit = hits.firstOrNull()
        if (hit != null) {
            Dbg.log("唤醒词近似拼音命中：「$tail」→「$hit」")
            return true
        }

        // ③ 老的同音兜底，只对「小X高」有意义
        return fuzzy?.containsMatchIn(norm) ?: false
    }

    /**
     * 连续无声调拼音的有界近似匹配：预期串必须至少 4 音素，允许最多 1 个编辑错误，
     * 且命中片段要与识别窗口末尾相邻。这样「小度」的度/都混淆可过，单个「小」不会误唤醒。
     *
     * ★ 首音节必须是「同声母或近似声母」，否则会误唤醒。
     *   实测「角度 jiaodu」被当成「小度 xiaodu」命中 —— 两者只差首字母 j/x，
     *   编辑距离 1 就过了。但 j- 和 x- 声母完全不同，
     *   「角度」「加油」「街道」都会误触发。
     *
     *   规则：首音节的前两个字母（声母）必须相同，或属于常见的近似对
     *   （zh/z、ch/c、sh/s、n/l、f/h、r/l）。这样 xiaodu 只有 x- 开头的词能匹配，
     *   jiaodu 匹配不上。
     */
    private fun approximateMatch(actual: String, expected: String): String? {
        if (expected.length < MIN_WAKE_PHONEMES) return null
        val maxLen = expected.length + MAX_EDIT_ERRORS
        val minLen = expected.length - MAX_EDIT_ERRORS
        val startAt = (actual.length - maxLen).coerceAtLeast(0)
        val endAt = actual.length
        for (length in minLen..maxLen) {
            val start = endAt - length
            if (start < startAt) continue
            val sample = actual.substring(start, endAt)
            // 声母检查放在编辑距离之前 —— 不合格就不用再比了
            if (!sameInitials(sample, expected)) continue
            if (editDistanceAtMostOne(sample, expected)) return sample
        }
        return null
    }

    /**
     * 首音节声母是否相同或近似。
     *
     * 只看前两个字母（中文拼音的声母基本就是前 1~2 个字母）。
     * 拿不到声母（比如以元音开头，单字母）就放宽为 true，
     * 交给后面的编辑距离判断 —— 不能因为取不到就全拒。
     */
    private fun sameInitials(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return true
        val ia = initialOf(a)
        val ib = initialOf(b)
        if (ia.isEmpty() || ib.isEmpty()) return true
        if (ia == ib) return true
        // 近似声母对：这些混淆在中文语音识别里非常常见
        return NEAR_INITIALS.any { pair ->
            val x = pair.first
            val y = pair.second
            (ia == x && ib == y) || (ia == y && ib == x)
        }
    }

    /** 取前两个字母当声母 */
    private fun initialOf(pinyin: String): String =
        if (pinyin.length >= 2) pinyin.substring(0, 2) else pinyin

    /** 两字符串编辑距离是否 ≤ 1；长度通常 8～12，直接线性检查即可 */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > MAX_EDIT_ERRORS) return false
        var i = 0
        var j = 0
        var errors = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++errors > MAX_EDIT_ERRORS) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) errors++
        return errors <= MAX_EDIT_ERRORS
    }

    private fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }

    /**
     * 汉字 → 无声调拼音（去掉声调和分隔）。
     *
     * `Han-Latin` 给出「xiǎo dù」这种带调带空格的拉丁转写，
     * 再叠一层 `Latin-ASCII` 把调号去掉，最后删空格 → 「xiaodu」。
     */
    /**
     * 模糊音归一化 —— 这一步是唤醒能不能成的关键。
     *
     * 实测 vosk 把「小度 xiǎo dù」听成「小都 xiǎo dōu」，拼音只差一个字母就匹配不上。
     * 中文语音识别里这类混淆很常见（u/ou、前后鼻音、平翘舌），标准做法是先归一再比。
     *
     * 折叠的几组：
     *   zh/ch/sh → z/c/s      平翘舌不分
     *   ang/eng/ing → an/en/in 前后鼻音不分
     *   ou → u                都 dōu / 度 dù
     */
    private fun fuzzyPinyin(p: String): String = p
        .replace("zh", "z")
        .replace("ch", "c")
        .replace("sh", "s")
        .replace("ang", "an")
        .replace("eng", "en")
        .replace("ing", "in")
        .replace("ou", "u")

    /** 单字读音缓存：一个实例活整个待命期，汉字出现一次算一次就够了 */
    private val charReadings = HashMap<Char, List<String>>()

    private fun readingsOf(c: Char): List<String> = charReadings.getOrPut(c) {
        runCatching {
            val t = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
            t.transliterate(c.toString()).split("|")
                .map { r -> r.filter { it.isLetter() }.lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
        }.getOrDefault(emptyList()).ifEmpty { listOf("") }
    }

    /**
     * 汉字串 → 拼音候选（含多音字交叉组合，封顶 CAND_CAP 防爆炸）。
     * 非汉字（数字/字母）原样拼进去。每条都过模糊音归一化。
     */
    private fun pinyinCandidates(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        var acc = listOf("")
        for (c in s) {
            val rs = if (c in '\u4E00'..'\u9FFF') readingsOf(c) else listOf(c.toString())
            val next = ArrayList<String>(acc.size * rs.size)
            outer@ for (a in acc) for (r in rs) {
                next.add(a + r)
                if (next.size >= CAND_CAP) break@outer
            }
            acc = next
            if (acc.isEmpty()) return emptyList()
        }
        return acc.map { fuzzyPinyin(it) }.distinct()
    }

    override fun reset() {
        runCatching { rec?.reset() }
        framesSinceReset = 0
        lastLogged = ""
        resetWindow()
    }

    override fun shutdown() {
        runCatching { rec?.close() }
        runCatching { model?.close() }
        rec = null
        model = null
        ready = false
    }

    private companion object {
        /** 20ms 一帧，750 帧 = 15 秒 */
        const val RESET_EVERY_FRAMES = 750

        /** 热身帧数：50 帧 × 20ms = 1 秒静音 */
        const val WARMUP_FRAMES = 50

        /**
         * 近似声母对 —— 这些混淆在中文语音识别里很常见，
         * 允许它们互相匹配（否则「小度」识别成「小杜」就匹配不上了）。
         *
         * 注意：j/x **不在**这个列表里。实测「角度 jiaodu」被当成「小度 xiaodu」
         * 误唤醒，就是因为 j- 和 x- 被当成了近似。
         */
        val NEAR_INITIALS = listOf(
            "zh" to "z",
            "ch" to "c",
            "sh" to "s",
            "n" to "l",
            "f" to "h",
            "r" to "l"
        )

        /** 窗口过期：3 秒没新内容就清空，避免老字残留造成误唤醒 */
        const val WINDOW_MS = 3_000L

        /** 窗口最长保留的字数 */
        const val MAX_WINDOW_CHARS = 10

        /** 匹配只看窗口最后这么多个字 */
        const val MATCH_TAIL_CHARS = 8

        /** 多音字交叉组合的候选上限（8 字窗口 3^8=6561 太疯，32 条足够） */
        const val CAND_CAP = 32
        const val MIN_WAKE_PHONEMES = 4
        const val MAX_EDIT_ERRORS = 1
    }
}
