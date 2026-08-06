# Crazy 8s — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is in the
game setup screen.

## Table

- 2 to 6 players — **toggle**. Everyone plays for themselves.
- One 52-card deck.
- Seven cards each heads-up, five otherwise — **toggle** to fix it at 5, 7 or 9
  instead.
- The rest becomes the stock; one card is turned up to start the discard pile.
  If that card is an eight, the suit it shows is the one in force.

## Play

On your turn, play one card that matches either

- the **suit in force**, or
- the **rank** of the card on top of the pile.

**Eights are wild.** An eight may be played on anything, and the player names
the suit that is then in force. This is why the suit in force is stated in words
on the table rather than left to be read off the pile — after an eight, the top
card tells you nothing.

If you cannot play:

- **Draw until you can — *toggle*, on by default.** Keep drawing until a
  playable card turns up, or until there is nothing left to draw.
- With the toggle off, draw a single card; if it still does not play, the turn
  passes.
- When the stock is empty it is rebuilt from the discards, leaving the top card
  in place. If there is genuinely nothing to draw and nothing to play, you pass.

If every player passes in turn, the round is blocked and ends with nobody out.

## Scoring

The round ends when a player sheds their last card. Everyone else scores penalty
points for what they were caught holding:

| Card | Penalty |
| --- | --- |
| Eight | 50 |
| Ten, jack, queen, king | 10 |
| Ace | 1 |
| Everything else | Face value |

A blocked round is scored the same way — everyone still holding cards pays for
them.

Lowest total after the last round wins — **toggle** for 1, 3 or 5 rounds.

## What the computer opponent does

It plays the most expensive card it can get rid of, saves its eights until the
endgame or until it has nothing else, and when it does play one it names the
suit it holds most of.
