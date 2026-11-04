package com.navagent.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.MediaRecorder

/**
 * 麦克风唯一所有者。
 *
 * ★ 这是整套语音架构的硬约束：**一个麦克风只能有一个 AudioRecord**。
 * 之前用系统 SpeechRecognizer 的路径已经废掉了 —— AudioRecord 一旦被我们持有，
 * 系统识别器就拿不到麦克风。所以 ASR 走 MiniMax 在线（用户拍板），
 * 唤醒走 vosk 离线，两者都消费这里的 PCM。
 *
 * 16kHz / 单声道 / PCM16，20ms 一帧（320 采样）。所有下游（VAD、唤醒、声纹）
 * 都按这个帧率喂。
 */
class AudioCapture(
    /** 每帧回调，在采集线程里调用，别在这里做重活 */
    private val onFrame: (ShortArray, Int) -> Unit
) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    var lastError: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            lastError = "设备不支持 16kHz 录音（minBuffer=$minBuf）"
            Dbg.log(lastError!!)
            return false
        }
        val bufSize = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 8)
        val rec = try {
            // 系统 AEC 会显式挂到录音会话上，因此这里仍使用更适合离线唤醒和
            // ASR 的 VOICE_RECOGNITION 前处理。VOICE_COMMUNICATION 在 P40 上
            // 会同时压低主人近场语音，真机表现为 AEC=true，但播报中喊“小猪”
            // Vosk 只能零星识别出“小”，几乎拿不到完整候选。
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize
            )
        } catch (t: Throwable) {
            lastError = "AudioRecord 创建失败：${t.message}"
            Dbg.log(lastError!!)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "AudioRecord 初始化失败 state=${rec.state}"
            Dbg.log(lastError!!)
            rec.release()
            return false
        }
        record = rec
        attachEchoCanceler(rec)
        running = true
        rec.startRecording()
        Dbg.log(
            "AudioCapture 启动 ${SAMPLE_RATE}Hz source=VOICE_RECOGNITION " +
                "buf=$bufSize aec=${echoCanceler?.enabled == true}"
        )

        thread = Thread {
            val frame = ShortArray(FRAME_SAMPLES)
            var fill = 0
            while (running) {
                val n = rec.read(frame, fill, FRAME_SAMPLES - fill)
                if (n < 0) {
                    Dbg.log("AudioRecord.read 返回 $n，停止采集")
                    break
                }
                fill += n
                if (fill < FRAME_SAMPLES) continue
                onFrame(frame, FRAME_SAMPLES)
                fill = 0
            }
        }.apply {
            isDaemon = true
            name = "audio-capture"
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        runCatching { echoCanceler?.enabled = false }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        Dbg.log("AudioCapture 停止")
    }

    val isRunning: Boolean get() = running

    private fun attachEchoCanceler(rec: AudioRecord) {
        if (!AcousticEchoCanceler.isAvailable()) {
            Dbg.log("系统 AEC 不可用，继续使用 OEM 默认预处理")
            return
        }
        runCatching {
            echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)?.apply {
                enabled = true
            }
            Dbg.log("系统 AEC ${if (echoCanceler?.enabled == true) "已启用" else "启用失败"}")
        }.onFailure {
            Dbg.log("系统 AEC 创建失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS // 320
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
