package com.navagent.app

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile

/**
 * 离线自测：拿一段 wav 跑一遍 KWS，看看模型到底认不认得出唤醒词。
 *
 * 为什么需要它：
 *   真机日志里播报期间 Vosk 能吐出「小」「猪」，但 Sherpa KWS 从来没命中过
 *   （一条 `Sherpa KWS 命中` 都没有）。到底是模型/关键词文件配错了，
 *   还是麦克风那条 TTS 混音根本激不活解码器，光看线上日志分不清。
 *
 *   模型包自带 test_wavs/ 和 test_keywords.txt，用官方样例跑一遍：
 *   命中 = 模型和配置没问题，问题在输入音频；不命中 = 配置有问题。
 */
object SherpaKwsTester {

    fun run(
        context: Context,
        wavPath: String,
        keywordsAsset: String = "$ASSET_DIR/keywords_test.txt"
    ): String {
        val file = File(wavPath)
        if (!file.exists()) return "文件不存在：$wavPath"

        val wav = readWav(file) ?: return "不是可解析的 wav：$wavPath"
        val seconds = wav.samples.size.toDouble() / wav.rate
        Dbg.log("KWS 自测 ${file.name} 时长 ${"%.2f".format(seconds)}s 采样率 ${wav.rate}")

        var spotter: KeywordSpotter? = null
        val streams = ArrayList<Pair<String, OnlineStream>>()
        return try {
            spotter = KeywordSpotter(context.assets, buildConfig(context, keywordsAsset))
            for (line in readKeywordLines(context, keywordsAsset)) {
                streams += line to spotter.createStream(line)
            }

            val hits = ArrayList<String>()
            val samples = wav.samples
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(512, samples.size - offset)
                val buf = FloatArray(n)
                for (i in 0 until n) buf[i] = samples[offset + i] / 32768f
                for ((_, stream) in streams) {
                    stream.acceptWaveform(buf, wav.rate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val result = spotter.getResult(stream)
                        if (result.keyword.isNotBlank()) {
                            val at = offset.toDouble() / wav.rate
                            hits += "${result.keyword}@${"%.2f".format(at)}s"
                            Dbg.log("KWS 自测命中 ${result.keyword} @${"%.2f".format(at)}s")
                        }
                    }
                }
                offset += n
            }
            if (hits.isEmpty()) "无命中" else hits.joinToString(", ")
        } catch (t: Throwable) {
            "异常 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            streams.forEach { runCatching { it.second.release() } }
            runCatching { spotter?.release() }
        }
    }

    private fun buildConfig(context: Context, keywordsAsset: String): KeywordSpotterConfig {
        val transducer = OnlineTransducerModelConfig(
            encoder = "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "$ASSET_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            joiner = "$ASSET_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        )
        val model = OnlineModelConfig(
            transducer = transducer,
            tokens = "$ASSET_DIR/tokens.txt",
            numThreads = 1,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2"
        )
        return KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioCapture.SAMPLE_RATE,
                featureDim = 80,
                dither = 0f
            ),
            modelConfig = model,
            maxActivePaths = 8,
            keywordsFile = keywordsAsset,
            keywordsScore = 2.0f,
            keywordsThreshold = 0.12f,
            numTrailingBlanks = 1
        )
    }

    private fun readKeywordLines(context: Context, asset: String): List<String> {
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        return text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }

    private data class Wav(val samples: ShortArray, val rate: Int)

    /** 只支持 16bit PCM，够读官方样例。 */
    private fun readWav(file: File): Wav? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff) != "RIFF") return null
                raf.skipBytes(4)
                val wave = ByteArray(4)
                raf.readFully(wave)
                if (String(wave) != "WAVE") return null

                var rate = 16_000
                var bits = 16
                var channels = 1
                var dataOffset = -1
                var dataSize = 0
                while (raf.filePointer < raf.length()) {
                    val id = ByteArray(4)
                    if (raf.read(id) < 4) break
                    val size = readIntLE(raf)
                    when (String(id)) {
                        "fmt " -> {
                            raf.skipBytes(2)
                            channels = readShortLE(raf)
                            rate = readIntLE(raf)
                            raf.skipBytes(6)
                            bits = readShortLE(raf)
                            raf.skipBytes(size - 16)
                        }
                        "data" -> {
                            dataOffset = raf.filePointer.toInt()
                            dataSize = size
                            break
                        }
                        else -> raf.skipBytes(size)
                    }
                    if (size % 2 == 1) raf.skipBytes(1)
                }
                if (dataOffset < 0 || bits != 16) return null
                val stride = maxOf(channels, 1)
                val count = minOf(dataSize / 2, (raf.length() - dataOffset).toInt() / 2)
                val raw = ByteArray(count * 2)
                raf.seek(dataOffset.toLong())
                raf.readFully(raw)
                val samples = ShortArray(count / stride)
                var s = 0
                var i = 0
                while (i + 1 < raw.size && s < samples.size) {
                    val lo = raw[i].toInt() and 0xFF
                    val hi = raw[i + 1].toInt()
                    samples[s++] = ((hi shl 8) or lo).toShort()
                    i += 2 * stride
                }
                Wav(samples, rate)
            }
        } catch (t: Throwable) {
            Dbg.log("KWS 自测读 wav 失败 ${t.message}")
            null
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private const val ASSET_DIR = "sherpa-kws"
}
