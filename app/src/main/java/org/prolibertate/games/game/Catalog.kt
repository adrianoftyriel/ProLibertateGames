package org.prolibertate.games.game

/**
 * The catalogue the main menu is built from.
 *
 * Every game the app intends to ship appears here, whether or not its rules
 * engine exists yet. [GameDescriptor.available] is what the menu keys off to
 * decide between "play" and "coming soon", so adding a game is a matter of
 * flipping that flag once its engine and screen land.
 */
enum class GameCategory(val label: String) {
    CARD("Playing Card Games"),
    BOARD("Board Games"),
}

data class GameDescriptor(
    val id: String,
    val title: String,
    val category: GameCategory,
    val minPlayers: Int,
    val maxPlayers: Int,
    /** True when players are grouped into partnerships rather than playing solo. */
    val teamBased: Boolean,
    val blurb: String,
    val available: Boolean,
)

object GameCatalog {

    const val EUCHRE = "euchre"
    const val KAISER = "kaiser"
    const val PRESIDENT = "president"
    const val GOLF = "golf"
    const val WIZARD = "wizard"
    const val CRAZY_EIGHTS = "crazy8s"
    const val SEQUENCE = "sequence"
    const val MASTERMIND = "mastermind"
    const val BACKGAMMON = "backgammon"
    const val CHESS = "chess"
    const val CHECKERS = "checkers"

    val all: List<GameDescriptor> = listOf(
        GameDescriptor(
            id = EUCHRE,
            title = "Euchre",
            category = GameCategory.CARD,
            minPlayers = 4,
            maxPlayers = 4,
            teamBased = true,
            blurb = "Four players, two partnerships, 24-card deck. Bowers are trump.",
            available = true,
        ),
        GameDescriptor(
            id = KAISER,
            title = "Kaiser",
            category = GameCategory.CARD,
            minPlayers = 4,
            maxPlayers = 4,
            teamBased = true,
            blurb = "Prairie trick-taking with bidding. The 5 of hearts and 3 of spades decide it.",
            available = false,
        ),
        GameDescriptor(
            id = PRESIDENT,
            title = "President",
            category = GameCategory.CARD,
            minPlayers = 3,
            maxPlayers = 7,
            teamBased = false,
            blurb = "Shed your hand first and rule the table. Last one out serves the drinks.",
            available = false,
        ),
        GameDescriptor(
            id = GOLF,
            title = "Golf",
            category = GameCategory.CARD,
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Lowest score wins over nine holes. Match pairs to cancel them out.",
            available = false,
        ),
        GameDescriptor(
            id = WIZARD,
            title = "Wizard",
            category = GameCategory.CARD,
            minPlayers = 3,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Bid your tricks exactly. Wizards always win, jesters always lose.",
            available = false,
        ),
        GameDescriptor(
            id = CRAZY_EIGHTS,
            title = "Crazy 8s",
            category = GameCategory.CARD,
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Match rank or suit, and eights change everything.",
            available = false,
        ),
        GameDescriptor(
            id = SEQUENCE,
            title = "Sequence",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 12,
            teamBased = true,
            blurb = "Play a card, claim its square, and build five in a row.",
            available = true,
        ),
        GameDescriptor(
            id = MASTERMIND,
            title = "Mastermind",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Break the hidden code from black and white peg clues.",
            available = false,
        ),
        GameDescriptor(
            id = BACKGAMMON,
            title = "Backgammon",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Race your checkers home, and hit your opponent on the way.",
            available = false,
        ),
        GameDescriptor(
            id = CHESS,
            title = "Chess",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "The old game. Full legal moves, castling, en passant, promotion.",
            available = false,
        ),
        GameDescriptor(
            id = CHECKERS,
            title = "Checkers",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Forced captures, kings, and the long diagonal.",
            available = false,
        ),
    )

    fun byId(id: String): GameDescriptor? = all.firstOrNull { it.id == id }

    fun byCategory(category: GameCategory): List<GameDescriptor> =
        all.filter { it.category == category }
}
