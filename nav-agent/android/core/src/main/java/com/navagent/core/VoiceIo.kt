package com.navagent.core

/** 语音识别。整段 PCM 进去，文本出来。失败返回 null。 */
interface Asr {
    fun transcribe(pcm: ShortArray, sampleRate: Int = 16000): String?
}

/**
 * 离线唤醒词。
 * feed 是流式喂的，内部自己攒缓冲；命中就返回 true。
 */
interface WakeWord {
    /** 模型是否加载成功。失败时 [feed] 永远返回 false，界面据此提示 */
    val ready: Boolean

    /**
     * 最近一次命中时的识别窗口文本。
     *
     * 用来判断命中的唤醒词是「用户喊的」还是「播报内容里带的」：
     * 窗口里唤醒词出现的次数比当前播报文本里多，说明多出来那一次是用户说的。
     * 没命中过就是 null。
     */
    val lastMatchText: String?

    /**
     * @param strict true 时只接受完整唤醒词或完整同音词，禁用词首丢失、
     *               任意片段和近似拼音兜底。播报期间必须使用严格模式，
     *               防止 TTS 正文中的普通词误触发打断。
     */
    fun feed(pcm: ShortArray, len: Int, strict: Boolean = false): Boolean
    fun reset()
    fun shutdown()
}

/**
 * 声纹门禁。
 *
 * ★ 设计原则：**失败一律放行**。
 * 它是用来防误触发的（乘客说话、导航播报串音），不是身份认证。
 * 取不到音频、算不出 embedding、超时 —— 全部 allow=true，别当锁用。
 */
interface SpeakerGate {
    /** 用一段有效语音注册"主人"。返回是否注册成功 */
    fun enroll(pcm: ShortArray, sampleRate: Int = 16000): Boolean
    /** 与已注册模板的余弦相似度；未注册或失败返回 null（调用方应放行） */
    fun score(pcm: ShortArray, sampleRate: Int = 16000): Float?
    val enrolled: Boolean
}

/**
 * 云端语音合成。
 *
 * 本地 TTS 零延迟、离线可用，但音色取决于手机装的引擎（这台华为是 hiai，也可以换讯飞）。
 * 想要更自然的音色就得走云端 —— 代价是每句多几百毫秒网络往返，且离线不可用。
 *
 * ★ 实现方负责：合成 → 播放 → 播完调 [onDone]。中途失败也要调 [onDone]，
 *   否则 [VoiceGuide] 的播报队列会卡死（isSpeaking 一直为 true，拾音永远被挡）。
 */
interface CloudTts {
    /** 合成并播放。回调可能在工作线程，调用方自己切主线程 */
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    /** 立即停止当前播放 */
    fun stop()
}
