package org.prolibertate.games.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.prolibertate.games.game.engine.PlayerSlot
import org.prolibertate.games.game.engine.TableConfig

/**
 * The wire protocol, shared by the LAN and Bluetooth transports.
 *
 * The host is authoritative: clients send [MoveIntent] and render whatever
 * [StateSync] tells them. Nothing is trusted from a client beyond "I would like
 * to make this move", which the host re-validates against the rules before it
 * takes effect. Each client is sent a state redacted for its own seat, so an
 * opponent's hand is never transmitted in the first place.
 */
const val PROTOCOL_VERSION = 1

/** Service type advertised over mDNS, and the RFCOMM service name. */
const val SERVICE_TYPE = "_plgames._tcp"
const val SERVICE_NAME = "ProLibertateGames"

/** Fixed RFCOMM UUID. Both ends must agree; this is ours. */
const val RFCOMM_UUID = "8f1d5a20-3a4e-4b26-9d31-6c0d2f7a91b4"

val protocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "t"
}

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

@Serializable
@SerialName("rejected")
data class Rejected(val reason: String) : NetMessage

@Serializable
@SerialName("bye")
data object Bye : NetMessage
