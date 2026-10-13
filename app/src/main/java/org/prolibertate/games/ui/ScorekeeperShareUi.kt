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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.prolibertate.games.net.ScorekeeperSession

/*
 * The parts of the Scorekeeper that only exist while a sheet is being shared:
 * finding one to join, waiting to be let in, and — on the host — letting people
 * in and seeing who is there.
 */

/** Guest: the sheets nearby, and a way in by address when they cannot be found. */
@Composable
internal fun JoinPane(
    modifier: Modifier,
    session: ScorekeeperSession,
    shared: ScorekeeperSession.State,
    playerName: String,
) {
    LaunchedEffect(Unit) { session.startDiscovery() }

    var typed by remember { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Join a sheet somebody else is keeping. The host is asked to let " +
                "you in, and once you are, you see the same sheet they do.",
            style = MaterialTheme.typography.bodyMedium,
        )
        shared.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (shared.discovered.isEmpty()) {
            Text("No shared sheets found yet. Make sure the host has tapped Share.")
        } else {
            shared.discovered.forEach { host ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { session.join(host, playerName) },
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

        // Always offered, for the same reason the game lobby always offers it:
        // over a phone's hotspot nothing is found, and a list that only says
        // "no sheets" leaves nowhere to go.
        Text("Join by address", fontWeight = FontWeight.Bold)
        Text(
            text = "The host's screen shows where it is, under Sharing.",
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
                onClick = { session.joinAt(typed, playerName) },
                enabled = typed.isNotBlank(),
            ) { Text("Connect") }
        }
    }
}

/** Guest: asked to join, and nobody has answered yet — or somebody has said no. */
@Composable
internal fun WaitingPane(
    modifier: Modifier,
    shared: ScorekeeperSession.State,
    onLeave: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when {
                shared.refused -> shared.message ?: "The host declined."
                shared.awaiting -> "Waiting for ${shared.hostName} to let you in…"
                else -> "Waiting for ${shared.hostName} to set up the sheet…"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (shared.awaiting) {
            Text(
                text = "They will see a request with your name on it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onLeave) { Text(if (shared.refused) "Back" else "Cancel") }
    }
}

/** Guest: the link is down, or was never up. Shown over a sheet that can still be read. */
@Composable
internal fun ConnectionBanner(shared: ScorekeeperSession.State, onSync: () -> Unit) {
    if (shared.connected) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = shared.message ?: "Not connected to the host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSync) { Text("Reconnect") }
    }
}

/**
 * Host: somebody is asking to join.
 *
 * Not dismissible by tapping outside it. A request that goes away on a stray
 * touch is a guest told nothing, and a request answered by accident is a stranger
 * with a pencil; either way it should take a button.
 */
@Composable
internal fun JoinRequestDialog(
    request: ScorekeeperSession.Request,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${request.name} wants to join") },
        text = { Text("Let them see this sheet and add scores to it?") },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

/** Host: where this phone is, who is on the sheet, and the way to stop sharing it. */
@Composable
internal fun ShareDialog(
    shared: ScorekeeperSession.State,
    onRemove: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sharing this sheet") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Others nearby can find it under Scorekeeper → Join a shared sheet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                shared.endpoint?.primary?.let { where ->
                    Text("This phone is at", style = MaterialTheme.typography.labelSmall)
                    Text(where, fontWeight = FontWeight.Bold)
                    Text(
                        text = "If the other phone doesn't find it on its own — which happens " +
                            "over a phone's hotspot — type that into its Join screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "Links received: ${shared.connections} · join requests: ${shared.hellos}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Divider()
                Text("On the sheet", fontWeight = FontWeight.Bold)
                if (shared.guests.isEmpty()) {
                    Text("Nobody else yet.", style = MaterialTheme.typography.bodySmall)
                }
                shared.guests.forEach { guest ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(guest.name)
                            Text(
                                text = if (guest.online) "Connected" else "Connection lost",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onRemove(guest.id) }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Stop sharing") } },
    )
}
