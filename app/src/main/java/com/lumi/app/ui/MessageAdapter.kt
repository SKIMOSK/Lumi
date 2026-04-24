package com.lumi.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumi.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MessageAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSpeak: ((String) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    private var lastLumiMsgId: Long = -1L

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_LUMI = 1
        private val thumbCache = mutableMapOf<Long, Bitmap>()

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    override fun onCurrentListChanged(prev: List<ChatMessage>, curr: List<ChatMessage>) {
        val newLastLumi = curr.lastOrNull { !it.isUser && !it.isLoading }?.id ?: -1L
        if (newLastLumi != lastLumiMsgId) {
            val prevIdx = prev.indexOfFirst { it.id == lastLumiMsgId }
            lastLumiMsgId = newLastLumi
            if (prevIdx >= 0) notifyItemChanged(prevIdx)
            val newIdx = curr.indexOfFirst { it.id == newLastLumi }
            if (newIdx >= 0) notifyItemChanged(newIdx)
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).isUser) TYPE_USER else TYPE_LUMI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
            else -> LumiViewHolder(inflater.inflate(R.layout.item_message_lumi, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(msg)
            is LumiViewHolder -> holder.bind(msg, msg.id == lastLumiMsgId)
        }
    }

    inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = view.findViewById(R.id.tvMessageTime)
        private val ivImage: ImageView = view.findViewById(R.id.ivMessageImage)

        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
            tvTime.text = msg.time
            if (msg.imageBase64 != null) {
                val bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT)
                ivImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                ivImage.visibility = View.VISIBLE
            } else {
                ivImage.visibility = View.GONE
            }
        }
    }

    inner class LumiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = view.findViewById(R.id.tvMessageTime)
        private val tvModel: TextView = view.findViewById(R.id.tvModelBadge)
        private val btnSpeak: ImageButton = view.findViewById(R.id.btnSpeak)
        private val scrollGallery: HorizontalScrollView = view.findViewById(R.id.scrollGallery)
        private val llGalleryThumbs: LinearLayout = view.findViewById(R.id.llGalleryThumbs)
        private val thumbJobs = mutableMapOf<Int, Job>()

        fun bind(msg: ChatMessage, isLast: Boolean) {
            tvText.text = msg.text
            tvTime.text = msg.time
            tvModel.text = if (msg.usedPro) "Pro" else "Flash"
            tvModel.visibility = View.VISIBLE
            if (isLast && onSpeak != null && !msg.isLoading) {
                btnSpeak.visibility = View.VISIBLE
                btnSpeak.setOnClickListener { onSpeak.invoke(msg.text) }
            } else {
                btnSpeak.visibility = View.GONE
            }

            // Gallery thumbnails
            thumbJobs.values.forEach { it.cancel() }
            thumbJobs.clear()
            llGalleryThumbs.removeAllViews()

            val ids = msg.galleryImageIds
            if (!ids.isNullOrEmpty()) {
                scrollGallery.visibility = View.VISIBLE
                val dp100 = (100 * context.resources.displayMetrics.density).toInt()
                val dp6   = (6  * context.resources.displayMetrics.density).toInt()
                ids.forEach { imageId ->
                    val iv = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp100, dp100).also { it.marginEnd = dp6 }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.drawable.bubble_lumi)
                        clipToOutline = true
                        setOnClickListener { openInGallery(imageId) }
                        setOnLongClickListener { copyToClipboard(imageId); true }
                    }
                    llGalleryThumbs.addView(iv)

                    val cached = thumbCache[imageId]
                    if (cached != null) {
                        iv.setImageBitmap(cached)
                    } else {
                        val job = scope.launch {
                            val bmp = withContext(Dispatchers.IO) { loadThumb(imageId) }
                            if (bmp != null) {
                                thumbCache[imageId] = bmp
                                iv.setImageBitmap(bmp)
                            }
                        }
                        thumbJobs[imageId.toInt()] = job
                    }
                }
            } else {
                scrollGallery.visibility = View.GONE
            }
        }

        private fun loadThumb(imageId: Long): Bitmap? = try {
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val full = BitmapFactory.decodeStream(stream) ?: return null
                val size = 200
                val scale = size.toFloat() / maxOf(full.width, full.height)
                if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                else full
            }
        } catch (_: Exception) { null }

        private fun openInGallery(imageId: Long) {
            try {
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Toast.makeText(context, "Nu s-a putut deschide galeria.", Toast.LENGTH_SHORT).show()
            }
        }

        private fun copyToClipboard(imageId: Long) {
            try {
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId.toString())
                val clip = ClipData.newUri(context.contentResolver, "Imagine Lumi", uri)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Imagine copiata.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Eroare la copiere.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val text: String,
    val time: String,
    val isUser: Boolean,
    val imageBase64: String? = null,
    val usedPro: Boolean = false,
    val isLoading: Boolean = false,
    val galleryImageIds: List<Long>? = null
)
