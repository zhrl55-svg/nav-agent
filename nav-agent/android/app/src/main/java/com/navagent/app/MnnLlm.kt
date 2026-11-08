package com.navagent.app

import com.navagent.core.ChatMessage
import com.navagent.core.Llm

/**
 * 本地大模型适配层：把 [MnnLocalLlm] 接到 [Llm] 接口上。
 *
 * ## 与云端实现（[OpenAiLlm]）的差别
 * - **没有 Function Calling**。导航/搜索/天气三个工具全部不可用，退化成纯聊天。
 *   这是 0.3B 的能力上限，不是没接——模型原生不支持 tools，硬塞只会输出乱码。
 * - **chat 和 chatText 等价**。云端那边 chat 是「判断意图 → 调工具 → 总结」两轮，
 *   chatText 只跑最后一步；本地没有工具流程，两个方法本来就是同一件事。
 *
 * ## 为什么自己拼 prompt
 * MNN 的 native 侧不会自动套 jinja 模板（实测裸 prompt 只吐 1 个 token），
 * 必须显式按 Qwen 的 `<|im_start|>` 格式拼好再喂进去。
 */
class MnnLlm(private val engine: MnnLocalLlm) : Llm {

    override fun chat(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? {
        // ★ 让设置页的「回复长度」在本地也生效。
        //   不接的话，用户切到本地后调这个值毫无反应——比不显示更让人困惑。
        //   上限卡 512：0.3B 在 P40 上 decode 约 40–78 t/s，512 token 已经要 7 秒。
        engine.setMaxNewTokens(Prefs.maxTokens.coerceIn(64, 512))
        val prompt = buildPrompt(system, history)
        return engine.generate(prompt, keepHistory = false) { piece -> onDelta(piece) }
    }

    /** 本地没有工具流程，重试就是重来一次，不需要单独实现 */
    override fun chatText(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? =
        chat(system, history, onDelta)

    private fun buildPrompt(system: String, history: List<ChatMessage>): String = buildString {
        append("<|im_start|>system\n")
        append(compactSystem(system))
        append("<|im_end|>\n")
        // 设置页「上下文轮数」对本地也生效，但最多 3 轮：
        // 每多带一轮就多几十个 token 的 prefill，而 0.3B 本来也记不住更远。
        history.takeLast(Prefs.historyTurns.coerceIn(1, MAX_TURNS) * 2).forEach { m ->
            val role = if (m.role == ChatMessage.Role.USER) "user" else "assistant"
            append("<|im_start|>").append(role).append('\n')
            append(m.text.trim())
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /**
     * 裁剪系统提示词。
     *
     * ★ 云端那份有几千字，一半是工具协议、搜索规则、候选确认流程。
     *   本地一条都用不上，但每一条都要 prefill —— 2000 token 在 P40 上要 10 秒。
     *
     * 两个动作：
     *  1) **整句剔除**含 web_search / 工具 / navigate / topic= 的句子。
     *     只按长度截断是不够的：实测截到 260 字时，「先调 web_search 搜到最新的再回答」
     *     这句正好留在里面，结果 0.3B 会声称「我搜了一下」——它根本没有工具。
     *  2) 剩下的按句拼到 260 字为止，保证不会切断半句话。
     */
    private fun compactSystem(system: String): String {
        val kept = StringBuilder()
        for (sentence in system.split('。')) {
            val raw = sentence.replace("\n", " ").trim()
            if (raw.isEmpty()) continue
            val toolish = raw.contains("web_search") || raw.contains("工具") ||
                raw.contains("navigate") || raw.contains("topic=") || raw.contains("函数")
            if (toolish) continue
            if (kept.length + raw.length > MAX_SYSTEM_CHARS) break
            kept.append(raw).append('。')
        }
        val head = kept.toString().ifBlank {
            "你是一个在车里陪用户说话的语音助手，回答口语化、短，不超过两句话。"
        }
        return head +
            "\n（当前是离线本地模式，没有联网和导航工具，不要声称自己能查东西。" +
            "不要重复用户说的话，直接回答或反问。）\n"
    }

    private companion object {
        /**
         * 本地只带最近 1 轮。
         * 实测：system 462 token 时 prefill 要 3.5 秒，首字延迟已经不可接受；
         * 每多带一轮对话就多几十个 token，而 0.3B 也记不住更远。
         */
        const val MAX_TURNS = 1

        /**
         * 系统提示词上限。
         *
         * ★ 云端那份有几千字（工具协议、搜索规则、候选确认），本地一条都用不上，
         *   但每一条都要 prefill。实测 700 字 = 462 token = 3.5 秒首字延迟，
         *   用户等到以为死机。压到 260 字后约 1.5 秒。
         *   本地模式的定位是「离线能聊两句」，不是「功能对齐云端」，
         *   所以这里牺牲人设细节换响应速度。
         */
        const val MAX_SYSTEM_CHARS = 260
    }
}
