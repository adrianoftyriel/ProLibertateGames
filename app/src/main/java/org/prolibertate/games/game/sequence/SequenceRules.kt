package org.prolibertate.games.game.sequence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.GameCatalog
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.game.cards.Decks
import org.prolibertate.games.game.cards.shuffledWith
import org.prolibertate.games.game.engine.GameRules
import org.prolibertate.games.game.engine.TableConfig
import kotlin.random.Random

/**
 * Sequence as a pure state machine. See RULES-sequence.md for the ruleset and
 * the caveat about the board layout.
 */
object SequenceRules : GameRules<SequenceState, SequenceMove> {

    override val gameId: String = GameCatalog.SEQUENCE

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The four straight lines a run of five can follow. */
    private val DIRECTIONS = listOf(
        1 to 0,   // horizontal
        0 to 1,   // vertical
        1 to 1,   // diagonal down-right
        1 to -1,  // diagonal down-left
    )

    override fun initialState(config: TableConfig): SequenceState {
        val options = json.decodeFromString<SequenceOptions>(config.optionsJson)
        require(config.seats.size == options.playerCount) {
            "Seat count ${config.seats.size} does not match ${options.playerCount} players"
        }

        val random = Random(config.seed)
        var deck = Decks.double52().shuffledWith(random)
        val handSize = handSizeFor(options.playerCount)
        val hands = mutableListOf<List<Card>>()
        for (seat in 0 until options.playerCount) {
            hands += deck.take(handSize)
            deck = deck.drop(handSize)
        }

        // Seats alternate around the table so partners are never adjacent.
        val teams = (0 until options.playerCount).map { it % options.teamCount }

        return SequenceState(
            options = options,
            seed = config.seed,
            teams = teams,
            chips = List(BOARD_CELLS) { NO_TEAM },
            locked = List(BOARD_CELLS) { false },
            hands = hands,
            handCounts = hands.map { it.size },
            drawPile = deck,
            discardPile = emptyList(),
            turn = 0,
            sequencesByTeam = List(options.teamCount) { 0 },
            winner = null,
            phase = SequencePhase.PLAYING,
            exchangedThisTurn = false,
            lastPlacedCell = null,
            log = listOf("Sequence — ${options.teamCount} teams, first to ${options.sequencesToWin}."),
        )
    }

    override fun currentSeat(state: SequenceState): Int? =
        if (state.phase == SequencePhase.PLAYING) state.turn else null

    // -----------------------------------------------------------------------
    // Legal moves
    // -----------------------------------------------------------------------

    override fun legalMoves(state: SequenceState, seat: Int): List<SequenceMove> {
        if (currentSeat(state) != seat) return emptyList()
        val team = state.teamOf(seat)
        val moves = mutableListOf<SequenceMove>()

        for (card in state.hands[seat].distinct()) {
            when {
                isTwoEyedJack(card) ->
                    // Wild: any empty square on the board.
                    for (cell in 0 until BOARD_CELLS) {
                        if (!SequenceBoard.isCorner(cell) && state.chips[cell] == NO_TEAM) {
                            moves += PlaceChip(card, cell)
                        }
                    }

                isOneEyedJack(card) ->
                    // Removes one opposing chip that is not part of a sequence.
                    for (cell in 0 until BOARD_CELLS) {
                        val occupant = state.chips[cell]
                        if (occupant != NO_TEAM && occupant != team && !state.locked[cell]) {
                            moves += RemoveChip(card, cell)
                        }
                    }

                else -> {
                    val squares = SequenceBoard.squaresByCard[card].orEmpty()
                    val open = squares.filter { state.chips[it] == NO_TEAM }
                    if (open.isEmpty()) {
                        // Dead card: both printed squares are taken.
                        if (state.options.deadCardExchange && !state.exchangedThisTurn) {
                            moves += ExchangeDeadCard(card)
                        }
                    } else {
                        open.forEach { moves += PlaceChip(card, it) }
                    }
                }
            }
        }
        return moves
    }

    // -----------------------------------------------------------------------
    // Move application
    // -----------------------------------------------------------------------

    override fun applyMove(state: SequenceState, seat: Int, move: SequenceMove): SequenceState {
        require(currentSeat(state) == seat) { "Not seat $seat's turn" }
        require(move in legalMoves(state, seat)) { "Illegal move $move for seat $seat" }
        return when (move) {
            is PlaceChip -> applyPlace(state, seat, move)
            is RemoveChip -> applyRemove(state, seat, move)
            is ExchangeDeadCard -> applyExchange(state, seat, move)
        }
    }

    private fun applyPlace(state: SequenceState, seat: Int, move: PlaceChip): SequenceState {
        val team = state.teamOf(seat)
        val chips = state.chips.toMutableList()
        chips[move.cell] = team

        val locked = state.locked.toMutableList()
        val newSequences = claimSequences(chips, locked, move.cell, team)

        val sequences = state.sequencesByTeam.toMutableList()
        sequences[team] = sequences[team] + newSequences

        val afterCard = discardAndDraw(state, seat, move.card)
        val won = sequences[team] >= state.options.sequencesToWin

        return afterCard.copy(
            chips = chips,
            locked = locked,
            sequencesByTeam = sequences,
            lastPlacedCell = move.cell,
            winner = if (won) team else null,
            phase = if (won) SequencePhase.GAME_OVER else SequencePhase.PLAYING,
            log = afterCard.log + buildString {
                append("Seat $seat plays ${move.card.label}")
                if (newSequences > 0) append(" — sequence!")
                append(".")
            },
        ).advanceTurn(skip = won)
    }

