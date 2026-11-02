package com.navagent.core

/** 一条对话消息。role 只有用户/助手两种对外的，system 提示词不进入历史。 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val ts: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT }
}

/** 模型适配层。换模型只实现这个接口，上层不动。 */
interface Llm {
    /**
     * 流式对话。
     * @param system 系统提示词（包含当前导航上下文）
     * @param history 已有对话
     * @param onDelta 每收到一段增量文本就回调，用于界面逐字上屏
     * @return 完整回复；失败返回 null
     */
    fun chat(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String?

    /**
     * 只跑「把话说完」这一步：不做意图判断、不调工具。
     *
     * ★ 专门给失败重试用。
     *
     * 为什么必须单独开一个方法：带工具的实现（[com.navagent.app.OpenAiLlm]）
     * 内部是「第一轮判断意图 → 执行工具 → 第二轮总结」的完整流程。
     * 如果重试直接再调一次 [chat]，等于把刚才那次搜索原样重跑一遍 ——
     * 白等五秒、白烧一次额度，而且重试本来是为了「请求更小、成功率更高」，
     * 结果请求一样大。
     *
     * 默认实现退化成 [chat]，不覆写也不会错（只是重试会重跑工具）。
     */
    fun chatText(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? =
        chat(system, history, onDelta)
}
