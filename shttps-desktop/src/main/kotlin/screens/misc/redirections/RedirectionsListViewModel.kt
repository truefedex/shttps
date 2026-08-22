package com.phlox.simpleserver.screens.misc.redirections

import androidx.lifecycle.ViewModel
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware.RedirectRule
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RedirectionsListState(
    val rules: MutableList<RedirectRule> = mutableListOf(),
    val redirectToIndex: Boolean = false,
)

class RedirectionsListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RedirectionsListState())
    val uiState: StateFlow<RedirectionsListState> = _uiState.asStateFlow()

    private val config: SHTTPSConfig = SHTTPSApp.getInstance().config

    init {
        loadRedirectRules()
        loadRedirectToIndex()
    }

    private fun loadRedirectRules() {
        val rules = config.getRedirectRules() ?: mutableListOf()
        _uiState.value = _uiState.value.copy(rules = rules.toMutableList())
    }

    private fun loadRedirectToIndex() {
        _uiState.value = _uiState.value.copy(redirectToIndex = config.getRedirectToIndex())
    }

    fun saveRedirectRules() {
        config.setRedirectRules(_uiState.value.rules)
    }

    fun toggleRedirectToIndex() {
        val newValue = !_uiState.value.redirectToIndex
        _uiState.value = _uiState.value.copy(redirectToIndex = newValue)
        config.setRedirectToIndex(newValue)
    }

    fun addRedirectRule(rule: RedirectRule) {
        val currentRules = _uiState.value.rules.toMutableList()
        currentRules.add(rule)
        _uiState.value = _uiState.value.copy(rules = currentRules)
        saveRedirectRules()
    }

    fun updateRedirectRule(index: Int, rule: RedirectRule) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index >= 0 && index < currentRules.size) {
            currentRules[index] = rule
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveRedirectRules()
        }
    }

    fun deleteRedirectRule(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index >= 0 && index < currentRules.size) {
            currentRules.removeAt(index)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveRedirectRules()
        }
    }

    fun moveRedirectRuleUp(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index > 0 && index < currentRules.size) {
            val rule = currentRules.removeAt(index)
            currentRules.add(index - 1, rule)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveRedirectRules()
        }
    }

    fun moveRedirectRuleDown(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index >= 0 && index < currentRules.size - 1) {
            val rule = currentRules.removeAt(index)
            currentRules.add(index + 1, rule)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveRedirectRules()
        }
    }

    fun toggleRedirectRuleEnabled(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index >= 0 && index < currentRules.size) {
            currentRules[index].enabled = !currentRules[index].enabled
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveRedirectRules()
        }
    }
}
