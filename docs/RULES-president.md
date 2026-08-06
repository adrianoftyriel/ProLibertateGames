# President — implemented rules

Also called Scum, Landlord, and a good deal worse. This is the ruleset the
engine enforces. Anything marked **toggle** is in the game setup screen.

## Table

- 3 to 7 players — **toggle**. Everyone plays for themselves.
- The whole 52-card deck is dealt out round-robin, so hands differ by at most
  one card. There is no kitty and nothing is held back.

## Ranking

Threes are low and twos are high:

```
3 4 5 6 7 8 9 10 J Q K A 2
```

Suits are irrelevant. A card only ever competes on rank.

## Play

The player holding the lowest card leads the first round; afterwards the
previous round's Scum leads.

- **Leading.** Play any number of cards of the same rank — a single, a pair, a
  triple, four of a kind. That count sets the size of the set for this pile.
- **Following.** Play the same number of cards, all of one rank, of a strictly
  higher rank. Or pass.
- Passing does not put you out of the round, only out of this pile.
- When everyone still holding cards has passed, the pile is taken down and
  whoever played last leads a fresh one.

**Twos clear the pile — *toggle*, on by default.** A set of twos beats anything
regardless of rank, takes the pile down, and the player who threw it leads
again.

**Four of a kind bombs — *toggle*, off by default.** Four matching cards beat
anything, whatever is on the pile, and clear it the same way.

## Going out

Shed your last card and you are out, taking the next free finishing position:
President first, then Vice President, down through the Citizens to Vice Scum and
Scum. The round ends when only one player still holds cards — they are the Scum.

Going out on a pile-clearing set does not let you lead the next pile; the lead
passes to the next player still in.

## Scoring

Finishing position scores `playerCount - 1 - position`, so in a four-hand game
the President takes 3, the Vice President 2, Vice Scum 1 and the Scum nothing.
Highest total after the last round wins — **toggle** for 3, 5 or 7 rounds.

## Card exchange — *toggle*, on by default

Between rounds the Scum hands their two best cards to the President, and the
President hands two of their worst back.

**Simplification:** the President's return is automatic — their two lowest
cards. The fuller rule lets the President choose which two to give back, which
would need a phase of its own; that is not implemented.

## Known simplifications

- No eights-stop, no revolution, no jokers, no suit-based tie-breaks.
- The exchange is always two cards, rather than scaling with position.
