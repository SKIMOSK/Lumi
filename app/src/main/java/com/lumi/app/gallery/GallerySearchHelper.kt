package com.lumi.app.gallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.lumi.app.ai.GeminiClient
import com.lumi.app.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val displayName: String
)

class GallerySearchHelper(private val context: Context) {

    companion object {
        private const val TAG = "GallerySearch"
        const val MAX_SCAN = 1000

        fun parseDateString(dateStr: String?): Long? {
            if (dateStr.isNullOrBlank()) return null
            val now = System.currentTimeMillis()
            val d = dateStr.lowercase()
            return when {
                d.contains("yesterday") || d.contains("ieri") -> now - 86_400_000L
                d.contains("last week") || d.contains("saptamana") -> now - 7 * 86_400_000L
                d.contains("last month") || d.contains("luna trecuta") -> now - 30 * 86_400_000L
                else -> {
                    for (fmt in listOf("yyyy-MM-dd", "dd.MM.yyyy", "MM/dd/yyyy")) {
                        try { SimpleDateFormat(fmt, Locale.US).parse(dateStr)?.time?.let { return it } } catch (_: Exception) {}
                    }
                    null
                }
            }
        }
    }

    /** Returns the [limit] most recent gallery images. */
    fun queryRecent(limit: Int = MAX_SCAN): List<GalleryImage> {
        val images = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    images.add(GalleryImage(
                        id = id,
                        uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    ))
                    count++
                }
            }
            images
        } catch (e: Exception) {
            Log.e(TAG, "queryRecent failed: ${e.message}")
            emptyList()
        }
    }

    fun filterByDateRange(images: List<GalleryImage>, fromMs: Long?, toMs: Long?): List<GalleryImage> =
        images.filter { img ->
            (fromMs == null || img.dateTaken >= fromMs) && (toMs == null || img.dateTaken <= toMs)
        }

    /**
     * Uses AI vision (Haiku) to check candidates against [description] in batches.
     * Encodes each image at 256px for speed. Returns up to [maxResults] matches.
     */
    suspend fun findByVision(
        candidates: List<GalleryImage>,
        description: String,
        client: GeminiClient,
        model: String = AppSettings.MODEL_FASTER,
        maxCandidates: Int = 80,
        batchSize: Int = 6,
        maxResults: Int = 10
    ): List<GalleryImage> {
        if (candidates.isEmpty() || description.isBlank()) return emptyList()
        val pool = candidates.take(maxCandidates)
        val matched = mutableListOf<GalleryImage>()

        for (batch in pool.chunked(batchSize)) {
            if (matched.size >= maxResults) break
            val pairs = batch.mapNotNull { img ->
                val b64 = try { encodeToBase64(img.id, 256) } catch (_: Exception) { null }
                if (b64 != null) img to b64 else null
            }
            if (pairs.isEmpty()) continue
            val indices = client.checkImagesMatch(pairs.map { it.second }, description, model)
            indices.forEach { idx ->
                if (idx < pairs.size && matched.size < maxResults) matched.add(pairs[idx].first)
            }
        }
        return matched
    }

    /** Encodes a gallery image as JPEG base64 for sending to the AI vision model. */
    fun encodeToBase64(imageId: Long, maxSize: Int = 512): String? {
        return try {
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream) ?: return null
                val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
                val scaled = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                } else bmp
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "encodeToBase64 failed: ${e.message}")
            null
        }
    }

    /** Saves a base64 image to the app's cache dir and returns a File for sharing. */
    fun saveBase64ToCache(base64: String, filename: String = "lumi_share.jpg"): File? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val file = File(context.cacheDir, filename)
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            Log.e(TAG, "saveBase64ToCache failed: ${e.message}")
            null
        }
    }

    fun formatSummary(images: List<GalleryImage>): String {
        if (images.isEmpty()) return "Nu s-au gasit imagini."
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return images.mapIndexed { i, img ->
            val date = if (img.dateTaken > 0) fmt.format(Date(img.dateTaken)) else "data necunoscuta"
            "[$i] ID:${img.id} | $date | ${img.displayName}"
        }.joinToString("\n")
    }

    fun getUri(imageId: Long): Uri =
        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
}
