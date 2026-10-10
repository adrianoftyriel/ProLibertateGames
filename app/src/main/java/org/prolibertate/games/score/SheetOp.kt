package org.prolibertate.games.score

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One change to a score sheet, as something that can be sent.
 *
 * A shared sheet cannot work by guests posting whole sheets at the host: two
 * people entering a round at the same moment would each send a sheet missing the
 * other's, and one of the rounds would quietly vanish. Sending what was *done*
 * instead means the host can apply everybody's changes in turn, and two rounds
 * entered together are simply two rounds.
 *
 * Players are named by id wherever they are named at all, for the same reason
 * [ScorePlayer.id] exists: a position is only right until somebody else moves
 * a column. Rounds have no id, so [EditRound] and [DeleteRound] go by index; if
 * two people delete rounds in the same instant the second can land on the wrong
 * one. Both of them are in the room looking at the same table, and the sheet
 * shows what happened.
 *
 * Applying an op to a sheet it no longer makes sense for — editing a round that
 * has since been deleted, renaming a player who has been removed — does nothing,
 * as the sheet's own methods already do.
 */
@Serializable
sealed interface SheetOp {
    fun applyTo(sheet: ScoreSheet): ScoreSheet
}

/** Sets a fresh sheet up for [count] players. */
@Serializable
@SerialName("start")
data class StartSheet(val count: Int) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = ScoreSheet.of(count)
}

/** Rubs everything out. */
@Serializable
@SerialName("clear")
data object ClearSheet : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = ScoreSheet()
}

@Serializable
@SerialName("add-player")
data object AddPlayer : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.withPlayerAdded()
}

@Serializable
@SerialName("remove-player")
data class RemovePlayer(val playerId: Int) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.withPlayerRemoved(playerId)
}

@Serializable
@SerialName("rename")
data class RenamePlayer(val playerId: Int, val name: String) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.renamed(playerId, name)
}

/** Moves a player's column so that it sits at position [to]. */
@Serializable
@SerialName("move-player")
data class MovePlayer(val playerId: Int, val to: Int) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet {
        val from = sheet.players.indexOfFirst { it.id == playerId }
        return if (from < 0) sheet else sheet.moved(from, to)
    }
}

@Serializable
@SerialName("add-round")
data class AddRound(val deltas: Map<Int, Int>) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.withRound(deltas)
}

@Serializable
@SerialName("edit-round")
data class EditRound(val index: Int, val deltas: Map<Int, Int>) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.withRoundAt(index, deltas)
}

@Serializable
@SerialName("delete-round")
data class DeleteRound(val index: Int) : SheetOp {
    override fun applyTo(sheet: ScoreSheet): ScoreSheet = sheet.withoutRound(index)
}
