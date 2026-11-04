package com.navagent.app

import android.content.Context
import android.media.AudioManager

/**
 * 华为设备上的 ASR 回声消除开关。
 *
 * P40 Pro 的 audio_flinger 会给华为语音助手下发：
 *   ASR_AEC=ON
 *   VOIP_PKGNAME=com.huawei.vassistant
 *
 * 这是面向语音识别链路的前处理，和普通通话 AEC 不是同一条路径。App 不持有
 * 系统源码，只能通过 AudioManager 的隐藏 setParameters 接口发送同样参数；
 * 失败时完全静默回退到 AudioCapture 上显式挂载的 AcousticEchoCanceler。
 */
class HuaweiAudioPreprocess(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val setParameters = runCatching {
        AudioManager::class.java.getMethod("setParameters", String::class.java)
    }.getOrNull()

    @Volatile
    private var active = false

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        val manager = audioManager ?: return
        val method = setParameters ?: run {
            Dbg.log("华为 ASR_AEC 不可用：setParameters 不存在")
            return
        }
        val params = if (active) {
            listOf(
                "VOIP_PKGNAME=${appContext.packageName}",
                "ASR_AEC=ON"
            )
        } else {
            listOf("ASR_AEC=OFF")
        }
        runCatching {
            // Huawei's AudioPolicy parser rejects the whole call when two OEM
            // keys are packed into one semicolon-separated command. Amap can set
            // VOIP_PKGNAME successfully by sending it alone, so use the same form.
            params.forEach { method.invoke(manager, it) }
            Dbg.log("华为 ASR_AEC 已发送 ${if (active) "ON" else "OFF"}")
        }.onFailure {
            Dbg.log("华为 ASR_AEC 设置失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
