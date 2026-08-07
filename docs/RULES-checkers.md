# Checkers

English draughts, on the thirty-two dark squares of an eight-by-eight board.
Twelve pieces a side, Black moves first.

## The board

Squares are numbered 1 to 32 the way the game has always been written down:
square 1 at the top left, running along each row and down the board to 32. Black
starts on 1–12, White on 21–32, and the two middle rows are empty. Only the dark
squares are ever played on, so a move is two numbers — `11-15` for a quiet move,
`11x18` for a jump.

## Moving

A man moves one square diagonally **forwards**. Black moves down the board,
White up it.

A man that reaches the far row is **crowned** and becomes a king, which moves
one square diagonally in either direction.

## Taking

You take by jumping an enemy piece and landing on the empty square directly
beyond it. Three rules follow, and all three are enforced by the engine rather
than left to the players:

- **A capture is compulsory.** If anything can be taken anywhere on the board,
  a quiet move is not a legal move at all. The app simply will not offer one.
- **A multiple jump is one turn.** Where a jump can be followed by another with
  the same piece, it must be — and the whole chain is a single move. A shorter
  prefix of a longer capture is never offered, so there is no way to stop
  halfway.
- **A man may not jump backwards.** Only kings take in both directions.

You do *not* have to take the longest capture available: any complete jump will
do. That is the English rule; international draughts requires the maximum.

## Crowning mid-jump

A man that jumps into the far row is crowned and **the turn ends there**, even
with another jump available. This is the English rule and it is on by default.
Turn *Crowning ends the turn* off and the man carries on jumping as a man,
crowned only if it finishes on the back row — which is the international rule.

## Winning

A player who has no pieces, or who has pieces but no legal move, has lost. The
two are the same condition as far as the engine is concerned: no moves is a
loss, whatever the reason.

## Draws

- **Threefold repetition** — the same position with the same player to move,
  three times over.
- **Eighty plies without progress** — forty moves each with nothing taken and
  nothing crowned. Both are options.

## Flying kings

Off by default. Turned on, a king slides any distance along a diagonal and takes
an enemy piece from a distance, landing on any empty square beyond it — the
international king.

**One honest deviation:** a piece taken by a flying king is lifted immediately.
International draughts leaves captured pieces standing until the turn ends, so
they block the rest of the sequence, which occasionally forbids a chain this
implementation allows. Fixing it properly means tracking captured-but-present
pieces through the whole search, and it is written down here rather than
pretended away.

## What the computer does

Negamax with alpha-beta pruning and iterative deepening, to seven ply at the
strongest level — deeper than the chess player reaches, because compulsory
capture keeps the branching factor low.

There is no quiescence search and it does not need one: the usual failure of a
fixed-depth search is stopping in the middle of an exchange, and compulsory
capture handles that by itself, since a position where a piece has just been
taken usually has the recapture as its only legal move.

Its evaluation counts material first, with kings worth well over a man, and then
two things beginners underrate: **men are worth more the closer they are to
being crowned**, and **the back row is worth holding**, because an empty back
row is a road to a crown for the other side. The two pull against each other,
which is about right.

## The move generator is checked against published numbers

`CheckersRulesTest` counts the leaf nodes below the opening position — 7, 49,
302 and 1469 at one to four ply — and those are the published figures for
English draughts. A generator that matches them that far has very little room
left to be wrong: the counts fold in compulsory capture, crowning and the
men's forward-only rule all at once.
