package org.prolibertate.games.game.solitaire

import org.prolibertate.games.game.engine.GameAi

/**
 * Takes the first move on offer.
 *
 * Every patience here has one seat and it belongs to the person playing, so
 * nothing asks this during an ordinary game. [MatchController] needs an AI to
 * construct, though, and a seat that could be left empty has to be playable by
 * something.
 *
 * It is deliberately not clever. A greedy patience player that looked good would
 * be worse than this: it would be trusted as a hint, and a hint that walks a run
 * back and forth is more annoying than no hint at all. Klondike has a real one
 * because its move ordering is obvious; these three do not, and pretending
 * otherwise would be the wrong kind of helpful.
 */
class FirstLegalAi<S : Any, M : Any> : GameAi<S, M> {
    override fun chooseMove(state: S, seat: Int, legal: List<M>): M {
        require(legal.isNotEmpty()) { "No legal move for seat $seat" }
        return legal.first()
    }
}
