package com.alibaba.mnnllm.android.llm

/**
 * MNN 推理的流式回调。
 *
 * ★ 包名和类名不能改：libmnnllmapp.so 是按 `com_alibaba_mnnllm_android_llm_*`
 *   做 JNI 符号绑定的，改了类名就找不到 native 实现。
 */
interface GenerateProgressListener {
    /** 返回 false 表示中止本次生成 */
    fun onProgress(progress: String?): Boolean
}
