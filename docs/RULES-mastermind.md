# Mastermind

A hidden code of coloured pegs, and a fixed number of guesses to break it.

## Played as a duel

The pencil-and-paper game is one person setting a code and another breaking it,
which makes a poor two-handed game: one player would sit and watch.

Here **each player sets the code their opponent will have to break**, and then
both are breaking one, a guess each in turn. Whoever cracks theirs first wins.

Setting your own code is half of Mastermind, and a generated one takes it away.
Whether to repeat a colour, whether to leave one out, whether to do the thing
this particular opponent will not expect — those are real decisions, and no
random number generator makes them.

The codes are chosen one after the other rather than at the same time, because
the engine only ever has one player on the clock. It costs nothing: neither can
see the other's, so going second is not an advantage. Once both are down, the
guessing starts with whoever set first.

That symmetry is why the game ends at the foot of a round rather than the moment
somebody gets it. If the player who went first breaks the code on their sixth
guess, the player who replied still gets a sixth guess of their own — and
equalling it is a draw. Ending it any earlier would make going first worth half
a guess.

## The answer

Each guess is answered with pegs:

- a **dark** peg for a colour in the right place;
- a **light** peg for a colour that is in the code but somewhere else.

**A colour is never counted twice.** Three reds guessed against a code with one
red is worth exactly one peg, however they are arranged. The pegs say how many,
never which, and they are deliberately not lined up under the code they answer —
a tidy row would invite reading a position into them that is not there.

## Options

| | |
| --- | --- |
| **Colours** | 6 or 8 |
| **Pegs** | 3, 4 or 5 |
| **Guesses each** | 8, 10 or 12 |
| **Repeated colours** | on by default; off makes every peg a different colour |
| **Opponent** | Casual, Club or Strong |

The setup screen shows how many codes are possible, which is the honest measure
of how hard a game will be: 1,296 for the standard four pegs and six colours,
32,768 for five pegs and eight.

## Ending

- One player breaks it and the other does not — that player wins.
- Both break it in the same round — a draw.
- Neither breaks it before the guesses run out — a draw.

## Keeping the codes secret

This is the only game here with anything to hide, and it is the only one whose
`viewFor` does real work. The host holds both codes and sends each device **only
its own**: the code you are trying to break is never on your device in the first
place, so there is nothing to find by digging through what arrived over the
network.

A code set on a guest's device reaches the host as a move, and stops there.
Moves travel one way — they are intents sent to the host, never relayed on to
the other player — so the only copy that ever reaches an opponent's screen is
the redacted one.

What is *not* hidden is whether the other player has finished choosing. That is
not a secret, and a screen has to be able to say "waiting for them" rather than
sit there blank, so the state carries a plain flag per player alongside the
code.

A guess is scored by the host, which is the only machine that can score it. A
device asked to score a guess against a code it was never given refuses rather
than quietly answering "nothing" — tested, because that failure would look like
a very hard code rather than like a bug.

Both codes are revealed when the game ends. There is nothing left to protect,
and a code breaker wants to see what they were up against.

## What the computer does

**Setting a code**, the computer takes one at random and does not try to be
clever: a code chosen to beat a particular solver is a code with a pattern in
it, and a pattern is the one thing a code should not have.

That single choice is drawn from a source nobody else can see. Every other
random number in this app is seeded from the position so that a host and a
client agree on what happened — but a code seeded from anything that travels
over the wire could be reconstructed by the very player who is supposed to be
guessing it.

**Breaking one**, there is no tree to search — a guess cannot be answered badly,
and the opponent makes no reply — so the whole game is in choosing what to try
next.

- **Casual** guesses anything it has not tried, ruled out or not.
- **Club** never guesses a code the answers have already eliminated. That one
  rule is most of the strength, and it cracks a standard code in about five.
- **Strong** is Knuth's idea one ply deep: among the codes still standing, it
  plays the one whose *worst* possible answer leaves the fewest candidates
  behind, so it cannot be handed a reply that tells it little. Both lists are
  sampled when they are large — the full calculation on a fresh game is over a
  million scorings for a choice a sample makes just as well.
