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
    fun `a dev build lists everything including what is still coming`() {
        val shown = GameCategory.entries.flatMap {
            GameCatalog.byCategory(it, includeComingSoon = true)
        }
        assertEquals(GameCatalog.all.size, shown.size)
        assertTrue("unfinished games are visible while developing", shown.any { !it.available })
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
            setOf(GameCatalog.EUCHRE, GameCatalog.SEQUENCE),
            GameCatalog.playable.map { it.id }.toSet(),
        )
    }

    @Test
    fun `player counts are sane`() {
        GameCatalog.all.forEach { game ->
            assertTrue("${game.id} min players", game.minPlayers >= 2)
            assertTrue("${game.id} max >= min", game.maxPlayers >= game.minPlayers)
        }
    }
}
