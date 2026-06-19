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

    sealed class Row {
        data class Header(val titleRes: Int) : Row()
        data class Item(val value: ClipboardItem) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submitList(newItems: List<ClipboardItem>) {
        val pinned = newItems.filter { it.isPinned }
        val recent = newItems.filterNot { it.isPinned }
        rows = buildList {
            if (pinned.isNotEmpty()) {
                add(Row.Header(R.string.clipboard_pinned))
                pinned.forEach { add(Row.Item(it)) }
            }
            if (recent.isNotEmpty()) {
                add(Row.Header(R.string.clipboard_recent))
                recent.forEach { add(Row.Item(it)) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == VIEW_TYPE_HEADER) {
            R.layout.item_clipboard_header
        } else {
            R.layout.item_clipboard
        }
        return ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView? = view.findViewById(R.id.item_text)
        private val pinBtn: ImageButton? = view.findViewById(R.id.item_pin)
        private val deleteBtn: ImageButton? = view.findViewById(R.id.item_delete)

        fun bind(row: Row) {
            when (row) {
                is Row.Header -> bindHeader(row.titleRes)
                is Row.Item -> bindItem(row.value)
            }
        }

        private fun bindHeader(titleRes: Int) {
            text?.setText(titleRes)
        }

        private fun bindItem(item: ClipboardItem) {
            text?.text = item.text
            itemView.setOnClickListener { onSelect(item.text) }
            val pinColor = if (item.isPinned) R.color.kb_accent else R.color.kb_chrome_icon
            pinBtn?.setColorFilter(ContextCompat.getColor(itemView.context, pinColor))
            pinBtn?.setOnClickListener { onPin(item.id) }

            deleteBtn?.setOnClickListener { onDelete(item.id) }
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ITEM = 1
    }
}
