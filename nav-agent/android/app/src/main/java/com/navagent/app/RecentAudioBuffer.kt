package com.navagent.app

import java.util.ArrayDeque

/**
 * 唤醒候选的短音频环形缓冲。
 *
 * Vosk 命中时只能告诉我们“文本像唤醒词”，无法提供声纹所需的完整 PCM。
 * 这里保留最近一小段麦克风音频，命中后立刻交给声纹门禁做二次确认。
 *
 * [add] 在 audio-capture 线程持续调用，[snapshot]/[clear] 可能来自主线程，
 * 所以三个入口都做同步。
 */
class RecentAudioBuffer(private val capacitySamples: Int) {

    private val frames = ArrayDeque<ShortArray>()
    private var samples = 0

    @Synchronized
    fun add(frame: ShortArray, len: Int) {
        if (len <= 0) return
        frames.addLast(frame.copyOf(len))
        samples += len
        while (samples > capacitySamples && frames.isNotEmpty()) {
            samples -= frames.removeFirst().size
        }
    }

    @Synchronized
    fun snapshot(): ShortArray {
        val out = ShortArray(samples)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }

    @Synchronized
    fun clear() {
        frames.clear()
        samples = 0
    }
}
