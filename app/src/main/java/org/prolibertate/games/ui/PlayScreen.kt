package org.prolibertate.games.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.euchre.EuchreAi
import org.prolibertate.games.game.euchre.EuchreMove
import org.prolibertate.games.game.euchre.EuchrePhase
import org.prolibertate.games.game.euchre.EuchreRules
import org.prolibertate.games.game.euchre.EuchreState
import org.prolibertate.games.game.sequence.SequenceAi
import org.prolibertate.games.game.sequence.SequenceMove
import org.prolibertate.games.game.sequence.SequenceRules
import org.prolibertate.games.game.sequence.SequenceState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.ui.game.EuchreScreen
import org.prolibertate.games.ui.game.TRICK_HOLD_MILLIS
import org.prolibertate.games.ui.game.SequenceScreen

/**
 * Builds the match for the chosen game and hands it to that game's screen.
 *
 * Offline play and hosting are the same thing here — an offline game is simply
 * a host with no connections — so there is one path through this code whether
 * or not anyone else is at the table.
 */
@Composable
fun PlayScreen(
    env: AppEnv,
    route: Route.Play,
    settings: Settings,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val role = if (route.hosting) MatchController.Role.HOST else MatchController.Role.CLIENT

    when (route.gameId) {
        GameCatalog.EUCHRE -> {
            val controller = remember(route) {
                MatchController<EuchreState, EuchreMove>(
                    rules = EuchreRules,
                    ai = EuchreAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    // A scored hand pauses on screen, then the next one is dealt.
                    advanceIdle = { state ->
                        if (state.phase == EuchrePhase.HAND_OVER) {
                            EuchreRules.nextHand(state)
                        } else {
                            null
                        }
                    },
                    aiThinkingMillis = { settings.scaled(700L) },
                    // Keep the finished trick on the table before the next
                    // card lands on top of it.
                    holdBeforeNextMove = { state ->
                        if (state.completedTrick.isNotEmpty()) {
                            settings.scaled(TRICK_HOLD_MILLIS)
                        } else {
                            0L
                        }
                    },
                )
            }
            StartMatch(env, controller, route)
            EuchreScreen(
                controller = controller,
                localSeat = route.localSeat,
                trickHoldMillis = settings.scaled(TRICK_HOLD_MILLIS),
                onExit = onExit,
            )
        }

        GameCatalog.SEQUENCE -> {
            val controller = remember(route) {
                MatchController<SequenceState, SequenceMove>(
                    rules = SequenceRules,
                    ai = SequenceAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            SequenceScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        else -> Text("That game isn't playable yet.")
    }
}

@Composable
private fun <S : Any, M : Any> StartMatch(
    env: AppEnv,
    controller: MatchController<S, M>,
    route: Route.Play,
) {
    LaunchedEffect(controller) {
        if (route.hosting) {
            controller.startAsHost(env.lobby.hostConnections)
        } else {
            env.lobby.clientConnection?.let { controller.startAsClient(it) }
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
}
