package com.navagent.app

import android.content.Context
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 进程内本地大模型（MNN-LLM）。
 *
 * 和 MNN Chat 的 API 那套的本质区别：**模型跑在导航 App 自己的进程里**，
 * 不经过 127.0.0.1，不依赖别的 App 活着。
 * 今天实测 MNN Chat 有两个死穴（后台不监听、队列卡死需要重启），
 * 在库集成方案里都不存在——进程活着模型就在。
 *
 * ## 模型放哪
 * `filesDir/models/mnn-llm/`，需要 4 个文件：
 *   config.json        运行参数（backend_type / thread_num / sampler…）
 *   llm_config.json    结构 + jinja 模板
 *   llm.mnn            计算图      931KB
 *   llm.mnn.weight     权重        164MB（int4）
 *   tokenizer.mtok     词表        1.6MB
 * 这 5 个文件就是 MNN Chat 下载的那份，直接拷过来即可，不用自己转换。
 *
 * ## 线程与内存
 * 加载约 2 秒、常驻内存约 300MB，必须在后台线程初始化且只初始化一次。
 */
class MnnLocalLlm(private val context: Context) {

    private val lock = ReentrantLock()
    private var session: LlmSession? = null
    private var initFailed = false

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastLoadMs: Long = -1
        private set

    @Volatile
    var lastGenerateMs: Long = -1
        private set

    fun modelDir(): File = File(context.filesDir, "models/mnn-llm")

    /** 模型文件是否齐（不加载） */
    fun modelPresent(): Boolean {
        val d = modelDir()
        return File(d, "llm.mnn").exists() &&
            File(d, "llm.mnn.weight").exists() &&
            File(d, "tokenizer.mtok").exists() &&
            File(d, "config.json").exists()
    }

