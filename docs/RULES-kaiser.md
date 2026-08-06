# Kaiser — implemented rules

Kaiser is the prairie game, played hard in Saskatchewan and not much anywhere
else. This is the ruleset the engine enforces. Anything marked **toggle** is in
the game setup screen.

## Table

Four players in two partnerships, sitting opposite. Seats 0 and 2 are one team,
seats 1 and 3 the other.

## The deck — 32 cards

Eight through ace in every suit, plus:

- the **seven of clubs** and the **seven of diamonds**, which are ordinary cards
  making up the count, and
- the **five of hearts** and the **three of spades**, which replace the other
  two sevens and are what the whole game is played for.

Eight cards each, and the deck is exhausted.

## What a hand is worth

Ten points, always:

| Source | Points |
| --- | --- |
| Each of the eight tricks | 1 |
| Taking the five of hearts | +5 |
| Taking the three of spades | −3 |

So a trick containing the five of hearts is worth six, and one containing the
three of spades is worth minus two. Both in the same trick is worth three.

## Bidding

Bidding opens to the dealer's left and goes round. Each bid is a number of
points the bidder's side undertakes to take.

- The floor is **5, 6, 7 or 8** — **toggle**, six by default.
- The ceiling is ten, because ten is the most a hand can be worth. Bidding
  above what exists is only a way to guarantee going down, so it is not offered.
- **No trump** — **toggle**, on by default. A no-trump bid outranks the same
  number bid with a suit; it pays double and costs double.
- Each bid must beat the one before it.
- If the first three players pass, the dealer must bid — there is no throwing
  the hand in from the dealer's seat. If all four pass (only possible when the
  dealer's forced bid is somehow not reached), the hand is redealt.

The winning bidder then names trump, or is committed to no trump if that is what
they bid.

## Play

The bidder leads. Follow suit if you can; otherwise play anything.

There are no bowers — Kaiser ranks straight down from the ace, so the jack is
just a jack. Trump beats every other suit; otherwise only the led suit can win.

## Scoring

- **Bidding side made it** (took at least the bid): they score every point they
  took, doubled if the contract was no trump.
- **Bidding side went down**: they lose the amount of the bid, doubled if it was
  no trump.
- **Defenders always keep what they took**, made or not.

First side to the target wins — **toggle** for 32, 52 or 62 points. A side that
falls to the matching negative score loses outright; without that floor a table
that keeps bidding and keeps going down never reaches a result at all.

## What the computer opponent does

It rates each suit by trump length and top cards, adds for holding the five of
hearts and subtracts for holding the three of spades, and bids the floor rather
than reaching. In play it chases the five of hearts, ducks when the three of
spades is on the table, and lets its partner have a trick it is already winning.
