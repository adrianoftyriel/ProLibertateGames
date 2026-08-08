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
        GameMenu.entries.forEach { menu ->
            val shown = GameCatalog.inMenu(menu, includeComingSoon = false)
            assertTrue(
                "$menu offers an unfinished game in a release build",
                shown.all { it.available },
            )
        }
    }

    @Test
    fun `every game is reachable from at least one menu`() {
        // Counting is no good now that a game can be in several menus: the
        // union is what matters, and a game in none of them would be in the
        // catalogue but on no screen.
        val reachable = GameMenu.entries.flatMap {
            GameCatalog.inMenu(it, includeComingSoon = true)
        }.toSet()
        assertEquals(GameCatalog.all.toSet(), reachable)
        GameCatalog.all.forEach {
            assertTrue("${it.id} is in no menu", it.menus.isNotEmpty())
        }
    }

    @Test
    fun `a game can be in more than one menu`() {
        // The whole point of the set. A patience is solitaire and a card game;
        // Euchre is a card game and a trick-taking one.
        val klondike = GameCatalog.byId(GameCatalog.KLONDIKE)!!
        assertTrue(klondike.menus.containsAll(setOf(GameMenu.SOLITAIRE, GameMenu.CARDS)))
        assertTrue(GameCatalog.inMenu(GameMenu.SOLITAIRE).contains(klondike))
        assertTrue(GameCatalog.inMenu(GameMenu.CARDS).contains(klondike))
    }

    @Test
    fun `trick-taking sits under cards, and its games are card games too`() {
        assertEquals(GameMenu.CARDS, GameMenu.TRICK_TAKING.parent)
        assertEquals(listOf(GameMenu.TRICK_TAKING), GameMenu.CARDS.children)
        assertTrue(GameMenu.TRICK_TAKING.isTopLevel.not())
        assertTrue(GameMenu.SOLITAIRE.isTopLevel)

        val tricks = GameCatalog.inMenu(GameMenu.TRICK_TAKING)
        assertTrue("there should be some", tricks.isNotEmpty())
        // A sub-menu that listed something its parent did not would be a game
        // you could reach one way and not the other.
        assertTrue(
            "every trick-taking game is also a card game",
            tricks.all { it.menus.contains(GameMenu.CARDS) },
        )
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
            GameCatalog.inMenu(GameMenu.BOARD, includeComingSoon = false),
            GameCatalog.inMenu(GameMenu.BOARD, includeComingSoon = true),
        )
    }

    @Test
    fun `hiding unfinished games still leaves something to play in every menu`() {
        // A menu filtered down to nothing would leave a stranded heading, and a
        // release where a whole section was empty would be a broken one.
        GameMenu.entries.forEach { menu ->
            assertTrue(
                "$menu has nothing playable in a release build",
                GameCatalog.hasAnything(menu, includeComingSoon = false),
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