    private fun applyRemove(state: SequenceState, seat: Int, move: RemoveChip): SequenceState {
        val chips = state.chips.toMutableList()
        chips[move.cell] = NO_TEAM
        return discardAndDraw(state, seat, move.card)
            .copy(
                chips = chips,
                log = state.log + "Seat $seat removes a chip with ${move.card.label}.",
            )
            .advanceTurn(skip = false)
    }

    /** Swapping a dead card costs the card but not the turn. */
    private fun applyExchange(
        state: SequenceState,
        seat: Int,
        move: ExchangeDeadCard,
    ): SequenceState = discardAndDraw(state, seat, move.card).copy(
        exchangedThisTurn = true,
        log = state.log + "Seat $seat swaps dead card ${move.card.label}.",
    )

    /** Moves the played card to the discard pile and replaces it from the deck. */
    private fun discardAndDraw(
        state: SequenceState,
        seat: Int,
        card: Card,
    ): SequenceState {
        val hands = state.hands.toMutableList()
        hands[seat] = hands[seat] - card

        var draw = state.drawPile
        var discard = state.discardPile

        if (draw.isEmpty() && discard.isNotEmpty()) {
            // Deck exhausted: the discards are shuffled back in and play
            // continues. This happens before the card just played joins the
            // pile, so a swapped dead card can never come straight back —
            // which would otherwise let a player exchange the same card
            // forever.
            val random = Random(state.seed + state.log.size)
            draw = discard.shuffledWith(random)
            discard = emptyList()
        }
        discard = discard + card

        if (draw.isNotEmpty()) {
            hands[seat] = hands[seat] + draw.first()
            draw = draw.drop(1)
        }

        return state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            drawPile = draw,
            discardPile = discard,
        )
    }

    private fun SequenceState.advanceTurn(skip: Boolean): SequenceState =
        if (skip) this
        else copy(turn = (turn + 1) % playerCount, exchangedThisTurn = false)

    // -----------------------------------------------------------------------
    // Sequence detection
    // -----------------------------------------------------------------------

    /**
     * Counts and locks any runs of five completed by the chip just played at
     * [cell].
     *
     * Two sequences may share at most one chip, so a candidate run is only
     * accepted when at most one of its squares is already locked. Corners are
     * free squares that count for every team.
     */
    private fun claimSequences(
        chips: List<Int>,
        locked: MutableList<Boolean>,
        cell: Int,
        team: Int,
    ): Int {
        fun ownedBy(c: Int): Boolean = SequenceBoard.isCorner(c) || chips[c] == team

        val row = SequenceBoard.rowOf(cell)
        val col = SequenceBoard.colOf(cell)
        var found = 0

        for ((dr, dc) in DIRECTIONS) {
            // Every window of five that includes the new chip.
            for (offset in -(RUN_LENGTH - 1)..0) {
                val window = (0 until RUN_LENGTH).map { step ->
                    val r = row + (offset + step) * dr
                    val c = col + (offset + step) * dc
                    if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE) return@map -1
                    SequenceBoard.cellAt(r, c)
                }
                if (window.any { it < 0 }) continue
                if (!window.all { ownedBy(it) }) continue
                // Free corners are shared by everyone and never count as the
                // already-locked chip for the shared-chip limit.
                val lockedInWindow = window.count { locked[it] && !SequenceBoard.isCorner(it) }
                if (lockedInWindow > 1) continue

                window.forEach { locked[it] = true }
                found++
                break // At most one new sequence per direction.
            }
        }
        return found
    }

    // -----------------------------------------------------------------------
    // Results, redaction and wire format
    // -----------------------------------------------------------------------

    override fun isFinished(state: SequenceState): Boolean =
        state.phase == SequencePhase.GAME_OVER

    override fun summary(state: SequenceState): String = when (val winner = state.winner) {
        null -> state.sequencesByTeam
            .mapIndexed { team, count -> "Team $team: $count" }
            .joinToString("  ")

        else -> "Team $winner wins"
    }

    override fun viewFor(state: SequenceState, seat: Int): SequenceState = state.copy(
        hands = state.hands.mapIndexed { index, hand -> if (index == seat) hand else emptyList() },
        handCounts = state.handCounts,
        // The deck order is hidden information too.
        drawPile = emptyList(),
    )

    override fun encodeState(state: SequenceState): String = json.encodeToString(state)

    override fun decodeState(json: String): SequenceState = this.json.decodeFromString(json)

    override fun encodeMove(move: SequenceMove): String = json.encodeToString(move)

    override fun decodeMove(json: String): SequenceMove = this.json.decodeFromString(json)
}
