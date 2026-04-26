package com.lumi.app.system

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

class DocumentHelper(private val context: Context) {

    /** Filesystem locations to walk when MediaStore lookup misses. */
    private fun searchDirs(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        val pub = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        )
        val whatsapp = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            "WhatsApp/Media/WhatsApp Images"
        ).map { File(ext, it) }
        val others = listOf(
            "Telegram/Telegram Documents",
            "Telegram/Telegram Images",
            "Android/media/org.telegram.messenger/Telegram/Telegram Documents",
            "Android/media/org.telegram.messenger/Telegram/Telegram Images",
            "Signal/Media",
            "Discord"
        ).map { File(ext, it) }
        // Cache & temp directories the app itself wrote to (CREATE_FORWARD_FILE etc.)
        val appCache = listOfNotNull(
            context.getExternalFilesDir(null),
            context.cacheDir,
            context.filesDir
        )
        return pub + whatsapp + others + appCache
    }

    /**
     * Find the most relevant recent file for [query]. Strategy:
     *   1. Query MediaStore for files matching the query (covers Downloads, Documents, etc.
     *      including scoped-storage paths we couldn't walk directly).
     *   2. Fall back to a depth-limited walk of well-known directories.
     *   3. Score: name-token match > path-token match > generic-recent. Tie-break by mtime.
     * Tolerates SecurityException per directory — scoped-storage subtrees vary by OEM.
     */
    fun findRecentFile(query: String): File? {
        val q = normalizeQuery(query)
        val isGeneric = q.isBlank() || q in GENERIC_TOKENS
        val tokens = if (isGeneric) emptySet() else q.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

        val candidates = mutableListOf<Scored>()

        // ── 1. MediaStore lookup ──────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                queryMediaStore(tokens, isGeneric)?.let { candidates += it }
            } catch (_: Exception) { /* tolerate any MediaStore quirks */ }
        }

        // ── 2. Filesystem walk ────────────────────────────────────────────────
        for (dir in searchDirs()) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.walkTopDown().maxDepth(4).forEach { file ->
                    if (!file.isFile || file.isHidden) return@forEach
                    val score = scoreFile(file, tokens, isGeneric) ?: return@forEach
                    candidates += Scored(file, score, file.lastModified())
                }
            } catch (_: SecurityException) { /* scoped-storage denial — skip */ }
        }

        if (candidates.isEmpty()) return null
        // Highest score wins; ties broken by recency
        return candidates.maxWithOrNull(
            compareBy<Scored> { it.score }.thenBy { it.modified }
        )?.file
    }

    private fun queryMediaStore(tokens: Set<String>, isGeneric: Boolean): Scored? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val (selection, args) = if (tokens.isEmpty()) {
            // Most recent file across all media types
            null to null
        } else {
            // Build a LIKE query joined by AND — every token must appear in the name
            val likes = tokens.joinToString(" AND ") {
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            }
            likes to tokens.map { "%$it%" }.toTypedArray()
        }
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 50"

        val cursor = context.contentResolver.query(
            collection, projection, selection, args, sortOrder
        ) ?: return null
        cursor.use { c ->
            val pathIdx = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            var best: Scored? = null
            while (c.moveToNext()) {
                val path = if (pathIdx >= 0) c.getString(pathIdx) else null
                val file = path?.let { File(it) }?.takeIf { it.exists() && it.isFile } ?: continue
                val score = scoreFile(file, tokens, isGeneric) ?: continue
                val s = Scored(file, score, file.lastModified())
                val current = best
                if (current == null || s.score > current.score ||
                    (s.score == current.score && s.modified > current.modified)
                ) best = s
            }
            return best
        }
    }

    private fun scoreFile(file: File, tokens: Set<String>, isGeneric: Boolean): Int? {
        if (isGeneric) return SCORE_GENERIC
        val name = file.name.lowercase()
        val path = file.absolutePath.lowercase()
        // Score per token: 10 if in name, 4 if in path, 0 otherwise. Reject if no token matches.
        var score = 0
        var anyMatch = false
        for (t in tokens) {
            when {
                name.contains(t) -> { score += 10; anyMatch = true }
                path.contains(t) -> { score += 4;  anyMatch = true }
            }
        }
        // Bonus for filename starting with the first token (likely the user's intent)
        if (tokens.isNotEmpty() && name.startsWith(tokens.first())) score += 5
        return if (anyMatch) score else null
    }

    fun extractText(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "pdf" -> {
                    val document = PDDocument.load(file)
                    val stripper = PDFTextStripper()
                    val text = stripper.getText(document)
                    document.close()
                    text
                }
                "docx", "pptx", "xlsx" -> {
                    // Office Open XML — strip text from the main content part
                    val zip = ZipFile(file)
                    val mainEntry = when (file.extension.lowercase()) {
                        "docx" -> "word/document.xml"
                        "pptx" -> null  // multiple slide files; concatenate
                        else   -> "xl/sharedStrings.xml"
                    }
                    val sb = StringBuilder()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val keep = when {
                            mainEntry != null -> e.name == mainEntry
                            file.extension.lowercase() == "pptx" -> e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")
                            else -> false
                        }
                        if (keep) {
                            val xml = zip.getInputStream(e).reader().readText()
                            sb.append(xml.replace(Regex("<[^>]+>"), " "))
                            sb.append('\n')
                        }
                    }
                    zip.close()
                    sb.toString().replace(Regex("\\s+"), " ").trim()
                }
                "txt", "csv", "json", "md", "log", "xml", "yaml", "yml", "html", "htm" -> file.readText()
                "rtf" -> file.readText().replace(Regex("\\\\[a-z]+\\d* ?"), " ").replace(Regex("[{}]"), "")
                else -> "Format nesuportat (${file.extension}). Pot citi: pdf, docx, pptx, xlsx, txt, csv, json, md, rtf, html, xml."
            }
        } catch (e: Exception) {
            "Eroare la citire: ${e.message}"
        }
    }

    fun writeTextToNewFile(originalFile: File, newContent: String): File {
        val ext = originalFile.extension.lowercase()
        val baseName = originalFile.nameWithoutExtension
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // Plain-text formats: keep extension. Everything else: write a .txt sibling.
        if (ext in PLAIN_TEXT_EXT) {
            val dest = File(downloads, "${baseName}_corectat.$ext")
            dest.writeText(newContent)
            return dest
        }
        val txtDest = File(downloads, "${baseName}_corectat.txt")
        txtDest.writeText(newContent)
        return txtDest
    }

    private fun normalizeQuery(query: String): String =
        query.lowercase().trim().replace(Regex("[\\p{Punct}]+"), " ").replace(Regex("\\s+"), " ")

    private data class Scored(val file: File, val score: Int, val modified: Long)

    companion object {
        private const val SCORE_GENERIC = 1
        private val GENERIC_TOKENS = setOf("recent", "document", "fisier", "file", "ultim", "ultima", "last")
        private val PLAIN_TEXT_EXT = setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml", "html", "htm")
    }
}
