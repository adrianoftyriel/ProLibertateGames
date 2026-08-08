package org.prolibertate.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.GameDescriptor
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig
import org.prolibertate.games.game.backgammon.BackgammonLevel
import org.prolibertate.games.game.backgammon.BackgammonOptions
import org.prolibertate.games.game.checkers.CheckersLevel
import org.prolibertate.games.game.checkers.CheckersOptions
import org.prolibertate.games.game.chess.ChessLevel
import org.prolibertate.games.game.chess.ChessOptions
import org.prolibertate.games.game.crazyeights.CrazyEightsOptions
import org.prolibertate.games.game.cribbage.CribbageOptions
import org.prolibertate.games.game.euchre.EuchreOptions
import org.prolibertate.games.game.golf.GolfOptions
import org.prolibertate.games.game.hearts.HeartsOptions
import org.prolibertate.games.game.klondike.KlondikeOptions
import org.prolibertate.games.game.freecell.FreeCellOptions
import org.prolibertate.games.game.pyramid.PyramidOptions
import org.prolibertate.games.game.spider.SpiderOptions
import org.prolibertate.games.game.yahtzee.YahtzeeOptions
import org.prolibertate.games.game.pegsolitaire.PegBoard
import org.prolibertate.games.game.pegsolitaire.PegGoal
import org.prolibertate.games.game.pegsolitaire.PegSolitaireOptions
import org.prolibertate.games.game.kaiser.KaiserOptions
import org.prolibertate.games.game.mastermind.MastermindLevel
import org.prolibertate.games.game.mastermind.MastermindOptions
import org.prolibertate.games.game.morris.MorrisLevel
import org.prolibertate.games.game.morris.MorrisOptions
import org.prolibertate.games.game.pirates.PiratesLevel
import org.prolibertate.games.game.pirates.PiratesOptions
import org.prolibertate.games.game.president.PresidentOptions
import org.prolibertate.games.game.sequence.SequenceOptions
import org.prolibertate.games.game.tayu.TayuLevel
import org.prolibertate.games.game.tayu.TayuOptions
import org.prolibertate.games.game.tayu.TayuTiles
import org.prolibertate.games.game.wizard.WizardOptions

private val setupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Choose who is playing and which house rules apply.
 *
 * Rule variations are per game and live here rather than in a global settings
 * screen, because they change what the engine does rather than how it looks.
 */
