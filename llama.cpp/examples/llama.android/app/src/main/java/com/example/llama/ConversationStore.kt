package com.example.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Conversation(
    val id: String,
    val title: String,
    val timestampMillis: Long,
    val messages: List<Message>
)

/**
 * Lightweight summary used for the history list -- avoids loading every
 * conversation's full message list just to show a title and date.
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val timestampMillis: Long
)

class ConversationStore(context: Context) {
    private val dir: File = File(context.filesDir, "conversations").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.json")

    /** Derives a short title from the first user message, or falls back to a generic label. */
    private fun titleFor(messages: List<Message>): String {
        val firstUser = messages.firstOrNull { it.isUser }?.content ?: return "New conversation"
        return if (firstUser.length > 40) firstUser.take(40).trim() + "..." else firstUser
    }

    fun save(messages: List<Message>, existingId: String? = null): Conversation? {
        if (messages.isEmpty()) return null
        val id = existingId ?: UUID.randomUUID().toString()
        val conversation = Conversation(id, titleFor(messages), System.currentTimeMillis(), messages)

        val messagesArray = JSONArray()
        for (m in messages) {
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("content", m.content)
            obj.put("isUser", m.isUser)
            messagesArray.put(obj)
        }
        val root = JSONObject()
        root.put("id", conversation.id)
        root.put("title", conversation.title)
        root.put("timestampMillis", conversation.timestampMillis)
        root.put("messages", messagesArray)

        fileFor(id).writeText(root.toString())
        return conversation
    }

    fun list(): List<ConversationSummary> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    val root = JSONObject(f.readText())
                    ConversationSummary(
                        id = root.getString("id"),
                        title = root.getString("title"),
                        timestampMillis = root.getLong("timestampMillis")
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestampMillis }
            ?: emptyList()
    }

    fun load(id: String): Conversation? {
        return try {
            val root = JSONObject(fileFor(id).readText())
            val messagesArray = root.getJSONArray("messages")
            val messages = mutableListOf<Message>()
            for (i in 0 until messagesArray.length()) {
                val obj = messagesArray.getJSONObject(i)
                messages.add(Message(obj.getString("id"), obj.getString("content"), obj.getBoolean("isUser")))
            }
            Conversation(
                id = root.getString("id"),
                title = root.getString("title"),
                timestampMillis = root.getLong("timestampMillis"),
                messages = messages
            )
        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }
}
