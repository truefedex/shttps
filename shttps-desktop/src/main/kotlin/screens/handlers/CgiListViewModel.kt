package com.phlox.simpleserver.screens.handlers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.exec.CgiType
import com.phlox.simpleserver.profiles.ConfigProfiles
import com.phlox.simpleserver.profiles.ServedFolderGuard
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CgiListState(
    val cgiFolder: String? = null,
    val cgiPathPrefix: String? = null,
    val cgiTypes: List<CgiType> = emptyList(),
    val allowEditing: Boolean = false,
)

class CgiListViewModel(
    private val config: AppConfig,
    //null when an explicit config path was given on the command line, in which case there are no
    //profile directories to protect
    private val profiles: ConfigProfiles? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CgiListState())
    val uiState: StateFlow<CgiListState> = _uiState.asStateFlow()

    private var urlPrefixSaveJob: Job? = null

    init {
        loadCgiSettings()
    }

    private fun loadCgiSettings() {
        val cgiFolder = config.getCGIFolder()
        val cgiPathPrefix = config.getCGIPathPrefix()
        val cgiTypes = config.getCGITypes() ?: emptyList()
        val allowEditing = config.allowEditing
        
        _uiState.value = CgiListState(
            cgiFolder = cgiFolder,
            cgiPathPrefix = cgiPathPrefix,
            cgiTypes = cgiTypes,
            allowEditing = allowEditing
        )
    }

    /**
     * @return false when the folder would serve the app's own data - see [ServedFolderGuard]. The
     * screen reports that; a CGI folder is served like the document root, so the same rule holds.
     */
    fun updateCgiFolder(folder: String?): Boolean {
        if (folder != null && profiles?.isFolderAllowedToServe(File(folder)) == false) {
            return false
        }
        config.setCGIFolder(folder)
        _uiState.value = _uiState.value.copy(cgiFolder = folder)
        return true
    }

    fun updateCgiPathPrefix(prefix: String) {
        // Cancel previous save job
        urlPrefixSaveJob?.cancel()
        
        // Update UI state immediately
        _uiState.value = _uiState.value.copy(cgiPathPrefix = prefix)
        
        // Debounce the save (1 second delay like in Android)
        urlPrefixSaveJob = viewModelScope.launch {
            delay(1000)
            var newPrefix = prefix.trim()
            if (newPrefix.isNotEmpty() && !newPrefix.startsWith("/")) {
                newPrefix = "/$newPrefix"
            }
            config.setCGIPathPrefix(if (newPrefix.isEmpty()) null else newPrefix)
        }
    }

    fun refreshCgiTypes() {
        val cgiTypes = config.getCGITypes() ?: emptyList()
        _uiState.value = _uiState.value.copy(cgiTypes = cgiTypes)
    }

    fun saveCgiTypes(cgiTypes: List<CgiType>) {
        config.setCGITypes(cgiTypes)
        _uiState.value = _uiState.value.copy(cgiTypes = cgiTypes)
    }
}
