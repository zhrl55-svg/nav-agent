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
    override var ready = false
        private set

    /**
     * 最近一次命中时的识别文本（live 或窗口）。
     *
     * 播报期间用它分辨「唤醒词是播报里带的」还是「用户喊的」：
     * 文本里唤醒词比当前播报内容多一次，多出来的那次才是用户说的。
     */
    @Volatile
    override var lastMatchText: String? = null
        private set

    /**
     * 当前滑动窗口文本。宿主的打断词匹配（[com.navagent.core.BargeIn]）用。
     *
     * 只在采集线程上、紧跟 [feed] 之后读（VoiceEngine 里包在 wakeLock 中）。
     * 返回副本而不是 StringBuilder，避免调用方拿着引用跨帧读到半截。
     */
    override val heardText: String
        get() = window.toString()

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
     * ★ 2026-11-01 修：这段原来是写死的「小X高」，唤醒词换成「小度」之后
     *   这层兜底**整个失效**（条件不成立直接返回 null）。
     *   实测「小多」还能靠拼音匹配兜住，但「晓度」「小杜」「小渡」这种
     *   中间夹字或首字不同音的会漏。现在按当前唤醒词动态生成同音字表。
     */
    private val fuzzy: Regex? = buildFuzzy(wakeWords)

    /**
     * 按唤醒词生成同音兜底正则。
     *
     * 两条路径：
     *  1. **逐字精确**：唤醒词里每个字都在 [HOMOPHONES] 表里，就生成
     *     `[小晓笑校…][猪朱珠竹…][佩配培赔…][奇其琪骑…]` —— 每个位置只认同音字，
     *     误唤醒率最低。四字唤醒词（「小猪佩奇」）走这条。
     *  2. **首尾兜底**：有字没收录，退化成「首字同音组 + 中间任意 + 尾字同音组」。
     *     宽是宽了点（「小什么奇」都算），但总比完全没兜底强。
     *
     * ★ 为什么要两条：原来只认「小X高」两字结构，唤醒词换成「小度」时这层直接
     *   返回 null（条件不成立），换成「小猪佩奇」更是完全没覆盖 —— 实测日志里
     *   每次启动都是 `唤醒同音兜底正则=null`。
     */
    private fun buildFuzzy(words: List<String>): Regex? {
        val head = HOMOPHONES['小'] ?: "小"
        val parts = words.mapNotNull { w ->
            val chars = w.toList()
            when {
                chars.size < 2 -> null
                // ① 逐字都有同音组：最精确
                chars.all { HOMOPHONES.containsKey(it) } ->
                    chars.joinToString("") { "[${HOMOPHONES[it]}]" }
                // ② 有字没收录：首尾兜底，中间允许 0~n 个字
                else -> {
                    val tail = HOMOPHONES[chars.last()] ?: return@mapNotNull null
                    "[$head].{0,${chars.size - 1}}[$tail]"
                }
            }
        }.distinct()
        return if (parts.isEmpty()) null else Regex(parts.joinToString("|"))
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
        // ★ 诊断日志放在 try 外面，且只打 fuzzy。
        //   踩过：在 init 块里插值 $expectedPinyin，触发 by lazy 的构造期前向引用，
        //   抛 NullPointerException 被上面的 catch 抓住 → ready 被打成 false，
        //   唤醒直接失效（日志里能看到「唤醒就绪」紧跟一行「加载失败 NPE」）。
        runCatching { Dbg.log("唤醒同音兜底正则=$fuzzy") }
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

    override fun feed(pcm: ShortArray, len: Int, strict: Boolean): Boolean {
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
            if (live.isNotBlank() && match(live, strict)) {
                Dbg.log(
                    "唤醒词命中${if (strict) "（播报严格模式）" else ""}，" +
                        "partial=「$partial」 final=「$final」窗口=「$window」"
                )
                lastMatchText = live
                r.reset()
                framesSinceReset = 0
                resetWindow()
                return true
            }

            if (match(window.toString(), strict)) {
                Dbg.log("唤醒词命中${if (strict) "（播报严格模式）" else ""}，窗口=「$window」")
                lastMatchText = window.toString()
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

    private fun match(text: String, strict: Boolean = false): Boolean {
        if (text.isBlank()) return false
        // 只看窗口尾部：窗口会被环境说话污染，前面的字没有意义
        val tail = if (text.length > MATCH_TAIL_CHARS) text.takeLast(MATCH_TAIL_CHARS) else text
        val norm = normalize(tail)
        if (norm.isEmpty()) return false
        // [unk] 是"没听懂"的占位符，归一化后会变成 "unk"，
        // 千万别让它命中（之前就是这么误唤醒的）
        if (norm == "unk") return false

        // 播报期间的严格模式只认完整唤醒词。
        // 真机日志里「要知道着急」「需要时吧」「买票而」都曾被下面的
        // 词首丢失 / 片段 / 近似拼音兜底误判成「小猪」。这些兜底适合
        // 待命时尽量叫醒用户，但绝不能用来判断是否要打断导航播报。
        if (strict) return strictMatch(norm, tail)

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

        // ③ 词首丢失兜底 —— 见 [tailMatch] 注释。
        //    ★ 2026-11-02 加：真机日志里 vosk 把「小猪佩奇」稳定识别成
        //    「猪配其」/「猪皮」，「小」一次都没出现过。窗口拼音 `zupeiqi`
        //    和期望 `xiaozupeiqi` 差 4 个字母，编辑距离 4 > 阈值 2，
        //    所以永远唤不醒 —— 这不是阈值问题，是缺一整段。
        val tailHit = cands.firstNotNullOfOrNull { candidate ->
            expectedPinyin.firstNotNullOfOrNull { expected ->
                tailMatch(candidate, expected)
            }
        }
        if (tailHit != null) {
            Dbg.log("唤醒词词首丢失兜底命中：「$tail」→「$tailHit」")
            return true
        }

        // ④ 任意片段兜底 —— 只对四字及以上的唤醒词启用。
        //    用户明确要求"不怕误唤醒"，四字词听到任意两个字就认。
        //    两字词不进来，否则「角度」会被 `aodu` 片段重新误唤醒。
        val segHit = cands.firstNotNullOfOrNull { candidate ->
            expectedPinyin.firstNotNullOfOrNull { expected ->
                anySegmentMatch(candidate, expected)
            }
        }
        if (segHit != null) {
            Dbg.log("唤醒词片段命中：「$tail」→「$segHit」")
            return true
        }

        // ③ 老的同音兜底，只对「小X高」有意义
        return fuzzy?.containsMatchIn(norm) ?: false
    }

    /**
     * 播报期间的严格匹配。
     *
     * 允许：
     *  - 完整汉字唤醒词，例如「小猪」「你好小猪」
     *  - 完整同音词，例如「小朱」「晓猪」
     *  - 完整拼音等价，例如「小都」与「小度」
     *
     * 禁止：词首丢失、任意片段、编辑距离近似。那些规则是待命唤醒的
     * 召回兜底，放在 TTS 自身播放的连续文本上会产生大量误打断。
     */
    private fun strictMatch(norm: String, tail: String): Boolean {
        if (normalized.any { norm.contains(it) }) return true

        val candidates = pinyinCandidates(tail)
        val pinyinHit = candidates.asSequence()
            .flatMap { candidate -> expectedPinyin.asSequence().map { candidate to it } }
            .firstOrNull { (candidate, expected) -> candidate.contains(expected) }
        if (pinyinHit != null) {
            Dbg.log("严格模式完整拼音命中：「$tail」→「${pinyinHit.second}」")
            return true
        }

        if (fuzzy?.containsMatchIn(norm) == true) {
            Dbg.log("严格模式完整同音词命中：「$tail」")
            return true
        }
        return false
    }

    /**
     * 连续无声调拼音的有界近似匹配：预期串必须至少 4 音素，允许 MAX_EDIT_ERRORS 个编辑错误，
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
            if (editDistanceWithinLimit(sample, expected)) return sample
        }
        return null
    }

    /**
     * 词首丢失兜底：允许识别结果只覆盖唤醒词的**后半截**。
     *
     * ★ 为什么需要（2026-11-02，真机日志实证）：
     *   唤醒词配的是「小猪佩奇」，vosk 中文小模型稳定只吐后三个字 ——
     *   日志里「猪」「配」「其/七」「皮」都出现过，**「小」一次都没有**。
     *   窗口拼音是 `zupeiqi`，期望 `xiaozupeiqi`，少的是一整段 `xiao`，
     *   编辑距离 4，远超阈值 2。所以这不是"放宽阈值"能救的，是缺了开头。
     *
     *   vosk 小模型对词首轻音节、连读首字吞音很常见（喊「小猪佩奇」时
     *   「小」和后面的「猪」连得太紧，解码器直接并进下一个词）。
     *
     * 做法：拿 expected 的**后缀**去和 actual 的尾部比，覆盖率达到
     * [MIN_COVER_RATIO] 就算命中。
     *
     *   xiaozupeiqi 的后缀 zupeiqi（7/11 = 64%）→ 命中 ✓
     *   peiqi（5/11 = 45%）→ 不命中 ✓（否则任何说「佩奇」的都唤醒）
     *
     * 对两字唤醒词（如「小度」xiaodu）几乎无副作用：去掉词首只剩 `du`，
     * 长度不够 MIN_WAKE_PHONEMES，而且 sameInitials 也会挡住。
     */
    private fun tailMatch(actual: String, expected: String): String? {
        if (expected.length < MIN_WAKE_PHONEMES) return null
        // 覆盖率下限：至少认到唤醒词拼音的 60%
        val minCover = maxOf(MIN_WAKE_PHONEMES, expected.length * 3 / 5)
        // 从"整串"开始往下试，命中越长的后缀越可信
        for (keep in expected.length downTo minCover) {
            val suffix = expected.substring(expected.length - keep)
            val maxLen = keep + MAX_EDIT_ERRORS
            val minLen = keep - MAX_EDIT_ERRORS
            for (length in minLen..maxLen) {
                if (length > actual.length) continue
                val sample = actual.substring(actual.length - length)
                if (!sameInitials(sample, suffix)) continue
                if (editDistanceWithinLimit(sample, suffix)) return sample
            }
        }
        return null
    }

    /**
     * 任意片段兜底：唤醒词的**任意一段**命中就算（只对长词启用）。
     *
     * ★ 2026-11-02 加。用户明确要求「不怕误唤醒，喊得醒就行」。
     *   [tailMatch] 只能兜"词首丢了"；如果 vosk 丢的是中间或结尾
     *   （「小猪…奇」「小…佩奇」），后缀匹配照样不中。这里把 expected 的
     *   每一段都拿去比，任意 45% 以上的片段命中即可。
     *
     *   `xiaozupeiqi` 的片段：
     *     `xiaozu`（小猪）→ 命中 ✓
     *     `zupei`（猪佩）→ 命中 ✓
     *     `peiqi`（佩奇）→ 命中 ✓
     *     `zu`（猪）→ 长度不够，不命中 ✓
     *
     * 只对 ≥ [LONG_WAKE_PHONEMES] 的词启用：两字词的片段会撞上常用词，
     * 详见该常量的注释。
     */
    private fun anySegmentMatch(actual: String, expected: String): String? {
        if (expected.length < LONG_WAKE_PHONEMES) return null
        // 片段下限 = 至少 5 个字母（约两个音节）。
        // ★ 不能取 4：`xiao` 正好是 4 个字母，取 4 的话单说一个「小」
        //   就会唤醒（真机实测踩过，日志 `唤醒词片段命中：「小」→「xiao」`），
        //   车里说「小心」「小声点」全会误触。
        val minLen = maxOf(MIN_WAKE_PHONEMES + 1, expected.length * 45 / 100)
        // 从长片段往短试，先命中更可信
        for (len in expected.length downTo minLen) {
            for (start in 0..(expected.length - len)) {
                // 从词首开始的片段要求再多一个字：否则「小猪」的 `xiaozu`
                // 会退化成 `xiao` 命中，又回到单字唤醒。
                if (start == 0 && len <= minLen) continue
                val part = expected.substring(start, start + len)
                // 廉价预筛：片段首字母必须在识别结果里出现过，
                // 否则后面那圈编辑距离纯属白算（每帧都跑，省一点是一点）
                if (!actual.contains(part[0])) continue
                // sample 最多比片段短 1 个字母 —— 短太多就等于只听了半个词
                val lo = maxOf(MIN_WAKE_PHONEMES, len - 1)
                val hi = len + MAX_EDIT_ERRORS
                for (length in lo..hi) {
                    if (length > actual.length) continue
                    val sample = actual.substring(actual.length - length)
                    if (!sameInitials(sample, part)) continue
                    if (editDistanceWithinLimit(sample, part)) return sample
                }
            }
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

    /**
     * 两字符串编辑距离是否在 MAX_EDIT_ERRORS 以内。
     *
     * ★ 必须是真正的 Levenshtein DP，不能用「逐字符比、错了跳一个」的贪心写法。
     *   实测反例：`xiangduo` vs `xiaodu`（喊「小度」被听成「想多」），
     *   正确距离是 2（插一个 n、删一个 o），但贪心在插入点连续报 3 个错，
     *   直接判否 —— 放宽阈值也救不回来。
     *
     * 串长通常 6~12，O(n·m) 完全够用；带行最小值剪枝，超阈值立刻返回。
     */
    private fun editDistanceWithinLimit(a: String, b: String): Boolean {
        // ★ 短串按比例收紧：4 个字母的串如果也允许 3 个编辑错误，
        //   等于"几乎什么都算命中"（`xiao` 对任何 4 字母拼音都能过）。
        //   长串才放开到 MAX_EDIT_ERRORS。
        val limit = allowedErrors(minOf(a.length, b.length))
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        if (a == b) return true
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return false
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m] <= limit
    }

    /**
     * 这个长度的串允许几个编辑错误。
     *
     * 4 字母 → 1，6 字母 → 2，8 字母以上 → [MAX_EDIT_ERRORS]。
     * 短串多给一个错误，识别结果就会变成"什么都能匹配"。
     */
    private fun allowedErrors(len: Int): Int = when {
        len <= 4 -> 1
        len <= 6 -> 2
        else -> MAX_EDIT_ERRORS
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
        /**
         * 多久无条件重置一次解码器。
         *
         * ★ 2026-11-02 从 750（15 秒）放宽到 3000（60 秒）。
         *   15 秒太密：唤醒词说一半正好撞上重置点，前半截直接被清掉 ——
         *   日志里能看到「猪」单独出现、前面没有「小」，很可能就是撞了重置。
         *   重置的本意只是防解码状态无限增长，60 秒完全够，vosk 内部也有上限。
         */
        const val RESET_EVERY_FRAMES = 3000

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

        /** 窗口过期：4 秒没新内容就清空，避免老字残留造成误唤醒。
         *  3 秒时实测「小——度」这种拖长音的会被切掉后半截，放宽到 4 秒 */
        const val WINDOW_MS = 4_000L

        /** 窗口最长保留的字数。
         *  ★ 2026-11-02 从 10 放宽到 14：四字唤醒词（「小猪佩奇」）加上前后
         *  环境噪音，10 个字经常把唤醒词的前半截挤出窗口。 */
        const val MAX_WINDOW_CHARS = 14

        /** 匹配只看窗口最后这么多个字。
         *  ★ 2026-11-02 从 8 放宽到 12：四字唤醒词前面常带噪音字，
         *  只取末尾 8 字时「小猪」可能已经被挤出匹配范围。 */
        const val MATCH_TAIL_CHARS = 12

        /** 多音字交叉组合的候选上限（8 字窗口 3^8=6561 太疯，32 条足够） */
        const val CAND_CAP = 32
        const val MIN_WAKE_PHONEMES = 4

        /**
         * 长唤醒词的门槛（约 4 个音节以上）。
         *
         * ★ [anySegmentMatch] 的片段匹配只对这个长度以上的词启用。
         *   原因：片段匹配会忽略唤醒词的任意部分，两字词（「小度」xiaodu）的
         *   后缀 `aodu` 正好等于「角度 jiao-du」的尾部，一放开就会把
         *   「角度」重新变成误唤醒 —— 那是 2026-11-01 专门修掉的坑。
         *   四字词没有这个问题：`xiaozupeiqi` 的任意 45% 片段都不太可能是常用词。
         */
        const val LONG_WAKE_PHONEMES = 8

        /**
         * 允许的编辑错误数。
         *
         * ★ 2026-11-01 从 1 放宽到 2。实测证据（华为 P40 Pro 日志）：
         *   喊「小度」，vosk 识别成「想多」→ `xiangduo` vs `xiaodu`，编辑距离 2，
         *   卡在阈值 1 上永远匹配不上。用户感受就是「刚打开 app 喊不动，
         *   过一会儿又能喊醒」—— 其实是每次识别结果不同，恰好那次差两个字母。
         *
         * ★ 2026-11-02 再放宽到 3。用户明确要求「不怕误唤醒，只要喊得醒」。
         *   代价：`xiaodong`(小东)、`xiangduo`(想多) 这类距离 2~3 的词更容易命中。
         *   「角度 jiaodu」仍然安全 —— 首字母 j/x 不同，被 sameInitials 挡在前面。
         */
        const val MAX_EDIT_ERRORS = 3

        /**
         * 同音字兜底表（归一化后的汉字 → 同音字集合），用于 buildFuzzy()。
         *
         * 只收常见字：唤醒词里每个字如果在此表中，就生成逐字精确同音正则；
         * 有字不在表中时退化成「首字同音 + 中间任意 + 尾字同音」。
         *
         * ★ 表是按字收的，不是按词收的——「小猪佩奇」四个字都在表里就走逐字精确，
         *   不在就退化成兜底。这样加新唤醒词时，只要给新字补一行映射即可。
         */
        val HOMOPHONES = mapOf(
            // 常见首字（xiao）
            '小' to "小晓笑校萧效消销宵肖想",
            // 两字唤醒词尾字
            '度' to "度渡肚杜读毒多夺朵",
            '高' to "高搞告稿糕",
            '爱' to "爱艾哎哀",
            '艺' to "艺义亿易",
            '明' to "明名鸣",
            // 小猪佩奇
            '猪' to "猪朱珠竹",
            '佩' to "佩配培赔",
            '奇' to "奇其琪骑",
            '企' to "企起岂乞气",
            '鹅' to "鹅额俄厄",
            // 其他常用字
            '宝' to "宝保饱堡",
            '贝' to "贝备背被倍",
            '天' to "天添田甜",
            '使' to "使始史矢",
            '乐' to "乐勒乐",
            '尼' to "尼泥你拟",
            '马' to "马玛码妈麻",
            '叮' to "叮盯钉丁",
            '当' to "当档党荡",
            '猫' to "猫毛矛茅"
        )
    }
}
