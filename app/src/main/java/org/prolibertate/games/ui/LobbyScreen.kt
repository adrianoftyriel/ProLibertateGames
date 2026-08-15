package org.prolibertate.games.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.TableConfig
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
                teamOf = { seat -> teamForSeat(route.gameId, seat, seatCount) },
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
        // Scrolls rather than filling: a lazy list here took the whole screen
        // and pushed the Start button — and now the address to type in — off
        // the bottom of it. There are never more than a handful of rows.
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                JoiningBody(env, state, playerName)
            }
        }
    }
}

@Composable
private fun HostingBody(lobby: LobbyController, state: LobbyController.State) {
    // Where to find this phone, for when the other one cannot find it by
    // itself. Over a phone's own hotspot that is the normal case rather than
    // the exception, so this is stated plainly rather than tucked away.
    state.endpoint?.primary?.let { where ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("This phone is at", style = MaterialTheme.typography.labelSmall)
                Text(where, fontWeight = FontWeight.Bold)
                Text(
                    text = "If the other phone doesn't find the game on its own — which " +
                        "happens when you're sharing this phone's hotspot — type that " +
                        "into its Join screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Text("Seats", fontWeight = FontWeight.Bold)
    state.seats.forEach { slot ->
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
    state: LobbyController.State,
    playerName: String,
) {
    val discovered = state.discovered

    if (discovered.isEmpty()) {
        Text("No games found yet. Make sure the host has started theirs.")
        Text(
            text = "Both phones need to be on the same Wi-Fi — including one phone's " +
                "hotspot, though over a hotspot the game often has to be joined by " +
                "address rather than found.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        discovered.forEach { host ->
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

    Divider()
    ManualJoin(env, playerName)
}

/**
 * Joining by address.
 *
 * Always offered rather than kept for when discovery has failed, because over a
 * phone's hotspot discovery does not fail loudly — it just never finds
 * anything, and a screen that only says "no games found" gives no way forward.
 */
@Composable
private fun ManualJoin(env: AppEnv, playerName: String) {
    var typed by remember { mutableStateOf("") }

    Text("Join by address", fontWeight = FontWeight.Bold)
    Text(
        text = "The host's screen shows where it is. The port can be left off unless " +
            "the host is showing a different one.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            label = { Text("192.168.43.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { env.lobby.joinAt(typed, playerName, env.peerId) },
            enabled = typed.isNotBlank(),
        ) { Text("Connect") }
    }
}
