package com.alibaba.mnnllm.android.llm

/**
 * MNN-LLM 会话的**最小 JNI 桥**。
 *
 * ## 为什么要自己写这一层，而不是直接用 MnnLlmChat 的 LlmSession
 * 官方那个 LlmSession.kt 有 472 行，还牵出 ChatDataItem / ModelConfig / Jinja /
 * MmapUtils / QnnModule 等十几个类，整包搬进来会拖一大串依赖。
 * 而 JNI 侧只认「包名 + 类名 + 方法名 + 参数表」，所以我们声明同样的 native 方法
 * 即可直接复用官方编译好的 libmnnllmapp.so，一行 C++ 都不用写。
 *
 * ## 约束（改了就崩）
 * - 包名必须是 `com.alibaba.mnnllm.android.llm`
 * - 类名必须是 `LlmSession`
 * - native 方法名和参数顺序必须与 so 里的 `Java_com_alibaba_mnnllm_android_llm_LlmSession_*` 一致
 *
 * ## 与官方实现的差异
 * 只管 init / generate / reset / release 四件事，去掉了多模态、QNN、mmap、
 * 分段模型合并、mock latex 这些本场景用不到的分支。
 */
class LlmSession {

    @Volatile
    private var nativePtr: Long = 0L

    val ready: Boolean get() = nativePtr != 0L

    /**
     * 加载模型。阻塞调用，必须放后台线程。
     *
     * @param configPath      模型目录（里面要有 llm.mnn / llm.mnn.weight /
     *                        tokenizer.mtok / llm_config.json）
     * @param mergedConfigStr 运行参数 JSON，就是模型目录里 config.json 的内容
     *                        （backend_type / thread_num / sampler 那些）
     * @param configJsonStr   额外开关：{"is_r1":false,"mmap_dir":"","keep_history":true}
     * @return 是否加载成功
     */
    fun init(configPath: String, mergedConfigStr: String, configJsonStr: String): Boolean {
        if (nativePtr != 0L) return true
        nativePtr = initNative(configPath, null, mergedConfigStr, configJsonStr)
        return nativePtr != 0L
    }

    /**
     * 生成。返回的 map 里通常有 `text`（完整结果）和 `metrics`（性能）。
     * @param keepHistory 是否把这一轮记进 KV cache；false 则每次全新
     */
    fun generate(
        prompt: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any> {
        check(nativePtr != 0L) { "模型还没加载" }
        synchronized(this) {
            return submitNative(nativePtr, prompt, keepHistory, listener)
        }
    }

    /** 清空对话历史（KV cache 重置），不释放模型 */
    fun reset() {
        if (nativePtr != 0L) resetNative(nativePtr)
    }

    /**
     * 设置单次生成的最大 token 数。
     *
     * ★ 必须显式调用：native 侧的默认值很小，不设的话只会吐 1 个 token
     *   （2026-11-08 实测：不设 → 输出只有「我」一个字）。
     */
    fun setMaxNewTokens(n: Int) {
        if (nativePtr != 0L) updateMaxNewTokensNative(nativePtr, n)
    }

    fun release() {
        synchronized(this) {
            if (nativePtr != 0L) {
                releaseNative(nativePtr)
                nativePtr = 0L
            }
        }
    }

    // ------------------------------------------------------------ native 声明

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun resetNative(instanceId: Long)

    private external fun releaseNative(instanceId: Long)

    private external fun getDebugInfoNative(instanceId: Long): String

    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)

    companion object {
        init {
            // libmnnllmapp.so 依赖 libMNN.so，两者都必须打进 jniLibs
            System.loadLibrary("mnnllmapp")
        }
    }
}
