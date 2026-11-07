package com.navagent.core

/**
 * 播报打断策略 —— 纯逻辑，不碰 Android。
 *
 * ★ 2026-11-07 重做。原来的规则只有一条：「播报中命中唤醒词 + 声纹过线就
 *   `voice.silence()` 全清」。三个问题：
 *   1. 导航播报和闲聊播报一视同仁，导航正在说「前方 200 米左转」也能被一句
 *      「小猪」掐掉 —— 用户最需要听清的时候反而静音了；
 *   2. 除了唤醒词之外没有任何口令，「别说了」「先停下」这种最自然的说法完全无效；
 *   3. 助手列出 1、2、3 让用户选的时候，用户说「第二个」打不断，
 *      只能等它把整张表念完再说一遍。
 *
 * 现在的分层：
 *   - 导航播报（NAV）  → 永不打断（命中词记下来，播完再执行）
 *   - 闲聊/回答        → 唤醒词 / 先停下 / 别说了
 *   - 选择列表播报中   → 上述 + 我选择 / 我选第N个 / 选择第N个 / 第N个 / 最后一个
 *
 * ★ 为什么**不**支持直接说「2」：
 *   助手念列表时会输出「2、张掖宾馆」，麦克风把它收回来，Vosk 就会在窗口里
 *   出现一个「2」—— 允许纯数字等于让它自己把自己打断。所以序号必须带
 *   「第…个」「我选…」这类结构。
 */
enum class BargeMode {
    /** 不打断（没在播报，或正在播导航） */
    NONE,
    /** 普通闲聊/回答播报 */
    CHAT,
    /** 正在念选择列表 */
    CHOICE
}

enum class BargeKind {
    /** 唤醒词 */
    WAKE,
    /** 停下类口令（先停下 / 别说了…） */
    STOP,
    /** 选择列表里的序号 */
    INDEX
}

/**
 * 一次命中的结果。
 *
 * @param canonical 可以直接交给大脑的规范化说法（如「第2个」「最后一个」）。
 *    [BargeKind.INDEX] 之外为空串。
 */
data class BargeHit(
    val kind: BargeKind,
    val canonical: String,
    val matched: String,
    val index: Int
)

object BargeIn {

    /**
     * 判定当前该用哪一档规则。
     *
     * 注意顺序：**导航优先**。只要当前在播的是导航，不论是否在选择列表里
     * 都返回 [BargeMode.NONE] —— 选择列表一定是助手念的，不可能和导航同时播。
     */
    fun mode(speaking: Boolean, navSpeaking: Boolean, choiceActive: Boolean): BargeMode = when {
        !speaking -> BargeMode.NONE
        navSpeaking -> BargeMode.NONE
        choiceActive -> BargeMode.CHOICE
        else -> BargeMode.CHAT
    }

    /**
     * 在识别文本里找打断词。
     *
     * @param heard Vosk 滑动窗口里累积的最近十几个字（[com.navagent.app.VoskWake]）
     * @param wakeWords 当前配置的唤醒词（可能多个同义变体）
     */
    fun match(
        heard: String,
        mode: BargeMode,
        wakeWords: List<String>,
        /** 停止口令词表。由宿主从设置里注入 —— 词表是配置，不是逻辑 */
        stopWords: List<String> = STOP_PHRASES
    ): BargeHit? {
        if (mode == BargeMode.NONE) return null
        val norm = normalize(heard)
        if (norm.isEmpty() || norm == "unk") return null
        // 窗口会被自己的 TTS 和车噪污染，只看尾巴
        val tail = if (norm.length > TAIL_CHARS) norm.takeLast(TAIL_CHARS) else norm

        // ① 序号优先：说得越具体越该当成选择指令
        if (mode == BargeMode.CHOICE) {
            matchIndex(tail)?.let { return it }
        } else {
            // 非列表播报只认**带「选」字**的说法。
            //
            // ★ 2026-11-07 真机：助手其实没念编号（直接「丹霞景区。门票站。美酒店。」），
            //   选择窗口没打开，用户说「我选第一」就被当成普通闲聊放过去了。
            //   而「我选X」这种说法本身没有歧义 —— 播报内容不可能自己说「我选第一个」，
            //   所以即使不在列表窗口里也认。裸的「第X个」不在此列：
            //   助手念「2、」时回声会被识别成「第二个」，那才是真的会误触。
            matchPick(tail)?.let { return it }
        }

        // ② 唤醒词
        for (word in wakeWords) {
            val w = normalize(word)
            if (w.isNotEmpty() && tail.contains(w)) {
                return BargeHit(BargeKind.WAKE, "", w, 0)
            }
        }

        // ③ 停止口令
        for (phrase in stopWords) {
            if (tail.contains(phrase)) {
                return BargeHit(BargeKind.STOP, "", phrase, 0)
            }
        }
        return null
    }

    /**
     * 序号：最后一个 / 第N个 / 我选N / 选择第N个。
     *
     * 匹配顺序按"信息量"从高到低，先命中先返回：
     *   「最后一个」→ 它不可能是「第N个」的一部分，单独先判。
     */
    private fun matchIndex(tail: String): BargeHit? {
        if (LAST_ITEM.containsMatchIn(tail)) {
            return BargeHit(BargeKind.INDEX, "最后一个", "最后一个", -1)
        }
        // ①「第N个」。「个」是可选的 —— 真机日志里用户说「第二个」，
        //    Vosk 只吐出「第二」，后面跟的「个」被 TTS 混音吃掉了（2026-11-07）：
        //    16:32:49 「第二」→ 16:32:50 「这个」final「二哥」
        ORDINAL.find(tail)?.let { m ->
            val n = parseNumber(m.groupValues[1]) ?: return@let
            if (n < 1) return@let
            return BargeHit(BargeKind.INDEX, "第${n}个", m.value, n)
        }
        // ②「我选N」「选择N」「选N」。「选」和数字之间允许夹一个字：
        //    实测识别成「选的二个」（“我”没了、“第”变成“的”），
        //    按严格写法永远匹配不上。
        matchPick(tail)?.let { return it }
        return null
    }

