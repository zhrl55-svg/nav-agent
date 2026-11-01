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
}
