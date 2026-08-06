package org.prolibertate.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.update.UpdateChannel
import org.prolibertate.games.update.Updater

@Composable
fun SettingsScreen(
    env: AppEnv,
    settings: Settings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Null until the user touches the field, at which point their text takes
    // over for good. Keying this off the stored name instead meant every
    // keystroke re-seeded the field from what had just been written, so the
    // caret jumped and a cleared field refilled itself.
    var editedName by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var pendingRelease by remember { mutableStateOf<Updater.Release?>(null) }

    ScreenScaffold(title = "Settings", onBack = onBack) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingRow(
                title = "Sound",
                subtitle = "Card and chip effects",
            ) {
                Switch(
                    checked = settings.soundEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { env.settingsRepository.setSoundEnabled(enabled) }
                    },
                )
            }

            Divider()

            Column {
                Text("Animation speed", fontWeight = FontWeight.Bold)
                Text(
                    text = "${"%.1f".format(settings.animationSpeed)}× — " +
                        "how quickly cards move and the computer takes its turn",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = settings.animationSpeed,
                    onValueChange = { speed ->
                        scope.launch { env.settingsRepository.setAnimationSpeed(speed) }
                    },
                    valueRange = Settings.MIN_SPEED..Settings.MAX_SPEED,
                    // Half-step increments across 0.5x-2.0x.
                    steps = 5,
                )
            }

            Divider()

            Column {
                Text("Your name", fontWeight = FontWeight.Bold)
                Text(
                    "Shown to other players in a lobby",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = editedName ?: settings.playerName,
                    onValueChange = { entered ->
                        editedName = entered
                        scope.launch { env.settingsRepository.setPlayerName(entered) }
                    },
                    placeholder = { Text(Settings.DEFAULT_PLAYER_NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if ((editedName ?: settings.playerName).isBlank()) {
                    Text(
                        text = "Leave it empty and you'll show up as " +
                            "${Settings.DEFAULT_PLAYER_NAME}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Divider()

            Text("Updates", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Installed: v${env.updater.installedVersionName()} " +
                    "(${env.updater.installedChannel().label} channel).",
                style = MaterialTheme.typography.bodySmall,
            )

            Column {
                Text("Update channel", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = settings.updateChannel == channel,
                            onClick = {
                                scope.launch {
                                    env.settingsRepository.setUpdateChannel(channel)
                                }
                                updateStatus = null
                                pendingRelease = null
                            },
                            label = { Text(channel.label) },
                        )
                    }
                }
                Text(
                    text = settings.updateChannel.blurb + ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.updateChannel != env.updater.installedChannel()) {
                    // Each channel installs under its own package, so the other
                    // one arrives as a second app rather than replacing this
                    // one — no downgrade for Android to refuse.
                    Text(
                        text = "You're on a ${env.updater.installedChannel().label} build. " +
                            "The two channels install as separate apps, so this adds the " +
                            "${settings.updateChannel.label} build alongside this one and " +
                            "leaves it in place.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SettingRow(
                title = "Check on launch",
                subtitle = "Look for a newer release when the app starts",
            ) {
                Switch(
                    checked = settings.checkForUpdatesOnLaunch,
                    onCheckedChange = { enabled ->
                        scope.launch { env.settingsRepository.setCheckForUpdatesOnLaunch(enabled) }
                    },
                )
            }

            PrimaryAction(text = "Check for updates now") {
                scope.launch {
                    updateStatus = "Checking the ${settings.updateChannel.label} channel…"
                    when (val result = env.updater.check(settings.updateChannel)) {
                        is Updater.Result.UpToDate -> {
                            updateStatus =
                                "You're on the latest ${settings.updateChannel.label} build."
                            pendingRelease = null
                        }

                        is Updater.Result.Available -> {
                            pendingRelease = result.release
                            updateStatus = if (result.isChannelSwitch) {
                                "${result.release.tag} is the current " +
                                    "${result.release.channel.label} build."
                            } else {
                                "Version ${result.release.tag} is available."
                            }
                        }

                        is Updater.Result.Failed -> {
                            updateStatus = result.reason
                            pendingRelease = null
                        }
                    }
                }
            }

            pendingRelease?.let { release ->
                PrimaryAction(text = "Download and install ${release.tag}") {
                    scope.launch {
                        if (env.updater.needsInstallPermission()) {
                            updateStatus =
                                "Allow installs from this app, then tap install again."
                            env.updater.requestInstallPermission()
                        } else {
                            updateStatus = "Downloading…"
                            val error = env.updater.downloadAndInstall(release)
                            updateStatus = error ?: "Handing over to the installer…"
                        }
                    }
                }
            }

            updateStatus?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        control()
    }
}
