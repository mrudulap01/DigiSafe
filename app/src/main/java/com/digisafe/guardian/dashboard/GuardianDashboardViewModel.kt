package com.digisafe.guardian.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * DashboardUiState: Represents the current state of the Guardian Dashboard UI.
 */
sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(val events: List<DashboardEvent>) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
    object Empty : DashboardUiState()
}

/**
 * GuardianDashboardViewModel: Production-grade State management for the dashboard.
 * Uses StateFlow for cold-stream to hot-state conversion.
 */
class GuardianDashboardViewModel(private val repository: GuardianRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
    }

    private fun loadDashboard() {
        viewModelScope.launch {
            repository.getDashboardEvents()
                .catch { e ->
                    _uiState.value = DashboardUiState.Error(e.message ?: "Unknown Error")
                }
                .collect { events ->
                    _uiState.value = if (events.isEmpty()) {
                        DashboardUiState.Empty
                    } else {
                        DashboardUiState.Success(events)
                    }
                }
        }
    }

    fun refresh() {
        _uiState.value = DashboardUiState.Loading
        loadDashboard()
    }
}
