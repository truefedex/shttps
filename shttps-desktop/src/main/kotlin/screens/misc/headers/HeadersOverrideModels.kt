package com.phlox.simpleserver.screens.misc.headers

import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware
import com.phlox.server.utils.MultiMap

data class HeaderEntry(
    val name: String,
    val value: String
)

data class HeadersOverrideRule(
    val path: String = "/",
    val ifHeadersExist: CustomHeadersMiddleware.IfHeadersExist = CustomHeadersMiddleware.IfHeadersExist.OVERRIDE,
    val headers: List<HeaderEntry> = emptyList(),
    val filterMethods: Set<String>? = null,
    val filterStatusCodes: Set<Int>? = null,
    val filterPostfixes: Set<String>? = null,
)

internal fun HeadersOverrideRule.toServerRule(): CustomHeadersMiddleware.Rule {
    val headersMap = MultiMap<String, String>()
    headers.forEach { e ->
        val name = e.name.trim()
        if (name.isNotEmpty()) {
            headersMap.add(name, e.value)
        }
    }
    return CustomHeadersMiddleware.Rule(
        path.ifBlank { "/" },
        headersMap,
        ifHeadersExist,
        filterMethods?.takeIf { it.isNotEmpty() }?.toHashSet(),
        filterStatusCodes?.takeIf { it.isNotEmpty() }?.toHashSet(),
        filterPostfixes?.takeIf { it.isNotEmpty() }?.toHashSet(),
    )
}

internal fun CustomHeadersMiddleware.Rule.toUiRule(): HeadersOverrideRule {
    val entries = mutableListOf<HeaderEntry>()
    headers?.forEach { name, values ->
        values?.forEach { v ->
            entries.add(HeaderEntry(name, v))
        }
    }
    return HeadersOverrideRule(
        path = path ?: "/",
        ifHeadersExist = ifHeadersExist ?: CustomHeadersMiddleware.IfHeadersExist.OVERRIDE,
        headers = entries,
        filterMethods = filterMethods?.toSet(),
        filterStatusCodes = filterStatusCodes?.toSet(),
        filterPostfixes = filterPostfixes?.toSet(),
    )
}

