package com.phlox.simpleserver.screens.auth.roles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.database.model.TableData
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_not_available
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_role
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_load_roles
import com.phlox.simpleserver.shttps_desktop.generated.resources.role_deleted_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumSet

data class RolesListState(
    val roles: List<UserRole> = emptyList(),
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData()
)

class RolesListViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp
) : ViewModel() {
    private val _uiState = MutableStateFlow(RolesListState())
    val uiState: StateFlow<RolesListState> = _uiState.asStateFlow()

    private val database: Database? = shttpsApp.database

    init {
        loadRoles()
    }

    private fun loadRoles() {
        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val roles = mutableListOf<UserRole>()
                val tableData = database.getTableDataSecure(UserRole.ROLES_TABLE_NAME)
                tableData.use { data ->
                    while (data.next()) {
                        val role = UserRole.deserialize(data.currentRowToJsonObject())
                        roles.add(role)
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        roles = roles,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_load_roles, e.message ?: ""))
                }
            }
        }
    }

    fun deleteRole(role: UserRole) {
        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.delete(UserRole.ROLES_TABLE_NAME, arrayOf("name="), arrayOf(role.name))
                withContext(Dispatchers.Main) {
                    loadRoles()
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

    fun refreshRoles() {
        loadRoles()
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
