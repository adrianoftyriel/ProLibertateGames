# Backgammon

Twenty-four points, fifteen checkers a side, and two dice. Bring everything
round to your home board and bear it off before your opponent does.

## The board

Points are numbered 1 to 24. **White runs down the numbers and bears off past
1; Black runs up them and bears off past 24.** White's home board is points 1–6,
Black's is 19–24.

A point belongs to whoever has two or more checkers on it, and is closed to the
other player. A point with exactly one checker on it is a **blot**, and can be
hit.

## The opening

Each player throws one die and the higher number starts, playing both dice as
thrown. A tie is thrown again, so the game never opens on a double.

## A turn

Throw two dice and move one checker for each. **A double is four moves**, not
two.

The rule that decides most disputes is this one: **you must use as much of the
roll as you can.** If there is a way to play both dice, you must play both, even
when playing one would suit you better. If only one can be played, it must be
the higher one. The app enforces this by working out every way the whole roll
could be played before offering you anything, so a move that would strand the
other die is simply never on the board. That is why a legal-looking move
sometimes cannot be tapped: playing it would waste half your roll.

## Hitting and the bar

Landing on a blot sends it to the bar. A player with a checker on the bar must
bring it back on before moving anything else, entering in the opponent's home
board: White enters on 24 minus the die, Black on the die itself. If every entry
point is closed the turn is lost, and it can be lost several times over.

## Bearing off

Once all fifteen checkers are in your home board you may start bearing off. An
exact die takes a checker off the point that needs it. A bigger die may take one
off your highest occupied point, but only when nothing is standing further back —
an overshooting die may never take a checker that a smaller die could have
reached.

## Winning

Fifteen off wins. How much it is worth depends on how badly the loser lost:

| | |
| --- | --- |
| **Single**, 1 point | the loser has borne at least one checker off |
| **Gammon**, 2 points | the loser has borne nothing off |
| **Backgammon**, 3 points | and still has a checker on the bar or in the winner's home |

Counting gammons is an option. It changes nothing about the moves and everything
about whether a hopeless game is worth playing out.

## Not implemented

**There is no doubling cube.** A single game is played rather than a match to a
number of points, and with nothing being kept between games there is nothing for
a cube to double. It is the largest thing missing from this implementation and
the one a serious player will notice first.

## The dice

Every die in the game comes from the table's own seed and a count of how many
rolls have been made, so a game is reproducible from the state it started in and
a host and a client can never disagree about what was thrown. The seed travels
with the state, which is safe: backgammon has nothing hidden, and the host is
authoritative about every roll in any case.

## What the computer does

Not a search in the sense the chess and checkers players are one — there is no
tree to walk, because the opponent's reply depends on dice nobody has thrown
yet. Instead it weighs **whole turns**: every distinct way the roll can be
played, scored by the board it leaves behind.

Weighing turns rather than checkers is the point. A backgammon move is only good
in company — splitting the back checkers is right or wrong depending on what the
other die did — and a player choosing one checker at a time would never see it.

At the strongest level it also averages what the dice might do to it next: all
twenty-one distinct rolls, weighted properly (6-5 comes up twice as often as
5-5), taking the opponent's best answer to each.

The evaluation is built on the pip count, because backgammon is a race. Around
that: a blot is only a liability if it can be hit, so exposure is weighted by
how far away the nearest enemy checker is; a made point in your own home board
is worth having because it is one more number your opponent cannot come in on;
and a long run of consecutive points is what traps a checker behind it.
