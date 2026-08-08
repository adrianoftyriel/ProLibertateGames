package org.prolibertate.games.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.prolibertate.games.ui.common.CARD_ASPECT
import org.prolibertate.games.ui.theme.FeltGreenDark
import org.prolibertate.games.ui.theme.Parchment
import org.prolibertate.games.ui.theme.WallaceGold

/**
 * The furniture every patience table needs.
 *
 * FreeCell, Spider and Pyramid lay their cards out quite differently but they
 * are all made of the same three things: a place a card sits, an empty place
 * that can still be tapped into, and the two dialogs for leaving. Klondike keeps
 * its own copies — it was written first and its layout constants are tuned to
 * seven columns — but nothing new needs to repeat them.
 */
val TABLE_CARD_WIDTH: Dp = 44.dp
val TABLE_CARD_HEIGHT: Dp = TABLE_CARD_WIDTH / CARD_ASPECT

/** Overlapped by a third, which hides as little as a stack can afford to. */
val TABLE_STACK_STEP: Dp = TABLE_CARD_HEIGHT * 2f / 3f

@Composable
fun TableSlot(
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TABLE_CARD_WIDTH,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .width(width)
            .border(
                border = BorderStroke(if (highlighted) 3.dp else 0.dp, WallaceGold),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
    ) {
        content()
    }
}

/** A place with no card on it, drawn so it is still somewhere to aim at. */
@Composable
fun EmptySpace(label: String = "", width: Dp = TABLE_CARD_WIDTH) {
    Box(
        modifier = Modifier
            .width(width)
            .height(width / CARD_ASPECT)
            .background(FeltGreenDark, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = Parchment.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun PatienceEndDialog(what: String, onEnd: () -> Unit, onKeepPlaying: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text("End this game?") },
        text = { Text(what) },
        confirmButton = { TextButton(onClick = onEnd) { Text("End the game") } },
        dismissButton = { TextButton(onClick = onKeepPlaying) { Text("Keep playing") } },
    )
}

@Composable
fun PatienceLeftDialog(notice: String, onExit: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("The game has ended") },
        text = { Text(notice) },
        confirmButton = { TextButton(onClick = onExit) { Text("Leave the game") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Look at the table") } },
    )
}
