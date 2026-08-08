# Cribbage — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is in the
game setup screen.

## Table

- 2, 3 or 4 players — **toggle**.
  - **Two** play head to head, six cards each, laying two away.
  - **Three** play singly, five cards each, laying one away — and a fourth card
    is dealt straight into the crib, which is the only way three hands can fill
    it.
  - **Four** play in partnerships, sitting across from each other. Five cards
    each, one away, and **the two partners peg on one score**: your partner's
    crib is your crib, and their points are yours.
- One 52-card deck. The deal moves one seat to the left each hand.
- Game to **121** — twice round the board — or **61** — once — **toggle**.

Whatever the table, everybody keeps four cards and the crib holds four.

## The cut

Once everyone has laid away, the starter is cut and turned up. It belongs to
every hand and to the crib at the show.

**A jack turned is two for his heels**, and they are the dealer's however the
cut fell.

## The play

Starting at the dealer's left, cards are laid down one at a time and the count
runs up towards **thirty-one, which it may never pass**. A card that would take
it past cannot be played.

Scored as the card lands:

| | |
| --- | --- |
| Count reaches fifteen | 2 |
| Count reaches thirty-one | 2 |
| Pair | 2 |
| Third of a rank (pair royal) | 6 |
| Fourth of a rank (double pair royal) | 12 |
| Run of three or more | 1 a card |

Pairs and runs are read off the **end of the cards on the table**, so a pair
split by somebody else's card is not a pair, and a run counts however jumbled
the order it was laid in — 5, 3, 4 is a run of three.

**A player who cannot play says go**, and the table plays past them. When
nobody can play at all, whoever laid the last card takes **one for the last
card**, the count is cleared, and the seat after them starts the next one.
Thirty-one has already paid two, so no extra point is taken for it.

A go is called by the engine rather than tapped: the count only ever rises
within a series, so a hand that cannot play now cannot play later in it either,
and there is nothing to decide.

## The show

The hands are counted **from the dealer's left, round to the dealer, and the
dealer's crib last of all**. Each hand is its four cards plus the starter.

| | |
| --- | --- |
| Each combination of cards adding to fifteen | 2 |
| Each pair | 2 |
| Each run of three or more | 1 a card |
| Four cards of one suit in hand | 4 |
| …with the starter matching | 5 |
| Jack in hand of the starter's suit (his nob) | 1 |

Duplicated ranks multiply runs rather than lengthening them: 4-5-5-6 is **two
runs of three**, not a run of four.

**A crib takes nothing for four of a suit.** It scores a flush only when all
five cards, starter included, share one — the one rule that makes counting a
crib different from counting a hand.

The best hand in the game is twenty-nine: three fives and the jack of the suit
that is cut, with the fourth five as the starter.

## Winning

**The game ends on the point that reaches the target, mid-count if that is
where it falls.** A non-dealer who can count out has won before the dealer's
hand and crib are ever counted, and the table says so — hands the game never
got to are shown with what they would have been worth, greyed out rather than
added on.

**Skunks** — **toggle**, on by default. A loser left on the second street is
skunked, and one who never left the first is double skunked. In a game to 121
those lines are 91 and 61. It is a way of describing the result, not extra
points.

## What is not here

- **Muggins.** Points missed at the show cannot be claimed by an opponent,
  because they are never missed: the engine counts every hand in full. The rule
  exists to punish bad arithmetic, and there is none to punish.
- **The five-card game.** The old two-handed game — five cards each, a crib of
  four, three for last, game to 61 — is a different game rather than an option,
  and this is the six-card one.
- **Cutting for the deal.** Seat 0 deals the first hand and the deal moves left
  from there.

## What the computer opponent does

The lay-away is worked out rather than guessed. For every way of keeping four,
it scores that hand against **all forty-six cards that could still be cut** and
compares the averages. What goes to the crib is valued separately and then
added or subtracted depending on whose crib it is — a five, a pair or a fifteen
laid away is a gift to your own crib and a present to the other side's. At four
players your partner's crib counts as your own.

In the play it takes the points on offer, and otherwise tries not to leave the
count at five or twenty-one, where any of the sixteen ten-cards collects fifteen
or thirty-one. It will not lead a five for the same reason.
