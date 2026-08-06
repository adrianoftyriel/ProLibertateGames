# Ta Yü — implemented rules

This is the ruleset the engine enforces. Anything marked **toggle** is exposed
in the game setup screen.

Ta Yü is a connection game by Niek (Nicolas) Neuwahl, published by Kosmos in
1999 and nominated for the Spiel des Jahres that year; Goliath reissued it in
2009. It has been out of print for years, which is why it is rebuilt here.

Players lay 1×3 river tiles outwards from the centre of a large board. Each tile
carries three river mouths, and a mouth may never be walled off against the
blank flank of another tile — that constraint is what makes every placement
depend on everything already down. One side runs water to the north and south
edges, the other to the east and west. Scoring multiplies your two opposite
edges together, so a river that reaches only one of them is worth nothing at
all.

**The rulebook could not be obtained, so parts of this are reconstructed.**
Which parts, and how much to trust each, is set out under
[Provenance](#provenance). Read that before treating a number here as gospel.

## Table

- **Players — *toggle*.** Two or four.
  - **Two.** Seat 0 takes north–south, seat 1 east–west.
  - **Four.** Two partnerships: seats 0 and 2 play north–south, seats 1 and 3
    east–west, so partners are never seated next to each other.
- **Opponent — *toggle*.** Gentle or full strength. See
  [the computer opponent](#the-computer-opponent).
- **Tiles — *toggle*.** Two, three or four copies of every tile — 56, 84 or 112.
  Three is the reissued game and four is the 1999 original. The publisher itself
  cut it from four to three to shorten the game, which is the same reasoning
  behind offering two.

The three-player "flood" variant is **not** implemented — see
[Known gaps](#known-gaps).

## Board

An **18 × 18** grid of cells. Rows count from the north edge, columns from the
west.

Each edge has 18 possible **exits**, one per edge cell, where a river can run
off the board. Three exits on each edge are **marked** and count double.

## Tiles

Each tile is **1 × 3** and carries exactly **three river mouths** on its
perimeter, all joined to one another across the face of the tile — so water
entering any mouth can leave by either of the other two.

A 1×3 tile has eight places a mouth can sit. Taking the tile lying east–west
with its cells numbered 0, 1, 2 from the west:

| Slot | Where |
| --- | --- |
| `N0` `N1` `N2` | north flank, above each cell |
| `S0` `S1` `S2` | south flank, below each cell |
| `W` | the west end |
| `E` | the east end |

Choosing 3 of those 8 gives 56 arrangements, and a tile can be turned end for
end, which pairs them off: `N0↔S2`, `N1↔S1`, `N2↔S0`, `W↔E`. No arrangement is
its own mirror under that turn, so the 56 collapse to exactly **28 distinct
tiles**.

**84 = 3 × 28.** The reissued set is three copies of every tile that can exist,
and the 1999 original's 112 is four copies. Both counts landing on a whole
multiple of 28 is the main reason to trust this reconstruction of the geometry.

Of the 28, **12 have their three mouths on three different flanks** of the
rectangle, which matches the reissued tiles being marked with a concentric ring
on the centre stud — so those tiles are drawn with a ring here too.

The engine generates the set rather than listing it, so the tiles and the count
cannot drift apart.

## Turn

1. **Draw** one tile from the bag, face up. There are no hands and no hidden
   tiles: everyone sees what you drew, and the only concealed information in the
   whole game is the order of the bag.
2. **Place** it, in any of its four orientations.
3. If the drawn tile has no legal placement anywhere it is **set aside** and the
   turn passes. It does not come back.

Because a tile is only ever drawn for a seat that can play it, the player on the
clock always has at least one legal placement.

### The opening tile

The first tile must cover at least one of the four centre cells — (8,8), (8,9),
(9,8), (9,9). Every one of the 28 tiles can legally open, so the first turn can
never jam.

### Every tile after the first

A placement is legal when all of the following hold.

- **The three cells are empty** and in a straight line on the board.
- **Every one of the tile's three mouths is accounted for.** Take each mouth and
  look at what lies across it:
  - *an empty cell* — fine, the river runs on into open ground;
  - *off the board* — fine, and that is a scoring exit;
  - *a tile with a mouth facing back* — fine, the two join up;
  - *a tile with a blank flank there* — **illegal**. A river may never dead-end
    against the side of another tile.
- **The tile's own blank edges must not wall anything off either.** A blank part
  of the new tile landing against an existing tile's mouth is illegal for the
  same reason read from the other direction.
- **The tile must join the network.** At least one of its mouths must meet a
  mouth of a tile already down.

The engine enforces the two blanking rules as a single comparison: where a new
tile abuts an existing one, both sides must agree — mouth to mouth, or blank to
blank.

One consequence worth knowing, because it is what keeps the engine simple:
since every tile joins the network by a mouth, and all three mouths of a tile
are connected across its face, **everything on the board is always one single
river system** running back to the opening tile. Nothing ever has to trace
connectivity, and a mouth at the board's edge is a scoring exit by construction.
`TayuRulesTest` and the offline verification harness both check this holds over
whole games.

## End of the game

The game ends when the bag is empty, or when nothing left in it can be placed
anywhere. In practice a full 84-tile bag ends with a handful set aside — the
board jams before the bag runs dry.

## Scoring

Count each side's exits on each of its two edges. An ordinary exit counts **1**;
one of the three **marked** exits on that edge counts **2**. Then, for each
side:

```
score = (exits on one edge) × (exits on the opposite edge)
```

North–south multiplies its north count by its south; east–west multiplies east
by west. **A side that reaches only one of its two edges scores nothing** — that
is the whole tension of the game, and it happens in real play, not just in
theory.

Higher score wins. Equal scores are a draw.

### Where the marked exits sit

Positions **3, 8 and 13** along each edge, counted clockwise from that edge's
first cell, giving the board four-fold rotational symmetry so no side is better
served than another. This is a **choice, not a reproduction** — see below.

## Playing it on a phone

18 × 18 cells works out under 20dp a cell on a phone, which is too fine to tap
accurately, so laying a tile is two steps rather than one:

- **Tap the board** to line a placement up. Cells that the drawn tile could
  cover are dotted, and the placement appears as an amber ghost.
- **Tap the same cell again**, or press **Turn the tile**, to cycle through the
  other legal ways the tile could cover that cell. This is also how the tile
  gets turned — it has four orientations but only some of them fit, and a rotate
  control that landed on illegal positions would be worse than useless.
- **Lay the tile** commits it.

A tap on a cell the tile cannot cover is ignored rather than treated as a
deselection, so a fingertip landing one cell wide does not throw away a
placement that took several taps to line up.

Rivers are drawn as channels running from each mouth into a pool at the middle
of the tile. Exits show as a dot outside the board in the colour of the side
that edge belongs to, and the four edges are tinted the same way, so it is
possible to see at a glance which rivers are working for whom.

## The computer opponent

It rates every legal placement on the board that placement would produce,
weighing what its own axis would score, what the opponent's would score, and
which way the still-open river mouths are pointing.

That last term is what makes it play sensibly at all. Both scores sit at zero
for most of a game — a product needs both edges — so an opponent that looked
only at the score would have nothing to choose between its first fifty
placements.

Two settings, because only two could be shown to differ:

| Setting | What it does | Measured |
| --- | --- | --- |
| **Gentle** | Rates a placement only on what it scores at that moment, which is usually nothing, so it lays a plausible but aimless river | Beats random placement about 3 games in 4; loses to full strength about 4 in 5 |
| **Full strength** | Adds blocking, and notices which way the open mouths point | Won 20 of 20 against random placement, from either axis |

`TayuAiTest` holds both to those figures, and also checks that random against
random comes out even — if it did not, the board or the scoring would favour an
axis and every other result would be meaningless.

A third setting that blocked harder was written and dropped: it played full
strength dead even over 30 games, so shipping it as a step up would have been a
claim that could not be backed.

## Provenance

Everything here is reconstructed from secondary sources. The rulebook itself
could not be reached: outbound web requests are blocked by the build
environment's network policy, so only search-result summaries were available,
and BoardGameGeek's rules files were out of reach entirely.

**Attested by sources:** designer and year; 84 tiles in the reissue and 112 in
the original; 1×3 tiles with three connections each; the board's round
indentations, and the opening tile going in one of the four centre ones; the
mouth-accounting placement rule and the ban on a river ending against a blank;
north–south against east–west; the multiplicative scoring; marked exits counting
double, three to a side; the game ending when nothing can be placed; the
existence of a three-player flood variant.

**Reconstructed here, and the parts to check against a real copy:**

- **The board is 18 × 18.** One source describes the physical board as having
  18 × 18 indentations; several others say 19 × 19. 18 × 18 is used because it
  is the reading under which "the four centre indentations" is true — a 19 × 19
  grid has a single centre cell, not four. `BOARD_SIZE` in `TayuModel.kt` is the
  only thing that has to change if a rulebook settles it the other way.
- **The 28-tile set and its geometry.** Derived, not read off a component list.
  It is trusted because 84 and 112 are both exact multiples of 28, and because
  12 of the 28 touch three flanks, matching the ringed tiles in the reissue.
- **All three mouths on a tile interconnect.** Consistent with every description
  found, but never stated outright.
- **The marked exits at 3, 8 and 13.** Their real positions are unknown. Three
  mirror-symmetric marks are impossible on an even-length edge, so the real
  board cannot be reflectively symmetric within a side either; a rotationally
  symmetric layout was picked as the fairest guess. Cosmetic to the rules, and
  structural only to the final score.
- **An unplaceable drawn tile is set aside and the turn passes.** The other
  reading is that the game simply ends there.

This is the same posture as the Sequence board layout: where the real thing
could not be verified, ship an honest substitute and say so, rather than a guess
dressed up as authentic.

## Known gaps

- **The three-player flood variant is not implemented.** Sources agree it exists
  and that the third player tries to stop the other two reaching the edges, but
  nothing found says how the flood *scores*, and inventing a scoring rule would
  be shipping a different game. Two- and four-player tables cover the family
  case; this can be added if the rulebook turns up.
- **No draw-a-replacement rule.** A tile that cannot be placed is gone for good
  rather than swapped, which is the simpler of the two readings.
- **A full 84-tile game is long** — 84 placements, and every one of them is a
  real decision. The two- and three-copy settings exist for that reason.
- **The AI does not look ahead.** It rates the board one placement deep. It
  plays a strong positional game because the heuristic is aimed at the right
  thing, not because it is searching.