@Composable
fun GameSetupScreen(
    descriptor: GameDescriptor,
    playerName: String,
    onBack: () -> Unit,
    onPlayOffline: (TableConfig, Int) -> Unit,
    onHostOnline: (String) -> Unit,
) {
    var euchre by remember { mutableStateOf(EuchreOptions()) }
    var sequence by remember { mutableStateOf(SequenceOptions()) }
    var president by remember { mutableStateOf(PresidentOptions()) }
    var golf by remember { mutableStateOf(GolfOptions()) }
    var kaiser by remember { mutableStateOf(KaiserOptions()) }
    var crazyEights by remember { mutableStateOf(CrazyEightsOptions()) }
    var cribbage by remember { mutableStateOf(CribbageOptions()) }
    var wizard by remember { mutableStateOf(WizardOptions()) }
    var chess by remember { mutableStateOf(ChessOptions()) }
    var tayu by remember { mutableStateOf(TayuOptions()) }
    var morris by remember { mutableStateOf(MorrisOptions()) }
    var checkers by remember { mutableStateOf(CheckersOptions()) }
    var backgammon by remember { mutableStateOf(BackgammonOptions()) }
    var mastermind by remember { mutableStateOf(MastermindOptions()) }
    var pirates by remember { mutableStateOf(PiratesOptions()) }
    var hearts by remember { mutableStateOf(HeartsOptions()) }
    var pegs by remember { mutableStateOf(PegSolitaireOptions()) }
    var yahtzee by remember { mutableStateOf(YahtzeeOptions()) }
    var klondike by remember { mutableStateOf(KlondikeOptions()) }
    var freecell by remember { mutableStateOf(FreeCellOptions()) }
    var spider by remember { mutableStateOf(SpiderOptions()) }
    var pyramid by remember { mutableStateOf(PyramidOptions()) }
    // Not an engine option: which seat the local player takes. Seat 0 is
    // always White, so choosing Black means sitting in seat 1.
    var playWhite by remember { mutableStateOf(true) }
    // Pirates and Bulgars is not symmetrical, so which side you take is the
    // first thing about the game rather than a detail of it.
    var playPirates by remember { mutableStateOf(false) }

    val optionsJson = when (descriptor.id) {
        GameCatalog.EUCHRE -> setupJson.encodeToString(euchre)
        GameCatalog.SEQUENCE -> setupJson.encodeToString(sequence)
        GameCatalog.PRESIDENT -> setupJson.encodeToString(president)
        GameCatalog.GOLF -> setupJson.encodeToString(golf)
        GameCatalog.KAISER -> setupJson.encodeToString(kaiser)
        GameCatalog.CRAZY_EIGHTS -> setupJson.encodeToString(crazyEights)
        GameCatalog.CRIBBAGE -> setupJson.encodeToString(cribbage)
        GameCatalog.WIZARD -> setupJson.encodeToString(wizard)
        GameCatalog.CHESS -> setupJson.encodeToString(chess)
        GameCatalog.TAYU -> setupJson.encodeToString(tayu)
        GameCatalog.MORRIS -> setupJson.encodeToString(morris)
        GameCatalog.CHECKERS -> setupJson.encodeToString(checkers)
        GameCatalog.BACKGAMMON -> setupJson.encodeToString(backgammon)
        GameCatalog.MASTERMIND -> setupJson.encodeToString(mastermind)
        GameCatalog.PIRATES -> setupJson.encodeToString(pirates)
        GameCatalog.HEARTS -> setupJson.encodeToString(hearts)
        GameCatalog.PEG_SOLITAIRE -> setupJson.encodeToString(pegs)
        GameCatalog.YAHTZEE -> setupJson.encodeToString(yahtzee)
        GameCatalog.KLONDIKE -> setupJson.encodeToString(klondike)
        GameCatalog.FREECELL -> setupJson.encodeToString(freecell)
        GameCatalog.SPIDER -> setupJson.encodeToString(spider)
        GameCatalog.PYRAMID -> setupJson.encodeToString(pyramid)
        else -> "{}"
    }
    val seatCount = seatCountFor(descriptor.id, optionsJson)

    ScreenScaffold(title = descriptor.title, onBack = onBack) { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(descriptor.blurb, style = MaterialTheme.typography.bodyMedium)

            Divider()
            Text("House rules", fontWeight = FontWeight.Bold)

            when (descriptor.id) {
                GameCatalog.EUCHRE -> EuchreOptionsEditor(euchre) { euchre = it }
                GameCatalog.SEQUENCE -> SequenceOptionsEditor(sequence) { sequence = it }
                GameCatalog.PRESIDENT -> PresidentOptionsEditor(president) { president = it }
                GameCatalog.GOLF -> GolfOptionsEditor(golf) { golf = it }
                GameCatalog.KAISER -> KaiserOptionsEditor(kaiser) { kaiser = it }
                GameCatalog.CRAZY_EIGHTS -> CrazyEightsOptionsEditor(crazyEights) {
                    crazyEights = it
                }

                GameCatalog.CRIBBAGE -> CribbageOptionsEditor(cribbage) { cribbage = it }

                GameCatalog.WIZARD -> WizardOptionsEditor(wizard) { wizard = it }
                GameCatalog.CHESS -> ChessOptionsEditor(
                    options = chess,
                    playWhite = playWhite,
                    onChange = { chess = it },
                    onColour = { playWhite = it },
                )

                GameCatalog.TAYU -> TayuOptionsEditor(tayu) { tayu = it }

                GameCatalog.MORRIS -> MorrisOptionsEditor(morris) { morris = it }

                GameCatalog.CHECKERS -> CheckersOptionsEditor(checkers) { checkers = it }

                GameCatalog.BACKGAMMON -> BackgammonOptionsEditor(backgammon) { backgammon = it }

                GameCatalog.MASTERMIND -> MastermindOptionsEditor(mastermind) { mastermind = it }

                GameCatalog.PIRATES -> PiratesOptionsEditor(
                    options = pirates,
                    playPirates = playPirates,
                    onChange = { pirates = it },
                    onSide = { playPirates = it },
                )

                GameCatalog.HEARTS -> HeartsOptionsEditor(hearts) { hearts = it }

                GameCatalog.PEG_SOLITAIRE -> PegSolitaireOptionsEditor(pegs) { pegs = it }

                GameCatalog.YAHTZEE -> YahtzeeOptionsEditor(yahtzee) { yahtzee = it }

                GameCatalog.KLONDIKE -> KlondikeOptionsEditor(klondike) { klondike = it }

                GameCatalog.FREECELL -> FreeCellOptionsEditor(freecell) { freecell = it }

                GameCatalog.SPIDER -> SpiderOptionsEditor(spider) { spider = it }

                GameCatalog.PYRAMID -> PyramidOptionsEditor(pyramid) { pyramid = it }

                else -> Text("No options yet for this game.")
            }

            Divider()
            // A one-seat game has nobody to play against and no seat anyone
            // could join, so it is offered as itself rather than as a table
            // with every other chair filled by the computer.
            val solo = seatCount == 1
            Text(if (solo) "On your own" else "Opponents", fontWeight = FontWeight.Bold)
            Text(
                text = if (solo) {
                    "A puzzle for one. There is nobody to wait for and nothing to host."
                } else {
                    "Play now and every other seat is taken by the computer. Host a game " +
                        "instead and people can claim those seats as they join — whatever is " +
                        "still empty when you start stays computer-controlled."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            // Two games let the local player pick a seat rather than always
            // taking the first one: chess by colour, and Pirates and Bulgars by
            // which side of a very lopsided fight they fancy.
            val localSeat = when {
                descriptor.id == GameCatalog.CHESS && !playWhite -> 1
                descriptor.id == GameCatalog.PIRATES && playPirates -> 1
                else -> 0
            }
            PrimaryAction(text = if (solo) "Play" else "Play against the computer") {
                onPlayOffline(
                    offlineConfig(descriptor.id, optionsJson, seatCount, playerName, localSeat),
                    localSeat,
                )
            }
            if (!solo) {
                PrimaryAction(text = "Host a game for others to join") {
                    onHostOnline(optionsJson)
                }
            }
        }
    }
}

/** [localSeat] is the person at this device; every other seat starts as AI. */
private fun offlineConfig(
    gameId: String,
    optionsJson: String,
    seatCount: Int,
    playerName: String,
    localSeat: Int = 0,
): TableConfig = TableConfig(
    gameId = gameId,
    seats = (0 until seatCount).map { seat ->
        PlayerSlot(
            seat = seat,
            name = if (seat == localSeat) playerName else "Computer $seat",
            kind = if (seat == localSeat) PlayerKind.HUMAN_LOCAL else PlayerKind.AI,
            team = teamForSeat(gameId, seat, seatCount),
        )
    },
    optionsJson = optionsJson,
    // Seeded from the clock so consecutive games are not identical.
    seed = System.currentTimeMillis(),
)

/**
 * How many seats a table needs, read back out of the options the host chose.
 *
 * The lobby only carries the options as JSON, so this is the one place that
 * knows a six-handed Wizard game needs six seats rather than the catalogue's
 * minimum — seat the wrong number and the rules engine rejects the table.
 */
fun seatCountFor(gameId: String, optionsJson: String): Int {
    val fallback = GameCatalog.byId(gameId)?.minPlayers ?: 2
    return runCatching {
        when (gameId) {
            GameCatalog.EUCHRE, GameCatalog.KAISER -> 4
            GameCatalog.SEQUENCE ->
                setupJson.decodeFromString<SequenceOptions>(optionsJson).playerCount

            GameCatalog.PRESIDENT ->
                setupJson.decodeFromString<PresidentOptions>(optionsJson).playerCount

            GameCatalog.GOLF ->
                setupJson.decodeFromString<GolfOptions>(optionsJson).playerCount

            GameCatalog.CRAZY_EIGHTS ->
                setupJson.decodeFromString<CrazyEightsOptions>(optionsJson).playerCount

            GameCatalog.CRIBBAGE ->
                setupJson.decodeFromString<CribbageOptions>(optionsJson).playerCount

            GameCatalog.WIZARD ->
                setupJson.decodeFromString<WizardOptions>(optionsJson).playerCount

            GameCatalog.CHESS, GameCatalog.MORRIS, GameCatalog.CHECKERS,
            GameCatalog.BACKGAMMON, GameCatalog.MASTERMIND, GameCatalog.PIRATES -> 2

            GameCatalog.YAHTZEE ->
                setupJson.decodeFromString<YahtzeeOptions>(optionsJson).playerCount

            GameCatalog.TAYU ->
                setupJson.decodeFromString<TayuOptions>(optionsJson).playerCount

            else -> fallback
        }
    }.getOrDefault(fallback)
}

/**
 * Which side a seat is on.
 *
 * [seatCount] is here for the games that are only a partnership game at some
 * table sizes: cribbage seats two, three or four, and only the four-handed game
 * is played in pairs — reading `seat % 2` at a table of three would put the
 * first and third players on the same side, which is not a game anybody plays.
 */
fun teamForSeat(gameId: String, seat: Int, seatCount: Int): Int = when (gameId) {
    // Partners sit opposite each other. In Ta Yü that is also which pair of
    // edges you are running your rivers to: even seats north and south, odd
    // seats east and west.
    GameCatalog.EUCHRE, GameCatalog.KAISER, GameCatalog.SEQUENCE, GameCatalog.TAYU -> seat % 2
    GameCatalog.CRIBBAGE -> if (seatCount == 4) seat % 2 else seat
    // Everyone else plays for themselves.
    else -> seat
}

@Composable
private fun EuchreOptionsEditor(options: EuchreOptions, onChange: (EuchreOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Deck",
            values = listOf(24, 32),
            selected = options.deckSize,
            display = { "$it cards" },
            onSelect = { onChange(options.copy(deckSize = it)) },
        )
        ChipRow(
            label = "Game to",
            values = listOf(10, 11, 15),
            selected = options.pointsToWin,
            display = { "$it points" },
            onSelect = { onChange(options.copy(pointsToWin = it)) },
        )
        ToggleRow(
            title = "Stick the dealer",
            subtitle = "If everyone passes twice, the dealer must name trump",
            checked = options.stickTheDealer,
            onChange = { onChange(options.copy(stickTheDealer = it)) },
        )
        ToggleRow(
            title = "Allow going alone",
            subtitle = "A maker may sit their partner out for a bigger score",
            checked = options.allowGoingAlone,
            onChange = { onChange(options.copy(allowGoingAlone = it)) },
        )
    }
}

