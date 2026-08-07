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
    const val TAYU = "tayu"
    const val MORRIS = "morris"
    const val PIRATES = "pirates"

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
            available = true,
        ),
        GameDescriptor(
            id = PRESIDENT,
            title = "President",
            category = GameCategory.CARD,
            minPlayers = 3,
            maxPlayers = 7,
            teamBased = false,
            blurb = "Shed your hand first and rule the table. Last one out serves the drinks.",
            available = true,
        ),
        GameDescriptor(
            id = GOLF,
            title = "Golf",
            category = GameCategory.CARD,
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Lowest score wins over nine holes. Match pairs to cancel them out.",
            available = true,
        ),
        GameDescriptor(
            id = WIZARD,
            title = "Wizard",
            category = GameCategory.CARD,
            minPlayers = 3,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Bid your tricks exactly. Wizards always win, jesters always lose.",
            available = true,
        ),
        GameDescriptor(
            id = CRAZY_EIGHTS,
            title = "Crazy 8s",
            category = GameCategory.CARD,
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Match rank or suit, and eights change everything.",
            available = true,
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
            blurb = "Set a code for your opponent and break the one they set for you. " +
                "Whoever cracks theirs first wins.",
            available = true,
        ),
        GameDescriptor(
            id = BACKGAMMON,
            title = "Backgammon",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Race your checkers home, and hit your opponent on the way.",
            available = true,
        ),
        GameDescriptor(
            id = CHESS,
            title = "Chess",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "The old game. Full legal moves, castling, en passant, promotion.",
            available = true,
        ),
        GameDescriptor(
            id = CHECKERS,
            title = "Checkers",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Forced captures, kings, and the long diagonal.",
            available = true,
        ),
        GameDescriptor(
            id = MORRIS,
            title = "Nine Men's Morris",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Three in a row takes a piece. Reduce them to two and the game is yours.",
            available = true,
        ),
        GameDescriptor(
            id = PIRATES,
            title = "Pirates and Bulgars",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Two pirates hold the stronghold against twenty-four Bulgars. " +
                "Only one side can take pieces, and it is not the big one.",
            available = true,
        ),
        GameDescriptor(
            id = TAYU,
            title = "Ta Yü",
            category = GameCategory.BOARD,
            minPlayers = 2,
            maxPlayers = 4,
            // Two play solo, four play in partnerships across the two axes.
            teamBased = true,
            blurb = "Lay river tiles out from the centre. Reach both your edges, " +
                "because your score multiplies them.",
            available = true,
        ),
    )

    fun byId(id: String): GameDescriptor? = all.firstOrNull { it.id == id }

    /**
     * Games in a category.
     *
     * [includeComingSoon] is what separates the two update channels: a dev
     * build lists everything, including the games that are still only entries
     * in this table, so the roadmap is visible while working on it. A
     * production build lists only what can actually be played, so nobody
     * downloads a release and taps into a dead end.
     */
    fun byCategory(
        category: GameCategory,
        includeComingSoon: Boolean = true,
    ): List<GameDescriptor> = all.filter {
        it.category == category && (includeComingSoon || it.available)
    }

    /** Everything currently playable, regardless of category. */
    val playable: List<GameDescriptor> get() = all.filter { it.available }
}
