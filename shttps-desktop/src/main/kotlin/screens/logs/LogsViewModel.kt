package com.phlox.simpleserver.screens.logs

import androidx.lifecycle.ViewModel
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.utils.ServerLogsCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogsState(
    val logs: List<ServerLogsCollector.LogEntry> = emptyList(),
    val filteredLogs: List<ServerLogsCollector.LogEntry> = emptyList(),
    val filter: String = "",
    val selectedEntry: ServerLogsCollector.LogEntry? = null,
    val isPaused: Boolean = false,
)

class LogsViewModel : ViewModel(), ServerLogsCollector.Listener {
    private val _uiState = MutableStateFlow(LogsState())
    val uiState: StateFlow<LogsState> = _uiState.asStateFlow()

    private var shttpsApp: SHTTPSApp? = null
    private lateinit var loggingMiddleware: ServerLogsCollector

    fun initialize(shttpsApp: SHTTPSApp) {
        this.shttpsApp = shttpsApp
        this.loggingMiddleware = shttpsApp.logsCollector

        synchronized(loggingMiddleware.logs) {
            val currentLogs = loggingMiddleware.logs.toList()
            val filtered = filterLogs(currentLogs, _uiState.value.filter)
            _uiState.value = _uiState.value.copy(
                logs = currentLogs,
                filteredLogs = filtered,
                selectedEntry = _uiState.value.selectedEntry?.takeIf { filtered.contains(it) },
            )
        }

        loggingMiddleware.listener = this
    }

    fun selectEntry(entry: ServerLogsCollector.LogEntry?) {
        _uiState.value = _uiState.value.copy(selectedEntry = entry)
    }

    fun setFilter(filter: String) {
        val filteredLogs = filterLogs(_uiState.value.logs, filter)
        _uiState.value = _uiState.value.copy(
            filter = filter,
            filteredLogs = filteredLogs,
            selectedEntry = _uiState.value.selectedEntry?.takeIf { filteredLogs.contains(it) },
        )
    }

    private fun filterLogs(logs: List<ServerLogsCollector.LogEntry>, filter: String): List<ServerLogsCollector.LogEntry> {
        if (filter.isEmpty()) {
            return logs
        }

        val lowerFilter = filter.lowercase()
        return logs.filter { logEntry ->
            val method = logEntry.method?.lowercase().orEmpty()
            val path = logEntry.path?.lowercase().orEmpty()
            val host = logEntry.hostAddress?.lowercase().orEmpty()
            val phraseMatch = logEntry.responsePhrase?.lowercase()?.contains(lowerFilter) == true
            val codeMatch = logEntry.responseCode.toString().contains(lowerFilter)
            method.contains(lowerFilter) ||
                path.contains(lowerFilter) ||
                host.contains(lowerFilter) ||
                phraseMatch ||
                codeMatch
        }
    }

    override fun onLogEntry(entry: ServerLogsCollector.LogEntry) {
        if (_uiState.value.isPaused) {
            return
        }
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(0, entry)

        val filteredLogs = if (_uiState.value.filter.isEmpty()) {
            currentLogs
        } else {
            filterLogs(currentLogs, _uiState.value.filter)
        }

        _uiState.value = _uiState.value.copy(
            logs = currentLogs,
            filteredLogs = filteredLogs,
            selectedEntry = _uiState.value.selectedEntry?.takeIf { filteredLogs.contains(it) },
        )
    }

    fun togglePause() {
        val current = _uiState.value
        val newPaused = !current.isPaused
        if (!newPaused && ::loggingMiddleware.isInitialized) {
            synchronized(loggingMiddleware.logs) {
                val currentLogs = loggingMiddleware.logs.toList()
                val filtered = filterLogs(currentLogs, current.filter)
                _uiState.value = current.copy(
                    logs = currentLogs,
                    filteredLogs = filtered,
                    selectedEntry = current.selectedEntry?.takeIf { filtered.contains(it) },
                    isPaused = false,
                )
            }
        } else {
            _uiState.value = current.copy(isPaused = newPaused)
        }
    }

    fun clearLogs() {
        if (!::loggingMiddleware.isInitialized) {
            return
        }
        loggingMiddleware.clear()
        _uiState.value = _uiState.value.copy(
            logs = emptyList(),
            filteredLogs = emptyList(),
            selectedEntry = null,
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (::loggingMiddleware.isInitialized) {
            loggingMiddleware.listener = null
        }
    }
}
