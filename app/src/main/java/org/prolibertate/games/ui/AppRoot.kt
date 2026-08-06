package org.prolibertate.games.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.GameCategory
import org.prolibertate.games.game.GameDescriptor
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.net.LobbyController
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.settings.SettingsRepository
import org.prolibertate.games.update.UpdateChannel
import org.prolibertate.games.update.Updater

/** Everything the screens need, assembled once by the activity. */
class AppEnv(
    val settingsRepository: SettingsRepository,
    val updater: Updater,
    val lobby: LobbyController,
    val peerId: String,
    val requestBluetoothPermissions: () -> Unit,
)

/**
 * Where the app currently is.
 *
 * A hand-rolled stack rather than the navigation library: there are six
 * destinations, two of which carry a live [TableConfig], and a typed sealed
 * class is both smaller and harder to get wrong than argument encoding.
 */
sealed interface Route {
    data object Menu : Route
    data object Settings : Route
    data class Setup(val gameId: String) : Route
    data class Lobby(val gameId: String, val optionsJson: String, val hosting: Boolean) : Route
    data class Play(
        val gameId: String,
        val config: TableConfig,
        val hosting: Boolean,
        val localSeat: Int,
    ) : Route
}

@Composable
fun AppRoot(env: AppEnv) {
    val settings by env.settingsRepository.settings.collectAsState(initial = Settings())
    var stack by remember { mutableStateOf(listOf<Route>(Route.Menu)) }

    // Read from the installed APK's own version name, so this follows the build
    // rather than the channel the user has selected for future updates.
    val showComingSoon = remember {
        env.updater.installedChannel() == UpdateChannel.DEV
    }

    val push: (Route) -> Unit = { route -> stack = stack + route }

    val pop: () -> Unit = {
        if (stack.size > 1) {
            // Leaving a lobby or a table tears the networking down with it.
            when (stack.last()) {
                is Route.Lobby, is Route.Play -> env.lobby.stop()
                else -> Unit
            }
            stack = stack.dropLast(1)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    var splashDone by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AppContent(
            env = env,
            settings = settings,
            stack = stack,
            showComingSoon = showComingSoon,
            push = push,
            pop = pop,
        )

        // Over the top, so the menu behind it is already composed when it fades.
        if (!splashDone) {
            SplashScreen(onFinished = { splashDone = true })
        }
    }
}

/** The navigation stack proper, kept separate so the splash can sit over it. */
@Composable
private fun AppContent(
    env: AppEnv,
    settings: Settings,
    stack: List<Route>,
    showComingSoon: Boolean,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    when (val route = stack.last()) {
        is Route.Menu -> MainMenuScreen(
            // Dev builds show the games that are not finished yet; production
            // releases list only what can actually be played.
            showComingSoon = showComingSoon,
            onPickGame = { push(Route.Setup(it.id)) },
            onJoinGame = { push(Route.Lobby(gameId = "", optionsJson = "{}", hosting = false)) },
            onSettings = { push(Route.Settings) },
        )

        is Route.Settings -> SettingsScreen(
            env = env,
            settings = settings,
            onBack = { pop() },
        )

        is Route.Setup -> GameSetupScreen(
            descriptor = GameCatalog.byId(route.gameId)!!,
            playerName = settings.displayName,
            onBack = { pop() },
            onPlayOffline = { config, seat -> push(Route.Play(route.gameId, config, true, seat)) },
            onHostOnline = { optionsJson ->
                push(Route.Lobby(route.gameId, optionsJson, hosting = true))
            },
        )

        is Route.Lobby -> LobbyScreen(
            env = env,
            route = route,
            playerName = settings.displayName,
            onBack = { pop() },
            onStart = { config, hosting, seat ->
                push(Route.Play(config.gameId, config, hosting, seat))
            },
        )

        is Route.Play -> PlayScreen(
            env = env,
            route = route,
            settings = settings,
            onExit = { pop() },
        )
    }
}

// ---------------------------------------------------------------------------
// Main menu
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    showComingSoon: Boolean,
    onPickGame: (GameDescriptor) -> Unit,
    onJoinGame: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pro Libertate Games") },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onJoinGame) { Text("Join a game nearby") }
            }

            // The column count follows the available width, so a phone in
            // portrait gets two and a tablet in landscape gets four or more.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 172.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                GameCategory.entries.forEach { category ->
                    val games = GameCatalog.byCategory(category, includeComingSoon = showComingSoon)
                    // Don't leave a heading stranded over nothing.
                    if (games.isEmpty()) return@forEach

                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(games) { game ->
                        GameTile(game = game, onClick = { onPickGame(game) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameTile(game: GameDescriptor, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = game.available,
        colors = CardDefaults.cardColors(
            containerColor = if (game.available) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = game.blurb,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = if (game.available) {
                    if (game.minPlayers == game.maxPlayers) {
                        "${game.minPlayers} players"
                    } else {
                        "${game.minPlayers}–${game.maxPlayers} players"
                    }
                } else {
                    "Coming soon"
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { actions() },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopStart,
        ) {
            content(Modifier.fillMaxSize().padding(12.dp))
        }
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text)
    }
}
