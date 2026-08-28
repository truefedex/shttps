package com.phlox.simpleserver.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.ApplicationScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.MainWindowEvents
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.screens.VersionInfoScreen
import com.phlox.simpleserver.screens.attributions.AttributionsScreen
import com.phlox.simpleserver.screens.auth.AuthDetailsScreen
import com.phlox.simpleserver.screens.auth.UserDetailsScreen
import com.phlox.simpleserver.screens.auth.roles.RolesListScreen
import com.phlox.simpleserver.screens.auth.roles.RoleDetailsScreen
import com.phlox.simpleserver.screens.auth.rules.RulesListScreen
import com.phlox.simpleserver.screens.auth.rules.RuleDetailsScreen
import com.phlox.simpleserver.screens.home.HomeScreen
import com.phlox.simpleserver.screens.home.HomeViewModel
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.new_role
import org.jetbrains.compose.resources.stringResource
import com.phlox.simpleserver.screens.logs.LogsScreen
import com.phlox.simpleserver.screens.logs.LogsViewModel
import com.phlox.simpleserver.screens.qr.QrScreen
import com.phlox.simpleserver.security.ClientApprovalController
import com.phlox.simpleserver.screens.misc.redirections.RedirectEditScreen
import java.util.EnumSet
import com.phlox.simpleserver.screens.misc.redirections.RedirectionsListScreen
import com.phlox.simpleserver.screens.misc.redirections.RedirectionsListViewModel
import com.phlox.simpleserver.screens.misc.cors.CORSRulesScreen
import com.phlox.simpleserver.screens.misc.cors.CORSRulesListViewModel
import com.phlox.simpleserver.screens.misc.cors.CORSEditScreen
import com.phlox.simpleserver.screens.misc.headers.HeadersOverrideEditScreen
import com.phlox.simpleserver.screens.misc.headers.HeadersOverridesListScreen
import com.phlox.simpleserver.screens.misc.headers.HeadersOverridesViewModel
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware
import com.phlox.simpleserver.screens.channels.ChannelDetailsScreen
import com.phlox.simpleserver.screens.channels.ChannelDetailsViewModel
import com.phlox.simpleserver.screens.channels.ChannelsListScreen
import com.phlox.simpleserver.screens.channels.ChannelsListViewModel
import com.phlox.simpleserver.screens.handlers.CgiListScreen
import com.phlox.simpleserver.screens.handlers.CgiListViewModel
import com.phlox.simpleserver.screens.handlers.CgiTypeDetailsScreen
import com.phlox.simpleserver.screens.handlers.CgiTypeDetailsViewModel
import com.phlox.simpleserver.exec.CgiType
import io.github.vinceglb.autolaunch.AutoLaunch
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

