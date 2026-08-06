# Ta Yü — the ruleset to implement

Ta Yü is a connection game by Niek (Nicolas) Neuwahl, published by Kosmos in
1999 and nominated for the Spiel des Jahres that year; Goliath reissued it in
2009. It is long out of print, which is why it is being rebuilt here.

**This game is not implemented yet.** This document is the specification the
engine will be written against, and it is deliberately explicit about which
parts are attested by sources and which parts are reconstructed. Read the
[Provenance](#provenance) section before treating any number here as gospel.

Players lay 1×3 river tiles outwards from the centre of a large board. Each
tile carries three river mouths, and a river mouth may never be walled off
against the blank flank of another tile — so every placement is constrained by
everything already down. One side is trying to run water out to the north and
south edges, the other to the east and west. Scoring multiplies the two
opposite edges together, so a river that reaches only one of your edges is
worth nothing at all.

## Table

- **Players — *toggle*.** Two or four.
  - **Two.** One player takes north–south, the other east–west.
  - **Four.** Two partnerships: seats 0 and 2 play north–south, seats 1 and 3
    play east–west, so partners are never seated next to each other.
- The three-player "flood" variant is **not** being implemented — see
  [Known gaps](#known-gaps).

## Board

An **18 × 18** grid of cells. Rows are numbered from the north edge, columns
from the west edge.

Each of the four edges has 18 possible **exits** — one per edge cell — where a
river can run off the board. Three exits on each edge are **marked** and count
double.

```
        north edge (row 0)
      ┌────────────────────┐
 west │                    │ east
 edge │        18 × 18     │ edge
      │                    │
      └────────────────────┘
        south edge (row 17)
```

## Tiles

84 tiles, drawn blind from a bag. Each tile is **1 × 3** and carries exactly
**three river mouths** on its perimeter, all joined to one another by channels
across the face of the tile — so water entering any mouth can leave by either
of the other two.

A 1×3 tile has eight places a mouth can sit. Taking the tile lying east–west
and its cells numbered 0, 1, 2 from the west:

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

**84 = 3 × 28.** The set is three copies of every tile that can exist. (The
1999 Kosmos edition had 112 tiles — 4 × 28 — and the reissue cut it to three
copies each to shorten the game. Both counts landing on a whole multiple of 28
is the main reason to trust this reconstruction of the geometry.)

Of the 28, **12 have their three mouths on three different flanks** of the
rectangle, which matches the reissued tiles being marked with a concentric ring
on the centre stud for exactly that case.

The engine generates the set rather than listing it, so the tile table cannot
drift out of step with the count.

## Turn

1. **Draw** one tile from the bag, face up. There are no hands and no hidden
   tiles — everyone sees what you drew, and the only concealed information in
   the game is the order of the bag.
2. **Place** it, in any of its four orientations (east–west or north–south,
   either way round).
3. If the drawn tile has no legal placement anywhere, it is **set aside** and
   the turn passes. It does not come back.

### Placing the first tile

The opening tile must cover at least one of the four centre cells — (8,8),
(8,9), (9,8), (9,9). Everything else grows out from it.

### Placing every tile after the first

A placement is legal when all of the following hold.

- **The three cells are empty** and lie in a straight line on the board.
- **Every one of the tile's three mouths is accounted for.** Take each mouth in
  turn and look at what is on the other side of it:
  - *an empty cell* — fine, the river runs on into open ground;
  - *off the board* — fine, and that is a scoring exit (see below);
  - *a tile that has a mouth facing back* — fine, the two join up;
  - *a tile with a blank flank there* — **illegal**. A river may never dead-end
    against the side of another tile.
- **The tile's blank edges must not wall anything off either.** If a blank part
  of the new tile lands against an existing tile's mouth, the placement is
  illegal, for the same reason read from the other direction.
- **The tile must join the network.** At least one of its mouths must meet a
  mouth of a tile already on the board.

Because every tile joins the network by at least one mouth, and because all
three mouths of a tile are connected across its face, **everything on the board
is always one single river system** running back to the opening tile. The
engine gets connectivity for free and never has to trace it.

## End of the game

The game ends when the bag is empty, or when no tile left in the bag can be
placed anywhere.

## Scoring

Count each side's exits — mouths that run off the board — on each of its two
edges.

- An ordinary exit counts **1**.
- One of the three **marked** exits on that edge counts **2**.

Then, for each side:

```
score = (exits on one edge) × (exits on the opposite edge)
```

North–south multiplies its north count by its south count; east–west multiplies
east by west. **A side that reaches only one of its two edges scores nothing**,
which is the whole tension of the game: breadth on one edge is worthless
without a matching breakthrough on the other.

Higher score wins. Equal scores are a draw.

### Where the marked exits sit

Positions **3, 8 and 13** along each edge, counted clockwise from that edge's
first cell, giving the board four-fold rotational symmetry so no side is
better placed than another.

This is a **choice, not a reproduction** — see below.

## Provenance

Everything above is reconstructed from secondary sources. The rulebook itself
could not be reached from this machine: outbound web requests are blocked by
the environment's network policy, so only search-result summaries were
available, and BoardGameGeek's rules files were out of reach entirely.

**Attested by sources:** designer and year; 84 tiles in the reissue and 112 in
the original; 1×3 tiles with three connections each; the board's round
indentations and the opening tile going in one of the four centre ones; the
mouth-accounting placement rule and the ban on a river ending against a blank;
north–south versus east–west; the multiplicative scoring; marked exits counting
double, three to a side; the game ending when nothing can be placed; the
three-player flood variant existing.

**Reconstructed here, and the parts to check against a real copy:**

- **The board is 18 × 18.** One source describes the physical board as having
  18 × 18 indentations; several others say 19 × 19. 18 × 18 is used because it
  is the one that makes "the four centre indentations" true — a 19 × 19 grid
  has a single centre cell, not four. If a rulebook ever settles this the other
  way, `BOARD_SIZE` is the only number that has to change.
- **The 28-tile set and its geometry.** Derived, not read off a component list.
  It is trusted because 84 and 112 are both exact multiples of 28, and because
  12 of the 28 touch three flanks, matching the ringed tiles in the reissue.
- **All three mouths on a tile interconnect.** Consistent with every
  description found, but not stated outright anywhere.
- **The marked exits at 3, 8 and 13.** Their real positions are unknown. Three
  mirror-symmetric marks are impossible on an even-length edge, so the real
  board cannot be reflectively symmetric per side either; a rotationally
  symmetric layout was picked as the fairest guess. This is cosmetic to the
  rules and structural only to the final score.
- **An unplaceable drawn tile is set aside and the turn passes.** The
  alternative reading is that the game simply ends there.

This is the same posture as the Sequence board layout: where the real thing
could not be verified, ship an honest substitute and say so, rather than a
guess dressed up as authentic.

## Known gaps

- **The three-player flood variant is not implemented.** Sources agree it
  exists and that the third player tries to stop the other two reaching the
  edges, but nothing found says how the flood *scores*, and inventing a scoring
  rule would be making up a different game. Two- and four-player tables cover
  the family case; this can be added if the rulebook turns up.
- **No draw-a-replacement rule.** A tile that cannot be placed is gone for
  good rather than swapped, which is the simpler of the two readings.
