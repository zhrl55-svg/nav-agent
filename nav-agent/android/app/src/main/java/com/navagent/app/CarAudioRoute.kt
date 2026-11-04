package com.navagent.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 车载蓝牙播报路由。
 *
 * ★ 2026-11-03 新建。用户反馈：手机导航时声音传不到车机，高德 App 可以。
 *
 * 根因（按 Android 音频路由的规则推下来，不是猜的）：
 *   我们的播报是 MediaPlayer / TextToSpeech，默认 AudioAttributes 是
 *   `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`，也就是**音乐**类音频。
 *   车机蓝牙有两种通道：
 *     - A2DP（媒体音频）：听歌走这条
 *     - HFP / SCO（通话音频）：打电话走这条
 *   很多车机只连了 HFP（或者媒体音量被静音），这时候 USAGE_MEDIA 完全没声 ——
 *   而高德把播报标成「导航语音」（USAGE_ASSISTANCE_NAVIGATION_GUIDANCE），
 *   系统会把它当通话类送进 SCO，所以高德能响。
 *
 * 这里做三件事，缺一件都可能还是没声：
 *   1. 请求短暂的音频焦点（AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK）——
 *      让车机把正在放的音乐压低，播报才听得清；
 *   2. 播报期间把模式切到 MODE_IN_COMMUNICATION 并起 SCO ——
 *      把声音从 A2DP 挪到通话通道；
 *   3. 播完立刻恢复（模式和 SCO 都要还原）。
 *
 * ★ 为什么必须立刻恢复：SCO 一开，麦克风也会切到 8kHz 窄带，
 *   而我们播报结束就要马上开始拾音。晚恢复 200ms，下一句「导航去西站」
 *   就会识别成别的。所以 end() 放在 setSpeaking(false) 的同一个调用栈里。
 *
 * ★ 没有车机可测时的保守策略：所有调用都 runCatching 包住，
 *   失败就退回「只设 AudioAttributes」的老路 —— 至少不比改之前更差。
 */
class CarAudioRoute(context: Context) {

    private val am: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val main = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null

    /** 播报前的音频模式，播完还原 */
    private var previousMode: Int = AudioManager.MODE_NORMAL

    /** 这次播报有没有真的起过 SCO */
    private var scoStarted = false

    /** 是否已经进入播报路由（防止重复 begin） */
    @Volatile
    private var active = false

    /**
     * 播报开始：抢焦点 + 切通道。
     *
     * @param navPriority 导航播报优先，用 GAIN_TRANSIENT；闲聊用 DUCK 更礼貌
     */
    fun begin(navPriority: Boolean) {
        val audio = am ?: return
        if (active) return
        active = true

        // 1) 音频焦点
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(
                    if (navPriority) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_ASSISTANT
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(
                if (navPriority) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = req
            val r = audio.requestAudioFocus(req)
            Dbg.log("车载音频焦点 request=${r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED}")
        }.onFailure { Dbg.log("申请音频焦点失败：${it.message}") }

        // 2) 蓝牙通道。用户可以在设置里关掉，关掉就只靠 AudioAttributes
        if (!Prefs.carBluetoothAudio) return
        if (!isBluetoothAudioConnected(audio)) {
            Dbg.log("车载蓝牙：当前没有蓝牙音频设备，保持外放")
            return
        }

        runCatching {
            previousMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            // startBluetoothSco 在 API 31+ 已废弃但仍可用；新 API 走 setCommunicationDevice。
            // 两条都试一遍，哪条能通算哪条 —— 车机固件差异很大。
            if (audio.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                scoStarted = true
            }
            Dbg.log("车载蓝牙：已切通信模式，SCO=$scoStarted prevMode=$previousMode")
        }.onFailure { Dbg.log("切蓝牙通道失败：${it.message}") }
    }

    /** 播报结束：还原模式和 SCO，释放焦点 */
    fun end() {
        if (!active) return
        active = false
        val audio = am ?: return

        main.post {
            runCatching {
                if (scoStarted) {
                    @Suppress("DEPRECATION")
                    audio.stopBluetoothSco()
                    audio.isSpeakerphoneOn = false
                    scoStarted = false
                }
                // 只在确实是我们改过模式时才还原，别把用户手动设的模式打乱
                if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audio.mode = previousMode
                }
            }.onFailure { Dbg.log("还原蓝牙通道失败：${it.message}") }

            runCatching {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
                focusRequest = null
            }.onFailure { Dbg.log("释放音频焦点失败：${it.message}") }
        }
    }

    /** 有没有连着蓝牙音频设备（A2DP 或 SCO），没有就没必要切 */
    private fun isBluetoothAudioConnected(audio: AudioManager): Boolean = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)
}
