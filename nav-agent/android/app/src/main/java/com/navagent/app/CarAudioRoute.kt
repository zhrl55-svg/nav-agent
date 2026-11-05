package com.navagent.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 车载蓝牙媒体播报路由。
 *
 * 播报固定走蓝牙 A2DP（媒体音频），不切通信模式，也不启动 SCO。
 *
 * TextToSpeech 在本项目里显式使用 STREAM_MUSIC；MiniMax MediaPlayer 也使用
 * 导航语音属性。只要系统处于 MODE_NORMAL，蓝牙已连接时这些播放流就会走 A2DP。
 * 反过来切到 MODE_IN_COMMUNICATION 或 startBluetoothSco() 会把播报送进 HFP/SCO，
 * 音质会降到窄带，还会抢占麦克风。
 *
 * 这里只做两件事：
 *   1. 请求短暂的音频焦点（AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK）——
 *      让车机把正在放的音乐压低，播报才听得清；
 *   2. 记录当前是否连接了 A2DP 媒体设备，方便真机排障。
 */
class CarAudioRoute(context: Context) {

    private val am: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val main = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null

    /** 是否已经进入播报路由（防止重复 begin） */
    @Volatile
    private var active = false

    /**
     * 播报开始：抢焦点并确认蓝牙媒体通道可用。
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

        // 2) 蓝牙媒体通道。保持 MODE_NORMAL，由系统把 STREAM_MUSIC 路由到 A2DP。
        val a2dp = bluetoothA2dpDevice(audio)
        Dbg.log(
            if (a2dp == null) {
                "车载蓝牙：未连接 A2DP 媒体设备，播报将使用系统当前媒体路由"
            } else {
                "车载蓝牙：使用 A2DP 媒体音频 ${a2dp.productName} mode=${audio.mode}"
            }
        )
    }

    /** 播报结束：释放焦点。不修改系统音频模式。 */
    fun end() {
        if (!active) return
        active = false
        val audio = am ?: return

        main.post {
            runCatching {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
                focusRequest = null
            }.onFailure { Dbg.log("释放音频焦点失败：${it.message}") }
        }
    }

    /** 当前可用的 A2DP 媒体输出设备。 */
    private fun bluetoothA2dpDevice(
        audio: AudioManager
    ): android.media.AudioDeviceInfo? = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }.getOrNull()
}
