package org.prolibertate.games.game

/**
 * The menus the main screen is built from.
 *
 * A tree rather than a list, because "trick-taking" is a kind of card game
 * rather than a rival to it. [parent] is read from a `when` rather than passed
 * to the constructor: an enum entry cannot refer to another entry while the
 * class is still initialising, and a lookup afterwards is free.
 */
enum class GameMenu(val label: String, val blurb: String) {
    SOLITAIRE("Solitaire", "Played alone, against the deal or the board."),
    CARDS("Card Games", "Anything played with a pack."),
    TRICK_TAKING("Trick-taking", "Lead, follow, and count what you took."),
    BOARD("Board Games", "Boards, pieces and dice."),
    ;

    val parent: GameMenu? get() = when (this) {
        TRICK_TAKING -> CARDS
        else -> null
    }

    val children: List<GameMenu> get() = entries.filter { it.parent == this }

    val isTopLevel: Boolean get() = parent == null
}

data class GameDescriptor(
    val id: String,
    val title: String,
    /**
     * Every menu this game appears under. A patience is both solitaire and a
     * card game, and Euchre is both a card game and a trick-taking one — so
     * this is a set, and the same game is listed in each place it belongs.
     */
    val menus: Set<GameMenu>,
    val minPlayers: Int,
    val maxPlayers: Int,
    /** True when players are grouped into partnerships rather than playing solo. */
    val teamBased: Boolean,
    val blurb: String,
    val available: Boolean,
)

/**
 * The catalogue the menus are built from.
 *
 * Every game the app intends to ship appears here, whether or not its rules
 * engine exists yet. [GameDescriptor.available] is what the menu keys off to
 * decide between "play" and "coming soon", so adding a game is a matter of
 * flipping that flag once its engine and screen land.
 */
object GameCatalog {

    const val EUCHRE = "euchre"
    const val KAISER = "kaiser"
    const val PRESIDENT = "president"
    const val GOLF = "golf"
    const val WIZARD = "wizard"
    const val CRAZY_EIGHTS = "crazy8s"
    const val CRIBBAGE = "cribbage"

    const val HEARTS = "hearts"
    const val PEG_SOLITAIRE = "pegsolitaire"

