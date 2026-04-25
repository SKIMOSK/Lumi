package com.lumi.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log

class FileHelper(private val context: Context) {

    companion object {
        private const val TAG = "FileHelper"
        private const val MAX_TEXT_BYTES = 48_000

        private val TEXT_MIME_EXTRAS = setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/yaml", "application/csv",
            "application/x-sh", "application/x-python"
        )

        val SUPPORTED_IMAGE_MIMES = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp"
        )
    }

    fun getMimeType(uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    fun getDisplayName(uri: Uri): String {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
        }
        return uri.lastPathSegment ?: "file"
    }

    fun isTextMime(mimeType: String) =
        mimeType.startsWith("text/") || mimeType in TEXT_MIME_EXTRAS

    fun isSupportedImageMime(mimeType: String) = mimeType in SUPPORTED_IMAGE_MIMES

    fun readText(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val truncated = if (bytes.size > MAX_TEXT_BYTES) bytes.copyOf(MAX_TEXT_BYTES) else bytes
            truncated.toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.e(TAG, "readText failed: ${e.message}")
        null
    }

    fun readAsBase64(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "readAsBase64 failed: ${e.message}")
            null
        }
    }

    fun writeText(uri: Uri, content: String): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "writeText failed: ${e.message}")
        false
    }
}
