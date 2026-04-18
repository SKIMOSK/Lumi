package com.lumi.app.notes

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LumiNote(
    val id: String = System.nanoTime().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

class NotesHelper(private val context: Context) {

    private val notesFile = File(context.filesDir, "lumi_notes.json")
    private val gson = Gson()

    fun getAll(): List<LumiNote> {
        if (!notesFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<LumiNote>>() {}.type
            gson.fromJson<List<LumiNote>>(notesFile.readText(), type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun create(title: String, content: String): LumiNote {
        val note = LumiNote(title = title.ifBlank { "Notița ${getAll().size + 1}" }, content = content)
        val all = getAll().toMutableList().also { it.add(note) }
        save(all)
        return note
    }

    fun update(id: String, title: String? = null, content: String? = null): Boolean {
        val all = getAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(
            title = title ?: all[idx].title,
            content = content ?: all[idx].content,
            updatedAt = System.currentTimeMillis()
        )
        save(all)
        return true
    }

    fun search(query: String): List<LumiNote> {
        val q = query.lowercase()
        return getAll().filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) }
    }

    fun formatSummary(limit: Int = 20): String {
        val notes = getAll().takeLast(limit)
        if (notes.isEmpty()) return "Nu exista notite salvate."
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        return notes.joinToString("\n---\n") { n ->
            "ID:${n.id} | ${n.title} (${fmt.format(Date(n.updatedAt))})\n${n.content}"
        }
    }

    /** Attempt to read Samsung Notes — silently returns empty on non-Samsung devices. */
    fun readSamsungNotes(limit: Int = 10): List<String> {
        return try {
            val uri = Uri.parse("content://com.samsung.android.snote.provider/notes")
            context.contentResolver.query(uri, arrayOf("title", "memo_text"), null, null, null)
                ?.use { cursor ->
                    val out = mutableListOf<String>()
                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val title = cursor.getString(0) ?: ""
                        val body  = cursor.getString(1) ?: ""
                        out.add("$title: $body")
                        count++
                    }
                    out
                } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun save(notes: List<LumiNote>) = notesFile.writeText(gson.toJson(notes))
}
