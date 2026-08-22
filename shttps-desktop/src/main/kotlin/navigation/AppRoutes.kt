package com.phlox.simpleserver.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation routes for the application using type-safe navigation
 */
@Serializable
data object HomeRoute

@Serializable
data object RedirectionsListScreenRoute

@Serializable
data object CORSRulesListScreenRoute

@Serializable
data object HeadersOverridesListRoute

@Serializable
data class HeadersOverrideEditRoute(
    val ruleIndex: Int = -1,
)

@Serializable
data class CORSEditRoute(
    val ruleIndex: Int = -1,
    val origin: String = "*",
    val allowMethods: String = "",
    val allowHeaders: String = "",
    val allowCredentials: Boolean = false,
    val exposeHeaders: String = "",
    val maxAge: Int = 0
)

@Serializable
data object VersionInfoRoute

@Serializable
data class RedirectEditRoute(
    val ruleIndex: Int = -1,
    val fromPattern: String = "",
    val toDestination: String = "",
    val httpCode: Int = 302,
    val comment: String = ""
)

@Serializable
data class QrRoute(
    val text: String = ""
)

@Serializable
data object LogsRoute

@Serializable
data object CgiListScreenRoute

@Serializable
data class CgiTypeDetailsRoute(
    val cgiTypeIndex: Int = -1,
    val cgiTypeExtension: String = "",
    val cgiTypeMode: String = "CGI",
    val cgiTypeExecuteWith: String? = null,
    val cgiTypeExecutionTimeout: Int? = null
)

@Serializable
data object ChannelsListScreenRoute

/**
 * A predefined channel is addressed by its id, never by its position in the stored list - the same
 * way the API addresses it, and the only option anyway: [com.phlox.simpleserver.channels.ChannelDefinition]
 * carries a `JSONObject` initial state that cannot travel through route parameters.
 */
@Serializable
data class ChannelDetailsRoute(
    val channelId: String? = null
)

@Serializable
data object AuthDetailsRoute

@Serializable
data class UserDetailsRoute(
    val userIdentity: String = ""
)

@Serializable
data object RolesListRoute

@Serializable
data class RoleDetailsRoute(
    val roleName: String = "",
    val isNewRole: Boolean = false
)

@Serializable
data class RulesListRoute(
    val ruleType: String = "db", // "db" or "fs"
    val roleName: String = ""
)

@Serializable
data class RuleDetailsRoute(
    val ruleType: String = "db", // "db" or "fs"
    val roleName: String = "",
    val subject: String = "",
    val operation: String = "",
    val allow: Boolean = true,
    val expression: String = ""
)
