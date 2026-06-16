package dev.phucngu.simpletype.ime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.phucngu.simpletype.R

class ClipboardAdapter(
    private val onSelect: (String) -> Unit,
    private val onPin: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    private var items: List<ClipboardItem> = emptyList()

    fun submitList(newItems: List<ClipboardItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clipboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.item_text)
        private val pinBtn: ImageButton = view.findViewById(R.id.item_pin)
        private val deleteBtn: ImageButton = view.findViewById(R.id.item_delete)

        fun bind(item: ClipboardItem) {
            text.text = item.text
            text.setOnClickListener { onSelect(item.text) }
            
            val pinColor = if (item.isPinned) R.color.kb_accent else R.color.kb_chrome_icon
            pinBtn.setColorFilter(ContextCompat.getColor(itemView.context, pinColor))
            pinBtn.setOnClickListener { onPin(item.id) }
            
            deleteBtn.setOnClickListener { onDelete(item.id) }
        }
    }
}
