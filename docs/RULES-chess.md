# Chess — implemented rules

The full game, not a subset. This is the ruleset the engine enforces. Anything
marked **toggle** is in the game setup screen.

## Table

Two players. Seat 0 is always White; choosing to play Black in setup puts you in
seat 1 and turns the board round so your pieces are still at the near edge.

## Movement

All of it, including the parts that are usually left out:

- **Castling**, both sides, with every condition: the king and the rook must not
  have moved, the squares between them must be empty, and the king may not
  castle out of check, through an attacked square, or into check. The b-file
  square may be attacked on the queen's side — the king does not cross it.
- **En passant**, on the move immediately after a pawn steps two squares and
  only then. It is refused when taking would expose your own king, which is the
  one case a naive implementation gets wrong.
- **Promotion** to queen, rook, bishop or knight, chosen when the pawn arrives.
- **Pins** — a piece that shields its own king may not step off the line.

Rights are given up correctly: a rook that moves loses its castling right, and
so does a rook captured on its own corner.

## How a game ends

| Ending | When |
| --- | --- |
| Checkmate | No legal move, and in check |
| Stalemate | No legal move, and not in check |
| Insufficient material | Neither side has enough to mate, whatever either plays |
| Fifty-move rule | A hundred half-moves with no capture and no pawn move — **toggle** |
| Threefold repetition | The same position, side to move and rights, three times — **toggle** |

Insufficient material covers bare kings, a king and one minor piece, and two
bishops on the same colour square. King and two knights is *not* included: it
cannot be forced, but it can happen with cooperation, so it is not a dead
position.

Both draw rules apply automatically rather than needing a claim. Switch either
off in setup for a game that runs until somebody is mated.

## Notation

The move list is ordinary algebraic: `e4`, `Nf3`, `exd5`, `O-O`, `a8=Q+`,
`Ra8#`. Two pieces that can reach the same square are told apart by file, or by
rank when they share a file, or by the whole square when they share both.

## Positions

The engine reads and writes FEN, so a table can start from a set position rather
than the opening array — a puzzle, or an adjourned game.

## What the computer opponent does

A negamax search with alpha-beta pruning, iterative deepening, and a
capture-only quiescence tail. The quiescence tail is what stops it giving pieces
away: a search that simply stops after a fixed number of moves scores a position
in the middle of an exchange as though the recapture never comes.

Evaluation is material plus piece placement, with a term in bare endgames that
drives the losing king towards the edge — without it a won endgame just shuffles.

**Toggle** for three strengths:

| Level | Search | Plays |
| --- | --- | --- |
| Casual | 2 ply | Takes any move within about two thirds of a pawn of the best one |
| Club | 3 ply | Nearly always the best move it can see |
| Strong | 4 ply | The best move it can see |

Every level is capped by a node budget, so no single move can hang the app on a
sharp position — it plays the best move from the last depth it finished.

## Testing

Move generation is checked against the published **perft** counts: the exact
number of positions reachable at a given depth. The opening array is verified to
depth four (197,281 positions), along with four standard test positions chosen
because they break generators — castling through check, en passant that would
expose a king, promotion with capture, and heavily pinned pieces.
