package com.phlox.simpleserver.screens.handlers

import androidx.lifecycle.ViewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.exec.CgiType
import java.util.ArrayList

enum class CgiExtensionError { EMPTY, DUPLICATE }

data class CgiTypeDetailsState(
    val extension: String = "",
    val mode: CgiType.Mode = CgiType.Mode.CGI,
    val executeWith: String? = null,
    val executionTimeout: String = "",
    val editingIndex: Int = -1,
    val originalExtension: String? = null,
    val extensionError: CgiExtensionError? = null,
    val executionTimeoutError: Boolean = false,
)

class CgiTypeDetailsViewModel(
    private val config: AppConfig,
    initialIndex: Int?,
    initialCgiType: CgiType?
) : ViewModel() {
    
    private val _uiState = androidx.compose.runtime.mutableStateOf(
        if (initialIndex != null && initialCgiType != null) {
            CgiTypeDetailsState(
                extension = initialCgiType.extension,
                mode = initialCgiType.mode,
                executeWith = initialCgiType.executeWith,
                executionTimeout = initialCgiType.executionTimeout?.toString() ?: "",
                editingIndex = initialIndex,
                originalExtension = initialCgiType.extension
            )
        } else {
            CgiTypeDetailsState()
        }
    )
    
    val uiState: androidx.compose.runtime.State<CgiTypeDetailsState> = _uiState

    fun updateExtension(extension: String) {
        _uiState.value = _uiState.value.copy(
            extension = extension,
            extensionError = null
        )
    }

    fun updateMode(mode: CgiType.Mode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun updateExecuteWith(executeWith: String) {
        _uiState.value = _uiState.value.copy(
            executeWith = if (executeWith.trim().isEmpty()) null else executeWith.trim()
        )
    }

    fun updateExecutionTimeout(executionTimeout: String) {
        _uiState.value = _uiState.value.copy(
            executionTimeout = executionTimeout,
            executionTimeoutError = false
        )
    }

    fun saveCgiType(): Boolean {
        var extension = _uiState.value.extension.trim()
        
        if (extension.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                extensionError = CgiExtensionError.EMPTY
            )
            return false
        }

        // Remove leading dot if present
        if (extension.startsWith(".")) {
            extension = extension.substring(1)
        }

        val cgiTypes = config.getCGITypes()?.toMutableList() ?: ArrayList()
        val editingIndex = _uiState.value.editingIndex

        // Check for duplicate extension (excluding the one we're editing)
        for (i in cgiTypes.indices) {
            if (i != editingIndex && cgiTypes[i].extension == extension) {
                _uiState.value = _uiState.value.copy(
                    extensionError = CgiExtensionError.DUPLICATE
                )
                return false
            }
        }

        val timeoutStr = _uiState.value.executionTimeout.trim()
        val executionTimeout: Int? = if (timeoutStr.isEmpty()) {
            null
        } else {
            val parsed = timeoutStr.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                _uiState.value = _uiState.value.copy(
                    executionTimeoutError = true
                )
                return false
            }
            parsed
        }

        val newCgiType = CgiType(
            extension,
            _uiState.value.mode,
            _uiState.value.executeWith,
            executionTimeout
        )

        if (editingIndex >= 0 && editingIndex < cgiTypes.size) {
            // Update existing
            cgiTypes[editingIndex] = newCgiType
        } else {
            // Add new
            cgiTypes.add(newCgiType)
        }

        config.setCGITypes(cgiTypes)
        return true
    }

    fun deleteCgiType(): Boolean {
        val editingIndex = _uiState.value.editingIndex
        if (editingIndex < 0) {
            return false // Can't delete new types
        }

        val cgiTypes = config.getCGITypes()?.toMutableList() ?: return false
        if (editingIndex < cgiTypes.size) {
            cgiTypes.removeAt(editingIndex)
            config.setCGITypes(cgiTypes)
            return true
        }
        return false
    }

    fun canDelete(): Boolean {
        return _uiState.value.editingIndex >= 0
    }
}
