package com.navagent.app

import com.navagent.core.ChatMessage
import com.navagent.core.Llm

/**
 * 对话后端路由：云端 / 本地 两档，设置页可切。
 *
 * | 档位 | 后端 | 工具 | 适用 |
 * |---|---|---|---|
 * | `cloud` | MiniMax（OpenAI 兼容） | 全部可用 | **默认**，线上行为 |
 * | `local` | MNN-Pocket-0.3B（进程内） | 全部降级 | 没网、只想聊两句 |
 *
 * ## 为什么不做「自动切换」
 * 想过「有网走云端、没网走本地」，但两者**能力不对等**：本地没有工具，
 * 同一个问题「导航去国贸」云端会真的开始导航，本地只能回一句「好的」。
 * 自动切换会让行为变得不可预测——用户不知道自己这句话到底会不会被执行。
 * 所以做成显式开关，用户自己知道现在是什么模式。
 *
 * ## local 档的降级是「静默」的
 * 不会弹窗说「工具不可用」，因为语音场景弹窗没人看。系统提示词里已经写明
 * 「当前是离线本地模式，没有联网和导航工具」，模型会自己用话术带过。
 */
class LlmRouter(
    private val cloud: Llm,
    private val local: Llm
) : Llm {

    override fun chat(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? {
        val backend = pick()
        val started = System.currentTimeMillis()
        val result = backend.chat(system, history, onDelta)
        Dbg.log(
            "对话后端=${if (backend === local) "local" else "cloud"} " +
                "${System.currentTimeMillis() - started}ms 结果=${result?.length ?: -1}字"
        )
        return result
    }

    override fun chatText(system: String, history: List<ChatMessage>, onDelta: (String) -> Unit): String? =
        pick().chatText(system, history, onDelta)

    private fun pick(): Llm = if (Prefs.chatEngine == ENGINE_LOCAL) local else cloud

    private companion object {
        const val ENGINE_LOCAL = "local"
    }
}
