# Golf — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is in the
game setup screen. Lowest score wins, as on a golf course.

## Table

- 2 to 6 players — **toggle**.
- Each player gets a grid of face-down cards — **toggle**:

  | Cards | Layout |
  | --- | --- |
  | 4 | 2 rows of 2 |
  | 6 | 2 rows of 3 |
  | 9 | 3 rows of 3 |

- **Seen at the start — *toggle*.** How many of your own cards you may look at
  before play begins; two by default. They are the leftmost cards of the top
  row, so everyone starts with the same shape of knowledge.
- One card is turned up to start the discard pile; the rest is the stock.
- Six players on the 9-card board needs more cards than a deck holds, so two
  decks are shuffled together whenever the deal would exceed 24 cards.

## A turn

**Draw** one card, from either the stock or the top of the discard pile.

Then **place** it, in one of two ways:

- **Swap it in.** Put it into any grid slot, face up, and throw out whatever was
  there. That slot is now face up for the rest of the hole.
- **Throw it away and turn one of your own over.** Only available for a card
  taken from the *stock* — a card picked up off the discard pile has to be used.

## Ending a hole

The first player to have their whole grid face up closes the hole, and everyone
else gets exactly one more turn. Then every card is turned up and scored.

If the stock runs out it is refilled by shuffling the discard pile, keeping its
top card in play. If there is nothing left to recycle, the hole ends there.

## Scoring

| Card | Value |
| --- | --- |
| King | 0 |
| Ace | 1 |
| Two | −2 |
| Jack, Queen | 10 |
| 3 to 10 | face value |

**A column of matching ranks cancels to nothing.** That is the whole tactical
point: a pair of jacks in a column costs nothing instead of twenty.

On the 3×3 board, matching **rows** cancel as well. On the shallower boards they
do not — a "row" of three unrelated cards is not a line in a two-row grid.

Scores accumulate over the holes — **toggle** for 3, 6 or 9 — and the lowest
total wins.

## Known simplifications

- **No jokers.** Some house rules add them as −5 or wild. Adding a joker means
  adding a rank to the shared card model, which every other game would then have
  to account for, so it is left out rather than bolted on.
- No knocking, and no separate penalty for a failed knock.
- Nothing is peeked at mid-hole; the only way to learn a card is to turn it over.
