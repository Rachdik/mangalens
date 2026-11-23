package com.mangalens.app

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mangalens.app.databinding.ItemHistoryBinding
import com.mangalens.core.model.HistoryEntry
import com.mangalens.core.util.UrlNormalizer
import java.util.Date

class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryEntry, HistoryAdapter.HistoryViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(
        private val binding: ItemHistoryBinding,
        private val onClick: (HistoryEntry) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HistoryEntry) {
            binding.historyTitle.text = entry.title
            binding.historyUrl.text = UrlNormalizer.hostOf(entry.url)
            binding.historyTime.text = DateFormat.format("dd MMM yyyy HH:mm", Date(entry.visitedAt))
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem.url == newItem.url

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem == newItem
    }
}
