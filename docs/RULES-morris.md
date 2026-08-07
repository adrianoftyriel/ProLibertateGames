# Nine Men's Morris

Two players, nine pieces each, on twenty-four points. Make a line of three and
you take one of theirs. Take them down to two and the game is yours.

## The board

```
a7 ────────── d7 ────────── g7
│              │              │
│  b6 ─────── d6 ─────── f6   │
│  │           │           │  │
│  │   c5 ─── d5 ─── e5    │  │
│  │   │               │   │  │
a4 ─ b4 ─ c4         e4 ─ f4 ─ g4
│  │   │               │   │  │
│  │   c3 ─── d3 ─── e3    │  │
│  │           │           │  │
│  b2 ─────── d2 ─────── f2   │
│              │              │
a1 ────────── d1 ────────── g1
```

Twenty-four points on three squares. A piece moves along a drawn line to the
next point on it: around a square, or between squares along one of the four
ladders that run out through the middle of each edge. **The corners have no
ladder**, which is why the middle of an edge is worth more than the corner
beside it — four ways off the middle square's mid-edge points, two off a corner.

There is no point at the centre of the board, and the diagonals some boards are
printed with are not part of this game.

Points are named as the game is normally recorded: files **a**–**g** left to
right, ranks **7**–**1** top to bottom. Only the intersections listed above
exist; `d4` is the hole in the middle.

## A game

**Placing.** Starting with White, each player puts one piece at a time on any
empty point until all eighteen are down.

**Moving.** After that, a turn is one piece stepping along a line to an adjacent
empty point.

**Flying.** A player reduced to three pieces may move a piece to *any* empty
point instead of stepping. This is on by default and can be turned off.

## Mills

Three of your pieces on one of the sixteen marked lines is a **mill**, and
closing one takes an enemy piece off the board. Four mills to a square, twelve
in all, plus the four that run out along the ladders.

Three things about mills are easy to get wrong, and all three are implemented as
written here:

- **A mill is closed by the piece that arrives.** Moving a piece *out* of a mill
  takes nothing. Moving it back in closes the mill again and takes another
  piece — the same mill, opened and closed repeatedly, is a perfectly good
  way to win, and it is the strongest thing in the game.
- **Sliding along the line does not count.** If the point you left is part of
  the line you have just completed, it is not a mill: the third piece it needs
  is the one that made the move.
- **A piece standing in a mill cannot be taken** — unless every enemy piece is
  standing in one, in which case any of them may be taken. Without that
  exception a player whose pieces were all in mills could never be reduced, and
  the game would not end.

Closing a mill is not optional, and neither is taking for it: a move that
completes a line always carries the piece it removes. The only exception is the
case where there is nothing left to take, when the mill simply goes unpaid.

## Winning

- **Reduced to two.** A player with two pieces left cannot make a line and has
  lost. This only applies once their hand is empty — a player mid-placement with
  two on the board has seven more coming.
- **Nowhere to go.** A player with no legal move has lost. Since there is always
  an empty point to place on, this can only happen once pieces are being moved.

## Draws

Two players who can each hold a position have no reason ever to stop, so:

- **Threefold repetition.** The same board with the same player to move, three
  times over, is a draw.
- **No mill for a hundred plies.** Fifty moves each with nothing taken is a
  draw. Both figures are options, and the second can be turned off.

## Options

| | |
| --- | --- |
| **Opponent** | Casual, Club or Strong — how deep the computer searches |
| **Pieces each** | 3, 6 or 9 |
| **Flying on three** | on by default |
| **Threefold repetition** | on by default |
| **Draw with no mill for** | never, 50, 100 or 200 plies |

Fewer than nine pieces is a shorter game on the same board. It is *not* Three or
Six Men's Morris, which are played on smaller boards of their own; calling it
that would be claiming more than the code does.

## Playing it here

Tap an empty point to place a piece. Once everything is on the board, tap one of
your own pieces and then tap where it goes; tapping a different piece of yours
picks that one up instead. When a move closes a mill, the pieces you may take
are ringed in red — tap one. If only one piece may be taken there is nothing to
choose and the move goes straight through.

The board is not turned round for Black. It is symmetrical and both players are
looking at the same lines.

## What the computer does

A negamax search with alpha-beta pruning and iterative deepening, over the same
rules engine the game itself runs on, with a node budget so a phone does not
stall on the placing phase.

It has no quiescence search and does not need one: in chess a fixed-depth search
goes wrong by stopping halfway through an exchange, and in Morris there are no
exchanges — a piece is taken for a mill and nothing is taken back.

Its evaluation counts pieces first, then mills, lines that are one piece short
of a mill, how much room each side has to move, and how many of the mid-edge
junctions each side holds. It also knows that **being down to three is
dangerous even with flying on** — without that, the search notices that three
pieces can go anywhere, reads it as freedom, and starts steering towards losing
pieces to get there.
