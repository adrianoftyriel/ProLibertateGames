package org.prolibertate.games.game.klondike

import org.prolibertate.games.game.engine.GameAi

/**
 * Plays the deal the way a person does when they are not thinking hard.
 *
 * Nothing asks it to during an ordinary game — the only seat belongs to the
 * player — but a seat has to be playable by something, and this is what a
 * "suggest a move" button would offer.
 *
 * The order matters more than the numbers. Sending a card home always wins,
 * turning one up comes next, and aimless shuffling between columns scores below
 * drawing so it is only reached when there is nothing else at all. That last
 * ordering is what stops it walking a run back and forth between two columns for
 * ever.
 */
object KlondikeAi : GameAi<KlondikeState, KlondikeMove> {

    override fun chooseMove(state: KlondikeState, seat: Int, legal: List<KlondikeMove>): KlondikeMove {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return legal.maxBy { worth(state, it) }
    }

    private fun worth(state: KlondikeState, move: KlondikeMove): Int = when (move) {
        Draw -> 30
        Redeal -> 20
        is MoveCards -> when {
            move.to.kind == SpotKind.FOUNDATION -> 100
            // Taking a card back off a foundation is occasionally right and
            // usually not, so it sits below doing nothing much at all.
            move.from.kind == SpotKind.FOUNDATION -> -50
            move.from.kind == SpotKind.TABLEAU && turnsACardUp(state, move) -> 80
            move.from.kind == SpotKind.TABLEAU && emptiesAColumn(state, move) -> 60
            move.from.kind == SpotKind.WASTE -> 40
            else -> 15
        }
    }

    /** True when the move strips a column back to its buried cards. */
    private fun turnsACardUp(state: KlondikeState, move: MoveCards): Boolean {
        val pile = state.tableau[move.from.index]
        return move.count == pile.faceUp.size && pile.faceDown.isNotEmpty()
    }

    private fun emptiesAColumn(state: KlondikeState, move: MoveCards): Boolean {
        val pile = state.tableau[move.from.index]
        return move.count == pile.faceUp.size && pile.faceDown.isEmpty()
    }
}
