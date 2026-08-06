# Wizard — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is in the
game setup screen.

## Table

- 3 to 6 players — **toggle**. Everyone plays for themselves.

## The deck — 60 cards

An ordinary 52-card pack plus **four wizards** and **four jesters**.

## Rounds

Round 1 deals one card each, round 2 deals two, and so on until the pack runs
out. That is 20 rounds with three players, 15 with four, 12 with five and 10
with six. **Toggle** to stop after 5 or 10 rounds instead of playing the lot.

After the deal, the next card off the pack is turned for trump:

- an ordinary card — its suit is trump;
- a **jester** — the round is played with no trump;
- a **wizard** — the dealer names trump before anyone bids.

In the last round the whole pack has been dealt, so there is no card to turn and
no trump.

## Bidding

Starting to the dealer's left, each player says exactly how many tricks they
will take, from none up to the number of cards in hand. Every bid is heard, so
the later you bid the more you know.

**Screw the dealer — *toggle*, off by default.** The dealer may not name the
number that would make the bids add up to the tricks available, so at least one
player at the table is always going to be wrong.

## Play

The player to the dealer's left leads. Follow the led suit if you hold it —
except that a **wizard or a jester may always be played**, whatever you hold.
Playing one is how you get out of following.

**What suit is led:**

- an ordinary card leads its suit;
- a **jester** leads nothing — the suit is set by the next ordinary card;
- a **wizard** reaching the front means no suit is led at all, and everyone may
  play whatever they like.

**Who takes the trick:**

1. The **first wizard** played takes it, whatever else is down.
2. Otherwise the highest trump.
3. Otherwise the highest card of the led suit.
4. A **jester** never wins — except a trick of nothing but jesters, which goes
   to the first one played.

## Scoring

| Result | Score |
| --- | --- |
| Bid made exactly | 20, plus 10 for each trick taken |
| Bid missed | −10 for every trick over or under |

So bidding none and taking none is worth 20; bidding three and taking three is
worth 50; bidding three and taking one costs 20.

Highest total after the last round wins.

## What the computer opponent does

It counts its expected tricks — a wizard is one, a jester is none, and
everything else is a fraction depending on how high it is and whether it is
trump — and bids the nearest number still on offer. In play it chases tricks
while it is short of its bid, taking them as cheaply as it can, and ducks once
it has them, leading a jester when it has one to spare.

Over long runs of self-play it lands its bid exactly around half the time,
against about one in six for random legal play.