    /**
     * 加载模型。阻塞，调用方放后台线程。幂等。
     * @return 是否可用
     */
    fun ensureReady(): Boolean {
        if (ready) return true
        lock.withLock {
            if (ready) return true
            if (initFailed) return false
            if (!modelPresent()) {
                initFailed = true
                Dbg.log("本地LLM: 模型文件不全 ${modelDir().absolutePath}")
                return false
            }
            val t0 = System.currentTimeMillis()
            try {
                val dir = modelDir()
                // ★ configPath 必须是 **config.json 这个文件的路径**，不是模型目录。
                //   native 会拿它去 File::readText()，然后从路径反推模型目录。
                //   2026-11-08 第一次传了目录，报 "Module load failed for config: <dir>"。
                val cfg = File(dir, "config.json")
                // ★ max_new_tokens 必须写进配置里。
                //   模型自带的 config.json 没有这个字段，native 默认只生成 1 个 token
                //   （实测 decode_len=1，输出停在「我」）。
                //   单独调 updateMaxNewTokens 也没用，只有在 init 的合并配置里带上才生效。
                val runtimeConfig = org.json.JSONObject(cfg.readText())
                    .put("max_new_tokens", MAX_NEW_TOKENS)
                    // ★ 模型自带的 config.json 里 repetition_penalty 是 1.0（等于不惩罚）。
                    //   0.3B 在这种设置下很容易复读用户原话（实测「去张掖西站」→「去张掖西站」）。
                    //   调到 1.15 明显减少复读，再高会开始胡言乱语。
                    .put("repetition_penalty", 1.15)
                    .put("presence_penalty", 0.3)
                    .toString()
                // 额外开关。keep_history=false：车载指令之间没有上下文依赖，
                // 每句独立反而更稳，也避免 KV cache 越滚越大拖慢 prefill。
                val extra = """{"is_r1":false,"mmap_dir":"","keep_history":false}"""

                val s = LlmSession()
                if (!s.init(cfg.absolutePath, runtimeConfig, extra)) {
                    initFailed = true
                    Dbg.log("本地LLM: initNative 返回 0，加载失败")
                    return false
                }
                // native 默认 maxNewTokens 极小，不设就只吐 1 个 token
                s.setMaxNewTokens(MAX_NEW_TOKENS)
                session = s
                lastLoadMs = System.currentTimeMillis() - t0
                ready = true
                Dbg.log("本地LLM: 就绪，加载 ${lastLoadMs}ms")
                return true
            } catch (t: Throwable) {
                initFailed = true
                Dbg.log("本地LLM: 初始化异常 ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        }
    }

    /**
     * 生成一句话。
     * @param keepHistory false = 每轮独立，不复用 KV
     * @return 文本；不可用时返回 null
     */
    fun generate(
        prompt: String,
        keepHistory: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): String? {
        if (!ensureReady()) return null
        val s = session ?: return null
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        // ★ 文本是从 onProgress 的流式回调里攒出来的，不是从返回的 map 里取。
        //   官方 ChatPresenter 也是这么干的（GenerateResultProcessor 累积 progress）。
        //   第一次实现只读了 map["text"]，结果拿到空字符串。
        val acc = StringBuilder()
        try {
            val result = s.generate(prompt, keepHistory, object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    if (!progress.isNullOrEmpty()) {
                        if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - t0
                        acc.append(progress)
                        onDelta?.invoke(progress)
                    }
                    // ★ 返回值语义是「要不要停止」，不是「要不要继续」。
                    //   官方 ChatPresenter 里就是 `return stopGenerating`。
                    //   一开始按「继续」理解写成 true，结果每轮只出 1 个 token
                    //   （decode_len=1，输出停在「我」）。
                    return false
                }
            })
            lastGenerateMs = System.currentTimeMillis() - t0
            val fromStream = acc.toString().trim()
            val fromMap = (result["text"] as? String)?.trim().orEmpty()
            val text = fromStream.ifBlank { fromMap }
            Dbg.log(
                "本地LLM 生成 ${lastGenerateMs}ms（首字 ${if (firstTokenMs < 0) "?" else firstTokenMs}ms）" +
                    " meta=$result → 「${text.take(80)}」"
            )
            return text
        } catch (t: Throwable) {
            Dbg.log("本地LLM 生成异常 ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    /**
     * 自检：只在设置里打开 `mnn_llm_selftest` 时由 VoiceEngine 调用。
     * 目的不是产出，是让日志里出现一行真实的首字延迟和输出，验证链路通不通。
     */
    fun selfTest() {
        Thread {
            if (!ensureReady()) {
                Dbg.log("本地LLM 自检: 模型不可用")
                return@Thread
            }
            // 自检时显式套上 Qwen 的 chat 模板。
            // 如果 native 自己不套模板，裸 prompt 会在第一个 token 后立刻遇到
            // 停止条件（实测输出只有「我」一个字）。
            val templated = "<|im_start|>user\n用一句中文介绍你自己。<|im_end|>\n<|im_start|>assistant\n"
            val out = generate(templated)
            Dbg.log("本地LLM 自检完成: ${if (out.isNullOrBlank()) "无输出" else "「$out」"}")
            // 冷机那一次会被 ASR/KWS/声纹的模型加载抢 CPU，等 12 秒再跑一次取稳态值
            Thread.sleep(12_000)
            generate(templated)
            Dbg.log("本地LLM 自检（稳态）完成")
        }.apply {
            name = "mnn-selftest"
            isDaemon = true
            start()
        }
    }

    /** 运行时调整单次生成上限。设置页「回复长度」改完下一句生效 */
    fun setMaxNewTokens(n: Int) {
        lock.withLock { session?.setMaxNewTokens(n) }
    }

    fun release() {
        lock.withLock {
            session?.release()
            session = null
            ready = false
        }
    }

    companion object {
        /**
         * 单次生成上限。导航场景的意图/回复都很短，256 足够；
         * 给太大只会在模型胡言乱语时白白拖时间（decode 约 60 t/s，256 token 要 4 秒）。
         */
        const val MAX_NEW_TOKENS = 256

        /**
         * 进程内单例。
         *
         * ★ 必须是单例：模型 166MB、加载后常驻约 300MB。
         *   VoiceEngine（自检）和 ChatEngine（对话）各建一个的话，
         *   会把同一个模型加载两遍，内存直接翻倍。
         */
        @Volatile
        private var instance: MnnLocalLlm? = null

        fun get(context: Context): MnnLocalLlm {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: MnnLocalLlm(context.applicationContext).also { instance = it }
            }
        }
    }
}
