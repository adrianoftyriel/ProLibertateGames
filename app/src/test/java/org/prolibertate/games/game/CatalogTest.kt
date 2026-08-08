package org.prolibertate.games.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun `every game has a unique id`() {
        val ids = GameCatalog.all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.distinct().size)
        ids.forEach { assertEquals(it, GameCatalog.byId(it)?.id) }
    }

    @Test
    fun `a production build lists only games that can be played`() {
        GameCategory.entries.forEach { category ->
            val shown = GameCatalog.byCategory(category, includeComingSoon = false)
            assertTrue(
                "$category offers an unfinished game in a release build",
                shown.all { it.available },
            )
        }
    }

    @Test
    fun `a dev build lists the whole catalogue`() {
        val shown = GameCategory.entries.flatMap {
            GameCatalog.byCategory(it, includeComingSoon = true)
        }
        assertEquals(GameCatalog.all.size, shown.size)
    }

    @Test
    fun `every game in the catalogue can be played`() {
        // There is nothing left marked "coming soon". The machinery that hides
        // unfinished games from a production build is still there and still
        // tested above — it simply has nothing to hide at the moment, and this
        // is what will fail the day something is added to the list before its
        // engine is written.
        assertTrue(GameCatalog.all.all { it.available })
        assertEquals(
            GameCatalog.byCategory(GameCategory.BOARD, includeComingSoon = false),
            GameCatalog.byCategory(GameCategory.BOARD, includeComingSoon = true),
        )
    }

    @Test
    fun `hiding unfinished games still leaves something to play in each category`() {
        // A category filtered down to nothing would leave a stranded heading,
        // and a menu with no playable game at all would be a broken release.
        GameCategory.entries.forEach { category ->
            assertFalse(
                "$category has nothing playable in a release build",
                GameCatalog.byCategory(category, includeComingSoon = false).isEmpty(),
            )
        }
        assertTrue(GameCatalog.playable.isNotEmpty())
    }

    @Test
    fun `playable games are the ones with engines wired up`() {
        assertEquals(
            setOf(
                GameCatalog.EUCHRE,
                GameCatalog.SEQUENCE,
                GameCatalog.PRESIDENT,
                GameCatalog.GOLF,
                GameCatalog.KAISER,
                GameCatalog.CRAZY_EIGHTS,
                GameCatalog.CRIBBAGE,
                GameCatalog.HEARTS,
                GameCatalog.PEG_SOLITAIRE,
                GameCatalog.YAHTZEE,
                GameCatalog.KLONDIKE,
                GameCatalog.FREECELL,
                GameCatalog.SPIDER,
                GameCatalog.PYRAMID,
                GameCatalog.WIZARD,
                GameCatalog.CHESS,
                GameCatalog.TAYU,
                GameCatalog.MORRIS,
                GameCatalog.CHECKERS,
                GameCatalog.MASTERMIND,
                GameCatalog.BACKGAMMON,
                GameCatalog.PIRATES,
            ),
            GameCatalog.playable.map { it.id }.toSet(),
        )
    }

    @Test
    fun `player counts are sane`() {
        GameCatalog.all.forEach { game ->
            // One, not two. Peg solitaire is played alone, and the engine took
            // that without changes — one seat, always on the clock, nothing to
            // redact. Everything downstream reads the seat count from here, so
            // this is the only place that had to allow it.
            assertTrue("${game.id} min players", game.minPlayers >= 1)
            assertTrue("${game.id} max >= min", game.maxPlayers >= game.minPlayers)
        }
    }
}
