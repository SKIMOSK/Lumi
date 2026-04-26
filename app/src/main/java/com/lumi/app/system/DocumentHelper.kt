package com.lumi.app.system

import android.content.Context
import android.os.Environment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

class DocumentHelper(private val context: Context) {

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

    fun findRecentFile(query: String): File? {
        val q = query.lowercase().trim()
        val isGeneric = q.isBlank() || q in setOf("recent", "document", "fisier", "file", "ultim", "ultima")

        // Two-pass scoring: prefer files whose NAME matches the query, then by recency.
        var nameMatch: File? = null
        var nameMatchTime = 0L
        var pathMatch: File? = null
        var pathMatchTime = 0L
        var generic: File? = null
        var genericTime = 0L

        for (dir in searchDirs()) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.walkTopDown().maxDepth(4).forEach { file ->
                    if (!file.isFile || file.isHidden) return@forEach
                    val name = file.name.lowercase()
                    val path = file.absolutePath.lowercase()
                    val mt = file.lastModified()

                    if (isGeneric) {
                        if (mt > genericTime) { genericTime = mt; generic = file }
                        return@forEach
                    }
                    if (name.contains(q)) {
                        if (mt > nameMatchTime) { nameMatchTime = mt; nameMatch = file }
                    } else if (path.contains(q)) {
                        if (mt > pathMatchTime) { pathMatchTime = mt; pathMatch = file }
                    }
                }
            } catch (_: SecurityException) {
                // Permission-denied subtree on scoped storage — silently skip.
            }
        }
        return nameMatch ?: pathMatch ?: generic
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
                "docx" -> {
                    var text = ""
                    val zip = ZipFile(file)
                    val entry = zip.getEntry("word/document.xml")
                    if (entry != null) {
                        val xml = zip.getInputStream(entry).reader().readText()
                        text = xml.replace(Regex("<[^>]+>"), " ")
                                  .replace(Regex("\\s+"), " ")
                                  .trim()
                    }
                    zip.close()
                    text
                }
                "txt", "csv", "json", "md" -> file.readText()
                else -> "Format nesuportat (${file.extension}). Pot citi doar continut text (pdf, docx, txt)."
            }
        } catch (e: Exception) {
            "Eroare la citire: ${e.message}"
        }
    }

    fun writeTextToNewFile(originalFile: File, newContent: String): File {
        val newName = originalFile.nameWithoutExtension + "_corectat." + originalFile.extension
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), newName)
        if (originalFile.extension.lowercase() == "txt" || originalFile.extension.lowercase() == "md") {
            dest.writeText(newContent)
        } else {
            // For pdf/docx, editing and repacking is very complex natively without huge libraries.
            // We just save the corrected text as a .txt file for simplicity if we can't write pdf natively easily.
            val txtDest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), originalFile.nameWithoutExtension + "_corectat.txt")
            txtDest.writeText(newContent)
            return txtDest
        }
        return dest
    }
}
