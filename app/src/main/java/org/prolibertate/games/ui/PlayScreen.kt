package org.prolibertate.games.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.chess.ChessAi
import org.prolibertate.games.game.chess.ChessMove
import org.prolibertate.games.game.chess.ChessRules
import org.prolibertate.games.game.chess.ChessState
import org.prolibertate.games.game.crazyeights.CrazyEightsAi
import org.prolibertate.games.game.crazyeights.CrazyEightsMove
import org.prolibertate.games.game.crazyeights.CrazyEightsPhase
import org.prolibertate.games.game.crazyeights.CrazyEightsRules
import org.prolibertate.games.game.crazyeights.CrazyEightsState
import org.prolibertate.games.game.euchre.EuchreAi
import org.prolibertate.games.game.euchre.EuchreMove
import org.prolibertate.games.game.euchre.EuchrePhase
import org.prolibertate.games.game.euchre.EuchreRules
import org.prolibertate.games.game.euchre.EuchreState
import org.prolibertate.games.game.golf.GolfAi
import org.prolibertate.games.game.golf.GolfMove
import org.prolibertate.games.game.golf.GolfPhase
import org.prolibertate.games.game.golf.GolfRules
import org.prolibertate.games.game.golf.GolfState
import org.prolibertate.games.game.kaiser.KaiserAi
import org.prolibertate.games.game.kaiser.KaiserMove
import org.prolibertate.games.game.kaiser.KaiserPhase
import org.prolibertate.games.game.kaiser.KaiserRules
import org.prolibertate.games.game.kaiser.KaiserState
import org.prolibertate.games.game.president.PresidentAi
import org.prolibertate.games.game.president.PresidentMove
import org.prolibertate.games.game.president.PresidentPhase
import org.prolibertate.games.game.president.PresidentRules
import org.prolibertate.games.game.president.PresidentState
import org.prolibertate.games.game.sequence.SequenceAi
import org.prolibertate.games.game.sequence.SequenceMove
import org.prolibertate.games.game.sequence.SequenceRules
import org.prolibertate.games.game.sequence.SequenceState
import org.prolibertate.games.game.wizard.WizardAi
import org.prolibertate.games.game.wizard.WizardMove
import org.prolibertate.games.game.wizard.WizardPhase
import org.prolibertate.games.game.wizard.WizardRules
import org.prolibertate.games.game.wizard.WizardState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.ui.game.ChessScreen
import org.prolibertate.games.ui.game.CrazyEightsScreen
import org.prolibertate.games.ui.game.EuchreScreen
import org.prolibertate.games.ui.game.GolfScreen
import org.prolibertate.games.ui.game.KaiserScreen
import org.prolibertate.games.ui.game.PresidentScreen
import org.prolibertate.games.ui.game.TRICK_HOLD_MILLIS
import org.prolibertate.games.ui.game.SequenceScreen
import org.prolibertate.games.ui.game.WizardScreen

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

        GameCatalog.PRESIDENT -> {
            val controller = remember(route) {
                MatchController<PresidentState, PresidentMove>(
                    rules = PresidentRules,
                    ai = PresidentAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == PresidentPhase.ROUND_OVER) {
                            PresidentRules.nextRound(state)
                        } else {
                            null
                        }
                    },
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            PresidentScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.GOLF -> {
            val controller = remember(route) {
                MatchController<GolfState, GolfMove>(
                    rules = GolfRules,
                    ai = GolfAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == GolfPhase.HOLE_OVER) GolfRules.nextHole(state) else null
                    },
                    // The score card is read before the next hole is dealt.
                    awaitsConfirmation = { state -> state.phase == GolfPhase.HOLE_OVER },
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            GolfScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.KAISER -> {
            val controller = remember(route) {
                MatchController<KaiserState, KaiserMove>(
                    rules = KaiserRules,
                    ai = KaiserAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == KaiserPhase.HAND_OVER) {
                            KaiserRules.nextHand(state)
                        } else {
                            null
                        }
                    },
                    aiThinkingMillis = { settings.scaled(700L) },
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
            KaiserScreen(
                controller = controller,
                localSeat = route.localSeat,
                trickHoldMillis = settings.scaled(TRICK_HOLD_MILLIS),
                onExit = onExit,
            )
        }

        GameCatalog.WIZARD -> {
            val controller = remember(route) {
                MatchController<WizardState, WizardMove>(
                    rules = WizardRules,
                    ai = WizardAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == WizardPhase.ROUND_OVER) {
                            WizardRules.nextRound(state)
                        } else {
                            null
                        }
                    },
                    // Bids against tricks taken are read before the next deal.
                    awaitsConfirmation = { state -> state.phase == WizardPhase.ROUND_OVER },
                    aiThinkingMillis = { settings.scaled(700L) },
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
            WizardScreen(
                controller = controller,
                localSeat = route.localSeat,
                trickHoldMillis = settings.scaled(TRICK_HOLD_MILLIS),
                onExit = onExit,
            )
        }

        GameCatalog.CRAZY_EIGHTS -> {
            val controller = remember(route) {
                MatchController<CrazyEightsState, CrazyEightsMove>(
                    rules = CrazyEightsRules,
                    ai = CrazyEightsAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == CrazyEightsPhase.ROUND_OVER) {
                            CrazyEightsRules.nextRound(state)
                        } else {
                            null
                        }
                    },
                    awaitsConfirmation = { state ->
                        state.phase == CrazyEightsPhase.ROUND_OVER
                    },
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            CrazyEightsScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.CHESS -> {
            val controller = remember(route) {
                MatchController<ChessState, ChessMove>(
                    rules = ChessRules,
                    // Strength comes from the table's own options, so the setup
                    // screen sets it without this having to decode anything.
                    ai = ChessAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    // The search is the thinking time; adding more on top of it
                    // would only make the computer look slower than it is.
                    aiThinkingMillis = { settings.scaled(250L) },
                )
            }
            StartMatch(env, controller, route)
            ChessScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
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
