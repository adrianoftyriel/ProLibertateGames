# Euchre — implemented rules

This is the ruleset the engine enforces, so you can check it against how you
actually play. Anything marked **toggle** is exposed in the game setup screen;
anything else is currently fixed and would need a code change.

## Table

- Four players, two fixed partnerships. Seats 0 and 2 are one team, seats 1 and
  3 are the other, so partners sit opposite each other.
- The deal rotates one seat clockwise after every hand.

## Deck — *toggle*

- **24 cards** (9, 10, J, Q, K, A in each suit). The standard game.
- **32 cards** (7 through A). Five cards are still dealt to each player and one
  is still turned up, so the extra cards simply deepen the undealt remainder.
  Some 32-card variants deal eight cards and play eight tricks; that is *not*
  what this does.

## Ranking

With trump named, the order from the top is:

1. **Right bower** — the jack of trump.
2. **Left bower** — the jack of the *same colour* as trump. It counts as a trump
   card, not as a card of its printed suit. If spades are trump, the jack of
   clubs is a spade for the whole hand: it must be played when spades are led,
   and it does *not* satisfy following clubs.
3. A, K, Q, 10, 9 of trump.

Non-trump suits rank A, K, Q, J, 10, 9. A card that is neither trump nor of the
led suit cannot win a trick.

## Bidding

**Round one.** Starting to the dealer's left, each player may order up the
turned card or pass. Ordering up makes that card's suit trump; the dealer takes
the card into hand and discards one card face down.

**Round two.** If all four pass, the turn card is turned down and its suit may
not be named. Starting again to the dealer's left, each player may name any
other suit or pass.

**Stick the dealer — *toggle*, on by default.** If the first three players pass
in round two, the dealer must name a suit. With the toggle off, a fourth pass
throws the hand in and it is redealt by the next dealer.

**Going alone — *toggle*, on by default.** A maker may declare going alone when
ordering up or naming trump. Their partner sits the hand out entirely and plays
no cards; the hand is played three-handed.

## Play

- The player to the dealer's left leads to the first trick; after that the
  winner of each trick leads to the next.
- You must follow the led suit if you hold it, remembering that the left bower
  belongs to trump. Otherwise you may play anything.
- Five tricks are played.

## Scoring

Let the makers be the team that named trump.

| Outcome | Points |
| --- | --- |
| Makers take 3 or 4 tricks | 1 to the makers |
| Makers take all 5 | 2 to the makers |
| Makers take all 5, playing alone | 4 to the makers |
| Makers take fewer than 3 (euchred) | 2 to the defenders |

A lone hand that takes 3 or 4 tricks scores 1, the same as an ordinary make.

**Game — *toggle*.** First team to **10** points (default), or 11, or 15.

## Known simplifications

- Cards are dealt five at a time rather than in the traditional 3–2 / 2–3
  packets. With a shuffled deck this changes nothing about the odds, but it does
  mean the deal does not *look* like a real one.
- No defending alone, no farmer's hand, no notrump or misère bids.
