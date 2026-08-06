package org.prolibertate.games.game.tayu

import kotlinx.serialization.Serializable

/**
 * The board is 18 × 18. See RULES-tayu.md: sources disagree between this and
 * 19 × 19, and 18 is the reading under which the opening tile going into "one
 * of the four centre indentations" is true. If a rulebook ever settles it the
 * other way this constant is the only thing that has to change.
 */
const val BOARD_SIZE = 18
const val BOARD_CELLS = BOARD_SIZE * BOARD_SIZE

/** Every tile is three cells long. */
const val TILE_LENGTH = 3

/** Sentinel in [TayuState.tileAt] for a cell with nothing on it. */
const val NO_TILE = -1

/**
 * A compass direction, in clockwise order so that turning is arithmetic.
 *
 * Rows count from the north edge and columns from the west, so south is a
 * larger row — the same convention as the screen.
 */
@Serializable
enum class Facing(val dr: Int, val dc: Int) {
    NORTH(-1, 0),
    EAST(0, 1),
    SOUTH(1, 0),
    WEST(0, -1);

    val opposite: Facing get() = Facing.entries[(ordinal + 2) % 4]
    val left: Facing get() = Facing.entries[(ordinal + 3) % 4]
    val right: Facing get() = Facing.entries[(ordinal + 1) % 4]
}

/**
 * Which flank of a tile a river mouth sits on, described relative to the tile's
 * own length rather than to the board.
 *
 * A tile is laid pointing somewhere — [Facing] — and its three cells run that
 * way from the anchor. [FRONT] is the far short end, [BACK] the near one, and
 * [LEFT] and [RIGHT] are the two long flanks. Keeping mouths in these terms is
 * what lets one stored tile be laid in any of four orientations without a
 * lookup table per orientation.
 */
enum class Flank { LEFT, RIGHT, BACK, FRONT }

/** Where a mouth on [flank] points once the tile is laid pointing [tileFacing]. */
fun absoluteFacing(tileFacing: Facing, flank: Flank): Facing = when (flank) {
    Flank.FRONT -> tileFacing
    Flank.BACK -> tileFacing.opposite
    Flank.LEFT -> tileFacing.left
    Flank.RIGHT -> tileFacing.right
}

/**
 * The eight places a river mouth can sit on a 1 × 3 tile: one above and one
 * below each of the three cells, plus one at each short end.
 *
 * They are numbered so that a whole tile is a bitmask small enough to send as
 * one integer.
 */
object TileSlots {

    const val COUNT = 8

    /** Which of the tile's three cells each slot sits on. */
    private val CELL = intArrayOf(0, 1, 2, 0, 1, 2, 0, 2)

    private val FLANK = arrayOf(
        Flank.LEFT, Flank.LEFT, Flank.LEFT,
        Flank.RIGHT, Flank.RIGHT, Flank.RIGHT,
        Flank.BACK, Flank.FRONT,
    )

    /**
     * Slot reached by turning the tile end for end. No slot maps to itself,
     * which is exactly why the 56 possible mouth arrangements pair off cleanly
     * into 28 distinct tiles rather than leaving some stranded.
     */
    private val TURNED = intArrayOf(5, 4, 3, 2, 1, 0, 7, 6)

    fun cellOf(slot: Int): Int = CELL[slot]

    fun flankOf(slot: Int): Flank = FLANK[slot]

    fun turned(slot: Int): Int = TURNED[slot]
}

/**
 * The bag of tiles, generated rather than listed.
 *
 * A tile is a bitmask over [TileSlots]: exactly three of the eight slots carry
 * a river mouth, and all three are joined to one another across the face of the
 * tile, so water entering any mouth can leave by either of the others.
 *
 * Choosing three slots from eight gives 56 arrangements, and turning a tile end
 * for end pairs them off with none left over, so there are exactly **28
 * distinct tiles**. The reissued game holds three of each — 84 — and the 1999
 * original held four, 112. Both counts being whole multiples of 28 is the
 * evidence this geometry is the right one.
 */
object TayuTiles {

    const val MOUTHS_PER_TILE = 3

    /**
     * Every tile that can exist, one entry each.
     *
     * Taking the smaller mask of each turned-over pair is enough to pick one
     * representative apiece, because no arrangement of three mouths survives
     * being turned end for end unchanged.
     */
    val all: List<Int> = (0 until (1 shl TileSlots.COUNT))
        .filter { it.countOneBits() == MOUTHS_PER_TILE }
        .filter { it < turned(it) }