@Composable
private fun SequenceOptionsEditor(options: SequenceOptions, onChange: (SequenceOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Teams",
            values = listOf(2, 3),
            selected = options.teamCount,
            display = { "$it teams" },
            onSelect = { teams ->
                // Three teams race to a single sequence; two teams need two.
                onChange(options.copy(teamCount = teams, sequencesToWin = if (teams == 3) 1 else 2))
            },
        )
        ChipRow(
            label = "Players per team",
            values = listOf(1, 2, 3, 4),
            selected = options.playersPerTeam,
            display = { "$it" },
            onSelect = { onChange(options.copy(playersPerTeam = it)) },
        )
        ChipRow(
            label = "Sequences to win",
            values = listOf(1, 2, 3),
            selected = options.sequencesToWin,
            display = { "$it" },
            onSelect = { onChange(options.copy(sequencesToWin = it)) },
        )
        ToggleRow(
            title = "Dead card exchange",
            subtitle = "Swap a card whose squares are both already taken",
            checked = options.deadCardExchange,
            onChange = { onChange(options.copy(deadCardExchange = it)) },
        )
        Text(
            text = "${options.playerCount} players in total.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PresidentOptionsEditor(
    options: PresidentOptions,
    onChange: (PresidentOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(3, 4, 5, 6, 7),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Rounds",
            values = listOf(3, 5, 7),
            selected = options.roundsToPlay,
            display = { "$it" },
            onSelect = { onChange(options.copy(roundsToPlay = it)) },
        )
        ToggleRow(
            title = "Twos clear the pile",
            subtitle = "A two beats anything and takes the pile down",
            checked = options.twosClear,
            onChange = { onChange(options.copy(twosClear = it)) },
        )
        ToggleRow(
            title = "Four of a kind bombs",
            subtitle = "Four matching cards beat anything, whatever the pile",
            checked = options.fourOfAKindBomb,
            onChange = { onChange(options.copy(fourOfAKindBomb = it)) },
        )
        ToggleRow(
            title = "Card exchange",
            subtitle = "After each round the Scum hands their best cards to the President",
            checked = options.cardExchange,
            onChange = { onChange(options.copy(cardExchange = it)) },
        )
    }
}

