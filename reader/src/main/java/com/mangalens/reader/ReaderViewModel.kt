package com.mangalens.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ReaderViewModel(initialUrl: String) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState(url = initialUrl))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    fun onPageStarted(url: String) {
        _state.update {
            it.copy(
                url = url.ifBlank { it.url },
                isLoading = true,
                progress = 0,
                errorMessage = null
            )
        }
    }

    fun onProgress(progress: Int) {
        _state.update { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    fun onPageFinished(url: String, title: String) {
        _state.update {
            it.copy(
                url = url.ifBlank { it.url },
                title = title.ifBlank { it.title },
                isLoading = false,
                progress = 100
            )
        }
    }

    fun onError(message: String) {
        _state.update { it.copy(isLoading = false, errorMessage = message) }
    }

    companion object {
        fun factory(initialUrl: String) = viewModelFactory {
            initializer { ReaderViewModel(initialUrl) }
        }
    }
}
