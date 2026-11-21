package com.phlox.simpleserver.screens.auth

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.auth.ConfigBasedUserStore
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.auth.UserStore
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.database.DatabaseMigrator
import com.phlox.simpleserver.dialogs.DialogButton
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_must_be_enabled_for_users
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_not_enabled
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_create_user
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_user
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_migrate_users
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_update_guest_access
import com.phlox.simpleserver.shttps_desktop.generated.resources.guest_basic_auth_registration_warning
import com.phlox.simpleserver.shttps_desktop.generated.resources.guest_basic_auth_warning
import com.phlox.simpleserver.shttps_desktop.generated.resources.import_continue
import com.phlox.simpleserver.shttps_desktop.generated.resources.limited_support
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import com.phlox.simpleserver.shttps_desktop.generated.resources.user_registration_requirements
import com.phlox.simpleserver.shttps_desktop.generated.resources.user_roles_will_be_lost
import com.phlox.simpleserver.shttps_desktop.generated.resources.users_migrated_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.warning
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumSet

data class AuthDetailsState(
    val users: List<User> = emptyList(),
    val allowGuestAccess: Boolean = false,
    val storeUsersInDatabase: Boolean = false,
    val isDatabaseEnabled: Boolean = false,
    val authMode: SHTTPSConfig.AuthMode = SHTTPSConfig.AuthMode.NONE,
    val isAllowedUserRegistration: Boolean = false,
    val defaultRoleForNewUser: String = "",
    val newUserDirPattern: String = "",
    val availableRoles: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData()
)

class AuthDetailsViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthDetailsState())
    val uiState: StateFlow<AuthDetailsState> = _uiState.asStateFlow()

    private var userStore: UserStore = shttpsApp.provideUserStore()
    private val database: Database? = shttpsApp.database
    
    private var newUserDirPatternUpdateJob: Job? = null

    init {
        loadUsers()
        loadAvailableRoles()
        updateState()
    }

    private fun loadUsers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val users = mutableListOf<User>()
                val userCount = userStore.count()
                for (i in 0 until userCount) {
                    val user = userStore.get(i)
                    if (user != null) {
                        users.add(user)
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(users = users)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    private fun updateState() {
        val hasGuest = userStore.find(User.GUEST_IDENTITY) != null
        _uiState.value = _uiState.value.copy(
            allowGuestAccess = hasGuest,
            storeUsersInDatabase = config.isStoreUsersInDatabase,
            isDatabaseEnabled = config.isDatabaseEnabled,
            authMode = config.authMode,
            isAllowedUserRegistration = config.isAllowedUserRegistration,
            defaultRoleForNewUser = config.defaultRoleForNewUser,
            newUserDirPattern = config.getNewUserDirPattern()
        )
    }

    fun toggleGuestAccess(allowGuest: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (allowGuest) {
                    val guestUser = User(
                        User.GUEST_IDENTITY, 
                        "", 
                        null,
                        EnumSet.of(User.FileSystemRights.READ, User.FileSystemRights.LIST_CONTENTS),
                        EnumSet.noneOf(User.DBRights::class.java), 
                        null, System.currentTimeMillis(), null, null,
                        EnumSet.noneOf(User.SystemRights::class.java),
                        0
                    )
                    userStore.create(guestUser)
                } else {
                    userStore.delete(User.GUEST_IDENTITY)
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(allowGuestAccess = allowGuest)
                    loadUsers()
                    
                    // Show warning if guest access is enabled with basic auth
                    if (allowGuest && config.authMode == SHTTPSConfig.AuthMode.BASIC_AUTH) {
                        showMessageDialog(
                            getString(Res.string.limited_support),
                            getString(Res.string.guest_basic_auth_warning)
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_update_guest_access, e.message ?: ""))
                }
            }
        }
    }

    fun toggleStoreUsersInDatabase(storeInDatabase: Boolean) {
        if (storeInDatabase == config.isStoreUsersInDatabase) return
        
        if (storeInDatabase && !config.isDatabaseEnabled) {
            viewModelScope.launch {
                showMessageDialog(
                    getString(Res.string.database_not_enabled),
                    getString(Res.string.database_must_be_enabled_for_users)
                )
            }
            return
        }

        if (storeInDatabase) {
            startUsersMigration(true)
        } else {
            viewModelScope.launch {
                showMessageDialog(
                    title = getString(Res.string.warning),
                    message = getString(Res.string.user_roles_will_be_lost),
                    buttons = listOf(
                        DialogButton(getString(Res.string.cancel)) {  },
                        DialogButton(getString(Res.string.import_continue)) { startUsersMigration(false) }
                    ))
            }
        }
    }

    private fun startUsersMigration(toDatabase: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = userStore
                config.isStoreUsersInDatabase = toDatabase
                val dest = shttpsApp.provideUserStore(true)

                // Migrate users
                if (database != null && source is ConfigBasedUserStore) {
                    DatabaseMigrator.toggleUsersInDB(database, toDatabase)
                }
                
                dest.deleteAll()
                val userCount = source.count()
                for (i in 0 until userCount) {
                    val user = source.get(i)
                    if (user != null) {
                        dest.create(user)
                    }
                }
                source.deleteAll()
                
                if (database != null && source is com.phlox.simpleserver.auth.DBBasedUserStore) {
                    DatabaseMigrator.toggleUsersInDB(database, toDatabase)
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        storeUsersInDatabase = toDatabase
                    )
                    userStore = dest
                    loadUsers()
                    showMessageDialog(getString(Res.string.success), getString(Res.string.users_migrated_successfully))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_migrate_users, e.message ?: ""))
                }
            }
        }
    }

    fun createNewUser() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = userStore.createEmptyUserWithAvailableIdentity()
                withContext(Dispatchers.Main) {
                    loadUsers()
                    // TODO: Navigate to user details screen
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_create_user, e.message ?: ""))
                }
            }
        }
    }

    fun editUser(user: User) {
        // Navigation is handled by the screen
    }

    fun deleteUser(user: User) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userStore.delete(user.identity)
                withContext(Dispatchers.Main) {
                    loadUsers()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_delete_user, e.message ?: ""))
                }
            }
        }
    }

    fun updateAuthMode(mode: SHTTPSConfig.AuthMode) {
        config.authMode = mode
        _uiState.value = _uiState.value.copy(authMode = mode)
        
        // Show warning if guest access is enabled with basic auth
        if (mode == SHTTPSConfig.AuthMode.BASIC_AUTH && (_uiState.value.allowGuestAccess || _uiState.value.isAllowedUserRegistration)) {
            viewModelScope.launch {
                showMessageDialog(
                    getString(Res.string.limited_support),
                    getString(Res.string.guest_basic_auth_registration_warning)
                )
            }
        }
    }

    fun toggleUserRegistration(allowed: Boolean) {
        config.isAllowedUserRegistration = allowed
        _uiState.value = _uiState.value.copy(isAllowedUserRegistration = allowed)

        if (allowed && (config.authMode == SHTTPSConfig.AuthMode.BASIC_AUTH ||
                    config.defaultRoleForNewUser.isEmpty() ||
                    !config.isStoreUsersInDatabase)) {
            viewModelScope.launch {
                showMessageDialog(
                    getString(Res.string.limited_support),
                    getString(Res.string.user_registration_requirements)
                )
            }
        }
    }

    fun updateDefaultRoleForNewUser(role: String) {
        config.defaultRoleForNewUser = role
        _uiState.value = _uiState.value.copy(defaultRoleForNewUser = role)
    }

    fun updateNewUserDirPattern(pattern: String) {
        // Update UI state immediately
        _uiState.value = _uiState.value.copy(newUserDirPattern = pattern)
        
        // Cancel previous update job
        newUserDirPatternUpdateJob?.cancel()
        
        // Start debounced update job (1 second delay like in Android)
        newUserDirPatternUpdateJob = viewModelScope.launch {
            delay(1000) // 1 second debounce
            config.newUserDirPattern = pattern.trim()
        }
    }

    fun showMessageDialog(title: String, message: String, buttons: List<DialogButton>? = null) {
        _uiState.value = _uiState.value.copy(
            showMessageDialog = true,
            messageDialogData = MessageDialogData(
                title = title,
                message = message,
                onDismiss = {
                    _uiState.value = _uiState.value.copy(showMessageDialog = false)
                },
                buttons
            )
        )
    }

    fun refreshUsers() {
        loadUsers()
        loadAvailableRoles()
        updateState()
    }
}
