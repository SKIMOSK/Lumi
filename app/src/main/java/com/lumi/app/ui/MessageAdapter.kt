package com.lumi.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumi.app.R
import com.lumi.app.ai.Interaction

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_LUMI = 1

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
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
            is LumiViewHolder -> holder.bind(msg)
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
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivImage.setImageBitmap(bmp)
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

        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
            tvTime.text = msg.time
            tvModel.text = if (msg.usedPro) "Pro" else "Flash"
            tvModel.visibility = View.VISIBLE
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
    val isLoading: Boolean = false
)
