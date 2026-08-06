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
const val PROTOCOL_VERSION = 1

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

@Serializable
@SerialName("bye")
data object Bye : NetMessage
