# Sequence — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is exposed
in the game setup screen.

## ⚠️ The board layout is not the commercial one

`SequenceBoard.LAYOUT` in `SequenceModel.kt` is a **generated** 10×10 layout, not
a reproduction of the printed Sequence board. It was generated because the real
arrangement could not be verified from here, and shipping a guessed one labelled
as authentic would have been worse than shipping an honest substitute.

It satisfies every property the rules depend on:

- each of the 48 non-jack cards appears exactly twice,
- the four corners are free squares,
- no card is adjacent to its own twin (including diagonally).

So the game plays correctly — but square for square it will not match a physical
board, and two players using this app plus one physical board would disagree.
To fix that, replace the ten strings in `LAYOUT` with the real arrangement.
Nothing else needs to change; the parser, the tests and the AI all read from it.

## Table

- **Teams — *toggle*.** Two or three. Seats are dealt round-robin into teams, so
  team-mates are never seated adjacently.
- **Players per team — *toggle*.** 1 to 4.
- **Sequences to win — *toggle*.** Defaults to 2 for two teams and 1 for three
  teams, matching the usual rule, but can be set explicitly.

## Cards

Two full 52-card decks, shuffled together — 104 cards.

Hand size shrinks as the table grows:

| Players | Cards |
| --- | --- |
| 2 | 7 |
| 3–4 | 6 |
| 5–6 | 5 |
| 7–9 | 4 |
| 10+ | 3 |

After playing a card you draw a replacement. If the draw pile runs out, the
discards are shuffled to form a new one.

## Turn

Play one card and place a chip on a matching empty square, then draw.

- **Two-eyed jacks** (♦ and ♣) are wild: place a chip on any empty square.
- **One-eyed jacks** (♥ and ♠) remove one opposing chip — but never a chip that
  is part of a completed sequence, and never your own team's chip.

On a physical deck the difference is carried by the artwork: a jack drawn in
profile shows one eye, a jack drawn face-on shows two. These cards are drawn as
rank and suit only, so a jack in hand is captioned **WILD** or **REMOVE**
instead.
- **Dead card — *toggle*, on by default.** If both squares printed with your card
  are already occupied, you may discard it and draw a replacement. This does not
  cost your turn, and you may do it once per turn.

## Sequences

A sequence is five chips in a row — horizontally, vertically or diagonally.

- The **four corners are free squares** and count towards any team's sequence.
- Two sequences may **share at most one chip**. The engine enforces this by
  refusing to count a run of five that contains more than one already-locked
  square.
- Chips in a completed sequence are locked and cannot be removed by a one-eyed
  jack.

The first team to reach the sequence target wins immediately.

## Known simplifications

- A completely blocked table (no legal move for anyone) ends the game without a
  winner rather than triggering a formal draw.
- The AI does not count cards or track which squares opponents can still reach.
