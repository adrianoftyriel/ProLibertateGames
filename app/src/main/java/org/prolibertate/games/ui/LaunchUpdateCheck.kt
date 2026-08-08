package org.prolibertate.games.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.prolibertate.games.update.Release
import org.prolibertate.games.update.launchOffer

/**
 * The check on launch, and the only thing it is allowed to say.
 *
 * Runs once per start of the app, if the setting is on, and speaks only when
 * there is a build to install — see [launchOffer]. Everything else is silence:
 * an up-to-date app has nothing to report, and a phone with no network has
 * nothing to apologise for.
 *
 * It reads the setting straight out of the store rather than from the state the
 * screens are drawn with. That state starts life as the defaults and is
 * replaced a moment later by what is actually stored, so a check driven off it
 * would fire on the default — on — before ever learning the setting had been
 * turned off. This waits for the real value instead.
 */
@Composable
fun LaunchUpdateCheck(env: AppEnv, visible: Boolean) {
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Kept apart from [status] on purpose. Inferring "still working" from there
    // being a message meant a failed download — which leaves its reason on
    // screen — disabled both buttons for good, and the dialog could not be shut.
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val stored = env.settingsRepository.settings.first()
        if (!stored.checkForUpdatesOnLaunch) return@LaunchedEffect
        release = launchOffer(env.updater.check())
    }

    // Held back behind the splash, so an update prompt cannot appear over the
    // tartan before the app itself has.
    val pending = release
    if (pending == null || dismissed || !visible) return

    AlertDialog(
        onDismissRequest = { if (!working) dismissed = true },
        title = { Text("Update available") },
        text = {
            Text(
                text = status ?: "${pending.tag} is out on the " +
                    "${pending.channel.label.lowercase()} channel. You're on " +
                    "v${env.updater.installedVersionName()}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    scope.launch {
                        working = true
                        if (env.updater.needsInstallPermission()) {
                            // Android has to be told this app may install one
                            // first. Say so and stand down: they will be looking
                            // at a system screen, not at this.
                            status = "Allow installs from this app, then check " +
                                "again in Settings."
                            env.updater.requestInstallPermission()
                        } else {
                            status = "Downloading ${pending.tag}…"
                            val error = env.updater.downloadAndInstall(pending)
                            status = error ?: "Handing over to the installer…"
                        }
                        working = false
                    }
                },
            ) { Text("Install") }
        },
        dismissButton = {
            TextButton(
                enabled = !working,
                onClick = { dismissed = true },
            ) { Text("Not now") }
        },
    )
}
