package com.lumi.app.notes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NoteAppResult { SILENT, UI_OPENED, FAILED }

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

    /**
     * Attempt to create the note in the user's real notes app.
     * Priority: Samsung Notes content provider (silent) → Samsung Notes UI → Google Keep UI → generic share.
     * Returns SILENT if saved without showing any UI, UI_OPENED if the notes app was launched,
     * or FAILED if nothing worked.
     */
    fun createInApp(title: String, content: String): NoteAppResult {
        // 1. Samsung Notes — try silent content provider insert first
        val samsungProviderUris = listOf(
            "content://com.samsung.android.snote.provider/notes",
            "content://com.samsung.android.app.notes.sync.provider.SyncNoteProvider/SyncNote"
        )
        for (uriStr in samsungProviderUris) {
            try {
                val values = ContentValues().apply {
                    put("title", title)
                    put("memo_text", content)
                    put("body_plain", content)
                    put("modified_time", System.currentTimeMillis())
                }
                val inserted = context.contentResolver.insert(Uri.parse(uriStr), values)
                if (inserted != null) return NoteAppResult.SILENT
            } catch (_: Exception) {}
        }
        // 2. Samsung Notes — open via Intent.ACTION_SEND (app shows briefly, needs Save tap)
        if (tryStartActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.samsung.android.app.notes")
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, content)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })) return NoteAppResult.UI_OPENED
        // 3. Google Keep
        if (tryStartActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.google.android.keep")
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, content)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })) return NoteAppResult.UI_OPENED
        // 4. Any notes app that handles text/plain share
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, if (title.isNotBlank()) "$title\n\n$content" else content)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            NoteAppResult.UI_OPENED
        } catch (e: Exception) {
            Log.e("NotesHelper", "createInApp failed: ${e.message}")
            NoteAppResult.FAILED
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (_: Exception) { false }

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

    fun delete(id: String): Boolean {
        val all = getAll().toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) save(all)
        return removed
    }

    fun findByTitle(titleQuery: String): LumiNote? {
        val q = titleQuery.lowercase()
        val all = getAll()
        return all.firstOrNull { it.title.equals(titleQuery, ignoreCase = true) }
            ?: all.firstOrNull { it.title.lowercase().contains(q) }
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

    /**
     * Create a note in a specific app by name.
     * Supported: samsung, keep, onenote, notion, obsidian, standard (Standard Notes).
     */
    fun createInSpecificApp(app: String, title: String, content: String): NoteAppResult {
        val a = app.lowercase()
        return when {
            a.contains("obsidian") -> {
                // Obsidian URI scheme for creating new notes
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("obsidian://new?name=${Uri.encode(title)}&content=${Uri.encode(content)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    NoteAppResult.UI_OPENED
                } catch (_: Exception) {
                    openNoteViaShare("md.obsidian", title, content)
                }
            }
            a.contains("onenote") -> openNoteViaShare("com.microsoft.office.onenote", title, content)
            a.contains("notion")  -> openNoteViaShare("notion.id", title, content)
            a.contains("standard") -> openNoteViaShare("com.standardnotes.standardnotes", title, content)
            a.contains("keep")    -> openNoteViaShare("com.google.android.keep", title, content)
            a.contains("samsung") -> createInApp(title, content)
            else                  -> createInApp(title, content)
        }
    }

    private fun openNoteViaShare(pkg: String, title: String, content: String): NoteAppResult {
        return if (tryStartActivity(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, if (title.isNotBlank()) "$title\n\n$content" else content)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })) NoteAppResult.UI_OPENED else NoteAppResult.FAILED
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
