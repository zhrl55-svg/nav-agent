package com.navagent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.navagent.core.ChatMemory
import com.navagent.core.ChatMessage

data class MemoryFact(
    val id: Long,
    val category: String,
    val key: String,
    val value: String,
    val updatedAt: Long
)

data class MemoryStats(
    val messages: Int,
    val facts: Int
)

/**
 * App 本地的对话与长期记忆。
 *
 * 数据只保存在手机私有目录，不跟随聊天接口：
 * - messages 保存最近对话，进程重启后继续给模型提供上下文；
 * - memory_facts 保存用户明确说“记住……”的内容；
 * - 每次请求从同一份数据库拼上下文，切换 Minis / MiniMax / OpenAI 都不会丢。
 *
 * 当前离线 Qwen / BGE，先用关键词匹配筛选长期事实。数据量上限和注入长度都有限制，
 * 避免 system prompt 随使用时间无限增长。
 */
class LocalMemoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION),
    ChatMemory {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_created ON messages(created_at, id)")
        db.execSQL(
            """
            CREATE TABLE memory_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                fact_key TEXT NOT NULL UNIQUE,
                fact_value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_facts_updated ON memory_facts(updated_at, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 第一版数据库。以后如需加摘要或向量表，在这里按 oldVersion 做增量迁移。
    }

    @Synchronized
    override fun loadRecent(limit: Int): List<ChatMessage> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT role, content, created_at FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceAtMost(MAX_MESSAGES).toString())
        ).use { c ->
            while (c.moveToNext()) {
                val role = when (c.getString(0)) {
                    ROLE_USER -> ChatMessage.Role.USER
                    ROLE_ASSISTANT -> ChatMessage.Role.ASSISTANT
                    else -> continue
                }
                val text = c.getString(1).orEmpty()
                if (text.isNotBlank()) out += ChatMessage(role, text, c.getLong(2))
            }
        }
        out.reverse()
        return out
    }

    @Synchronized
    override fun append(message: ChatMessage) {
        if (message.text.isBlank()) return
        val db = writableDatabase
        db.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("role", if (message.role == ChatMessage.Role.USER) ROLE_USER else ROLE_ASSISTANT)
                put("content", message.text)
                put("created_at", message.ts)
            }
        )
        if (message.role == ChatMessage.Role.USER) rememberExplicitFacts(message.text)
        pruneMessages(db)
    }

    @Synchronized
    override fun removeLastIfMatching(message: ChatMessage) {
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, role, content FROM messages ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return
            val role = if (message.role == ChatMessage.Role.USER) ROLE_USER else ROLE_ASSISTANT
            if (c.getString(1) == role && c.getString(2) == message.text) {
                db.delete("messages", "id = ?", arrayOf(c.getLong(0).toString()))
            }
        }
    }

    @Synchronized
    override fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("memory_facts", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 只清聊天记录，保留用户明确要求记住的偏好。 */
    @Synchronized
    fun clearConversation() {
        writableDatabase.delete("messages", null, null)
    }

    @Synchronized
    fun stats(): MemoryStats = MemoryStats(
        messages = count("messages"),
        facts = count("memory_facts")
    )

    @Synchronized
    fun facts(limit: Int = MAX_FACTS): List<MemoryFact> {
        val out = ArrayList<MemoryFact>()
        readableDatabase.rawQuery(
            "SELECT id, category, fact_key, fact_value, updated_at " +
                "FROM memory_facts ORDER BY updated_at DESC, id DESC LIMIT ?",
            arrayOf(limit.coerceAtMost(MAX_FACTS).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += MemoryFact(
                    id = c.getLong(0),
                    category = c.getString(1),
                    key = c.getString(2),
                    value = c.getString(3),
                    updatedAt = c.getLong(4)
                )
            }
        }
        return out
    }

    @Synchronized
    fun deleteFact(id: Long) {
        writableDatabase.delete("memory_facts", "id = ?", arrayOf(id.toString()))
    }

    /**
     * 从设置页新增或编辑一条长期记忆。
     *
     * @param id 为 null 时新增；有值时原地更新，保留原有分类和稳定 key。
     * @return 内容为空、超过长度限制或写库失败时返回 false。
     */
    @Synchronized
    fun saveFact(id: Long?, value: String): Boolean {
        val fact = value.trim()
            .replace(Regex("[\\t\\u00A0]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
        if (fact.length !in 2..MAX_FACT_CHARS) return false

        val db = writableDatabase
        val now = System.currentTimeMillis()
        if (id != null) {
            val updated = db.update(
                "memory_facts",
                ContentValues().apply {
                    put("fact_value", fact)
                    put("updated_at", now)
                },
                "id = ?",
                arrayOf(id.toString())
            )
            if (updated > 0) {
                pruneFacts()
                return true
            }
        }

        val key = "manual_${fact.hashCode().toUInt().toString(16)}"
        val inserted = db.insertWithOnConflict(
            "memory_facts",
            null,
            ContentValues().apply {
                put("category", "manual")
                put("fact_key", key)
                put("fact_value", fact)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (inserted != -1L) pruneFacts()
        return inserted != -1L
    }

    @Synchronized
    override fun contextFor(query: String): String {
        val all = facts()
        if (all.isEmpty()) return ""
        val queryGrams = searchGrams(query)
        val ranked = all.sortedWith(
            compareByDescending<MemoryFact> { fact ->
                if (queryGrams.isEmpty()) 0 else queryGrams.count { it in fact.value }
            }.thenByDescending { it.updatedAt }
        )

        val body = StringBuilder()
        for (fact in ranked) {
            val line = "- ${fact.value}\n"
            if (body.length + line.length > MAX_CONTEXT_CHARS) break
            body.append(line)
        }
        if (body.isEmpty()) return ""
        return buildString {
            append("【App 本地长期记忆】\n")
            append("以下是用户在以往对话中明确要求你记住的信息。")
            append("回答时自然遵守；与本轮无关就不要主动提。\n")
            append(body)
        }.trimEnd()
    }

    /**
     * 只抽取明确记忆指令，避免把所有闲聊都永久保存。
     *
     * 支持“记住我不走高速”“请记住：周五限号”等说法，也允许分号分隔多条。
     */
    private fun rememberExplicitFacts(text: String) {
        val match = REMEMBER_PATTERN.find(text) ?: return
        val raw = match.groupValues.getOrNull(1).orEmpty().trim()
        if (raw.isEmpty() || raw.length > MAX_FACT_CHARS) return
        if (raw.endsWith('？') || raw.endsWith('?') || raw.endsWith("吗")) return

        raw.split('；', ';', '\n')
            .map { cleanFact(it) }
            .filter { it.length in 2..MAX_FACT_CHARS }
            .forEach { fact ->
                val key = "explicit_${fact.hashCode().toUInt().toString(16)}"
                writableDatabase.insertWithOnConflict(
                    "memory_facts",
                    null,
                    ContentValues().apply {
                        put("category", "explicit")
                        put("fact_key", key)
                        put("fact_value", fact)
                        put("updated_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        pruneFacts()
    }

    private fun cleanFact(input: String): String {
        var value = input.trim()
            .trimStart('，', ',', '：', ':', '。', '.', ' ')
            .trimEnd('。', '.', '！', '!', '吧', '啊', '呀', ' ')
        if (value.startsWith("了") && value.length > 1) {
            value = value.drop(1).trimStart('，', ',', '：', ':', ' ')
        }
        value = when {
            value.startsWith("我们") -> "用户" + value.drop(2)
            value.startsWith("我") -> "用户" + value.drop(1)
            value.startsWith("本人的") -> "用户" + value.drop(2)
            else -> value
        }
        return value.take(MAX_FACT_CHARS)
    }

    private fun pruneMessages(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM messages WHERE id NOT IN " +
                "(SELECT id FROM messages ORDER BY id DESC LIMIT $MAX_MESSAGES)"
        )
    }

    private fun pruneFacts() {
        writableDatabase.execSQL(
            "DELETE FROM memory_facts WHERE id NOT IN " +
                "(SELECT id FROM memory_facts ORDER BY updated_at DESC, id DESC LIMIT $MAX_FACTS)"
        )
    }

    private fun count(table: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun searchGrams(text: String): Set<String> {
        val normalized = text.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.length < 2) return emptySet()
        return normalized.windowed(2).toSet()
    }

    companion object {
        const val MAX_FACT_CHARS = 200
        private const val DB_NAME = "local_memory.db"
        private const val DB_VERSION = 1
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val MAX_MESSAGES = 500
        private const val MAX_FACTS = 30
        private const val MAX_CONTEXT_CHARS = 1_200

        private val REMEMBER_PATTERN = Regex(
            "(?:请|帮我|麻烦)?(?:你)?记住(?:一下|住)?[：:，,\\s]*(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
