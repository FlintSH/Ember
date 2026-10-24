package dev.fl1nt.ember.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.MainActivity
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.settings.EmberSettings
import dev.fl1nt.ember.ui.components.Hairline
import dev.fl1nt.ember.ui.home.HomeRoute
import dev.fl1nt.ember.ui.icons.Lucide
import dev.fl1nt.ember.ui.library.LibraryRoute
import dev.fl1nt.ember.ui.library.LibraryTab
import dev.fl1nt.ember.ui.links.ShortenRoute
import dev.fl1nt.ember.ui.onboarding.ConnectRoute
import dev.fl1nt.ember.ui.onboarding.ReadyScreen
import dev.fl1nt.ember.ui.onboarding.WelcomeScreen
import dev.fl1nt.ember.ui.paste.PasteRoute
import dev.fl1nt.ember.ui.settings.SettingsRoute
import dev.fl1nt.ember.ui.sync.AddSourceRoute
import dev.fl1nt.ember.ui.sync.SourceDetailRoute
import dev.fl1nt.ember.ui.sync.SyncRoute
import dev.fl1nt.ember.ui.theme.EmberTheme
import dev.fl1nt.ember.ui.theme.isDarkTheme
import dev.fl1nt.ember.ui.uploads.UploadSheet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data class LibraryDestination(val tab: String? = null)
@Serializable data object SyncDestination
@Serializable data object SettingsDestination
@Serializable data class PasteDestination(val text: String = "")
@Serializable data class ShortenDestination(val url: String = "")
@Serializable data object AddSourceDestination
@Serializable data class SourceDestination(val id: Long)

private sealed interface AccountLoad {
    data object Loading : AccountLoad

    data class Loaded(val account: Account?) : AccountLoad
}

