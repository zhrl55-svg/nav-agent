package com.navagent.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
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
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
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
        running = true
        rec.startRecording()
        Dbg.log("AudioCapture 启动 ${SAMPLE_RATE}Hz buf=$bufSize")

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
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        Dbg.log("AudioCapture 停止")
    }

    val isRunning: Boolean get() = running

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS // 320
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
