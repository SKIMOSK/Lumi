package com.lumi.app.notes

import android.content.Context
import java.io.File

class UserMemory(private val context: Context) {

    private val file = File(context.filesDir, "user_memory.txt")

    fun remember(fact: String): String {
        val trimmed = fact.trim()
        val current = load()
        val combined = if (current.isBlank()) trimmed else "$current\n$trimmed"
        file.writeText(trimToWordLimit(combined, 300))
        return "Am retinut: $trimmed"
    }

    fun load(): String = if (file.exists()) file.readText().trim() else ""

    fun isEmpty(): Boolean = load().isBlank()

    fun wordCount(): Int {
        val text = load()
        return if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    fun clear() { file.delete() }

    private fun trimToWordLimit(text: String, limit: Int): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val kept = mutableListOf<String>()
        var count = 0
        for (line in lines.reversed()) {
            val lw = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
            if (count + lw > limit) break
            kept.add(0, line.trim())
            count += lw
        }
        return kept.joinToString("\n")
    }
}