    /** The same mask with the tile turned end for end. */
    fun turned(mask: Int): Int {
        var result = 0
        for (slot in 0 until TileSlots.COUNT) {
            if (hasMouth(mask, slot)) result = result or (1 shl TileSlots.turned(slot))
        }
        return result
    }

    fun hasMouth(mask: Int, slot: Int): Boolean = (mask shr slot) and 1 == 1

    fun mouthSlots(mask: Int): List<Int> =
        (0 until TileSlots.COUNT).filter { hasMouth(mask, it) }

    /**
     * True when the tile's three mouths sit on three different flanks of the
     * rectangle. Twelve of the 28 do, which is what the concentric ring on the
     * centre stud marks on the reissued tiles — so the same ring is drawn here.
     */
    fun touchesThreeFlanks(mask: Int): Boolean =
        mouthSlots(mask).map { TileSlots.flankOf(it) }.distinct().size == 3

    /** The bag for a table: [copies] of every tile, shuffled by the caller. */
    fun bag(copies: Int): List<Int> = List(copies) { all }.flatten()
}

object TayuBoard {

    fun cellAt(row: Int, col: Int): Int = row * BOARD_SIZE + col

    fun rowOf(cell: Int): Int = cell / BOARD_SIZE

    fun colOf(cell: Int): Int = cell % BOARD_SIZE

    fun onBoard(row: Int, col: Int): Boolean =
        row in 0 until BOARD_SIZE && col in 0 until BOARD_SIZE

    /** The four middle cells. The opening tile has to cover one of them. */
    val centre: Set<Int> = setOf(
        cellAt(BOARD_SIZE / 2 - 1, BOARD_SIZE / 2 - 1),
        cellAt(BOARD_SIZE / 2 - 1, BOARD_SIZE / 2),
        cellAt(BOARD_SIZE / 2, BOARD_SIZE / 2 - 1),
        cellAt(BOARD_SIZE / 2, BOARD_SIZE / 2),
    )

    /**
     * Exits along an edge that count double.
     *
     * Positions on the real board are not known — see RULES-tayu.md. These
     * three give the board four-fold rotational symmetry, so no side is better
     * served than another, which is the fairest guess available.
     */
    val markedExits: Set<Int> = setOf(3, 8, 13)

    /**
     * Where along [edge] a river leaving cell ([row], [col]) runs off the
     * board, counted clockwise from that edge's first cell.
     */
    fun exitPosition(edge: Facing, row: Int, col: Int): Int = when (edge) {
        Facing.NORTH -> col
        Facing.EAST -> row
        Facing.SOUTH -> BOARD_SIZE - 1 - col
        Facing.WEST -> BOARD_SIZE - 1 - row
    }

    /** An ordinary exit is worth one; a marked one is worth two. */
    fun exitValue(edge: Facing, row: Int, col: Int): Int =
        if (exitPosition(edge, row, col) in markedExits) 2 else 1

    /** The cell whose exit sits at [position] along [edge]: inverse of [exitPosition]. */
    fun edgeCell(edge: Facing, position: Int): Int = when (edge) {
        Facing.NORTH -> cellAt(0, position)
        Facing.EAST -> cellAt(position, BOARD_SIZE - 1)
        Facing.SOUTH -> cellAt(BOARD_SIZE - 1, BOARD_SIZE - 1 - position)
        Facing.WEST -> cellAt(BOARD_SIZE - 1 - position, 0)
    }
}

/** The three cells a tile laid by [move] would cover, from its anchor outwards. */
fun cellsOf(move: TayuMove): List<Int> = (0 until TILE_LENGTH).map {
    TayuBoard.cellAt(move.row + it * move.facing.dr, move.col + it * move.facing.dc)
}

/** The two edges a side is trying to reach. */
enum class Axis(val label: String, val first: Facing, val second: Facing) {
    NORTH_SOUTH("north and south", Facing.NORTH, Facing.SOUTH),
    EAST_WEST("east and west", Facing.EAST, Facing.WEST),
}

/** Team 0 runs its rivers north and south, team 1 east and west. */
fun axisOfTeam(team: Int): Axis = if (team % 2 == 0) Axis.NORTH_SOUTH else Axis.EAST_WEST