    /** 只匹配「选N」这一种说法（不管前面有没有「第」） */
    private fun matchPick(tail: String): BargeHit? {
        val m = PICK.find(tail) ?: return null
        val n = parseNumber(m.groupValues[1]) ?: return null
        if (n < 1) return null
        return BargeHit(BargeKind.INDEX, "第${n}个", m.value, n)
    }

    /**
     * 中文/阿拉伯数字 → Int。
     *
     * 只需要覆盖 1~99：候选列表按设计不会超过 8 条，10 以上只是兜底。
     * 解析失败返回 null，交给上层当成"没选中"。
     */
    fun parseNumber(raw: String): Int? {
        val s = raw.filter { it.isDigit() || it in CN_DIGITS || it == '十' || it == '两' }
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()

        // 十 / 十二 / 二十 / 二十三
        val ten = s.indexOf('十')
        if (ten >= 0) {
            val head = s.substring(0, ten)
            val tail = s.substring(ten + 1)
            val h = if (head.isEmpty()) 1 else digitOf(head.lastOrNull()) ?: return null
            val t = if (tail.isEmpty()) 0 else digitOf(tail.first()) ?: return null
            return h * 10 + t
        }
        return digitOf(s.last())
    }

    private fun digitOf(c: Char?): Int? = when (c) {
        null -> null
        '零', '〇' -> 0
        '一' -> 1
        '二', '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> if (c.isDigit()) c.digitToInt() else null
    }

    /** 只留文字和数字，去掉标点空格 —— Vosk 输出和配置写法经常不一样 */
    fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 停止口令。
     *
     * 全部是**短句**：长了识别不准，且容易被正常闲聊误命中。
     * 「停下」是「先停下」的子串，两条都留着不影响 —— 只要有前缀就够。
     *
     * ★ 2026-11-07 补「打断」等：真机日志 16:44:10 用户明说「打断」，
     *   Vosk 也老老实实吐出了「打断」，但词表里没有这一条，整段播报继续念完。
     *   用户的原话是「刚才好像没有打断」—— 词表漏词等于功能不存在。
     */
    val STOP_PHRASES = listOf(
        "先停下",
        "先停一下",
        "别说了",
        "不要说了",
        "别讲了",
        "别念了",
        "别读了",
        "打断",
        "停一下",
        "停下"
    )

    private val LAST_ITEM = Regex("最后[一1]?[个各]|最后那个|最后[一二三四五六七八九1-9]")

    /**
     * 「第N个」。个/各可省略，第和数字之间允许夹一个虚字（的/地）。
     *
     * 放宽的依据是真机日志（2026-11-07）：Vosk 在 TTS 混音里把「第二个」
     * 吐成「第二」「这个二哥」「选的二个」三种形态，严格写法一个都接不住。
     */
    private val ORDINAL = Regex("第[的地]?([0-9零〇一二三四五六七八九十两]{1,3})[个各]?")

    /**
     * 「我选N」「选择N」「选N」。
     *
     * 「选」后面允许跟 择/的/得/地/要 再跟数字：实测「我选第二个」被识别成
     * 「选的二个」——「我」和「第」都丢了，只有「选」「的」「二」「个」活下来。
     */
    private val PICK = Regex(
        "(?:我)?选(?:择)?[的得地要吧啊]?(?:第[的地]?)?([0-9零〇一二三四五六七八九十两]{1,3})"
    )

    private val CN_DIGITS = "零〇一二三四五六七八九".toSet()

    /** 只比窗口尾部这么多个字，避免远处的文本干扰 */
    private const val TAIL_CHARS = 14
}

/**
 * 选择列表识别。
 *
 * ★ 为什么靠文本正则，不靠模型自己报：
 *   模型本来就被要求「需要用户挑一个时用 1、2、3 编号列出来」，这是稳定行为；
 *   再让它输出一个特殊标记的话，标记会被 TTS 念出来，还得在播报层过滤，
 *   为了准确性引入新的失败点不划算。先按文本判，漏检再上标记。
 *
 * 判定条件：**至少两条**带编号的项。单条编号在正常叙述里太常见，不能当列表。
 */
object ChoiceList {

    /**
     * 返回列表项数；不是列表返回 0。
     *
     * 流式场景下会被反复调用（每段增量一次），所以只扫一遍、不做多余分配。
     */
    fun count(text: String): Int {
        if (text.length < MIN_CHARS) return 0
        val seen = HashSet<String>()
        for (m in ITEM.findAll(text)) {
            // 取编号本身当 key：阿拉伯数字取数字，中文取汉字
            val key = m.value.lastOrNull { it.isDigit() || it in CN_NUMERALS } ?: continue
            seen.add(key.toString())
            if (seen.size >= MAX_ITEMS) break
        }
        return if (seen.size >= 2) seen.size else 0
    }

    /**
     * 编号必须出现在**句首或标点之后**：`1、` `2.` `3）`，也认 `一、` `第二、`。
     *
     * 不加这个约束的话，「走了 2.5 公里」这种也会被当成列表项。
     */
    private val ITEM = Regex(
        "(?:^|[\\s。！？；;，,\\n])(?:第?[一二三四五六七八九]|[1-9])[、.．)）:：]"
    )

    private val CN_NUMERALS = "一二三四五六七八九".toSet()

    private const val MIN_CHARS = 8
    private const val MAX_ITEMS = 9
}
