package org.prolibertate.games.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.prolibertate.games.game.cribbage.CribbageAi
import org.prolibertate.games.game.cribbage.CribbageMove
import org.prolibertate.games.game.cribbage.CribbagePhase
import org.prolibertate.games.game.cribbage.CribbageRules
import org.prolibertate.games.game.cribbage.CribbageState
import org.prolibertate.games.game.euchre.EuchreAi
import org.prolibertate.games.game.euchre.EuchreMove
import org.prolibertate.games.game.euchre.EuchrePhase
import org.prolibertate.games.game.euchre.EuchreRules
import org.prolibertate.games.game.euchre.EuchreState
import org.prolibertate.games.game.freecell.FreeCellMove
import org.prolibertate.games.game.freecell.FreeCellRules
import org.prolibertate.games.game.freecell.FreeCellState
import org.prolibertate.games.game.pyramid.PyramidMove
import org.prolibertate.games.game.pyramid.PyramidRules
import org.prolibertate.games.game.pyramid.PyramidState
import org.prolibertate.games.game.solitaire.FirstLegalAi
import org.prolibertate.games.game.spider.SpiderMove
import org.prolibertate.games.game.spider.SpiderRules
import org.prolibertate.games.game.spider.SpiderState
import org.prolibertate.games.game.klondike.KlondikeAi
import org.prolibertate.games.game.klondike.KlondikeMove
import org.prolibertate.games.game.klondike.KlondikeRules
import org.prolibertate.games.game.klondike.KlondikeState
import org.prolibertate.games.game.yahtzee.YahtzeeAi
import org.prolibertate.games.game.yahtzee.YahtzeeMove
import org.prolibertate.games.game.yahtzee.YahtzeeRules
import org.prolibertate.games.game.yahtzee.YahtzeeState
import org.prolibertate.games.game.hearts.HeartsAi
import org.prolibertate.games.game.hearts.HeartsMove
import org.prolibertate.games.game.hearts.HeartsPhase
import org.prolibertate.games.game.hearts.HeartsRules
import org.prolibertate.games.game.hearts.HeartsState
import org.prolibertate.games.game.pegsolitaire.PegSolitaireAi
import org.prolibertate.games.game.pegsolitaire.PegSolitaireMove
import org.prolibertate.games.game.pegsolitaire.PegSolitaireRules
import org.prolibertate.games.game.pegsolitaire.PegSolitaireState
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
import org.prolibertate.games.game.backgammon.BackgammonAi
import org.prolibertate.games.game.backgammon.BackgammonMove
import org.prolibertate.games.game.backgammon.BackgammonRules
import org.prolibertate.games.game.backgammon.BackgammonState
import org.prolibertate.games.game.checkers.CheckersAi
import org.prolibertate.games.game.checkers.CheckersMove
import org.prolibertate.games.game.checkers.CheckersRules
import org.prolibertate.games.game.checkers.CheckersState
import org.prolibertate.games.game.mastermind.MastermindAi
import org.prolibertate.games.game.mastermind.MastermindMove
import org.prolibertate.games.game.mastermind.MastermindRules
import org.prolibertate.games.game.mastermind.MastermindState
import org.prolibertate.games.game.morris.MorrisAi
import org.prolibertate.games.game.morris.MorrisMove
import org.prolibertate.games.game.morris.MorrisRules
import org.prolibertate.games.game.morris.MorrisState
import org.prolibertate.games.game.pirates.PiratesAi
import org.prolibertate.games.game.pirates.PiratesMove
import org.prolibertate.games.game.pirates.PiratesRules
import org.prolibertate.games.game.pirates.PiratesState
import org.prolibertate.games.game.president.PresidentAi
import org.prolibertate.games.game.president.PresidentMove
import org.prolibertate.games.game.president.PresidentPhase
import org.prolibertate.games.game.president.PresidentRules
import org.prolibertate.games.game.president.PresidentState
import org.prolibertate.games.game.sequence.SequenceAi
import org.prolibertate.games.game.sequence.SequenceMove
import org.prolibertate.games.game.sequence.SequenceRules
import org.prolibertate.games.game.sequence.SequenceState
import org.prolibertate.games.game.tayu.TayuAi
import org.prolibertate.games.game.tayu.TayuMove
import org.prolibertate.games.game.tayu.TayuRules
import org.prolibertate.games.game.tayu.TayuState
import org.prolibertate.games.game.wizard.WizardAi
import org.prolibertate.games.game.wizard.WizardMove
import org.prolibertate.games.game.wizard.WizardPhase
import org.prolibertate.games.game.wizard.WizardRules
import org.prolibertate.games.game.wizard.WizardState
import org.prolibertate.games.net.MatchController
import org.prolibertate.games.settings.Settings
import org.prolibertate.games.ui.game.BackgammonScreen
import org.prolibertate.games.ui.game.CheckersScreen
import org.prolibertate.games.ui.game.ChessScreen
import org.prolibertate.games.ui.game.CrazyEightsScreen
import org.prolibertate.games.ui.game.CribbageScreen
import org.prolibertate.games.ui.game.HeartsScreen
import org.prolibertate.games.ui.game.FreeCellScreen
import org.prolibertate.games.ui.game.KlondikeScreen
import org.prolibertate.games.ui.game.PyramidScreen
import org.prolibertate.games.ui.game.EuchreScreen
import org.prolibertate.games.ui.game.GolfScreen
import org.prolibertate.games.ui.game.KaiserScreen
import org.prolibertate.games.ui.game.MastermindScreen
import org.prolibertate.games.ui.game.MorrisScreen
import org.prolibertate.games.ui.game.PegSolitaireScreen
import org.prolibertate.games.ui.game.SpiderScreen
import org.prolibertate.games.ui.game.YahtzeeScreen
import org.prolibertate.games.ui.game.PiratesScreen
import org.prolibertate.games.ui.game.PresidentScreen
import org.prolibertate.games.ui.game.TRICK_HOLD_MILLIS
import org.prolibertate.games.ui.game.SequenceScreen
import org.prolibertate.games.ui.game.TayuScreen
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

        GameCatalog.CRIBBAGE -> {
            val controller = remember(route) {
                MatchController<CribbageState, CribbageMove>(
                    rules = CribbageRules,
                    ai = CribbageAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == CribbagePhase.SHOW) {
                            CribbageRules.nextHand(state)
                        } else {
                            null
                        }
                    },
                    // The show is the half of cribbage that is read rather than
                    // played, so the table stops on it until somebody says go on.
                    awaitsConfirmation = { state -> state.phase == CribbagePhase.SHOW },
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            CribbageScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.TAYU -> {
            val controller = remember(route) {
                MatchController<TayuState, TayuMove>(
                    rules = TayuRules,
                    // As with chess, the strength is part of the table's own
                    // options, so this does not have to decode anything.
                    ai = TayuAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    aiThinkingMillis = { settings.scaled(700L) },
                )
            }
            StartMatch(env, controller, route)
            TayuScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.CHECKERS -> {
            val controller = remember(route) {
                MatchController<CheckersState, CheckersMove>(
                    rules = CheckersRules,
                    ai = CheckersAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    aiThinkingMillis = { settings.scaled(250L) },
                )
            }
            StartMatch(env, controller, route)
            CheckersScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.BACKGAMMON -> {
            val controller = remember(route) {
                MatchController<BackgammonState, BackgammonMove>(
                    rules = BackgammonRules,
                    ai = BackgammonAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    // A checker at a time, so the pause is per checker rather
                    // than per turn — half of it, or a double would take an age.
                    aiThinkingMillis = { settings.scaled(350L) },
                )
            }
            StartMatch(env, controller, route)
            BackgammonScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.MASTERMIND -> {
            val controller = remember(route) {
                MatchController<MastermindState, MastermindMove>(
                    rules = MastermindRules,
                    ai = MastermindAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    aiThinkingMillis = { settings.scaled(900L) },
                )
            }
            StartMatch(env, controller, route)
            MastermindScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.PIRATES -> {
            val controller = remember(route) {
                MatchController<PiratesState, PiratesMove>(
                    rules = PiratesRules,
                    ai = PiratesAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    aiThinkingMillis = { settings.scaled(250L) },
                )
            }
            StartMatch(env, controller, route)
            PiratesScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.HEARTS -> {
            val controller = remember(route) {
                MatchController<HeartsState, HeartsMove>(
                    rules = HeartsRules,
                    ai = HeartsAi,
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    advanceIdle = { state ->
                        if (state.phase == HeartsPhase.ROUND_OVER) {
                            HeartsRules.startNextRound(state)
                        } else {
                            null
                        }
                    },
                    // What a round cost is read before the next deal.
                    awaitsConfirmation = { state -> state.phase == HeartsPhase.ROUND_OVER },
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
            HeartsScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.PEG_SOLITAIRE -> {
            // Playing again means a fresh board rather than a fresh round, and
            // it cannot go through advanceIdle: driveIdleSeats returns as soon
            // as rules.isFinished is true, and a peg board with no jumps left
            // genuinely is finished. Rebuilding the controller is the honest
            // way to deal again, and the board is deterministic, so the retry
            // is the same puzzle.
            var generation by remember(route) { mutableIntStateOf(0) }
            val controller = remember(route, generation) {
                MatchController<PegSolitaireState, PegSolitaireMove>(
                    rules = PegSolitaireRules,
                    // Never consulted while a person holds the only seat.
                    ai = PegSolitaireAi,
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                )
            }
            StartMatch(env, controller, route)
            PegSolitaireScreen(
                controller = controller,
                onRestart = { generation++ },
                onExit = onExit,
            )
        }

        GameCatalog.YAHTZEE -> {
            val controller = remember(route) {
                MatchController<YahtzeeState, YahtzeeMove>(
                    rules = YahtzeeRules,
                    ai = YahtzeeAi,
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    // Long enough to watch the dice land, short enough that a
                    // six-handed game is not an evening of waiting.
                    aiThinkingMillis = { settings.scaled(500L) },
                )
            }
            StartMatch(env, controller, route)
            YahtzeeScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
        }

        GameCatalog.KLONDIKE -> {
            // Dealing again rebuilds the controller, for the same reason peg
            // solitaire does: a blocked deal is genuinely finished, so
            // driveIdleSeats has already stopped and advanceIdle never runs.
            var generation by remember(route) { mutableIntStateOf(0) }
            val controller = remember(route, generation) {
                MatchController<KlondikeState, KlondikeMove>(
                    rules = KlondikeRules,
                    // Only ever asked for a hint; the seat is the player's.
                    ai = KlondikeAi,
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                )
            }
            StartMatch(env, controller, route)
            KlondikeScreen(
                controller = controller,
                onRestart = { generation++ },
                onExit = onExit,
            )
        }

        GameCatalog.FREECELL -> {
            var generation by remember(route) { mutableIntStateOf(0) }
            val controller = remember(route, generation) {
                MatchController<FreeCellState, FreeCellMove>(
                    rules = FreeCellRules,
                    ai = FirstLegalAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                )
            }
            StartMatch(env, controller, route)
            FreeCellScreen(controller = controller, onRestart = { generation++ }, onExit = onExit)
        }

        GameCatalog.SPIDER -> {
            var generation by remember(route) { mutableIntStateOf(0) }
            val controller = remember(route, generation) {
                MatchController<SpiderState, SpiderMove>(
                    rules = SpiderRules,
                    ai = FirstLegalAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                )
            }
            StartMatch(env, controller, route)
            SpiderScreen(controller = controller, onRestart = { generation++ }, onExit = onExit)
        }

        GameCatalog.PYRAMID -> {
            var generation by remember(route) { mutableIntStateOf(0) }
            val controller = remember(route, generation) {
                MatchController<PyramidState, PyramidMove>(
                    rules = PyramidRules,
                    ai = FirstLegalAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                )
            }
            StartMatch(env, controller, route)
            PyramidScreen(controller = controller, onRestart = { generation++ }, onExit = onExit)
        }

        GameCatalog.MORRIS -> {
            val controller = remember(route) {
                MatchController<MorrisState, MorrisMove>(
                    rules = MorrisRules,
                    // As with chess, the strength is part of the table's own
                    // options, so this does not have to decode anything.
                    ai = MorrisAi(),
                    config = route.config,
                    scope = scope,
                    role = role,
                    localSeats = setOf(route.localSeat),
                    primarySeat = route.localSeat,
                    // The search is the thinking time; anything added on top of
                    // it would only make the computer look slower than it is.
                    aiThinkingMillis = { settings.scaled(250L) },
                )
            }
            StartMatch(env, controller, route)
            MorrisScreen(controller = controller, localSeat = route.localSeat, onExit = onExit)
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
