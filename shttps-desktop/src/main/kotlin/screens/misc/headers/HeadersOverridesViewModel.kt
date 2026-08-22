package com.phlox.simpleserver.screens.misc.headers

import androidx.lifecycle.ViewModel
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeadersOverridesState(
    val rules: List<HeadersOverrideRule> = emptyList()
)

class HeadersOverridesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HeadersOverridesState())
    val uiState: StateFlow<HeadersOverridesState> = _uiState.asStateFlow()

    private val config: SHTTPSConfig = SHTTPSApp.getInstance().config

    init {
        load()
    }

    private fun load() {
        val stored = config.getHeadersOverrides()
        val rules = stored?.map { it.toUiRule() } ?: emptyList()
        _uiState.value = _uiState.value.copy(rules = rules)
    }

    fun persist() {
        val serverRules: List<CustomHeadersMiddleware.Rule> = _uiState.value.rules.map { it.toServerRule() }
        config.setHeadersOverrides(serverRules)
    }

    fun setRules(rules: List<HeadersOverrideRule>) {
        _uiState.value = _uiState.value.copy(rules = rules)
        persist()
    }

    fun addRule(rule: HeadersOverrideRule) {
        setRules(_uiState.value.rules + rule)
    }

    fun updateRule(index: Int, rule: HeadersOverrideRule) {
        val current = _uiState.value.rules.toMutableList()
        if (index !in current.indices) return
        current[index] = rule
        setRules(current)
    }

    fun deleteRule(index: Int) {
        val current = _uiState.value.rules.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        setRules(current)
    }

    fun moveUp(index: Int) {
        val current = _uiState.value.rules.toMutableList()
        if (index <= 0 || index !in current.indices) return
        val r = current.removeAt(index)
        current.add(index - 1, r)
        setRules(current)
    }

    fun moveDown(index: Int) {
        val current = _uiState.value.rules.toMutableList()
        if (index !in 0 until current.size - 1) return
        val r = current.removeAt(index)
        current.add(index + 1, r)
        setRules(current)
    }
}

