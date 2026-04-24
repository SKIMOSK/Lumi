package com.lumi.app.system

import android.content.Context
import android.os.Environment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

class DocumentHelper(private val context: Context) {

    fun findRecentFile(query: String): File? {
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Documents"),
            File(Environment.getExternalStorageDirectory(), "Telegram/Telegram Documents")
        )

        val q = query.lowercase()
        var bestMatch: File? = null
        var newestTime = 0L
        val isGeneric = q.isBlank() || q == "recent" || q == "document" || q == "fisier"

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile && !file.isHidden) {
                    val name = file.name.lowercase()
                    // If searching for "discord", we check the path too
                    if (isGeneric || name.contains(q) || file.absolutePath.lowercase().contains(q)) {
                        if (file.lastModified() > newestTime) {
                            newestTime = file.lastModified()
                            bestMatch = file
                        }
                    }
                }
            }
        }
        return bestMatch
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
