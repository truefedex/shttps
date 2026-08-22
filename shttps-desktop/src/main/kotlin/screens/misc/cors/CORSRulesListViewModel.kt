package com.phlox.simpleserver.screens.misc.cors

import androidx.lifecycle.ViewModel
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CORSRulesListState(
    val rules: MutableList<CORSMiddleware.CORSRule> = mutableListOf(),
)

class CORSRulesListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CORSRulesListState())
    val uiState: StateFlow<CORSRulesListState> = _uiState.asStateFlow()

    private val config: SHTTPSConfig = SHTTPSApp.getInstance().config

    init {
        loadCORSRules()
    }

    private fun loadCORSRules() {
        val rules = config.corsRules?.toMutableList() ?: mutableListOf()
        _uiState.value = _uiState.value.copy(rules = rules)
    }

    fun saveCORSRules() {
        config.setCORSRules(_uiState.value.rules)
    }

    fun addCORSRule(rule: CORSMiddleware.CORSRule) {
        val currentRules = _uiState.value.rules.toMutableList()
        currentRules.add(rule)
        _uiState.value = _uiState.value.copy(rules = currentRules)
        saveCORSRules()
    }

    fun updateCORSRule(index: Int, rule: CORSMiddleware.CORSRule) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index in currentRules.indices) {
            currentRules[index] = rule
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveCORSRules()
        }
    }

    fun deleteCORSRule(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index in currentRules.indices) {
            currentRules.removeAt(index)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveCORSRules()
        }
    }

    fun moveCORSRuleUp(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index > 0 && index < currentRules.size) {
            val rule = currentRules.removeAt(index)
            currentRules.add(index - 1, rule)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveCORSRules()
        }
    }

    fun moveCORSRuleDown(index: Int) {
        val currentRules = _uiState.value.rules.toMutableList()
        if (index in 0 until currentRules.size - 1) {
            val rule = currentRules.removeAt(index)
            currentRules.add(index + 1, rule)
            _uiState.value = _uiState.value.copy(rules = currentRules)
            saveCORSRules()
        }
    }
}
