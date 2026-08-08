package org.prolibertate.games.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.R
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.GameMenu
import org.prolibertate.games.game.GameDescriptor
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.net.LobbyController
import org.prolibertate.games.score.ScorekeeperRepository
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.settings.SettingsRepository
import org.prolibertate.games.update.UpdateChannel
import org.prolibertate.games.update.Updater

/** Everything the screens need, assembled once by the activity. */
class AppEnv(
    val settingsRepository: SettingsRepository,
    val updater: Updater,
    val lobby: LobbyController,
    val scorekeeper: ScorekeeperRepository,
    val peerId: String,
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

    /** One section of the menu: its games, and any sub-section under it. */
    data class Section(val menu: GameMenu) : Route
    data object Settings : Route
    data object Scorekeeper : Route
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
            val leaving = stack.last()
            // Leaving a lobby or a table tears the networking down with it.
            when (leaving) {
                is Route.Lobby, is Route.Play -> env.lobby.stop()
                else -> Unit
            }
            stack = stack.dropLast(1).let { remaining ->
                // Leaving a table must not land back in the lobby that started
                // it. A lobby starts advertising the moment it is drawn, so
                // ending a game would have put the player straight into another
                // one — which is no way to leave a game.
                if (leaving is Route.Play) {
                    remaining.dropLastWhile { it is Route.Lobby }
                } else {
                    remaining
                }
            }
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

        // Looks while the splash is up and waits until it has gone before
        // saying anything, so the check costs nothing in time and cannot
        // interrupt the opening of the app.
        LaunchUpdateCheck(env = env, visible = splashDone)
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
            onPickSection = { push(Route.Section(it)) },
            onJoinGame = { push(Route.Lobby(gameId = "", optionsJson = "{}", hosting = false)) },
            onScorekeeper = { push(Route.Scorekeeper) },
            onSettings = { push(Route.Settings) },
        )

        is Route.Section -> SectionScreen(
            menu = route.menu,
            showComingSoon = showComingSoon,
            onPickGame = { push(Route.Setup(it.id)) },
            onPickSection = { push(Route.Section(it)) },
            onBack = pop,
        )

        is Route.Settings -> SettingsScreen(
            env = env,
            settings = settings,
            onBack = { pop() },
        )

        is Route.Scorekeeper -> ScorekeeperScreen(
            repository = env.scorekeeper,
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
    onPickSection: (GameMenu) -> Unit,
    onJoinGame: () -> Unit,
    onScorekeeper: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                // The installed name rather than a literal, so a dev build says
                // so in its own title bar as well as in the app drawer.
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
            )
        }
    ) { padding ->
        // Only the top of the tree. Trick-taking is reached through Card Games,
        // which is where somebody looking for it would go.
        val sections = GameMenu.entries.filter {
            it.isTopLevel && GameCatalog.hasAnything(it, includeComingSoon = showComingSoon)
        }

        // One column, held in the middle. There are only ever a handful of
        // entries here now that the games themselves live a level down, and a
        // short list spread across a grid reads as a page half empty rather
        // than as a menu. Capped in width so it does not become a row of very
        // wide buttons on a tablet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                sections.forEach { section ->
                    SectionTile(
                        menu = section,
                        count = countIn(section, showComingSoon),
                        onClick = { onPickSection(section) },
                    )
                }

                // Below the games, and full width like them, so the column
                // stays one column all the way down.
                OutlinedButton(
                    onClick = onJoinGame,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Join a game nearby") }
                // Not a game, and deliberately not in the catalogue: it keeps
                // score for whatever is being played on the actual table.
                OutlinedButton(
                    onClick = onScorekeeper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scorekeeper") }
            }
        }
    }
}


/**
 * How many games a section offers, counting whatever is under it as well.
 *
 * A game in both a section and its sub-section is one game, not two, so this
 * counts a set rather than adding the lists together.
 */
private fun countIn(menu: GameMenu, showComingSoon: Boolean): Int =
    (GameCatalog.inMenu(menu, showComingSoon) +
        menu.children.flatMap { GameCatalog.inMenu(it, showComingSoon) })
        .toSet().size

/**
 * One section of the menu.
 *
 * Sub-sections come first and its own games after, so Card Games opens on
 * "Trick-taking" and then everything played with a pack — including the
 * trick-taking games themselves, which belong in both places and are listed in
 * both. A game reachable one way and not the other would just be a game
 * somebody could not find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(
    menu: GameMenu,
    showComingSoon: Boolean,
    onPickGame: (GameDescriptor) -> Unit,
    onPickSection: (GameMenu) -> Unit,
    onBack: () -> Unit,
) {
    val children = menu.children.filter {
        GameCatalog.hasAnything(it, includeComingSoon = showComingSoon)
    }
    val games = GameCatalog.inMenu(menu, includeComingSoon = showComingSoon)

    ScreenScaffold(title = menu.label, onBack = onBack) { modifier ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 172.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = menu.blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(children) { child ->
                SectionTile(
                    menu = child,
                    count = countIn(child, showComingSoon),
                    onClick = { onPickSection(child) },
                )
            }
            if (games.isNotEmpty() && children.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "All ${menu.label.lowercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(games) { game ->
                GameTile(game = game, onClick = { onPickGame(game) })
            }
        }
    }
}

/** A way into a section, rather than into a game. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTile(menu: GameMenu, count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = menu.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = menu.blurb,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = if (count == 1) "1 game" else "$count games",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
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
                text = when {
                    !game.available -> "Coming soon"
                    // A puzzle for one, rather than "1 players".
                    game.maxPlayers == 1 -> "On your own"
                    game.minPlayers == game.maxPlayers -> "${game.minPlayers} players"
                    else -> "${game.minPlayers}–${game.maxPlayers} players"
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
