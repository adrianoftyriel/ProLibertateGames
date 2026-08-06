package org.prolibertate.games.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.prolibertate.games.game.cards.Card
import org.prolibertate.games.ui.theme.CardBlack
import org.prolibertate.games.ui.theme.CardRed

/**
 * A playing card.
 *
 * Sized by width with a fixed 5:7 aspect ratio so the same composable works
 * from a phone in portrait up to a tablet in landscape — callers pass a width
 * derived from the space they actually have rather than a hard-coded dp.
 */
@Composable
fun PlayingCardView(
    card: Card,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    selected: Boolean = false,
    enabled: Boolean = true,
    /**
     * Short note printed on the face, for when the rank and suit alone do not
     * say what the card does — a Sequence jack being the obvious case, since
     * one-eyed and two-eyed jacks are only distinguishable by their artwork.
     */
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val ink = if (card.suit.isRed) CardRed else CardBlack
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(CARD_ASPECT)
            .clip(RoundedCornerShape(width * 0.12f))
            .background(Color.White)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                shape = RoundedCornerShape(width * 0.12f),
            )
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Shrink the pips slightly when a caption has to share the face.
        val pipScale = if (caption == null) 0.34f else 0.28f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = card.rank.short,
                color = ink,
                fontWeight = FontWeight.Bold,
                fontSize = (width.value * pipScale).sp,
            )
            Text(
                text = card.suit.symbol,
                color = ink,
                fontSize = (width.value * pipScale).sp,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    color = ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = (width.value * 0.145f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The back of a card, for hands you are not allowed to see. */
@Composable
fun CardBackView(
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(CARD_ASPECT)
            .clip(RoundedCornerShape(width * 0.12f))
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, Color(0x33000000), RoundedCornerShape(width * 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(width * 0.12f)
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(width * 0.06f)),
        )
    }
}

const val CARD_ASPECT = 5f / 7f
