package com.lumi.app.gallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

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
                        uri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
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
     * Uses ML Kit Image Labeling to score each candidate image against [query].
     * Processes up to 200 images to keep latency reasonable.
     * Returns the top [maxResults] matches sorted by confidence.
     */
    suspend fun findByLabel(
        candidates: List<GalleryImage>,
        query: String,
        maxResults: Int = 10
    ): List<GalleryImage> {
        if (candidates.isEmpty()) return emptyList()
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()
        )
        val scored = mutableListOf<Pair<GalleryImage, Float>>()

        for (img in candidates.take(200)) {
            try {
                val inputImage = InputImage.fromFilePath(context, img.uri)
                val labels = suspendCancellableCoroutine<List<com.google.mlkit.vision.label.ImageLabel>> { cont ->
                    labeler.process(inputImage)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(emptyList()) }
                    cont.invokeOnCancellation { labeler.close() }
                }
                val score = labels.sumOf { label ->
                    val text = label.text.lowercase()
                    keywords.count { kw ->
                        text.contains(kw) || kw.contains(text)
                    }.toDouble() * label.confidence
                }.toFloat()
                if (score > 0f) scored.add(img to score)
            } catch (_: Exception) {}
        }

        labeler.close()
        return scored.sortedByDescending { it.second }.take(maxResults).map { it.first }
    }

    /** Encodes a gallery image as JPEG base64 for sending to the AI vision model. */
    fun encodeToBase64(imageId: Long, maxSize: Int = 512): String? {
        return try {
            val uri = Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
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
