package com.example.rag

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

// Petite classe pour stocker un message et savoir qui l'a envoyé
data class Message(val text: String, val isUser: Boolean)

class MessageAdapter(private val messages: List<Message>) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAiMessage: TextView = view.findViewById(R.id.tvAiMessage)
        val tvUserMessage: TextView = view.findViewById(R.id.tvUserMessage)
        val markwon: Markwon = Markwon.create(itemView.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        if (message.isUser) {
            holder.tvUserMessage.text = message.text
            holder.tvUserMessage.visibility = View.VISIBLE
            holder.tvAiMessage.visibility = View.GONE
        } else {
            holder.tvAiMessage.text = message.text
            holder.tvAiMessage.visibility = View.VISIBLE
            holder.tvUserMessage.visibility = View.GONE
            holder.markwon.setMarkdown(holder.tvAiMessage, message.text)
        }
    }

    override fun getItemCount() = messages.size
}