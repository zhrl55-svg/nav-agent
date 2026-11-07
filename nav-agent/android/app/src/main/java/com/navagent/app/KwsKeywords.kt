package com.navagent.app

import android.icu.text.Transliterator

/**
 * 汉字 → sherpa-onnx 关键词行。
 *
 * ★ 为什么需要它：
 *   sherpa 的 KWS 模型工作单位是**带声调的音素 token**，它不认识汉字。
 *   关键词行的格式是 `x iǎo zh ū @小猪`（每个音节拆成 声母 token + 韵母 token）。
 *   以前这个串是写死在代码里的（`KEYWORD_LINE`），所以唤醒词一改就失效。
 *
 * ★ 为什么不需要内置拼音表：
 *   Android 从 API 29 起自带 ICU，`Transliterator("Han-Latin")` 输出的就是
 *   **带声调**的拼音（小猪 → "xiǎo zhū"）。VoskWake 用的是
 *   "Han-Latin; Latin-ASCII"，那一步会把声调抹掉；这里不能用那条链。
 *
 * ★ 拆分规则来自 keywords.txt 的实测格式：
 *   小 = x + iǎo，猪 = zh + ū，艺 = y + ì，爱 = ài（零声母整串保留）。
 *   声母表必须**长的在前**（zh/ch/sh 先于 z/c/s），否则「zhū」会被切成 z + hū。
 */
object KwsKeywords {

    private val converter: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
    }

    /** 声母表：按长度降序排，双字母的必须先匹配 */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    /**
     * 生成一行关键词。转不出来（非汉字、ICU 不可用）返回 null，调用方跳过。
     *
     * @return 例如 `x iǎo zh ū @小猪`
     */
    fun line(keyword: String): String? {
        val word = keyword.trim()
        if (word.isEmpty()) return null
        val t = converter ?: return null
        val pinyin = runCatching { t.transliterate(word) }.getOrNull()?.trim()
        if (pinyin.isNullOrEmpty()) return null
        val syllables = pinyin.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (syllables.isEmpty()) return null
        val tokens = syllables.flatMap { splitSyllable(it) }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") + " @" + word
    }

    /** 一批词 → 多行；重复词只留一行（label 是匹配键，不能重复） */
    fun lines(words: List<String>): List<String> =
        words.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { line(it) }
            .toList()

    private fun splitSyllable(syllable: String): List<String> {
        val s = syllable.lowercase()
        for (initial in INITIALS) {
            if (s.length > initial.length && s.startsWith(initial)) {
                return listOf(initial, s.substring(initial.length))
            }
        }
        // 零声母（爱 ài、儿 èr、恩 ēn…）：整串就是一个 token
        return listOf(s)
    }
}
