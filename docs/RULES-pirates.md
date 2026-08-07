# Pirates and Bulgars

Two pirates hold a stronghold at the head of a cross-shaped board. Twenty-four
Bulgars come at it from the other three arms. Only one side can take pieces, and
it is not the big one.

## Note the caveats about the reconstruction

**"Dalmatian Pirates and the Volga Bulgars" is Sid Sackson's theming, in *The
Book of Classic Board Games*, of the game usually called Asalto** — itself a
nineteenth-century descendant of Fox and Geese. That much is attested.

The book could not be obtained, so **what is implemented here is Asalto**, and
where Asalto's own sources disagree a choice had to be made. What follows says
which is which, in the same spirit as the Ta Yü document: the game is playable
and coherent, and it is not a transcription of a rulebook nobody here has read.

**Attested** — the shape of the board and its thirty-three points; the
stronghold being one nine-point arm; two hunters against twenty-four; the
hunters taking by jumping and the crowd being unable to take at all; the crowd
winning by filling the stronghold or by penning the hunters in; the hunters
winning by cutting the crowd below the number needed to fill it.

**Chosen here** —

- **Which side is which.** Sackson's theme names two peoples; nothing available
  said which of them is the pair and which the crowd. The pirates are the two,
  on the reasoning that a raiding party is small and a stronghold is what you
  raid.
- **The diagonals.** Boards of this family are printed with diagonals in a
  pattern rather than everywhere. The pattern used is the usual one: a diagonal
  exists where the row and column of a point add to an even number, which gives
  the centre eight lines and its neighbours four.
- **Compulsory capture instead of huffing.** Traditionally a hunter who fails to
  take when it could is *huffed* — removed by the other player. That is the same
  rule with an extra step and a way to forget to use it, so a pirate that can
  take simply must. It is an option, and turning it off restores the choice
  without restoring the punishment.
- **Bulgars may not retreat.** Standard for the soldiers in Asalto, and stated
  here as an option because it is the single rule that most changes how the game
  feels.
- **The draw rule.** No source gives one. Sixty plies with nothing taken and no
  ground gained is called a draw, because two sides that can each hold a
  position otherwise have no reason ever to stop.

## The board

```
        c7  d7  e7          ← the stronghold, nine points
        c6  d6  e6
        c5  d5  e5
a4  b4  c4  d4  e4  f4  g4
a3  b3  c3  d3  e3  f3  g3    (the middle band is seven wide)
a2  b2  c2  d2  e2  f2  g2
        c1  d1  e1
```

Thirty-three points: a seven-by-seven grid with the four corners cut away. The
top arm — nine points — is the stronghold, which leaves exactly twenty-four
points outside it. That is not a coincidence: the board and the pieces are cut
to each other, and the game opens with every point occupied except the seven the
pirates are not standing on.

Lines run orthogonally between all neighbouring points, and diagonally where the
row and column add to an even number. **The lines the screen draws come from the
same table the moves come from**, so what you can see and what you may play
cannot disagree.

## The two sides

**The Bulgars** — twenty-four. Move one step along a line, towards the
stronghold or across it, **never back down the board**. They cannot take
anything at all.

**The pirates** — two. Move one step along a line in any direction, and take by
jumping an adjacent Bulgar to the empty point directly beyond. A jump may be
followed by another with the same pirate, and then it must be: a chain is one
turn, and it cannot be abandoned halfway.

The Bulgars move first. The pirates are already where they want to be.

## Winning

**The Bulgars win** by occupying all nine points of the stronghold, or by
leaving the pirates with no legal move.

**The pirates win** by cutting the Bulgars below nine, at which point there are
not enough of them left to fill the stronghold however long the game runs.

## What the computer does

Negamax with alpha-beta pruning and iterative deepening, over the same rules
engine the game runs on.

The interesting part of a hunt game is that the two sides want opposite shapes
out of the same board, and one evaluation has to speak for both. The terms are
written from the Bulgars' point of view and negated for the pirates, so the two
cannot drift apart and start valuing the same position differently.

What it counts: how many Bulgars are left and how far up the board they have
come; how much of the stronghold they hold; how much room the pirates have to
work in; and — the term that does the most work — **how many Bulgars are
standing where a pirate could jump them**. A Bulgar with an empty point behind
it on a line is a Bulgar about to be taken, and not being jumpable is most of
staying alive in this game.
