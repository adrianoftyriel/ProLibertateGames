package org.prolibertate.games.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.engine.PlayerKind
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

/**
 * The wire protocol, shared by every transport.
 *
 * The host is authoritative: clients send [MoveIntent] and render whatever
 * [StateSync] tells them. Nothing is trusted from a client beyond "I would like
 * to make this move", which the host re-validates against the rules before it
 * takes effect. Each client is sent a state redacted for its own seat, so an
 * opponent's hand is never transmitted in the first place.
 */
/**
 * 2 added the heartbeat. A peer that does not answer [Ping] cannot be told
 * apart from one that has gone away, so both ends have to speak it or a quiet
 * link would be dropped as dead the first time nobody moved for a while. The
 * lobby already refuses a mismatch with "update both devices", which is the
 * honest thing to say about it.
 */
const val PROTOCOL_VERSION = 2

/** Service type and name advertised over mDNS. */
const val SERVICE_TYPE = "_plgames._tcp"
const val SERVICE_NAME = "ProLibertateGames"

val protocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "t"
}

/**
 * Which seat [deviceId] is sitting in, given the table the host has settled on.
 *
 * A guest has to answer this for itself: the seat list arrives as a whole and
 * says nothing about who is reading it. Matching on [PlayerSlot.deviceId] is the
 * only reliable way round, because that is the one field a guest can recognise —
 * [PlayerSlot.peerId] is an address the host's transport invented for the link
 * and bears no relation to anything the guest knows about itself.
 *
 * The fall back to the first remote seat is for a host running a version that
 * sends no device ids. It is right whenever there is only one guest, and with two
 * it hands them both the same seat, which is why it is only ever reached last.
 */
fun seatForDevice(config: TableConfig, deviceId: String): Int =
    config.seats.firstOrNull { it.deviceId == deviceId }?.seat
        ?: config.seats.firstOrNull { it.kind == PlayerKind.HUMAN_REMOTE }?.seat
        ?: 0

@Serializable
sealed interface NetMessage

/** First message a joining client sends. */
@Serializable
@SerialName("hello")
data class Hello(
    val peerId: String,
    val displayName: String,
    val protocol: Int = PROTOCOL_VERSION,
) : NetMessage

/** Host's reply, or a refusal if the lobby is full or the versions differ. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val hostName: String,
    val gameId: String,
    val accepted: Boolean,
    val reason: String? = null,
) : NetMessage

/** Broadcast whenever the seat list or options change. */
@Serializable
@SerialName("lobby")
data class LobbyUpdate(
    val gameId: String,
    val seats: List<PlayerSlot>,
    val optionsJson: String,
    val hostName: String,
) : NetMessage

/** Host starts the match; everyone builds the same initial state from this. */
@Serializable
@SerialName("start")
data class StartGame(val config: TableConfig) : NetMessage

/** A client's redacted view of the authoritative state. */
@Serializable
@SerialName("state")
data class StateSync(
    val seq: Int,
    val yourSeat: Int,
    val stateJson: String,
    /** Encoded moves this seat may currently make. Empty when it is not its turn. */
    val legalMoves: List<String>,
) : NetMessage

/** A client asking to play a move. The host validates it. */
@Serializable
@SerialName("intent")
data class MoveIntent(val moveJson: String) : NetMessage

/**
 * A client asking for the current state.
 *
 * A guest's link is opened in the lobby and is only handed to the table once the
 * host's [StartGame] has been through a recomposition, so the host publishes the
 * opening position before the guest's table is listening for it. Rather than
 * leave that to timing, the guest asks as soon as it is listening — which is also
 * what gets a guest back in step after any missed push.
 */
@Serializable
@SerialName("resync")
data object Resync : NetMessage

@Serializable
@SerialName("rejected")
data class Rejected(val reason: String) : NetMessage

/**
 * Sent to a link nobody has spoken on for a while, and answered with [Pong].
 *
 * Two things need it. A phone whose screen has gone off puts its Wi-Fi radio
 * into power save and, given long enough, stops the app's networking
 * altogether; an access point ages an idle association out of its tables. A
 * link carrying nothing between one player's turn and the next is idle for
 * minutes at a time, which is exactly what both of those collect. And a TCP
 * link that dies that way dies quietly: the read never returns and the next
 * write is buffered into a socket that no longer goes anywhere, so without
 * traffic of its own the app cannot tell a link that is waiting from one that
 * is gone.
 *
 * Neither reaches a screen — see [StreamConnection], which answers and swallows
 * them, so nothing above the transport has to know they exist.
 */
@Serializable
@SerialName("ping")
data object Ping : NetMessage

@Serializable
@SerialName("pong")
data object Pong : NetMessage

@Serializable
@SerialName("bye")
data object Bye : NetMessage