    const val YAHTZEE = "yahtzee"
    const val KLONDIKE = "klondike"
    const val FREECELL = "freecell"
    const val SPIDER = "spider"
    const val PYRAMID = "pyramid"
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
            menus = setOf(GameMenu.CARDS, GameMenu.TRICK_TAKING),
            minPlayers = 4,
            maxPlayers = 4,
            teamBased = true,
            blurb = "Four players, two partnerships, 24-card deck. Bowers are trump.",
            available = true,
        ),
        GameDescriptor(
            id = KAISER,
            title = "Kaiser",
            menus = setOf(GameMenu.CARDS, GameMenu.TRICK_TAKING),
            minPlayers = 4,
            maxPlayers = 4,
            teamBased = true,
            blurb = "Prairie trick-taking with bidding. The 5 of hearts and 3 of spades decide it.",
            available = true,
        ),
        GameDescriptor(
            id = PRESIDENT,
            title = "President",
            menus = setOf(GameMenu.CARDS),
            minPlayers = 3,
            maxPlayers = 7,
            teamBased = false,
            blurb = "Shed your hand first and rule the table. Last one out serves the drinks.",
            available = true,
        ),
        GameDescriptor(
            id = GOLF,
            title = "Golf",
            menus = setOf(GameMenu.CARDS),
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Lowest score wins over nine holes. Match pairs to cancel them out.",
            available = true,
        ),
        GameDescriptor(
            id = WIZARD,
            title = "Wizard",
            menus = setOf(GameMenu.CARDS, GameMenu.TRICK_TAKING),
            minPlayers = 3,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Bid your tricks exactly. Wizards always win, jesters always lose.",
            available = true,
        ),
        GameDescriptor(
            id = CRAZY_EIGHTS,
            title = "Crazy 8s",
            menus = setOf(GameMenu.CARDS),
            minPlayers = 2,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Match rank or suit, and eights change everything.",
            available = true,
        ),
        GameDescriptor(
            id = CRIBBAGE,
            title = "Cribbage",
            menus = setOf(GameMenu.CARDS),
            minPlayers = 2,
            maxPlayers = 4,
            // Only at four, where the two pairs share a board between them.
            teamBased = true,
            blurb = "Fifteen two, fifteen four, and one for his nob. Peg your way " +
                "twice round the board to 121.",
            available = true,
        ),
        GameDescriptor(
            id = HEARTS,
            title = "Hearts",
            menus = setOf(GameMenu.CARDS, GameMenu.TRICK_TAKING),
            minPlayers = 4,
            maxPlayers = 4,
            teamBased = false,
            blurb = "Take no trick worth taking. Every heart costs a point, the queen " +
                "of spades costs thirteen, and taking all of them costs everybody else.",
            available = true,
        ),
        GameDescriptor(
            id = KLONDIKE,
            title = "Klondike",
            menus = setOf(GameMenu.SOLITAIRE, GameMenu.CARDS),
            minPlayers = 1,
            maxPlayers = 1,
            teamBased = false,
            blurb = "Patience, as everyone means it. Seven columns down to the aces, " +
                "and a pack that decides most of it before you start.",
            available = true,
        ),
        GameDescriptor(
            id = FREECELL,
            title = "FreeCell",
            menus = setOf(GameMenu.SOLITAIRE, GameMenu.CARDS),
            minPlayers = 1,
            maxPlayers = 1,
            teamBased = false,
            blurb = "Every card face up from the start, and four cells to park what is " +
                "in the way. Nearly every deal can be won by someone good enough.",
            available = true,
        ),
        GameDescriptor(
            id = SPIDER,
            title = "Spider",
            menus = setOf(GameMenu.SOLITAIRE, GameMenu.CARDS),
            minPlayers = 1,
            maxPlayers = 1,
            teamBased = false,
            blurb = "Two packs, ten columns, eight runs to build from king to ace. " +
                "One suit is a pastime; four is a fight.",
            available = true,
        ),
        GameDescriptor(
            id = PYRAMID,
            title = "Pyramid",
            menus = setOf(GameMenu.SOLITAIRE, GameMenu.CARDS),
            minPlayers = 1,
            maxPlayers = 1,
            teamBased = false,
            blurb = "Take cards away in pairs that make thirteen, kings alone. " +
                "Nothing is built and nothing goes home — the pyramid just has to go.",
            available = true,
        ),
        GameDescriptor(
            id = SEQUENCE,
            title = "Sequence",
            menus = setOf(GameMenu.BOARD, GameMenu.CARDS),
            minPlayers = 2,
            maxPlayers = 12,
            teamBased = true,
            blurb = "Play a card, claim its square, and build five in a row.",
            available = true,
        ),
        GameDescriptor(
            id = MASTERMIND,
            title = "Mastermind",
            menus = setOf(GameMenu.BOARD),
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
            menus = setOf(GameMenu.BOARD),
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Race your checkers home, and hit your opponent on the way.",
            available = true,
        ),
        GameDescriptor(
            id = CHESS,
            title = "Chess",
            menus = setOf(GameMenu.BOARD),
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "The old game. Full legal moves, castling, en passant, promotion.",
            available = true,
        ),
        GameDescriptor(
            id = CHECKERS,
            title = "Checkers",
            menus = setOf(GameMenu.BOARD),
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Forced captures, kings, and the long diagonal.",
            available = true,
        ),
        GameDescriptor(
            id = MORRIS,
            title = "Nine Men's Morris",
            menus = setOf(GameMenu.BOARD),
            minPlayers = 2,
            maxPlayers = 2,
            teamBased = false,
            blurb = "Three in a row takes a piece. Reduce them to two and the game is yours.",
            available = true,
        ),
        GameDescriptor(
            id = PEG_SOLITAIRE,
            title = "Peg Solitaire",
            menus = setOf(GameMenu.SOLITAIRE, GameMenu.BOARD),
            // The first game here played alone. Everything downstream reads the
            // seat count from these two numbers, so one seat is all it takes.
            minPlayers = 1,
            maxPlayers = 1,
            teamBased = false,
            blurb = "The tee game from the table, and four larger boards. Jump a peg, " +
                "take the one you passed, and try to finish with one standing.",
            available = true,
        ),
        GameDescriptor(
            id = YAHTZEE,
            title = "Yahtzee",
            menus = setOf(GameMenu.BOARD),
            minPlayers = 1,
            maxPlayers = 6,
            teamBased = false,
            blurb = "Five dice, three throws, thirteen boxes to fill. Every box can be " +
                "written in once, so the hard part is what to give up on.",
            available = true,
        ),
        GameDescriptor(
            id = PIRATES,
            title = "Pirates and Bulgars",
            menus = setOf(GameMenu.BOARD),
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
            menus = setOf(GameMenu.BOARD),
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
    fun inMenu(
        menu: GameMenu,
        includeComingSoon: Boolean = true,
    ): List<GameDescriptor> = all.filter {
        it.menus.contains(menu) && (includeComingSoon || it.available)
    }

    /**
     * Whether a menu is worth showing at all.
     *
     * A section with nothing in it and no populated child under it is a heading
     * over an empty room, which is what a production build would get if every
     * game in a menu were still unfinished.
     */
    fun hasAnything(menu: GameMenu, includeComingSoon: Boolean = true): Boolean =
        inMenu(menu, includeComingSoon).isNotEmpty() ||
            menu.children.any { inMenu(it, includeComingSoon).isNotEmpty() }

    /** Everything currently playable, whatever menu it is under. */
    val playable: List<GameDescriptor> get() = all.filter { it.available }
}
