package org.prolibertate.games.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.net.DiscoveredHost
import org.prolibertate.games.net.LobbyController
import org.prolibertate.games.net.seatForDevice

/**
 * Hosting or joining a table.
 *
 * A host advertises on every available transport, and the seat list does not
 * care which one a given player arrived on.
 */
@Composable
fun LobbyScreen(
    env: AppEnv,
    route: Route.Lobby,
    playerName: String,
    onBack: () -> Unit,
    onStart: (TableConfig, Boolean, Int) -> Unit,
) {
    val state by env.lobby.state.collectAsState()

    LaunchedEffect(route) {
        if (route.hosting) {
            // Seat the table the host actually set up, not the catalogue
            // minimum: the rules engine rejects a table of the wrong size.
            val seatCount = seatCountFor(route.gameId, route.optionsJson)
            env.lobby.startHosting(
                gameId = route.gameId,
                optionsJson = route.optionsJson,
                hostName = playerName,
                seatCount = seatCount,
                teamOf = { seat -> teamForSeat(route.gameId, seat) },
            )
        } else {
            env.lobby.startDiscovery()
        }
    }

    // Both roles leave for the table the moment the host commits.
    LaunchedEffect(state.started) {
        val config = state.started ?: return@LaunchedEffect
        val seat = if (route.hosting) {
            0
        } else {
            seatForDevice(config, env.peerId)
        }
        env.lobby.clearStarted()
        onStart(config, route.hosting, seat)
    }

    val title = if (route.hosting) "Hosting" else "Join a game"

    ScreenScaffold(title = title, onBack = onBack) { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val available = env.lobby.availableTransports()
            Text(
                text = if (available.isEmpty()) {
                    "No Wi-Fi available. Turn it on to play with others."
                } else {
                    (if (route.hosting) "Visible over " else "Searching over ") +
                        available.joinToString(" and ") { it.kind.label } + "."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Divider()

            if (route.hosting) {
                HostingBody(env.lobby, state)
            } else {
                JoiningBody(env, state.discovered, playerName)
            }
        }
    }
}

@Composable
private fun HostingBody(lobby: LobbyController, state: LobbyController.State) {
    Text("Seats", fontWeight = FontWeight.Bold)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(state.seats) { slot ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Seat ${slot.seat} — ${slot.name}")
                        Text(
                            text = when (slot.kind) {
                                PlayerKind.HUMAN_LOCAL -> "You"
                                PlayerKind.HUMAN_REMOTE -> "Joined"
                                PlayerKind.AI -> "Computer"
                            } + "  ·  Team ${slot.team}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (slot.kind == PlayerKind.HUMAN_REMOTE) {
                        TextButton(onClick = {
                            lobby.setSeatKind(slot.seat, PlayerKind.AI, "Computer ${slot.seat}")
                        }) { Text("Drop") }
                    }
                }
            }
        }
    }

    PrimaryAction(
        text = "Start game",
        enabled = state.seats.isNotEmpty(),
    ) {
        lobby.startMatch(seed = System.currentTimeMillis())
    }
    Text(
        text = "Any seat still showing Computer will be played by the AI.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun JoiningBody(
    env: AppEnv,
    discovered: List<DiscoveredHost>,
    playerName: String,
) {
    if (discovered.isEmpty()) {
        Text("No games found yet. Make sure the host has started theirs.")
        Text(
            text = "Both devices need to be on the same Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(discovered) { host ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { env.lobby.join(host, playerName, env.peerId) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(host.name, fontWeight = FontWeight.Bold)
                    Text(
                        text = "over ${host.kind.label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
