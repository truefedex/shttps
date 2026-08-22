package com.phlox.simpleserver.screens.auth.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.DBAccessRule
import com.phlox.simpleserver.auth.FSAccessRule
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_not_available
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_delete_rule
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_save_rule
import com.phlox.simpleserver.shttps_desktop.generated.resources.info
import com.phlox.simpleserver.shttps_desktop.generated.resources.new_rule_not_saved_yet
import com.phlox.simpleserver.shttps_desktop.generated.resources.rule_created_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.rule_deleted_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.rule_updated_successfully
import com.phlox.simpleserver.shttps_desktop.generated.resources.success
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RuleDetailsState(
    val subject: String = "",
    val subjectError: Boolean = false,
    val operation: String = "",
    val allow: Boolean = true,
    val expression: String = "",
    val isLoading: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData()
)

class RuleDetailsViewModel(
    private val config: AppConfig,
    private val shttpsApp: SHTTPSApp,
    private val ruleType: String,
    private val roleName: String,
    subject: String,
    operation: String,
    allow: Boolean,
    expression: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        RuleDetailsState(
            subject = subject,
            operation = operation,
            allow = allow,
            expression = expression
        )
    )
    val uiState: StateFlow<RuleDetailsState> = _uiState.asStateFlow()

    private val database: Database? = shttpsApp.database
    private val originalSubject = subject
    private val originalOperation = operation

    fun updateSubject(newSubject: String) {
        _uiState.value = _uiState.value.copy(
            subject = newSubject,
            subjectError = false
        )
    }

    fun updateOperation(newOperation: String) {
        _uiState.value = _uiState.value.copy(operation = newOperation)
    }

    fun updateAllow(newAllow: Boolean) {
        _uiState.value = _uiState.value.copy(allow = newAllow)
    }

    fun updateExpression(newExpression: String) {
        _uiState.value = _uiState.value.copy(expression = newExpression)
    }

    fun saveRule() {
        val trimmedSubject = _uiState.value.subject.trim()
        if (trimmedSubject.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                subjectError = true
            )
            return
        }

        val operation = _uiState.value.operation
        if (operation.isEmpty()) {
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
                val ruleJson = JSONObject()
                ruleJson.put("role_name", roleName)
                ruleJson.put("subject", trimmedSubject)
                ruleJson.put("operation", operation)
                ruleJson.put("allow", _uiState.value.allow)
                val trimmedExpression = _uiState.value.expression.trim()
                if (trimmedExpression.isNotEmpty()) {
                    ruleJson.put("expression", trimmedExpression)
                }

                val tableName = if (ruleType == "db") {
                    DBAccessRule.DB_ACCESS_RULES_TABLE_NAME
                } else {
                    FSAccessRule.FS_ACCESS_RULES_TABLE_NAME
                }

                if (isNewRule()) {
                    database.insert(tableName, ruleJson)
                    withContext(Dispatchers.Main) {
                        showMessageDialog(getString(Res.string.success), getString(Res.string.rule_created_successfully))
                    }
                } else {
                    database.update(
                        tableName, ruleJson,
                        arrayOf("role_name=", "subject=", "operation="),
                        arrayOf(roleName, originalSubject, originalOperation)
                    )
                    withContext(Dispatchers.Main) {
                        showMessageDialog(getString(Res.string.success), getString(Res.string.rule_updated_successfully))
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_save_rule, e.message ?: ""))
                }
            }
        }
    }

    fun deleteRule() {
        if (isNewRule()) {
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.info), getString(Res.string.new_rule_not_saved_yet))
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
                val tableName = if (ruleType == "db") {
                    DBAccessRule.DB_ACCESS_RULES_TABLE_NAME
                } else {
                    FSAccessRule.FS_ACCESS_RULES_TABLE_NAME
                }
                database.delete(
                    tableName,
                    arrayOf("role_name=", "subject=", "operation="),
                    arrayOf(roleName, originalSubject, originalOperation)
                )
                withContext(Dispatchers.Main) {
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

    private fun isNewRule(): Boolean {
        return originalSubject.isEmpty() || originalOperation.isEmpty()
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