@Composable
fun EmberRoot(container: AppContainer, destination: StateFlow<String?>, onDestinationHandled: () -> Unit) {
    val load by remember { container.accounts.account.map { AccountLoad.Loaded(it) } }.collectAsStateWithLifecycle(AccountLoad.Loading)
    val settings by container.settings.settings.collectAsStateWithLifecycle(EmberSettings())
    val justConnected by container.justConnected.collectAsStateWithLifecycle()
    // Reconnecting goes straight to the connect form, with the address filled in.
    var reconnecting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(load) { if ((load as? AccountLoad.Loaded)?.account != null) reconnecting = false }

    EmberTheme(settings.themeMode) {
        SystemBars(isDarkTheme(settings.themeMode))
        CompositionLocalProvider(LocalAppContainer provides container) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val current = load
                val connected = justConnected
                when {
                    current is AccountLoad.Loading -> Unit
                    connected != null && current is AccountLoad.Loaded && current.account != null ->
                        ReadyScreen(connected, current.account.origin.displayName, onDone = { container.justConnected.value = null })
                    current is AccountLoad.Loaded && current.account == null -> OnboardingFlow(startAtConnect = reconnecting)
                    else -> MainFlow(destination, onDestinationHandled, onReconnect = {
                        reconnecting = true
                        scope.launch {
                            container.scheduler.cancelAll()
                            container.accounts.disconnect()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun SystemBars(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    SideEffect {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun OnboardingFlow(startAtConnect: Boolean) {
    var connecting by rememberSaveable { mutableStateOf(startAtConnect) }
    if (connecting) {
        androidx.activity.compose.BackHandler { connecting = false }
        ConnectRoute(onBack = { connecting = false })
    } else {
        WelcomeScreen(onConnect = { connecting = true })
    }
}

private data class Tab(val route: Any, val label: Int, val icon: ImageVector, val matches: (androidx.navigation.NavDestination) -> Boolean)

private val TABS = listOf(
    Tab(HomeDestination, R.string.nav_home, Lucide.House) { it.hasRoute(HomeDestination::class) },
    Tab(LibraryDestination(), R.string.nav_library, Lucide.FolderOpen) { it.hasRoute(LibraryDestination::class) },
    Tab(SyncDestination, R.string.nav_sync, Lucide.FolderSync) { it.hasRoute(SyncDestination::class) },
    Tab(SettingsDestination, R.string.nav_settings, Lucide.Settings) { it.hasRoute(SettingsDestination::class) },
)

@Composable
private fun MainFlow(destination: StateFlow<String?>, onDestinationHandled: () -> Unit, onReconnect: () -> Unit) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current
    val pending by destination.collectAsStateWithLifecycle()
    var openUploadPicker by remember { mutableStateOf(false) }
    var selectedUpload by remember { mutableStateOf<dev.fl1nt.ember.data.db.UploadEntity?>(null) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBar = currentDestination != null && TABS.any { it.matches(currentDestination) }

    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        when (target) {
            MainActivity.DESTINATION_HOME -> navController.navigateTab(HomeDestination)
            MainActivity.DESTINATION_RECENT -> navController.navigateTab(LibraryDestination(LibraryTab.RECENT.name))
            MainActivity.DESTINATION_SYNC -> navController.navigateTab(SyncDestination)
            MainActivity.DESTINATION_SETTINGS -> navController.navigateTab(SettingsDestination)
            MainActivity.DESTINATION_PASTE -> navController.navigate(PasteDestination())
            MainActivity.DESTINATION_SHORTEN -> navController.navigate(ShortenDestination())
            MainActivity.DESTINATION_UPLOAD -> {
                navController.navigateTab(HomeDestination)
                openUploadPicker = true
            }
            MainActivity.DESTINATION_SYNC_NOW -> {
                container.scheduler.syncNow(userInitiated = true)
                navController.navigateTab(SyncDestination)
            }
        }
        onDestinationHandled()
    }

    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        bottomBar = {
            if (showBar) {
                Column {
                    Hairline()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        TABS.forEach { tab ->
                            val selected = tab.matches(currentDestination)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // On tablets and unfolded phones, keep content at a comfortable reading width.
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()), contentAlignment = Alignment.TopCenter) {
            NavHost(navController, startDestination = HomeDestination, modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
                composable<HomeDestination> {
                    HomeRoute(
                        onPaste = { navController.navigate(PasteDestination()) },
                        onShorten = { navController.navigate(ShortenDestination()) },
                        onOpenSync = { navController.navigateTab(SyncDestination) },
                        onAddSource = { navController.navigate(AddSourceDestination) },
                        onSeeAll = { navController.navigateTab(LibraryDestination(LibraryTab.RECENT.name)) },
                        onSnackbar = showMessage,
                        openUploadPicker = openUploadPicker,
                        onUploadPickerHandled = { openUploadPicker = false },
                        onReconnect = onReconnect,
                    )
                }
                composable<LibraryDestination> { entry ->
                    val tab = entry.toRoute<LibraryDestination>().tab?.let { name -> LibraryTab.entries.firstOrNull { it.name == name } }
                    LibraryRoute(initialTab = tab, onShorten = { navController.navigate(ShortenDestination()) }, onSnackbar = showMessage)
                }
                composable<SyncDestination> {
                    SyncRoute(
                        onAdd = { navController.navigate(AddSourceDestination) },
                        onOpen = { navController.navigate(SourceDestination(it)) },
                        onOpenSettings = { navController.navigateTab(SettingsDestination) },
                    )
                }
                composable<SettingsDestination> { SettingsRoute() }
                composable<PasteDestination> { entry -> PasteRoute(onBack = { navController.popBackStack() }, initialText = entry.toRoute<PasteDestination>().text) }
                composable<ShortenDestination> { entry -> ShortenRoute(onBack = { navController.popBackStack() }, initialUrl = entry.toRoute<ShortenDestination>().url) }
                composable<AddSourceDestination> {
                    AddSourceRoute(
                        onBack = { navController.popBackStack() },
                        onSaved = { id ->
                            navController.popBackStack()
                            navController.navigate(SourceDestination(id))
                            showMessage(container.application.getString(R.string.sync_source_added))
                        },
                    )
                }
                composable<SourceDestination> { entry ->
                    SourceDetailRoute(entry.toRoute<SourceDestination>().id, onBack = { navController.popBackStack() }, onSelectUpload = { selectedUpload = it })
                }
            }
        }
    }

    selectedUpload?.let { upload ->
        UploadSheet(
            upload,
            onDismiss = { selectedUpload = null },
            onRetry = { scope.launch { container.uploads.retry(upload.id) }; selectedUpload = null },
            onCancel = { scope.launch { container.uploads.cancel(upload.id) }; selectedUpload = null },
            onRemove = { scope.launch { container.uploads.remove(upload.id) }; selectedUpload = null },
        )
    }
}

private val MAX_CONTENT_WIDTH = 840.dp

private fun NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
