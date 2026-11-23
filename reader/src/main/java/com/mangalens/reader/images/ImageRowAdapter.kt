package com.mangalens.reader.images

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mangalens.reader.R
import com.mangalens.reader.databinding.ItemImageRowBinding
import java.util.Locale

data class ImageRow(
    val image: DetectedImage,
    val state: ImageDownloadState?
)

class ImageRowAdapter : ListAdapter<ImageRow, ImageRowAdapter.RowViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemImageRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RowViewHolder(
        private val binding: ItemImageRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ImageRow) {
            val context = binding.root.context
            val fileName = row.image.url.substringAfterLast('/').substringBefore('?').ifBlank { row.image.url }
            binding.rowTitle.text = fileName
            binding.rowMeta.text = context.getString(
                R.string.image_row_meta,
                row.image.width,
                row.image.height,
                row.image.docOrder
            )

            when (val state = row.state) {
                is ImageDownloadState.Ready -> {
                    binding.rowStatus.text = context.getString(
                        R.string.image_status_ready,
                        formatSize(state.bytes)
                    )
                    binding.thumb.load(state.file)
                }

                is ImageDownloadState.Failed -> {
                    binding.rowStatus.text = context.getString(R.string.image_status_failed, state.reason)
                    binding.thumb.setImageResource(android.R.color.transparent)
                }

                ImageDownloadState.Downloading -> {
                    binding.rowStatus.text = context.getString(R.string.image_status_downloading)
                    binding.thumb.setImageResource(android.R.color.transparent)
                }

                else -> {
                    binding.rowStatus.text = context.getString(R.string.image_status_pending)
                    binding.thumb.setImageResource(android.R.color.transparent)
                }
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private object Diff : DiffUtil.ItemCallback<ImageRow>() {
        override fun areItemsTheSame(oldItem: ImageRow, newItem: ImageRow): Boolean =
            oldItem.image.key == newItem.image.key

        override fun areContentsTheSame(oldItem: ImageRow, newItem: ImageRow): Boolean =
            oldItem == newItem
    }
}
