package com.mangalens.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mangalens.core.di.Dependencies
import com.mangalens.core.model.HistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    val history: StateFlow<List<HistoryEntry>> =
        Dependencies.historyStore.observeRecent(HISTORY_LIMIT)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { Dependencies.historyStore.clear() }
        }
    }

    companion object {
        private const val HISTORY_LIMIT = 50

        val Factory = viewModelFactory {
            initializer { MainViewModel() }
        }
    }
}
