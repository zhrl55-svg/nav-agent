package com.navagent.core

import java.util.concurrent.Executors

/**
 * 对话编排：一句话进来 → 交给模型 → 流式回复 + 播报。
 *
 * 2026-11-01 起导航意图主要由模型判断并通过 Function Calling 执行。
 * 对明确的导航指令和上下文中已确认的地点，宿主可在 [interceptAction] 中做确定性兜底，
 * 防止本地模型声明了工具却只输出文字、没有真正执行动作。
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
    private val assistantName: () -> String = { "小高" },
    /**
     * 请求彻底失败时的系统提示。
     *
     * ★ 这不是 AI 的回复，是一条系统通知，**不进对话历史**。
     *   为什么单独开一个口子：以前失败时由 App 编一句「网络好像不太行」/「没听清，
     *   你再说一遍？」，两种说法都在替 AI 说话，而且「没听清」会让用户以为是自己
     *   没说清楚。现在只有连"让模型自己说一句"都做不到（两次请求全失败）时才走这里，
     *   由 UI 以系统提示的形式展示，不伪装成对话气泡。
     */
    private val onFailure: (String) -> Unit = {},

    /**
     * 这一次有没有把 `web_search` 工具交给模型。
     *
     * ★ 2026-11-02 加。接了本地接口之后默认不给 Tavily（本地服务自带检索），
     *   但 system prompt 里那一大段「必须先调 web_search」还留着 ——
     *   模型会照着调一个不存在的工具，轻则空转一轮，重则直接编造「我查到了」。
     *   prompt 必须跟着工具一起变。
     */
    private val webSearchAvailable: () -> Boolean = { true },
    /** 明确的本地动作意图可由宿主直接执行，避免不支持工具调用的模型只给文字承诺。 */
    private val interceptAction: (String, List<ChatMessage>) -> String? = { _, _ -> null },
    /**
     * App 本地记忆。启动时恢复最近对话，每轮把相关长期事实注入 system prompt。
     *
     * 记忆与模型提供商解耦：无论请求发往 Minis、MiniMax、OpenAI 还是本地模型，
     * 都使用同一份本地数据。
     */
    private val memory: ChatMemory = ChatMemory.None
) {

    private val lock = Any()

    private val history = ArrayList<ChatMessage>().apply {
        addAll(runCatching { memory.loadRecent(MAX_RESTORED_MESSAGES) }.getOrDefault(emptyList()))
    }

    /**
     * 待处理的句子。
     *
     * ★ 不能直接丢掉。实测开车时用户说完一句、模型还在生成，第二句"导航去张掖西站"
     * 就被静默吞了 —— 用户以为没听见，会一直重复。这里排队，最多压两句。
     */
    private val pending = ArrayDeque<String>()

    /**
     * 单线程执行器。send() 只负责入队，拦截、模型请求、工具调用和结果写入都不占
     * 调用方线程 —— 语音服务和 Activity 的主线程都不能被最长几十秒的网络请求卡住。
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-engine").apply { isDaemon = true }
    }

    /** reset() 时递增；旧请求晚到的结果不能写回新一轮对话。 */
    private var generation = 0L

    @Volatile
    var busy: Boolean = false
        private set

    /** 返回 false 表示这句被忽略（空串） */
    fun send(text: String): Boolean {
        val utterance = text.trim()
        if (utterance.isEmpty()) return false
        synchronized(lock) {
            if (busy) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(utterance)
                return true
            }
            busy = true
            worker.execute { process(utterance) }
            return true
        }
    }

    private fun process(utterance: String) {
        val userMessage = ChatMessage(ChatMessage.Role.USER, utterance)
        val runGeneration = synchronized(lock) {
            history += userMessage
            runCatching { memory.append(userMessage) }
            generation
        }
        try {
            val intercepted = runCatching {
                interceptAction(utterance, snapshot())
            }.getOrNull()
            if (!intercepted.isNullOrBlank()) {
                if (isCurrent(runGeneration)) {
                    appendAssistant(runGeneration, intercepted)
                    onDelta(intercepted)
                    onReply(intercepted)
                    runCatching { speak(intercepted) }
                }
                return
            }

            val sb = StringBuilder()
            // 边流边念：攒到一句话就立刻交给 TTS，别等整段生成完。
            // 实测模型出首字 1~3s、整段 3~5s，逐句播报能把"听到第一声"提前 2 秒。
            val spoken = StringBuilder()
            var spokenAny = false
            // 一段增量同时喂三处：整段结果、界面、播报缓冲
            fun feed(piece: String) {
                sb.append(piece)
                if (!isCurrent(runGeneration)) return
                onDelta(piece)
                spoken.append(piece)
                if (speakCompletedSentences(spoken)) spokenAny = true
            }

            /** 一次完整请求：可能包含「判断意图 → 调工具 → 总结」整条流程 */
            fun attempt(sys: String, hist: List<ChatMessage>): String? = try {
                llm.chat(sys, hist) { feed(it) }
            } catch (t: Throwable) {
                null
            }

            /** 只跑「把话说完」这一步，不重跑工具流程 */
            fun attemptText(sys: String, hist: List<ChatMessage>): String? = try {
                llm.chatText(sys, hist) { feed(it) }
            } catch (t: Throwable) {
                null
            }

            var full = attempt(systemPrompt(utterance), snapshot())

            // ★ 2026-11-02：失败后**不再由 App 编一句兜底**，而是让模型自己说。
            //
            // 为什么这么改：原来失败时会说「网络好像不太行」或「没听清，你再说一遍？」。
            // 两句都在替 AI 说话，而且「没听清」把锅甩给用户 —— 明明是他没听见，
            // 用户却会以为自己没说清楚。
            //
            // 现在的做法：换**更轻的上下文**（只带最近两轮）+ 明确告诉模型上一轮没成功，
            // 让它自己决定怎么回应。轻量上下文请求体小、成功率高，而且措辞由模型掌握，
            // 比任何写死的话术都自然。
            //
            // ★ 重试走 chatText 而不是 chat：chat 会重跑整条工具流程。
            //   实测搜新闻失败时，重试等于再搜一次（5 秒 + 一次额度），
            //   而失败原因往往就在搜回来的内容里 —— 重跑一遍大概率还是失败。
            //   chatText 只跑总结那一段，并且会把已经搜到的结果带上（见 OpenAiLlm）。
            //
            // 只要模型明确报告失败就重试，哪怕已经念出了半句 ——
            // 那半句通常只是「我帮你查一下」，用户等的是后面的答案。
            if (full.isNullOrBlank()) {
                full = attemptText(
                    retryPrompt(sb.toString().trim(), utterance),
                    snapshot().takeLast(RETRY_HISTORY)
                )
            }

            val answer = (full ?: sb.toString()).trim()

            // 两次都没拿到任何内容：这是系统故障，不是对话。
            // 撤回刚加进去的用户消息（避免下一轮模型看到一句"没人回的问话"），
            // 然后用系统提示告知，**不伪造 AI 回复**。
            if (answer.isEmpty()) {
                if (isCurrent(runGeneration)) {
                    removeLastUser(utterance, runGeneration)
                    onFailure("这次没连上，稍后再试")
                }
                return
            }
            if (!isCurrent(runGeneration)) return

            // 收尾：把没说完的半句念掉
            if (spoken.isNotBlank()) {
                speak(spoken.toString().trim())
                spokenAny = true
                spoken.setLength(0)
            }
            // 兜底：整段回复一个句末标点都没有（或模型返回空）时上面一句都没念，
            // 这里必须补上，否则界面上有字、耳朵里没声。
            if (!spokenAny) speak(answer)

            appendAssistant(runGeneration, answer)
            onReply(answer)
        } catch (t: Throwable) {
            if (isCurrent(runGeneration)) {
                removeLastUser(utterance, runGeneration)
                onFailure("这次没连上，稍后再试")
            }
        } finally {
            finishAndDrain()
        }
    }

    private fun isCurrent(expected: Long): Boolean = synchronized(lock) {
        generation == expected
    }

    private fun appendAssistant(runGeneration: Long, text: String) {
        val message = ChatMessage(ChatMessage.Role.ASSISTANT, text)
        synchronized(lock) {
            if (generation == runGeneration) {
                history += message
                runCatching { memory.append(message) }
            }
        }
    }

    private fun removeLastUser(utterance: String, runGeneration: Long) {
        synchronized(lock) {
            if (generation != runGeneration || history.isEmpty()) return
            val last = history.last()
            if (last.role == ChatMessage.Role.USER && last.text == utterance) {
                history.removeAt(history.lastIndex)
                runCatching { memory.removeLastIfMatching(last) }
            }
        }
    }

    private fun finishAndDrain() {
        val next = synchronized(lock) {
            pending.removeFirstOrNull().also {
                if (it == null) busy = false
            }
        }
        if (next != null) worker.execute { process(next) }
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
        synchronized(lock) {
            generation++
            history.clear()
            pending.clear()
            runCatching { memory.clear() }
        }
    }

    fun snapshot(): List<ChatMessage> = synchronized(lock) { history.toList() }

    private fun systemPrompt(query: String): String = buildString {
        append("你是「${assistantName()}」，一个在车里陪用户说话的语音助手。")
        // ★ 必须注入当前时间。模型只知道自己训练到哪天，不知道"今天"是几号，
        //   问日期、问"明天天气"、说"周六去哪"全都会瞎编。这条不加必然出错。
        nowText()?.let { append("现在是 ").append(it).append("。") }
        append("用户正在开车，你的回答会被朗读出来，所以：")
        append("1) 口语化，短，一般不超过两句话；不要用列表、不要用 Markdown、不要念表情符号。")
        // 这一条是整套导航能力的关键：不写清楚，模型会只回一句「好的」然后什么都不做
        append("2) 用户要去某地、改道、停止导航、打开地图，**必须调 navigate 工具**，")
        append("   不要只回「好的」而不动作。地名照用户原话写，保留省市县。")
        append("   如果前文讨论或搜索过地名、店铺、景点等具体地点，并且你问用户要不要去那里，")
        append("   用户回答「去」「好」「就这家」「走吧」等肯定答复时，必须结合前文确定目的地，")
        append("   立即调用 navigate 工具实际开始导航；不能只用文字表示同意、确认或说「这就带你去」。")
        append("   只有用户明确拒绝、改选其他地点或表达不确定时，才不要直接导航；目的地仍有歧义时先问清楚。")
        // ★ 2026-11-04：模型看到历史目的地会「自作主张」重放导航。
        //   实测用户说「哎呀，现在很完美呀」这种闲聊，模型自己调了
        //   navigate(水勘院超市) —— 用户根本没要求导航，最后还跑到石家庄同名地点。
        //
        //   ★ 注意：**不是**「闲聊就不许调 navigate」——
        //     该导航的时候照样调，只是**必须走确认流程**。
        append("   ★ 需要导航就调 navigate，但**必须走确认流程**：")
        append("     用户只说了个地名（没指定是哪一个）→ 不带 confirmed 调用，")
        append("     工具会返回候选，你要用 1、2、3 列出来问用户「是哪个」；")
        append("     用户从候选里选了（回了号码或名字）→ 再调一次，destination 用完整名称、confirmed=true。")
        append("     绝不可以在用户没选的情况下自己填 confirmed=true 去导航。")
        // ★ 2026-11-04：导航播报会打断你说的话，所以导航成功后**只说一句短的**。
        //   高德在导航开始时会自己播「准备出发，全程 2.6 公里，大约 9 分钟」，
        //   如果你也念一遍，用户听到的是重复；
        //   而且你的话正在念的时候高德那句会把它**打断**（用户反馈：
        //   「全程视图打断了播报」「说了一半就没声了」）。
        append("   navigate 工具返回「已开始导航…」之后，你只需要简短确认一句（比如「好，正在导航」），")
        append("   **不要**重复念里程、时间、转向这些 —— 高德会自己播，重复还会互相打断。")
        append("   只有用户明确问「多久到」「怎么走」时才说具体数字。")
        // 主动休眠：用户明说「别打扰我」时必须真的闭嘴，不能只是嘴上答应。
        append("2b) 用户说「你休息吧」「你先休息吧」「不要打扰我」「你先不要打扰我」「安静点」")
        append("   「别烦我了」这类要你别说话，**必须调 navigate 工具的 action=sleep**，")
        append("   然后简短回一句（比如「好，有事叫我」）就不要再说了。")
        append("   不要把休眠说成「好的我知道了」却继续聊天 —— 那等于没执行。")
        append("3) 问天气调 get_weather；找店铺调 search_nearby。")
        append("用户说「附近、周边、周围」时传 scope=current；")
        append("说「沿途、顺路、路上」时传 scope=route，搜索当前导航剩余路线两侧。")
        append("只有导航中才能做沿途搜索；不在导航中时先如实告诉用户。")
        if (webSearchAvailable()) {
            append("用户询问新闻、价格、政策、近期人物/事件、当前版本或其他实时/可能变化的信息时，")
            append("必须先调用 web_search；不得只凭训练记忆回答或暗示已联网。")
            // ★ 2026-11-01 补：搜新闻不给力，一半原因在调用参数上。
            //   只传 general 会命中几个月前的旧文章；不传时间范围则默认一周内但可能混进更早的。
            append("   问新闻、最近发生的事，topic 必须传 news，并按时间传 time_range")
            append("   （今天或刚发生用 day，这周用 week，这个月用 month）。只传 general 会搜到旧文章。")
            append("   搜到的每条后面标了「几分钟前 / 几小时前 / 几天前」，回答前先看新旧；")
            append("   如果最新的一条也是几天前，要跟用户说明「目前搜到的最近报道是 X 天前」。")
            // ★ query 构造比参数更影响结果。实测模型自己发「甘肃张掖 近期 事件」，
            //   搜回来的是 CDT 周报（跟张掖无关的政治评论），还多花一倍时间。
            append("   搜索词要具体：写「地名 + 主题」，比如「张掖 新闻」「张掖 供暖」；")
            append("   不要用「近期事件」「最近发生了什么」这种泛词，会搜到无关内容。")
            append("   一次只发一个搜索，结果不够再补第二个，不要同时发多个。")
            append("   若搜索摘要不足以核实关键细节，再对搜索结果中相关链接调用 web_extract；不要读取未由搜索结果提供的猜测链接。")
            append("   搜索或网页读取失败时，明确告知没有核实到实时来源，再说明能提供的背景知识及其可能过时。")
        } else {
            // 接本地接口的情况：本地服务自带检索，我们这边没有 web_search 可调
            append("你这边没有搜索工具，不要尝试调用任何检索类工具，也不要声称「我查了一下」。")
            append("用户问新闻、价格、政策这类实时信息时，能答就答，")
            append("但不要编造具体的日期、数字、标题和链接；不确定就直说不知道。")
        }
        // 用户要求：需要他做选择时列序号，他回数字就行。语音场景这样最省事。
        append("4) 需要用户在几个选项里挑一个时（比如搜出几家店），用 1、2、3 编号列出来，")
        append("   每项一句话说完，最后问「要哪个」。用户回「2」「第二个」就按上一轮列表理解。")
        // ★ 2026-11-04：地点候选确认。这条不写清楚，模型会跳过确认直接导航，
        //   或者搜完候选只说一句「找到几个地方」就不再管了 —— 用户根本没法选。
        append("4b) navigate 工具返回「搜到 N 个候选」时，说明**还没导航**，只是搜到了几个可能的地方。")
        append("   这时你必须：用 1、2、3 把候选列出来，问用户「是哪个」（候选名 + 地址，一句话说完）。")
        append("   用户回答后（比如「2」或直接说名字），**再调一次 navigate**，")
        append("   destination 用候选列表里的完整名称，并且 confirmed=true。")
        append("   ★ 只有**一个**候选时才可以直接导航（confirmed=true），不用问。")
        append("   ★ 有两个以上候选时，即使其中一个名字跟用户说的完全一致，")
        append("     也**必须先列出来问**。为什么：同名的地方往往有好几个")
        append("     （「张掖西站」「张掖西站(进站口)」「张掖西站停车场」），")
        append("     选错了用户开半天才发现，白跑一趟。")
        append("5) 不确定的事就说不知道，不要编。涉及日期、星期、几号，以上面给的「现在是」为准。")
        // ★ 2026-11-01 改：原来是一刀切「政治不展开，一句这个我不聊带过」。
        //   实测用户说「听一下今天的国内和国际新闻」，搜回来的全是新闻联播要闻，
        //   模型撞上这条直接输出 0 字 —— 用户等半天一片沉默。
        //   新闻播报本来就要念事实，禁的是「表态」，不是「念标题」。
        append("6) 政治、军事、领土争议、宗教这类话题**只转述事实、不表态**：")
        append("   新闻里出现就照实说一句（比如「今天新闻联播报道了某某会议召开」），")
        append("   用户追问立场、评价、谁对谁错时，才用一句「这个我不聊」带过。")
        // 搜新闻一次搜完，别国内、国际分三次 —— 每次都多等 5 秒，还容易超时
        append("   新闻类问题一次搜完（比如「今天国内国际要闻」），不要拆成国内、国际分几次搜。")
        val ctx = context()
        if (ctx.isNotBlank()) append("\n当前状态：").append(ctx)
        val remembered = runCatching { memory.contextFor(query) }.getOrDefault("")
        if (remembered.isNotBlank()) append("\n\n").append(remembered)
    }

    /**
     * 失败重试用的 system prompt。
     *
     * 和正常 prompt 的唯一区别是末尾加了一段"上一轮没成功，你自己决定怎么说"。
     * 措辞故意**不提供任何现成句子** —— 只说明事实（刚才没返回）和边界
     * （不要编实时信息、不要提技术词），具体怎么讲交给模型。
     *
     * 为什么强调"不要提技术词"：模型很容易说出"系统超时""接口异常"这类话，
     * 在车里听着像报错，比不说还糟。
     *
     * @param alreadySaid 已经播出去/上屏的半句话。有值就让模型接着说完，
     *                    否则它会把同一句话再说一遍 —— 用户听到的是重复。
     */
    private fun retryPrompt(alreadySaid: String, query: String): String =
        systemPrompt(query) + buildString {
        append("\n\n【补充】你上一条回复没有成功发出来（网络或超时）。")
        if (alreadySaid.isNotBlank()) {
            append("你已经说出口的是：「").append(alreadySaid.take(120)).append("」。")
            append("请接着把它说完，不要重复已经说过的部分。")
        } else {
            append("请直接用一两句话回应用户刚才说的话。")
        }
        append("如果手上确实没有任何可用的信息，就如实说没查到，不要编造。")
        append("不要提系统、接口、超时、网络这类技术词，就像正常聊天一样说。")
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

        /**
         * 失败重试时只带最近这么多条历史（约两轮）。
         *
         * 重试的目的就是"请求小一点、成功率高一点"，带全量历史等于重跑一遍
         * 刚才失败的那个大请求。两轮够模型知道"在聊什么"了。
         */
        const val RETRY_HISTORY = 4

        /** 进程重启后恢复的本地消息上限；界面能看到近期对话，又不至于拖慢启动。 */
        const val MAX_RESTORED_MESSAGES = 100
    }
}
