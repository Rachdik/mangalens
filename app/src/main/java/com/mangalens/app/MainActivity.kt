package com.mangalens.app

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangalens.app.databinding.ActivityMainBinding
import com.mangalens.core.model.HistoryEntry
import com.mangalens.core.util.UrlNormalizer
import com.mangalens.reader.ReaderActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyAdapter: HistoryAdapter

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyAdapter = HistoryAdapter { entry -> openReader(entry.url) }
        binding.historyList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        binding.openButton.setOnClickListener {
            openReader(binding.urlInput.text?.toString().orEmpty())
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openReader(binding.urlInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.clearHistoryButton.setOnClickListener {
            viewModel.clearHistory()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.history.collect(::renderHistory)
            }
        }
    }

    private fun renderHistory(items: List<HistoryEntry>) {
        historyAdapter.submitList(items)
        binding.emptyHistory.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.clearHistoryButton.isEnabled = items.isNotEmpty()
    }

    private fun openReader(rawUrl: String) {
        val normalized = UrlNormalizer.normalize(rawUrl)
        if (normalized == null) {
            binding.urlError.text = getString(R.string.invalid_url)
            binding.urlError.visibility = View.VISIBLE
            return
        }
        binding.urlError.visibility = View.GONE
        startActivity(ReaderActivity.intent(this, normalized))
    }
}
