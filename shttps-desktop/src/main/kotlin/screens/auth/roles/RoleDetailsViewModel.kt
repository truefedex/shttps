package com.phlox.simpleserver.screens.auth.roles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.screens.auth.applySystemRightChange
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_not_available
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_role
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_save_role
import com.phlox.simpleserver.shttps_desktop.generated.resources.info
import com.phlox.simpleserver.shttps_desktop.generated.resources.new_role_not_saved_yet
import com.phlox.simpleserver.shttps_desktop.generated.resources.role_created_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.role_deleted_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.role_updated_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumSet

data class RoleDetailsState(
    val role: UserRole,
    val roleName: String = "",
    val roleNameError: Boolean = false,
    val fileSystemRights: Set<User.FileSystemRights> = emptySet(),
    val dbRights: Set<User.DBRights> = emptySet(),
    val systemRights: Set<User.SystemRights> = emptySet(),
    val channelRights: Set<User.ChannelRights> = emptySet(),
    val storageSizeLimit: String = "",
    val useGigabytesUnits: Boolean = false,
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData()
)

class RoleDetailsViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp,
    private val role: UserRole
) : ViewModel() {
    private val _uiState = MutableStateFlow(RoleDetailsState(role = role))
    val uiState: StateFlow<RoleDetailsState> = _uiState.asStateFlow()

    private val database: Database? = shttpsApp.database

    init {
        loadRoleData()
    }

    private fun loadRoleData() {
        val storageLimitText = if (role.storageLimit != null) {
            val limit = role.storageLimit!!
            val useGb = _uiState.value.useGigabytesUnits
            (if (useGb) (limit / (1024 * 1024 * 1024)) else (limit / (1024 * 1024))).toString()
        } else {
            ""
        }
        
        _uiState.value = _uiState.value.copy(
            roleName = role.name,
            fileSystemRights = role.fsRights ?: emptySet(),
            dbRights = role.dbRights ?: emptySet(),
            systemRights = role.systemRights ?: emptySet(),
            channelRights = role.channelRights ?: emptySet(),
            storageSizeLimit = storageLimitText
        )
    }

    fun updateRoleName(newName: String) {
        // Validate role name: only letters, numbers, spaces, and underscores
        val validPattern = Regex("^[a-zA-Z0-9_ ]*$")
        
        if (!validPattern.matches(newName)) {
            // Filter out invalid characters
            val filtered = newName.filter { it.isLetterOrDigit() || it == '_' || it == ' ' }
            _uiState.value = _uiState.value.copy(
                roleName = filtered,
                roleNameError = false
            )
        } else {
            _uiState.value = _uiState.value.copy(
                roleName = newName,
                roleNameError = false
            )
        }
    }

    fun updateFileSystemRight(right: User.FileSystemRights, enabled: Boolean) {
        val currentRights = _uiState.value.fileSystemRights.toMutableSet()
        if (enabled) {
            currentRights.add(right)
        } else {
            currentRights.remove(right)
        }
        
        _uiState.value = _uiState.value.copy(fileSystemRights = currentRights)
        val fsRightsEnumSet = EnumSet.noneOf(User.FileSystemRights::class.java)
        fsRightsEnumSet.addAll(currentRights)
        role.fsRights = fsRightsEnumSet
    }

    fun updateDBRight(right: User.DBRights, enabled: Boolean) {
        val currentRights = _uiState.value.dbRights.toMutableSet()
        if (enabled) {
            currentRights.add(right)
        } else {
            currentRights.remove(right)
        }
        
        _uiState.value = _uiState.value.copy(dbRights = currentRights)
        val dbRightsEnumSet = EnumSet.noneOf(User.DBRights::class.java)
        dbRightsEnumSet.addAll(currentRights)
        role.dbRights = dbRightsEnumSet
    }

    fun updateSystemRight(right: User.SystemRights, enabled: Boolean) {
        val currentRights = _uiState.value.systemRights.toMutableSet()
        applySystemRightChange(currentRights, right, enabled)

        _uiState.value = _uiState.value.copy(systemRights = currentRights)
        val systemRightsEnumSet = EnumSet.noneOf(User.SystemRights::class.java)
        systemRightsEnumSet.addAll(currentRights)
        role.systemRights = systemRightsEnumSet
    }

    /**
     * Mutates the role only - like the other rights here, this is written by `saveRole`, not on
     * every toggle the way the user screen does it.
     *
     * CONNECT and POST are deliberately not coupled: a one-shot publish over
     * `POST /api/channels/{id}/message` needs POST alone and never opens a socket.
     */
    fun updateChannelRight(right: User.ChannelRights, enabled: Boolean) {
        val currentRights = _uiState.value.channelRights.toMutableSet()
        if (enabled) {
            currentRights.add(right)
        } else {
            currentRights.remove(right)
        }

        _uiState.value = _uiState.value.copy(channelRights = currentRights)
        val channelRightsEnumSet = EnumSet.noneOf(User.ChannelRights::class.java)
        channelRightsEnumSet.addAll(currentRights)
        role.channelRights = channelRightsEnumSet
    }

    fun updateStorageSizeLimit(newLimit: String) {
        val currentUseGb = _uiState.value.useGigabytesUnits
        _uiState.value = _uiState.value.copy(storageSizeLimit = newLimit)
        
        // Debounced save
        viewModelScope.launch(Dispatchers.IO) {
            delay(1000) // 1 second delay
            if (_uiState.value.storageSizeLimit == newLimit && _uiState.value.useGigabytesUnits == currentUseGb) {
                saveStorageSizeLimit(newLimit, currentUseGb)
            }
        }
    }

    private fun saveStorageSizeLimit(limitText: String, useGigabytes: Boolean) {
        val trimmed = limitText.trim()
        if (trimmed.isEmpty()) {
            role.storageLimit = null
            return
        }
        
        try {
            val limitValue = trimmed.toLong()
            role.storageLimit = if (useGigabytes) {
                limitValue * (1024 * 1024 * 1024)
            } else {
                limitValue * (1024 * 1024)
            }
        } catch (e: NumberFormatException) {
            // Invalid number, ignore
        }
    }

    fun toggleStorageSizeUnits() {
        val newUseGb = !_uiState.value.useGigabytesUnits
        val currentLimit = role.storageLimit
        
        val displayText = if (currentLimit != null) {
            val limit = currentLimit
            (if (newUseGb) (limit / (1024 * 1024 * 1024)) else (limit / (1024 * 1024))).toString()
        } else {
            ""
        }
        
        _uiState.value = _uiState.value.copy(
            useGigabytesUnits = newUseGb,
            storageSizeLimit = displayText
        )
    }

    fun saveRole() {
        val trimmedName = _uiState.value.roleName.trim()
        if (trimmedName.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                roleNameError = true
            )
            return
        }

        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                role.name = trimmedName
                
                if (isNewRole()) {
                    // Insert new role
                    database.insert(UserRole.ROLES_TABLE_NAME, role.serialize())
                    withContext(Dispatchers.Main) {
                        showMessageDialog(getString(Res.string.success), getString(Res.string.role_created_successfully))
                    }
                } else {
                    // Update existing role
                    database.update(
                        UserRole.ROLES_TABLE_NAME, 
                        role.serialize(), 
                        arrayOf("name="), 
                        arrayOf(role.name)
                    )
                    withContext(Dispatchers.Main) {
                        showMessageDialog(getString(Res.string.success), getString(Res.string.role_updated_successfully))
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_save_role, e.message ?: ""))
                }
            }
        }
    }

    fun deleteRole() {
        if (isNewRole()) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.info), getString(Res.string.new_role_not_saved_yet))
            }
            return
        }

        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.delete(UserRole.ROLES_TABLE_NAME, arrayOf("name = ?"), arrayOf(role.name))
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.success), getString(Res.string.role_deleted_successfully))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_delete_role, e.message ?: ""))
                }
            }
        }
    }

    private fun isNewRole(): Boolean {
        // Check if this is a new role by trying to find it in the database
        return try {
            if (database == null) return true
            val result = database.getTableDataSecure(
                UserRole.ROLES_TABLE_NAME, 
                null, null, null,
                arrayOf("name="), 
                arrayOf(role.name), 
                null, false, false, null
            )
            result.use { data -> !data.next() }
        } catch (e: Exception) {
            e.printStackTrace()
            true // Assume new if we can't check
        }
    }

    fun showMessageDialog(title: String, message: String) {
        _uiState.value = _uiState.value.copy(
            showMessageDialog = true,
            messageDialogData = MessageDialogData(
                title = title,
                message = message,
                onDismiss = {
                    _uiState.value = _uiState.value.copy(showMessageDialog = false)
                }
            )
        )
    }
}