@Composable
fun AppNavigationGraph(
    navController: NavHostController,
    appScope: ApplicationScope,
    window: ComposeWindow,
    config: AppConfig,
    appSettingsDir: File,
    onHideAppToTrayValueChange: () -> Unit,
    mainWindowEvents: MutableSharedFlow<MainWindowEvents>,
    serverRunning: MutableState<Boolean>,
    autoLaunch: AutoLaunch,
    approvalController: ClientApprovalController,
    //passed down only so the update prompt can wait for the window to actually be on screen
    windowVisible: State<Boolean>
) {
    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(
        appScope, config, appSettingsDir, mainWindowEvents, serverRunning, autoLaunch,
        approvalController
    ) }
    val redirectionsListViewModel: RedirectionsListViewModel = viewModel { RedirectionsListViewModel() }
    val corsRulesListViewModel: CORSRulesListViewModel = viewModel { CORSRulesListViewModel() }
    val headersOverridesViewModel: HeadersOverridesViewModel = viewModel { HeadersOverridesViewModel() }
    
    val homeState by homeViewModel.uiState.collectAsState()
    
    NavHost(
        navController = navController,
        startDestination = HomeRoute
    ) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel = homeViewModel,
                navController = navController,
                window = window,
                windowVisible = windowVisible,
                onHideAppToTrayValueChange = onHideAppToTrayValueChange,
                onNavigateToRedirectionsListScreen = {
                    navController.navigate(RedirectionsListScreenRoute)
                },
                onNavigateToCORSRulesListScreen = {
                    navController.navigate(CORSRulesListScreenRoute)
                },
                onNavigateToVersionInfo = {
                    navController.navigate(VersionInfoRoute)
                },
                onNavigateToQr = { text ->
                    val route = QrRoute(text = text)
                    navController.navigate(route)
                },
                onNavigateToLogs = {
                    navController.navigate(LogsRoute)
                },
                onNavigateToAuthDetails = {
                    navController.navigate(AuthDetailsRoute)
                },
                onNavigateToCgiList = {
                    navController.navigate(CgiListScreenRoute)
                },
                onNavigateToChannelsList = {
                    navController.navigate(ChannelsListScreenRoute)
                }
            )
        }

        composable<ChannelsListScreenRoute> {
            val channelsListViewModel: ChannelsListViewModel = viewModel {
                //the predefined channels and the idle sweep are built when the server starts, so a
                //change to any of these settings needs it restarted
                ChannelsListViewModel(config) { homeViewModel.restartServerIfRunning() }
            }
            ChannelsListScreen(
                viewModel = channelsListViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToChannelDetails = { channelId ->
                    navController.navigate(ChannelDetailsRoute(channelId = channelId))
                },
                navController = navController
            )
        }

        composable<ChannelDetailsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ChannelDetailsRoute>()

            val channelDetailsViewModel: ChannelDetailsViewModel = viewModel {
                ChannelDetailsViewModel(
                    config = config,
                    channelId = route.channelId
                ) { homeViewModel.restartServerIfRunning() }
            }

            ChannelDetailsScreen(
                viewModel = channelDetailsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSaveSuccess = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("channelsWasEdited", true)
                    navController.popBackStack()
                }
            )
        }
        
        composable<CgiListScreenRoute> {
            val cgiListViewModel: CgiListViewModel = viewModel { CgiListViewModel(config) }
            CgiListScreen(
                viewModel = cgiListViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToCgiTypeDetails = { index, cgiType ->
                    val route = CgiTypeDetailsRoute(
                        cgiTypeIndex = index ?: -1,
                        cgiTypeExtension = cgiType?.extension ?: "",
                        cgiTypeMode = cgiType?.mode?.name ?: "CGI",
                        cgiTypeExecuteWith = cgiType?.executeWith,
                        cgiTypeExecutionTimeout = cgiType?.executionTimeout
                    )
                    navController.navigate(route)
                },
                navController = navController
            )
        }
        
        composable<CgiTypeDetailsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CgiTypeDetailsRoute>()
            val cgiType = if (route.cgiTypeIndex >= 0 && route.cgiTypeExtension.isNotEmpty()) {
                CgiType(
                    route.cgiTypeExtension,
                    CgiType.Mode.valueOf(route.cgiTypeMode),
                    route.cgiTypeExecuteWith,
                    route.cgiTypeExecutionTimeout
                )
            } else {
                null
            }
            
            val cgiTypeDetailsViewModel: CgiTypeDetailsViewModel = viewModel {
                CgiTypeDetailsViewModel(
                    config = config,
                    initialIndex = if (route.cgiTypeIndex >= 0) route.cgiTypeIndex else null,
                    initialCgiType = cgiType
                )
            }
            
            CgiTypeDetailsScreen(
                viewModel = cgiTypeDetailsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSaveSuccess = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("cgiTypesWasEdited", true)
                    navController.popBackStack()
                }
            )
        }
        
        composable<CORSRulesListScreenRoute> {
            CORSRulesScreen(
                viewModel = corsRulesListViewModel,
                onNavigateBack = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("corsRulesWasEdited", true)
                    navController.popBackStack()
                },
                onNavigateToEdit = { ruleIndex, rule ->
                    val route = CORSEditRoute(
                        ruleIndex = ruleIndex,
                        origin = rule.origin ?: "*",
                        allowMethods = rule.allowMethods?.joinToString(", ") ?: "",
                        allowHeaders = rule.allowHeaders?.joinToString(", ") ?: "",
                        allowCredentials = rule.allowCredentials == true,
                        exposeHeaders = rule.exposeHeaders?.joinToString(", ") ?: "",
                        maxAge = rule.maxAge
                    )
                    navController.navigate(route)
                }
            )
        }

        composable<HeadersOverridesListRoute> {
            HeadersOverridesListScreen(
                viewModel = headersOverridesViewModel,
                onNavigateBack = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("headersOverridesWasEdited", true)
                    navController.popBackStack()
                },
                onNavigateToEdit = { ruleIndex ->
                    navController.navigate(HeadersOverrideEditRoute(ruleIndex = ruleIndex))
                }
            )
        }

        composable<HeadersOverrideEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<HeadersOverrideEditRoute>()
            val state by headersOverridesViewModel.uiState.collectAsState()
            val idx = route.ruleIndex
            val rule = if (idx in state.rules.indices) state.rules[idx] else null
            if (rule != null) {
                HeadersOverrideEditScreen(
                    ruleIndex = idx,
                    rule = rule,
                    onNavigateBack = { navController.popBackStack() },
                    onSave = { updated ->
                        headersOverridesViewModel.updateRule(idx, updated)
                        navController.popBackStack()
                    }
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable<RedirectionsListScreenRoute> {
            RedirectionsListScreen(
                viewModel = redirectionsListViewModel,
                onNavigateBack = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("redirectsWasEdited", true)
                    navController.popBackStack()
                },
                onNavigateToEdit = { ruleIndex, rule ->
                    val route = RedirectEditRoute(
                        ruleIndex = ruleIndex,
                        fromPattern = rule.from,
                        toDestination = rule.to,
                        httpCode = rule.code,
                        comment = rule.comment
                    )
                    navController.navigate(route)
                }
            )
        }
        
        composable<VersionInfoRoute> {
            VersionInfoScreen(
                config = config,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToAttributions = {
                    navController.navigate(AttributionsRoute)
                }
            )
        }

        composable<AttributionsRoute> {
            AttributionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable<RedirectEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RedirectEditRoute>()
            val rule = RedirectsMiddleware.RedirectRule(
                route.fromPattern,
                route.toDestination,
                route.httpCode,
                true,
                route.comment
            )
            
            RedirectEditScreen(
                rule = rule,
                ruleIndex = route.ruleIndex,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSave = { savedRule, index ->
                    if (index >= 0) {
                        redirectionsListViewModel.updateRedirectRule(index, savedRule)
                    } else {
                        redirectionsListViewModel.addRedirectRule(savedRule)
                    }
                    navController.popBackStack()
                }
            )
        }

        composable<CORSEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CORSEditRoute>()
            val rule = CORSMiddleware.CORSRule().apply {
                origin = route.origin
                allowMethods = route.allowMethods.takeIf { it.isNotEmpty() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray()
                allowHeaders = route.allowHeaders.takeIf { it.isNotEmpty() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray()
                allowCredentials = if (route.allowCredentials) true else null
                exposeHeaders = route.exposeHeaders.takeIf { it.isNotEmpty() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray()
                maxAge = route.maxAge
            }

            CORSEditScreen(
                rule = rule,
                ruleIndex = route.ruleIndex,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSave = { savedRule, index ->
                    if (index >= 0) {
                        corsRulesListViewModel.updateCORSRule(index, savedRule)
                    } else {
                        corsRulesListViewModel.addCORSRule(savedRule)
                    }
                    navController.popBackStack()
                }
            )
        }
        
        composable<QrRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<QrRoute>()
            
            QrScreen(
                text = route.text,
                navController = navController,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable<LogsRoute> {
            val logsViewModel: LogsViewModel = viewModel { LogsViewModel() }
            
            // Initialize the logs view model with the SHTTPS app instance
            LaunchedEffect(Unit) {
                logsViewModel.initialize(homeViewModel.getSHTTPSApp())
            }
            
            LogsScreen(
                viewModel = logsViewModel,
                navController = navController,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable<AuthDetailsRoute> {
            AuthDetailsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToUserDetails = { user ->
                    val route = UserDetailsRoute(userIdentity = user.identity)
                    navController.navigate(route)
                },
                onNavigateToRoles = {
                    navController.navigate(RolesListRoute)
                },
                config = config,
                shttpsApp = homeViewModel.getSHTTPSApp()
            )
        }
        
        composable<UserDetailsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<UserDetailsRoute>()
            val shttpsApp = homeViewModel.getSHTTPSApp()
            val userStore = shttpsApp.provideUserStore()
            val user = userStore.find(route.userIdentity)
            
            if (user != null) {
                UserDetailsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoles = {
                        navController.navigate(RolesListRoute)
                    },
                    config = config,
                    shttpsApp = shttpsApp,
                    user = user
                )
            }
        }
        
        composable<RolesListRoute> {
            val newRoleName = stringResource(Res.string.new_role)
            RolesListScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToRoleDetails = { role ->
                    val route = RoleDetailsRoute(
                        roleName = role.name,
                        isNewRole = role.name == newRoleName
                    )
                    navController.navigate(route)
                },
                config = config,
                shttpsApp = homeViewModel.getSHTTPSApp()
            )
        }
        
        composable<RoleDetailsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RoleDetailsRoute>()
            val shttpsApp = homeViewModel.getSHTTPSApp()
            
            // Create a role object for the screen
            val newRoleName = stringResource(Res.string.new_role)
            val role: UserRole? = if (route.isNewRole) {
                UserRole(
                    newRoleName,
                    EnumSet.noneOf(User.FileSystemRights::class.java),
                    EnumSet.noneOf(User.DBRights::class.java),
                    null,
                    EnumSet.noneOf(User.SystemRights::class.java)
                )
            } else {
                // Try to find the role in the database
                val database = shttpsApp.database
                if (database != null) {
                    try {
                        database.getTableDataSecure(UserRole.ROLES_TABLE_NAME,
                            null, null, null, arrayOf("name="),
                            arrayOf(route.roleName), null, false,
                            false, null).use { data ->
                            if (data.next()) {
                                val foundRole = UserRole.deserialize(data.currentRowToJsonObject())
                                return@use foundRole
                            } else {
                                return@use null
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else {
                    null
                }
            }

            if (role != null) {
                RoleDetailsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRules = { ruleType, roleName ->
                        val route = RulesListRoute(ruleType = ruleType, roleName = roleName)
                        navController.navigate(route)
                    },
                    config = config,
                    shttpsApp = shttpsApp,
                    role = role
                )
            }
        }

        composable<RulesListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RulesListRoute>()
            val shttpsApp = homeViewModel.getSHTTPSApp()
            
            RulesListScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToRuleDetails = { ruleType, roleName, rule ->
                    val ruleRoute = if (rule == null) {
                        RuleDetailsRoute(
                            ruleType = ruleType,
                            roleName = roleName
                        )
                    } else {
                        val subject = if (ruleType == "db") {
                            (rule as com.phlox.simpleserver.auth.DBAccessRule).subject
                        } else {
                            (rule as com.phlox.simpleserver.auth.FSAccessRule).subject
                        }
                        val operation = if (ruleType == "db") {
                            (rule as com.phlox.simpleserver.auth.DBAccessRule).operation
                        } else {
                            (rule as com.phlox.simpleserver.auth.FSAccessRule).operation
                        }
                        val allow = if (ruleType == "db") {
                            (rule as com.phlox.simpleserver.auth.DBAccessRule).allow
                        } else {
                            (rule as com.phlox.simpleserver.auth.FSAccessRule).allow
                        }
                        val expression = if (ruleType == "db") {
                            (rule as com.phlox.simpleserver.auth.DBAccessRule).expression ?: ""
                        } else {
                            (rule as com.phlox.simpleserver.auth.FSAccessRule).expression ?: ""
                        }
                        RuleDetailsRoute(
                            ruleType = ruleType,
                            roleName = roleName,
                            subject = subject,
                            operation = operation,
                            allow = allow,
                            expression = expression
                        )
                    }
                    navController.navigate(ruleRoute)
                },
                ruleType = route.ruleType,
                roleName = route.roleName,
                config = config,
                shttpsApp = shttpsApp
            )
        }

        composable<RuleDetailsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RuleDetailsRoute>()
            val shttpsApp = homeViewModel.getSHTTPSApp()
            
            RuleDetailsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                ruleType = route.ruleType,
                roleName = route.roleName,
                subject = route.subject,
                operation = route.operation,
                allow = route.allow,
                expression = route.expression,
                config = config,
                shttpsApp = shttpsApp
            )
        }
    }
}