/**
 * How hard the computer plays.
 *
 * Only two settings, because only two could be shown to differ. [GENTLE] rates
 * a placement purely on what it scores at that moment, which for most of a game
 * is nothing at all, so it lays a reasonable-looking but aimless river: it beats
 * random placement about three games in four, and loses to [FULL] about four in
 * five. [FULL] adds blocking and, more to the point, notices which way the open
 * river mouths are pointing. Both figures are held to by TayuAiTest.
 *
 * A third setting that blocked harder was tried and dropped: it played the full
 * setting dead even, so shipping it as a step up would have been a claim that
 * could not be backed.
 */
@Serializable
enum class TayuLevel(
    val label: String,
    val ownWeight: Int,
    val blockWeight: Int,
    val reachWeight: Int,
) {
    /** Takes a score when it sees one and otherwise digs more or less anywhere. */
    GENTLE("Gentle", ownWeight = 20, blockWeight = 0, reachWeight = 0),

    /** Builds towards its own edges and spoils yours. */
    FULL("Full strength", ownWeight = 20, blockWeight = 12, reachWeight = 2),
}

@Serializable
data class TayuOptions(
    /** Two play head to head; four play as two partnerships across the axes. */
    val playerCount: Int = 2,
    val level: TayuLevel = TayuLevel.FULL,
    /**
     * Copies of each of the 28 tiles. Three is the reissued game's 84 tiles and
     * four is the 1999 original's 112 — the publisher itself cut it to three to
     * shorten the game, so two is offered on the same reasoning.
     */
    val tileCopies: Int = 3,
) {
    init {
        require(playerCount == 2 || playerCount == 4) { "Ta Yü seats two or four" }
        require(tileCopies in 2..4) { "tileCopies must be 2, 3 or 4" }
    }

    val tileCount: Int get() = tileCopies * TayuTiles.all.size
}

/** A tile on the board: its mouths, where its first cell sits, and which way it points. */
@Serializable
data class PlacedTile(
    val mask: Int,
    /** The tile's first cell; the other two run [facing] from here. */
    val row: Int,
    val col: Int,
    val facing: Facing,
    val seat: Int,
) {
    /** The three cells this tile covers, from the anchor outwards. */
    fun cells(): List<Int> = (0 until TILE_LENGTH).map {
        TayuBoard.cellAt(row + it * facing.dr, col + it * facing.dc)
    }
}

@Serializable
enum class TayuPhase { PLAYING, GAME_OVER }

@Serializable
data class TayuState(
    val options: TayuOptions,
    val seed: Long,
    /** Team index per seat. */
    val teams: List<Int>,
    /** Index into [placed] for the tile covering each cell, or [NO_TILE]. */
    val tileAt: List<Int>,
    val placed: List<PlacedTile>,
    /**
     * Tiles still in the bag, in the order they will come out. The one piece of
     * hidden information in the game, and the only thing [TayuRules.viewFor]
     * has to strip.
     */
    val bag: List<Int>,
    /**
     * The tile the seat on the clock has drawn and must place. Everyone can see
     * it — on the table it is drawn out of the bag face up.
     */
    val drawn: Int?,
    val bagCount: Int,
    /** Drawn tiles that had nowhere legal to go. They do not come back. */
    val setAside: List<Int>,
    val turn: Int,
    val phase: TayuPhase,
    val log: List<String>,
) {
    val playerCount: Int get() = teams.size

    fun teamOf(seat: Int): Int = teams[seat]

    fun axisOf(seat: Int): Axis = axisOfTeam(teamOf(seat))
}

/**
 * Laying a tile down: where its first cell goes and which way it points.
 *
 * The only move there is in Ta Yü, so there is no sealed hierarchy. Which tile
 * is being laid is deliberately absent — it can only ever be [TayuState.drawn],
 * and a move carrying its own copy could disagree with the state it is applied
 * to.
 */
@Serializable
data class TayuMove(val row: Int, val col: Int, val facing: Facing)

/** Exits reached on each edge, already counting marked ones double. */
data class EdgeExits(val north: Int, val east: Int, val south: Int, val west: Int) {

    fun on(edge: Facing): Int = when (edge) {
        Facing.NORTH -> north
        Facing.EAST -> east
        Facing.SOUTH -> south
        Facing.WEST -> west
    }

    /**
     * What an axis has actually scored: its two edges multiplied together, so
     * reaching only one of them is worth nothing at all.
     */
    fun product(axis: Axis): Int = on(axis.first) * on(axis.second)
}
