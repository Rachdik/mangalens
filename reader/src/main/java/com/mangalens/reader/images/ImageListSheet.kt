package com.mangalens.reader.images

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangalens.reader.R
import com.mangalens.reader.databinding.DialogImagesBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Panel debug untuk memverifikasi hasil deteksi dan unduhan gambar. */
class ImageListSheet(
    private val activity: AppCompatActivity,
    private val session: ImageScanSession
) {

    private var dialog: AlertDialog? = null
    private var collector: Job? = null

    fun show() {
        if (dialog?.isShowing == true) return

        val binding = DialogImagesBinding.inflate(activity.layoutInflater)
        val adapter = ImageRowAdapter()
        binding.imageRecycler.layoutManager = LinearLayoutManager(activity)
        binding.imageRecycler.adapter = adapter

        val sheet = AlertDialog.Builder(activity)
            .setTitle(R.string.detected_images)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                collector?.cancel()
                collector = null
            }
            .create()

        dialog = sheet
        sheet.show()

        collector = activity.lifecycleScope.launch {
            combine(session.images, session.states) { images, states ->
                images.map { ImageRow(it, states[it.key]) }
            }.collect { rows ->
                adapter.submitList(rows)
                binding.emptyImages.visibility =
                    if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
