package com.navagent.core

/**
 * 对话持久化接口。
 *
 * 记忆由宿主保存，[ChatEngine] 只负责在正确时机读写。这样 core 模块仍然不依赖
 * Android、SQLite 或具体文件格式，换存储实现时不需要改聊天编排。
 */
interface ChatMemory {
    /** 按时间顺序返回最近的消息，旧消息在前。 */
    fun loadRecent(limit: Int): List<ChatMessage>

    /**
     * 保存一条消息。实现可在 USER 消息中抽取用户明确要求记住的长期事实。
     */
    fun append(message: ChatMessage)

    /**
     * 删除刚写入、但没有得到回复的用户消息。
     *
     * 只删除仍是数据库最后一条且内容一致的 USER 消息，避免误删后续并发写入。
     */
    fun removeLastIfMatching(message: ChatMessage)

    /** 清空对话消息和长期事实。 */
    fun clear()

    /**
     * 生成本轮 system prompt 使用的长期记忆片段。
     *
     * 返回空串表示没有可注入的长期事实。
     */
    fun contextFor(query: String): String

    /** 默认实现供不需要记忆的调用方使用。 */
    object None : ChatMemory {
        override fun loadRecent(limit: Int): List<ChatMessage> = emptyList()
        override fun append(message: ChatMessage) = Unit
        override fun removeLastIfMatching(message: ChatMessage) = Unit
        override fun clear() = Unit
        override fun contextFor(query: String): String = ""
    }
}
