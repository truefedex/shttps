package com.phlox.simpleserver.screens.auth

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.auth.UserStore
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.database.model.TableData
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_user
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import com.phlox.simpleserver.shttps_desktop.generated.resources.user_deleted_successfully
import org.jetbrains.compose.resources.getString
import com.phlox.simpleserver.utils.Utils
import com.phlox.simpleserver.utils.DocumentFileUtils
import com.phlox.server.utils.docfile.DocumentFile
import com.phlox.server.utils.docfile.RawDocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet

enum class UsernameFieldError { EMPTY, IN_USE }

data class UserDetailsState(
    val user: User? = null,
    val username: String = "",
    val password: String = "",
    val usernameError: UsernameFieldError? = null,
    val isGuest: Boolean = false,
    val fileSystemRights: Set<User.FileSystemRights> = emptySet(),
    val dbRights: Set<User.DBRights> = emptySet(),
    val systemRights: Set<User.SystemRights> = emptySet(),
    val channelRights: Set<User.ChannelRights> = emptySet(),
    val availableRoles: List<String> = emptyList(),
    val selectedRole: String? = null,
    val customRootFolder: String? = null,
    val storageSizeLimit: String = "",
    val useGigabytesUnits: Boolean = false,
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData(),
    val usedStorage: Long = 0,
    val isRecalculatingStorage: Boolean = false,
    val storageProgress: Int? = null,
    val usedStorageDisplayText: String = ""
)

class UserDetailsViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp,
    private val user: User
) : ViewModel() {
    private val _uiState = MutableStateFlow(UserDetailsState())
    val uiState: StateFlow<UserDetailsState> = _uiState.asStateFlow()

    private val userStore: UserStore = shttpsApp.provideUserStore()
    private val database: Database? = shttpsApp.database

    init {
        loadUserData()
        loadAvailableRoles()
        updateUsedStorage(false)
    }

    private fun loadUserData() {
        val storageLimitText = if (user.storageLimit != null) {
            val limit = user.storageLimit!!
            val useGb = _uiState.value.useGigabytesUnits
            (if (useGb) (limit / (1024 * 1024 * 1024)) else (limit / (1024 * 1024))).toString()
        } else {
            ""
        }
        
        _uiState.value = _uiState.value.copy(
            user = user,
            username = user.identity,
            password = "", // Don't show password hash
            isGuest = user.identity == User.GUEST_IDENTITY,
            fileSystemRights = user.fsRights ?: emptySet(),
            dbRights = user.dbRights ?: emptySet(),
            systemRights = user.systemRights ?: emptySet(),
            channelRights = user.channelRights ?: emptySet(),
            selectedRole = user.role,
            customRootFolder = user.rootDir,
            storageSizeLimit = storageLimitText
        )
    }

    private fun loadAvailableRoles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val roles = mutableListOf<String>()
                
                if (database != null && config.isStoreUsersInDatabase) {
                    try {
                        val tableData = database.getTableDataSecure(UserRole.ROLES_TABLE_NAME)
                        tableData.use { data ->
                            while (data.next()) {
                                val role = UserRole.deserialize(data.currentRowToJsonObject())
                                roles.add(role.name)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(availableRoles = roles)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateUsername(newUsername: String) {
        _uiState.value = _uiState.value.copy(
            username = newUsername,
            usernameError = null
        )
        
        // Debounced save
        viewModelScope.launch(Dispatchers.IO) {
            delay(1000) // 1 second delay
            if (_uiState.value.username == newUsername) {
                saveUsername(newUsername)
            }
        }
    }

    private suspend fun saveUsername(newUsername: String) {
        val trimmedUsername = newUsername.trim()
        if (trimmedUsername.isEmpty()) {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    usernameError = UsernameFieldError.EMPTY
                )
            }
            return
        }
        
        if (trimmedUsername != user.identity) {
            val success = userStore.rename(user, trimmedUsername)
            withContext(Dispatchers.Main) {
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        usernameError = null,
                        user = user.clone().apply { identity = trimmedUsername }
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        usernameError = UsernameFieldError.IN_USE
                    )
                }
            }
        }
    }

    fun updatePassword(newPassword: String) {
        _uiState.value = _uiState.value.copy(password = newPassword)
        
        // Debounced save
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000) // 1 second delay
            if (_uiState.value.password == newPassword) {
                savePassword(newPassword)
            }
        }
    }

    private fun savePassword(newPassword: String) {
        val trimmedPassword = newPassword.trim()
        val hash = Utils.sha256(Utils.hashFNV1a32(trimmedPassword))
        
        if (user.passwordHash != hash) {
            user.passwordHash = hash
            userStore.update(user)
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
        user.fsRights = fsRightsEnumSet
        userStore.update(user)
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
        user.dbRights = dbRightsEnumSet
        userStore.update(user)
    }

    fun updateSystemRight(right: User.SystemRights, enabled: Boolean) {
        val currentRights = _uiState.value.systemRights.toMutableSet()
        applySystemRightChange(currentRights, right, enabled)

        _uiState.value = _uiState.value.copy(systemRights = currentRights)
        val systemRightsEnumSet = EnumSet.noneOf(User.SystemRights::class.java)
        systemRightsEnumSet.addAll(currentRights)
        user.systemRights = systemRightsEnumSet
        userStore.update(user)
    }

    /**
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
        user.channelRights = channelRightsEnumSet
        userStore.update(user)
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
            user.storageLimit = null
            userStore.update(user)
            return
        }
        
        try {
            val limitValue = trimmed.toLong()
            user.storageLimit = if (useGigabytes) {
                limitValue * (1024 * 1024 * 1024)
            } else {
                limitValue * (1024 * 1024)
            }
            userStore.update(user)
        } catch (e: NumberFormatException) {
            // Invalid number, ignore
        }
    }

    fun toggleStorageSizeUnits() {
        val newUseGb = !_uiState.value.useGigabytesUnits
        val currentLimit = user.storageLimit
        
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

    fun updateSelectedRole(role: String?) {
        _uiState.value = _uiState.value.copy(selectedRole = role)
        user.role = role
        userStore.update(user)
        updateRightsSectionsEnabled()
    }
    
    private fun updateRightsSectionsEnabled() {
        // This is handled in the UI by checking selectedRole
        // The UI components will be enabled/disabled based on whether a role is selected
    }

    fun updateCustomRootFolder(folder: String?) {
        _uiState.value = _uiState.value.copy(customRootFolder = folder)
        user.rootDir = folder
        userStore.update(user)
    }

    fun deleteUser() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userStore.delete(user.identity)
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.success), getString(Res.string.user_deleted_successfully))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_delete_user, e.message ?: ""))
                }
            }
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

    fun refreshRoles() {
        loadAvailableRoles()
    }

    fun updateUsedStorage(forceRecalculate: Boolean) {
        _uiState.value = _uiState.value.copy(
            usedStorageDisplayText = "",
            isRecalculatingStorage = true,
            storageProgress = null
        )

        if (forceRecalculate) {
            viewModelScope.launch(Dispatchers.IO) {
                val checkStartTime = System.currentTimeMillis()
                val userRootDir = getUserRootDir()
                
                if (userRootDir != null) {
                    val calculatedSize = userRootDir.calculateDirectorySize()
                    user.usedStorage = calculatedSize
                    userStore.update(user.identity, User.FIELD_USED_STORAGE, calculatedSize)
                    
                    val checkEndTime = System.currentTimeMillis()
                    val elapsed = checkEndTime - checkStartTime
                    if (elapsed < 1000) {
                        delay(1000 - elapsed)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    renderUsedStorageInfo()
                }
            }
        } else {
            renderUsedStorageInfo()
        }
    }

    private fun renderUsedStorageInfo() {
        val currentUser = user
        val formattedSize = Utils.formatFileSize(currentUser.usedStorage)
        
        val storageLimit = userStore.provideUserRightsEvaluator().getStorageLimit(currentUser)
            ?: getUserRootDir()?.getStorageSize()
        
        val progress = if (storageLimit != null && storageLimit > 0) {
            (currentUser.usedStorage * 100 / storageLimit).coerceAtMost(100).toInt()
        } else {
            null
        }
        
        _uiState.value = _uiState.value.copy(
            usedStorage = currentUser.usedStorage,
            usedStorageDisplayText = formattedSize,
            isRecalculatingStorage = false,
            storageProgress = progress
        )
    }

    private fun getUserRootDir(): DocumentFile? {
        val rootDir = config.getRootDir()
        return user.rootDir?.let {
            DocumentFileUtils.checkOrCreateUserDir(rootDir, it)
        } ?: rootDir
    }
}
