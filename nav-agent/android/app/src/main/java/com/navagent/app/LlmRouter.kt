package com.navagent.app

import android.content.Context
import com.navagent.core.ChatMessage
import com.navagent.core.Llm

/**
 * 对话后端路由：云端 / 本地 / 自动 三档。
 *
 * | 档位 | 后端 | 工具 | 适用 |
 * |---|---|---|---|
 * | `auto`（默认） | 有网走云端，断网走本地 | 云端全可用 / 本地靠规则 | 日常 |
 * | `cloud` | 强制 MiniMax | 全部可用 | 排查、需要联网搜索 |
 * | `local` | 强制进程内 0.3B | 只剩规则导航 | 省流量、纯离线 |
 *
 * ## auto 为什么现在才敢做
 * 早先不敢自动切，因为**能力不对等**：切到本地就等于失去工具调用，
 * 用户会莫名其妙发现「导航不好使了」，却不知道为什么。
 *
 * 2026-11-08 把导航挪到规则层之后，本地档照样能「导航去X」「停止导航」
 * 「继续导航」，降级只影响搜索、天气和闲聊深度 —— 代价小到可以接受。
 *
 * ## 切换必须让用户看见
 * 自动切换的前提是**不静默**：真的换了后端会通知一次，
 * 设置页也会显示当前实际走的是哪条路。
 */
class LlmRouter(
    private val context: Context,
    private val cloud: Llm,
    private val local: Llm,
    /** 自动模式真的换了后端时回调：true=云端，false=本地 */
    private val onModeChanged: ((Boolean) -> Unit)? = null
) : Llm {

    @Volatile
    private var lastAutoCloud: Boolean? = null

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

    private fun pick(): Llm {
        val mode = Prefs.chatEngine
        val online = NetworkState.isOnline(context)
        val useCloud = when (mode) {
            ENGINE_LOCAL -> false
            ENGINE_CLOUD -> true
            else -> online
        }
        // 只有 auto 档需要提示切换；手动档是用户自己选的，不用再告诉他
        if (mode == ENGINE_AUTO) {
            val changed = lastAutoCloud != null && lastAutoCloud != useCloud
            if (lastAutoCloud == null) {
                Dbg.log("对话后端=auto 初始 ${if (useCloud) "云端" else "本地（离线）"} online=$online")
            }
            lastAutoCloud = useCloud
            if (changed) {
                Dbg.log("对话后端=auto 切换 → ${if (useCloud) "云端" else "本地（离线）"} online=$online")
                onModeChanged?.invoke(useCloud)
            }
        }
        return if (useCloud) cloud else local
    }

    companion object {
        const val ENGINE_AUTO = "auto"
        const val ENGINE_CLOUD = "cloud"
        const val ENGINE_LOCAL = "local"

        /** 给 UI 用的当前实际后端描述 */
        fun describe(context: Context): String = when (Prefs.chatEngine) {
            ENGINE_LOCAL -> "本地（0.3B）"
            ENGINE_CLOUD -> "云端（MiniMax）"
            else -> if (NetworkState.isOnline(context)) "自动 · 云端" else "自动 · 本地（离线）"
        }
    }
}
