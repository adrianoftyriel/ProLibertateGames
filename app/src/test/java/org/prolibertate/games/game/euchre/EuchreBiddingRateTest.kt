package org.prolibertate.games.game.euchre

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

/**
 * How often the AI takes the bid.
 *
 * This exists because an over-eager AI does not look like a tuning problem from
 * the table — it looks like the game refusing to let you bid. If the three
 * computer players order up nearly every deal, a human dealer is dragged into
 * picking up the turn card again and again and never gets a say.
 */
class EuchreBiddingRateTest {

    private val json = Json { encodeDefaults = true }

    private fun config(seed: Long, options: EuchreOptions = EuchreOptions()) = TableConfig(
        gameId = "euchre",
        seats = (0 until 4).map {
            PlayerSlot(seat = it, name = "P$it", kind = PlayerKind.AI, team = it % 2)
        },
        optionsJson = json.encodeToString(options),
        seed = seed,
    )

    private data class Outcome(
        val orderedUpInRoundOne: Boolean,
        val reachedRoundTwo: Boolean,
        val dealerForcedToPickUp: Boolean,
    )

    /** Runs the bidding for one deal with every seat played by the AI. */
    private fun bidOutDeal(seed: Long): Outcome {
        val ai = EuchreAi()
        var state = EuchreRules.initialState(config(seed))
        val dealer = state.dealer
        var reachedRoundTwo = false
        var guard = 0

        while (guard++ < 40) {
            when (state.phase) {
                EuchrePhase.BID_ROUND_1 -> Unit
                EuchrePhase.BID_ROUND_2 -> reachedRoundTwo = true
                else -> break
            }
            val seat = EuchreRules.currentSeat(state) ?: break
            val legal = EuchreRules.legalMoves(state, seat)
            state = EuchreRules.applyMove(state, seat, ai.chooseMove(state, seat, legal))
        }

        // Reaching the discard step means somebody ordered it up and the dealer
        // now has to take the card whether they like it or not.
        val orderedUpInRoundOne = !reachedRoundTwo && state.trump != null
        return Outcome(
            orderedUpInRoundOne = orderedUpInRoundOne,
            reachedRoundTwo = reachedRoundTwo,
            dealerForcedToPickUp = orderedUpInRoundOne && state.maker != dealer,
        )
    }

    @Test
    fun `the ai does not order up on most deals`() {
        val deals = 2000
        val outcomes = (0 until deals).map { bidOutDeal(it.toLong()) }

        val orderedUp = outcomes.count { it.orderedUpInRoundOne }
        val forcedPickUp = outcomes.count { it.dealerForcedToPickUp }
        val roundTwo = outcomes.count { it.reachedRoundTwo }

        val orderedUpRate = orderedUp * 100.0 / deals
        val forcedRate = forcedPickUp * 100.0 / deals
        println(
            "ordered up in round 1: %.1f%%   dealer forced to pick up: %.1f%%   reached round 2: %.1f%%"
                .format(orderedUpRate, forcedRate, roundTwo * 100.0 / deals)
        )

        // Ordering up should be a decision, not a formality. Real play turns the
        // card down more often than not, so a majority of deals must survive
        // round one.
        assertTrue(
            "AI orders up on %.1f%% of deals — far too eager".format(orderedUpRate),
            orderedUpRate <= 55.0,
        )
        assertTrue(
            "AI almost never bids (%.1f%%) — too timid".format(orderedUpRate),
            orderedUpRate >= 15.0,
        )
        // The specific complaint: as dealer you keep being handed the turn card.
        assertTrue(
            "dealer is dragged into picking up on %.1f%% of deals".format(forcedRate),
            forcedRate <= 45.0,
        )
    }

    @Test
    fun `going alone stays rare`() {
        val ai = EuchreAi()
        var alone = 0
        val deals = 1000
        repeat(deals) { seed ->
            var state = EuchreRules.initialState(config(seed.toLong()))
            var guard = 0
            while (state.trump == null && guard++ < 40) {
                val seat = EuchreRules.currentSeat(state) ?: break
                val legal = EuchreRules.legalMoves(state, seat)
                if (legal.isEmpty()) break
                state = EuchreRules.applyMove(state, seat, ai.chooseMove(state, seat, legal))
            }
            if (state.aloneSeat != null) alone++
        }
        val rate = alone * 100.0 / deals
        println("went alone: %.1f%%".format(rate))
        assertTrue("going alone on %.1f%% of deals is too often".format(rate), rate <= 12.0)
    }
}
