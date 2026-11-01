package com.navagent.core

/**
 * 对话编排：一句话进来 → 交给模型 → 流式回复 + 播报。
 *
 * 2026-11-01 起**不再有规则分支**。以前是「正则优先，没命中才问模型」，
 * 但实测正则漏得厉害（「打开导航去X」「拐到前面那家」「回刚才那个地方」全匹配不上），
 * 用户的体感是"导航坏了"。现在导航动作也走 Function Calling，
 * 由模型判断意图、调 `navigate` 工具执行。
 *
 * 代价是延迟（7ms → 1.5~3s）和离线不可用，用户确认接受。
 *
 * 这一层仍然不碰 Android、不碰 SDK、不认识 Activity：
 * 导航动作由宿主实现成工具注入给 [llm]，这里只管对话流的编排。
 */
class ChatEngine(
    private val llm: Llm,
    /** 流式增量，主线程 */
    private val onDelta: (String) -> Unit,
    /** 一条助手消息完成 */
    private val onReply: (String) -> Unit,
    /** 要念出来的话 */
    private val speak: (String) -> Unit,
    /** 导航上下文，拼进 system prompt，让模型知道"我现在在开车去哪" */
    private val context: () -> String = { "" },
    /** AI 名字，每轮取一次 —— 设置页改名后不用重建引擎 */
    private val assistantName: () -> String = { "小高" }
) {

    private val history = ArrayList<ChatMessage>()

    /**
     * 待处理的句子。
     *
     * ★ 不能直接丢掉。实测开车时用户说完一句、模型还在生成，第二句"导航去张掖西站"
     * 就被静默吞了 —— 用户以为没听见，会一直重复。这里排队，最多压两句。
     */
    private val pending = ArrayDeque<String>()

    @Volatile
    var busy: Boolean = false
        private set

    /** 返回 false 表示这句被忽略（空串） */
    @Synchronized
    fun send(text: String): Boolean {
        val utterance = text.trim()
        if (utterance.isEmpty()) return false
        if (busy) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(utterance)
            return true
        }
        return process(utterance)
    }

    private fun process(utterance: String): Boolean {
        history += ChatMessage(ChatMessage.Role.USER, utterance)

        busy = true
        val sb = StringBuilder()
        // 边流边念：攒到一句话就立刻交给 TTS，别等整段生成完。
        // 实测模型出首字 1~3s、整段 3~5s，逐句播报能把"听到第一声"提前 2 秒。
        val spoken = StringBuilder()
        Thread {
            var spokenAny = false
            val full = try {
                llm.chat(systemPrompt(), history.toList()) { piece ->
                    sb.append(piece)
                    onDelta(piece)
                    spoken.append(piece)
                    if (speakCompletedSentences(spoken)) spokenAny = true
                }
            } catch (t: Throwable) {
                null
            }
            // 空回复要分两种情况：网络真挂了（full == null）vs 模型自己没说话（full == ""）。
            // 后者常见于模型对某个话题触发了自身的安全判断，说"网络不行"会误导用户。
            val answer = (full ?: sb.toString()).trim().ifEmpty {
                if (full == null) "网络好像不太行，再说一遍试试"
                else "这个我答不上来，换个问法或者换个话题。"
            }
            // 收尾：把没说完的半句念掉
            if (spoken.isNotBlank()) {
                speak(spoken.toString().trim())
                spokenAny = true
                spoken.setLength(0)
            }
            // 兜底：整段回复一个句末标点都没有（或模型返回空）时上面一句都没念，
            // 这里必须补上，否则界面上有字、耳朵里没声。
            if (!spokenAny) speak(answer)

            history += ChatMessage(ChatMessage.Role.ASSISTANT, answer)
            onReply(answer)
            busy = false
            drainPending()
        }.apply { isDaemon = true; name = "chat-engine" }.start()
        return true
    }

    @Synchronized
    private fun drainPending() {
        if (busy) return
        val next = pending.removeFirstOrNull() ?: return
        process(next)
    }

    /**
     * 从缓冲里切出完整的句子交给 TTS，剩下的半句留在缓冲里等下一段增量。
     * @return 是否真的念了一句
     *
     * 只在句末标点处切；逗号太碎，一句话被切成三段听着像结巴，所以不用逗号。
     */
    private fun speakCompletedSentences(buf: StringBuilder): Boolean {
        val text = buf.toString()
        val cut = text.lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'))
        if (cut < 0) return false
        val sentence = text.substring(0, cut + 1).trim()
        buf.delete(0, cut + 1)
        if (sentence.isEmpty()) return false
        speak(sentence)
        return true
    }

    fun reset() {
        history.clear()
        pending.clear()
    }

    fun snapshot(): List<ChatMessage> = history.toList()

    private fun systemPrompt(): String = buildString {
        append("你是「${assistantName()}」，一个在车里陪用户说话的语音助手。")
        // ★ 必须注入当前时间。模型只知道自己训练到哪天，不知道"今天"是几号，
        //   问日期、问"明天天气"、说"周六去哪"全都会瞎编。这条不加必然出错。
        nowText()?.let { append("现在是 ").append(it).append("。") }
        append("用户正在开车，你的回答会被朗读出来，所以：")
        append("1) 口语化，短，一般不超过两句话；不要用列表、不要用 Markdown、不要念表情符号。")
        // 这一条是整套导航能力的关键：不写清楚，模型会只回一句「好的」然后什么都不做
        append("2) 用户要去某地、改道、停止导航、打开地图，**必须调 navigate 工具**，")
        append("   不要只回「好的」而不动作。地名照用户原话写，保留省市县。")
        // 主动休眠：用户明说「别打扰我」时必须真的闭嘴，不能只是嘴上答应。
        append("2b) 用户说「你休息吧」「你先休息吧」「不要打扰我」「你先不要打扰我」「安静点」")
        append("   「别烦我了」这类要你别说话，**必须调 navigate 工具的 action=sleep**，")
        append("   然后简短回一句（比如「好，有事叫我」）就不要再说了。")
        append("   不要把休眠说成「好的我知道了」却继续聊天 —— 那等于没执行。")
        append("3) 问天气调 get_weather；找附近店铺调 search_nearby。用户询问新闻、价格、政策、近期人物/事件、当前版本或其他实时/可能变化的信息时，必须先调用 web_search；不得只凭训练记忆回答或暗示已联网。")
        append("   若搜索摘要不足以核实关键细节，再对搜索结果中相关链接调用 web_extract；不要读取未由搜索结果提供的猜测链接。")
        append("   搜索或网页读取失败时，明确告知没有核实到实时来源，再说明能提供的背景知识及其可能过时。")
        // 用户要求：需要他做选择时列序号，他回数字就行。语音场景这样最省事。
        append("4) 需要用户在几个选项里挑一个时（比如搜出几家店），用 1、2、3 编号列出来，")
        append("   每项一句话说完，最后问「要哪个」。用户回「2」「第二个」就按上一轮列表理解。")
        append("5) 不确定的事就说不知道，不要编。涉及日期、星期、几号，以上面给的「现在是」为准。")
        append("6) 政治、军事、领土争议、宗教这类话题不展开，一句「这个我不聊」带过，换个话题。")
        val ctx = context()
        if (ctx.isNotBlank()) append("\n当前状态：").append(ctx)
    }

    /** 「2026年11月1日 星期日 14:01」。拿不到就返回 null，别让 prompt 出现空档 */
    private fun nowText(): String? = runCatching {
        java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern(
                "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA
            )
        )
    }.getOrNull()

    private companion object {
        /** 最多压两句，再多就把最老的挤掉 */
        const val MAX_PENDING = 2
    }
}