@Composable
private fun GolfOptionsEditor(options: GolfOptions, onChange: (GolfOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(2, 3, 4, 5, 6),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Cards each",
            values = listOf(4, 6, 8, 9),
            selected = options.gridSize,
            display = { "$it" },
            onSelect = { size ->
                // Keep the opening reveals inside the new grid.
                onChange(
                    options.copy(
                        gridSize = size,
                        startingReveals = options.startingReveals.coerceAtMost(size),
                    )
                )
            },
        )
        ChipRow(
            label = "Holes",
            values = listOf(3, 6, 9),
            selected = options.holes,
            display = { "$it" },
            onSelect = { onChange(options.copy(holes = it)) },
        )
        ChipRow(
            label = "Seen at the start",
            values = (0..options.gridSize).toList(),
            selected = options.startingReveals,
            display = { "$it" },
            onSelect = { onChange(options.copy(startingReveals = it)) },
        )
        ToggleRow(
            title = "Line up the final putt",
            subtitle = "Throw a drawn card away without turning your last card over, " +
                "rather than being forced to close the hole",
            checked = options.lineUpFinalPutt,
            onChange = { onChange(options.copy(lineUpFinalPutt = it)) },
        )
        Text(
            text = "${options.rows} rows of ${options.cols}. Matching columns cancel out" +
                if (options.gridSize == 9) ", and so do matching rows." else ".",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun KaiserOptionsEditor(options: KaiserOptions, onChange: (KaiserOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Lowest opening bid",
            values = listOf(5, 6, 7, 8),
            selected = options.minimumBid,
            display = { "$it" },
            onSelect = { onChange(options.copy(minimumBid = it)) },
        )
        ChipRow(
            label = "Game to",
            values = listOf(32, 52, 62),
            selected = options.pointsToWin,
            display = { "$it points" },
            onSelect = { target ->
                // The floor moves with the target: a long game needs a long
                // rope before a team that keeps going down is written off.
                onChange(options.copy(pointsToWin = target, losingScore = -target))
            },
        )
        ToggleRow(
            title = "No-trump bids",
            subtitle = "Pays double, and costs double when it goes down",
            checked = options.allowNoTrump,
            onChange = { onChange(options.copy(allowNoTrump = it)) },
        )
        Text(
            text = "Four players in two partnerships. The 5♥ is worth five and the 3♠ " +
                "costs three, so a hand is worth ten points in all.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CrazyEightsOptionsEditor(
    options: CrazyEightsOptions,
    onChange: (CrazyEightsOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(2, 3, 4, 5, 6),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Cards each",
            values = listOf(0, 5, 7, 9),
            selected = options.startingHand,
            display = { if (it == 0) "standard" else "$it" },
            onSelect = { onChange(options.copy(startingHand = it)) },
        )
        ChipRow(
            label = "Rounds",
            values = listOf(1, 3, 5),
            selected = options.roundsToPlay,
            display = { "$it" },
            onSelect = { onChange(options.copy(roundsToPlay = it)) },
        )
        ToggleRow(
            title = "Draw until you can play",
            subtitle = "Otherwise you draw a single card and the turn passes on",
            checked = options.drawUntilPlayable,
            onChange = { onChange(options.copy(drawUntilPlayable = it)) },
        )
        Text(
            text = "Standard is seven cards heads-up and five otherwise. Eights are wild " +
                "and cost fifty if you are caught holding one.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CribbageOptionsEditor(options: CribbageOptions, onChange: (CribbageOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(2, 3, 4),
            selected = options.playerCount,
            display = { count ->
                when (count) {
                    2 -> "2, head to head"
                    3 -> "3, every hand for itself"
                    else -> "4, in partnerships"
                }
            },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Game to",
            values = listOf(61, 121),
            selected = options.pointsToWin,
            display = { if (it == 61) "61, once round" else "121, twice round" },
            onSelect = { onChange(options.copy(pointsToWin = it)) },
        )
        ToggleRow(
            title = "Call the skunk",
            subtitle = "Say so at the finish when the loser never got off the second street",
            checked = options.countSkunks,
            onChange = { onChange(options.copy(countSkunks = it)) },
        )
        val shortCrib = if (options.playerCount == 3) {
            ", with a fourth dealt straight into it"
        } else {
            ""
        }
        Text(
            text = "${options.dealSize} cards each, and everyone lays " +
                "${options.layAwaySize} away to the dealer's crib$shortCrib. Hands are " +
                "counted from the dealer's left, so a game can be won before the " +
                "dealer counts at all.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WizardOptionsEditor(options: WizardOptions, onChange: (WizardOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(3, 4, 5, 6),
            selected = options.playerCount,
            display = { "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Rounds",
            values = listOf(0, 5, 10),
            selected = options.rounds,
            display = { if (it == 0) "full game" else "$it" },
            onSelect = { onChange(options.copy(rounds = it)) },
        )
        ToggleRow(
            title = "Screw the dealer",
            subtitle = "The dealer may not make the bids add up, so somebody always misses",
            checked = options.screwTheDealer,
            onChange = { onChange(options.copy(screwTheDealer = it)) },
        )
        Text(
            text = "A full game deals the whole sixty-card pack out: " +
                "${options.totalRounds()} rounds at this table.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ChessOptionsEditor(
    options: ChessOptions,
    playWhite: Boolean,
    onChange: (ChessOptions) -> Unit,
    onColour: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "You play",
            values = listOf(true, false),
            selected = playWhite,
            display = { if (it) "White" else "Black" },
            onSelect = onColour,
        )
        ChipRow(
            label = "Opponent",
            values = ChessLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ToggleRow(
            title = "Fifty-move rule",
            subtitle = "Fifty moves each with no capture and no pawn move is a draw",
            checked = options.fiftyMoveRule,
            onChange = { onChange(options.copy(fiftyMoveRule = it)) },
        )
        ToggleRow(
            title = "Threefold repetition",
            subtitle = "The same position three times over is a draw",
            checked = options.threefoldRepetition,
            onChange = { onChange(options.copy(threefoldRepetition = it)) },
        )
        Text(
            text = "Full rules: castling, en passant, promotion, stalemate, and a draw " +
                "when neither side has enough material to mate.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CheckersOptionsEditor(options: CheckersOptions, onChange: (CheckersOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Opponent",
            values = CheckersLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ToggleRow(
            title = "Flying kings",
            subtitle = "A king slides the length of a diagonal and takes from a distance, " +
                "as in the international game",
            checked = options.flyingKings,
            onChange = { onChange(options.copy(flyingKings = it)) },
        )
        ToggleRow(
            title = "Crowning ends the turn",
            subtitle = "A man jumping into the back row stops there. Turn this off and it " +
                "carries on jumping as a man",
            checked = options.crowningEndsTheTurn,
            onChange = { onChange(options.copy(crowningEndsTheTurn = it)) },
        )
        ToggleRow(
            title = "Threefold repetition",
            subtitle = "The same position three times over is a draw",
            checked = options.threefoldRepetition,
            onChange = { onChange(options.copy(threefoldRepetition = it)) },
        )
        Text(
            text = "Captures are always compulsory, and a multiple jump is one turn — " +
                "there is no version of this game where you may stop halfway.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BackgammonOptionsEditor(
    options: BackgammonOptions,
    onChange: (BackgammonOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Opponent",
            values = BackgammonLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ToggleRow(
            title = "Count gammons",
            subtitle = "A loser who bore nothing off has lost double, and one still on the " +
                "bar has lost treble",
            checked = options.countGammons,
            onChange = { onChange(options.copy(countGammons = it)) },
        )
        Text(
            text = "One game rather than a match, and no doubling cube: there is nothing " +
                "to double when nothing is being kept.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MastermindOptionsEditor(
    options: MastermindOptions,
    onChange: (MastermindOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Opponent",
            values = MastermindLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ChipRow(
            label = "Colours",
            values = listOf(6, 8),
            selected = options.colours,
            display = { "$it" },
            onSelect = { colours ->
                onChange(
                    options.copy(
                        colours = colours,
                        // A code of distinct colours cannot be longer than the
                        // number of colours there are.
                        length = if (options.allowDuplicates) {
                            options.length
                        } else {
                            options.length.coerceAtMost(colours)
                        },
                    )
                )
            },
        )
        ChipRow(
            label = "Pegs",
            values = listOf(3, 4, 5),
            selected = options.length,
            display = { "$it" },
            onSelect = { onChange(options.copy(length = it)) },
        )
        ChipRow(
            label = "Guesses each",
            values = listOf(8, 10, 12),
            selected = options.maxGuesses,
            display = { "$it" },
            onSelect = { onChange(options.copy(maxGuesses = it)) },
        )
        ToggleRow(
            title = "Repeated colours",
            subtitle = "A colour may appear more than once in the code",
            checked = options.allowDuplicates,
            onChange = { onChange(options.copy(allowDuplicates = it)) },
        )
        Text(
            text = "${options.codeSpace()} possible codes. Both players are set one and " +
                "both are breaking one, so cracking it in the same round as your " +
                "opponent is a draw.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PiratesOptionsEditor(
    options: PiratesOptions,
    playPirates: Boolean,
    onChange: (PiratesOptions) -> Unit,
    onSide: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "You play",
            values = listOf(false, true),
            selected = playPirates,
            display = { if (it) "the two pirates" else "the twenty-four Bulgars" },
            onSelect = onSide,
        )
        ChipRow(
            label = "Opponent",
            values = PiratesLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ToggleRow(
            title = "A pirate who can take must take",
            subtitle = "The rule that huffing exists to enforce, applied directly",
            checked = options.captureIsCompulsory,
            onChange = { onChange(options.copy(captureIsCompulsory = it)) },
        )
        ToggleRow(
            title = "Bulgars may not retreat",
            subtitle = "They press towards the stronghold or across, and never back",
            checked = options.bulgarsMayNotRetreat,
            onChange = { onChange(options.copy(bulgarsMayNotRetreat = it)) },
        )
        Text(
            text = "The two sides are not playing the same game. The pirates take by " +
                "jumping and win by cutting the Bulgars below the nine it takes to fill " +
                "the stronghold; the Bulgars cannot take anything at all, and win by " +
                "filling it or by leaving the pirates nowhere to go.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MorrisOptionsEditor(options: MorrisOptions, onChange: (MorrisOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Opponent",
            values = MorrisLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ChipRow(
            label = "Pieces each",
            values = listOf(3, 6, 9),
            selected = options.piecesEach,
            display = { "$it" },
            onSelect = { onChange(options.copy(piecesEach = it)) },
        )
        ToggleRow(
            title = "Flying on three",
            subtitle = "A player down to three pieces may jump anywhere rather than " +
                "step along a line",
            checked = options.flyingWithThree,
            onChange = { onChange(options.copy(flyingWithThree = it)) },
        )
        ToggleRow(
            title = "Threefold repetition",
            subtitle = "The same position three times over is a draw",
            checked = options.threefoldRepetition,
            onChange = { onChange(options.copy(threefoldRepetition = it)) },
        )
        ChipRow(
            label = "Draw with no mill for",
            values = listOf(0, 50, 100, 200),
            selected = options.plyLimitWithoutMill,
            display = { if (it == 0) "never" else "$it moves" },
            onSelect = { onChange(options.copy(plyLimitWithoutMill = it)) },
        )
        Text(
            text = "Nine each is the game proper; fewer is a shorter game on the same " +
                "board rather than the smaller boards Three and Six Men's Morris are " +
                "really played on. Flying is the usual rule — without it a player " +
                "down to three is squeezed out, and with it they can be very hard " +
                "to finish off.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TayuOptionsEditor(options: TayuOptions, onChange: (TayuOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = listOf(2, 4),
            selected = options.playerCount,
            display = { if (it == 2) "2, head to head" else "4, in partnerships" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ChipRow(
            label = "Opponent",
            values = TayuLevel.entries.toList(),
            selected = options.level,
            display = { it.label },
            onSelect = { onChange(options.copy(level = it)) },
        )
        ChipRow(
            label = "Tiles",
            values = listOf(2, 3, 4),
            selected = options.tileCopies,
            display = { copies ->
                val count = copies * TayuTiles.all.size
                when (copies) {
                    3 -> "$count, as reissued"
                    4 -> "$count, as first published"
                    else -> "$count, a short game"
                }
            },
            onSelect = { onChange(options.copy(tileCopies = it)) },
        )
        Text(
            text = "There are ${TayuTiles.all.size} different tiles, and the bag holds " +
                "${options.tileCopies} of each. Even seats run their rivers north and " +
                "south, odd seats east and west, and a side's score is one edge " +
                "multiplied by the other — so reaching only one of them is worth nothing.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    values: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // Wraps rather than running off the edge: some of these rows carry ten
        // chips, and a chip you cannot see is a setting you cannot change.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(display(value)) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HeartsOptionsEditor(options: HeartsOptions, onChange: (HeartsOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Game ends at",
            values = listOf(50, 100),
            selected = options.targetScore,
            display = { "$it points" },
            onSelect = { onChange(options.copy(targetScore = it)) },
        )
        ToggleRow(
            title = "Shooting the moon",
            subtitle = "Take all twenty-six and score nothing, while everyone else takes " +
                "the lot. Switched off, a moon is simply the worst hand at the table.",
            checked = options.allowShootTheMoon,
            onChange = { onChange(options.copy(allowShootTheMoon = it)) },
        )
    }
}

@Composable
private fun PegSolitaireOptionsEditor(
    options: PegSolitaireOptions,
    onChange: (PegSolitaireOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Board",
            values = PegBoard.entries.toList(),
            selected = options.board,
            display = { it.label },
            // The opening hole is cleared as well: a hole that exists on the old
            // board may be a missing corner on the new one, and the options
            // refuse to be built that way.
            onSelect = { onChange(options.copy(board = it, startEmpty = null)) },
        )
        ChipRow(
            label = "Finish",
            values = PegGoal.entries.toList(),
            selected = options.goal,
            display = { it.label },
            onSelect = { onChange(options.copy(goal = it)) },
        )
    }
}

@Composable
private fun YahtzeeOptionsEditor(options: YahtzeeOptions, onChange: (YahtzeeOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Players",
            values = (1..6).toList(),
            selected = options.playerCount,
            display = { if (it == 1) "On your own" else "$it" },
            onSelect = { onChange(options.copy(playerCount = it)) },
        )
        ToggleRow(
            title = "Second Yahtzee scores a hundred",
            subtitle = "Only once the Yahtzee box holds the fifty. A zero written there " +
                "earlier forfeits it, as the printed rule has it.",
            checked = options.yahtzeeBonus,
            onChange = { onChange(options.copy(yahtzeeBonus = it)) },
        )
    }
}

@Composable
private fun KlondikeOptionsEditor(options: KlondikeOptions, onChange: (KlondikeOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Turn from the stock",
            values = listOf(1, 3),
            selected = options.drawCount,
            display = { "$it at a time" },
            onSelect = { onChange(options.copy(drawCount = it)) },
        )
        ChipRow(
            label = "Turning the waste back over",
            values = listOf(null, 0, 1, 2),
            selected = options.redealLimit,
            display = {
                when (it) {
                    null -> "As often as you like"
                    0 -> "Never — one pass"
                    else -> "$it times"
                }
            },
            onSelect = { onChange(options.copy(redealLimit = it)) },
        )
        ToggleRow(
            title = "Only a king fills a space",
            subtitle = "Turning this off lets any card into an empty column, which makes " +
                "far more deals winnable.",
            checked = options.kingsOnlyInSpaces,
            onChange = { onChange(options.copy(kingsOnlyInSpaces = it)) },
        )
    }
}

@Composable
private fun FreeCellOptionsEditor(options: FreeCellOptions, onChange: (FreeCellOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            label = "Free cells",
            values = (1..6).toList(),
            selected = options.freeCells,
            display = { "$it" },
            onSelect = { onChange(options.copy(freeCells = it)) },
        )
        ToggleRow(
            title = "Move a run in one go",
            subtitle = "The rules move a run one card at a time through the cells. This " +
                "does the shuffling for you, which is bookkeeping rather than a decision.",
            checked = options.allowSupermoves,
            onChange = { onChange(options.copy(allowSupermoves = it)) },
        )
    }
}

@Composable
private fun SpiderOptionsEditor(options: SpiderOptions, onChange: (SpiderOptions) -> Unit) {
    ChipRow(
        label = "Suits",
        values = listOf(1, 2, 4),
        selected = options.suits,
        display = {
            when (it) {
                1 -> "One — a pastime"
                2 -> "Two"
                else -> "Four — a fight"
            }
        },
        onSelect = { onChange(options.copy(suits = it)) },
    )
}

@Composable
private fun PyramidOptionsEditor(options: PyramidOptions, onChange: (PyramidOptions) -> Unit) {
    ChipRow(
        label = "Turns through the pack",
        values = listOf(0, 1, 2, 3),
        selected = options.redeals,
        display = { if (it == 0) "One pass only" else "$it turns back" },
        onSelect = { onChange(options.copy(redeals = it)) },
    )
}
