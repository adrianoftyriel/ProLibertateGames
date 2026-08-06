package org.prolibertate.games.game.engine

import kotlinx.serialization.Serializable

@Serializable
enum class PlayerKind {
    /** A person on this device. */
    HUMAN_LOCAL,

    /** A person on another device, reached over LAN or Bluetooth. */
    HUMAN_REMOTE,

    /** Driven by the host's AI. */
    AI,
}

@Serializable
data class PlayerSlot(
    val seat: Int,
    val name: String,
    val kind: PlayerKind,
    /** Partnership index. Solo games put every seat on its own team. */
    val team: Int,
    /** Identifies the remote device for [PlayerKind.HUMAN_REMOTE] seats. */
    val peerId: String? = null,
    /**
     * The joining device's own id, as it introduced itself.
     *
     * Kept alongside [peerId] because the two answer different questions.
     * [peerId] is how the host recognises which link a message came in on, and is
     * an address the transport made up — a socket's IP, a Bluetooth MAC. This is
     * how a guest recognises *itself* in a seat list it did not write, and only
     * the guest's own id can do that.
     */
    val deviceId: String? = null,
)

@Serializable
data class TableConfig(
    val gameId: String,
    val seats: List<PlayerSlot>,
    /** Game-specific options, serialised by the owning rules implementation. */
    val optionsJson: String,
    /** Shared shuffle seed so a hand is reproducible from its state. */
    val seed: Long,
)

/**
 * A game's rules, expressed as a pure state machine.
 *
 * Implementations must be free of Android dependencies and free of side
 * effects: [applyMove] returns a new state rather than mutating. That is what
 * lets the same code drive the local AI, the host's authoritative copy, and
 * the unit tests.
 */
interface GameRules<S : Any, M : Any> {

    val gameId: String

    fun initialState(config: TableConfig): S

    /** The seat expected to move, or null when nobody is on the clock. */
    fun currentSeat(state: S): Int?

    fun legalMoves(state: S, seat: Int): List<M>

    /**
     * Applies [move] on behalf of [seat].
     *
     * @throws IllegalArgumentException if the move is not currently legal —
     * the host validates every intent that arrives over the network this way.
     */
    fun applyMove(state: S, seat: Int, move: M): S

    fun isFinished(state: S): Boolean

    fun summary(state: S): String

    /**
     * Strips information [seat] is not entitled to see.
     *
     * The host holds the true state and sends each client only its own view,
     * so an opponent's hand never crosses the wire in the first place. Any
     * hidden-information game must implement this meaningfully.
     */
    fun viewFor(state: S, seat: Int): S

    fun encodeState(state: S): String
    fun decodeState(json: String): S
    fun encodeMove(move: M): String
    fun decodeMove(json: String): M
}

/** Chooses moves for unoccupied seats. Always runs on the host. */
interface GameAi<S : Any, M : Any> {
    fun chooseMove(state: S, seat: Int, legal: List<M>): M
}
