package com.phlox.simpleserver.screens.auth.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.DBAccessRule
import com.phlox.simpleserver.auth.FSAccessRule
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.database.model.TableData
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_not_available
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_rule
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_load_rules
import com.phlox.simpleserver.shttps_desktop.generated.resources.rule_deleted_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RulesListState(
    val rules: List<Any> = emptyList(),
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData()
)

class RulesListViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp,
    private val ruleType: String,
    private val roleName: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(RulesListState())
    val uiState: StateFlow<RulesListState> = _uiState.asStateFlow()

    private val database: Database? = shttpsApp.database

    init {
        loadRules()
    }

    private fun loadRules() {
        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rules = mutableListOf<Any>()
                val tableName = if (ruleType == "db") {
                    DBAccessRule.DB_ACCESS_RULES_TABLE_NAME
                } else {
                    FSAccessRule.FS_ACCESS_RULES_TABLE_NAME
                }

                val tableData = database.getTableDataSecure(
                    tableName,
                    null, null, null,
                    arrayOf("role_name="),
                    arrayOf(roleName),
                    null, false, false, null
                )
                tableData.use { data ->
                    while (data.next()) {
                        val rule = if (ruleType == "db") {
                            DBAccessRule.deserialize(data.currentRowToJsonObject())
                        } else {
                            FSAccessRule.deserialize(data.currentRowToJsonObject())
                        }
                        if (rule != null) {
                            rules.add(rule)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        rules = rules,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_load_rules, e.message ?: ""))
                }
            }
        }
    }

    fun deleteRule(rule: Any) {
        if (database == null) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.database_not_available))
            }
            return
        }

        val subject = if (ruleType == "db") {
            (rule as DBAccessRule).subject
        } else {
            (rule as FSAccessRule).subject
        }
        val operation = if (ruleType == "db") {
            (rule as DBAccessRule).operation
        } else {
            (rule as FSAccessRule).operation
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tableName = if (ruleType == "db") {
                    DBAccessRule.DB_ACCESS_RULES_TABLE_NAME
                } else {
                    FSAccessRule.FS_ACCESS_RULES_TABLE_NAME
                }
                database.delete(
                    tableName,
                    arrayOf("role_name=", "subject=", "operation="),
                    arrayOf(roleName, subject, operation)
                )
                withContext(Dispatchers.Main) {
                    loadRules()
                    showMessageDialog(getString(Res.string.success), getString(Res.string.rule_deleted_successfully))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_delete_rule, e.message ?: ""))
                }
            }
        }
    }

    fun refreshRules() {
        loadRules()
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

