# Pro Libertate Games

A collection of card and board games for Android, playable against the computer
or against other people over Wi-Fi (LAN).

## What works today

The app shell is complete and every game in the catalogue is playable end to end:

| | |
| --- | --- |
| **Cards** | Euchre, Kaiser, President, Golf, Wizard, Crazy 8s |
| **Board** | Sequence, Chess, Checkers, Backgammon, Mastermind, Nine Men's Morris, Pirates and Bulgars, Ta Yü |

Nothing is marked *Coming soon* at the moment, because nothing is waiting. The
machinery for it is still there and still tested: a game listed in `GameCatalog`
with `available = false` appears in the menu marked *Coming soon* **on dev builds
only**, so a production release lists just what can actually be played and nobody
installs it and taps into a dead end. The menu decides this from the installed
APK's own version name, so it follows the build rather than the channel selected
for future updates.

Adding a game means writing a `GameRules` implementation, a `GameAi` and a
screen, then flipping `available` — the menu, lobby, networking, settings and OTA
layers are game-agnostic and need no changes.

## Playing with other people

Pick a game, choose **Host a game for others to join**, and the device advertises
itself on the network. Other devices choose **Join a game nearby** and
pick the host from the list. Seats start out filled by the computer and are
handed to people as they join, so any mix of humans and AI works — whatever is
still marked *Computer* when the host starts stays AI.

- **Wi-Fi (LAN)** — both devices must be on the same network. Discovery uses
  mDNS/NSD; play runs over a TCP socket.

### How a match is kept in step

The host is authoritative. Clients send *intents* ("I would like to play this
card") and the host re-validates every one against the rules before it takes
effect — a client cannot make an illegal move by sending a malformed message.
Each client is then sent a state **redacted for its own seat**, so opponents'
hands and the deck order are never transmitted to devices that should not see
them.

## Scorekeeper

**Scorekeeper** on the main menu is a pencil for games the app does not deal —
darts, cribbage, whatever is actually on the table. It is laid out as the paper
it replaces: a column per player with their name at the head, a row per round
running down the page, and the running total ruled off at the foot.

```
     Alice   Bob   Carol
 1     +3     −1      +2
 2     +5     +7       •
 3   [   ] [   ]  [   ]   ← the row being filled in
 Σ      8      6       2
```

The bottom row is always the one being filled in. Finish it and it is written
down, and a fresh empty row opens above the totals for the next round. The
totals and that button stay put while the rounds scroll under them, so the
tally is never off-screen.

Tap a column head to name it or remove that player; **drag it sideways to move
the column**, and the whole column travels with the name. That works because
points are recorded against a player's id rather than their position, so
reordering the sheet moves the columns and nothing else. Ids are handed out
once and never reused: somebody sitting down where somebody else got up starts
at nought instead of inheriting a stranger's score. Removing a player does take
their points with them — a score nobody can see is a score nobody can correct.

Rounds are not written in ink. Tapping a round number down the left edge opens
that row for correction or lets it be struck out, and the totals follow.

Points are taken away by typing a minus, or with the **±** button, which flips
the last number typed. Not every keyboard offers a minus on its number pad, and
subtracting has to work on all of them.

The sheet is saved as it is edited, so leaving the app or taking a phone call
does not lose the game. It is kept in its own DataStore rather than with the
settings, because it is a game in progress rather than a preference.

`score/ScoreSheet.kt` holds the whole model and, like `game/`, has no Android in
it: every rule above is a pure function and unit-tested on the JVM.

## Settings

- **Sound** on/off.
- **Animation speed**, 0.5×–2×. One multiplier drives card movement and how long
  the computer appears to think.
- **Updates** — check on launch, or check on demand, from either of two
  channels:
  - **Production** — stable builds, published from `main`.
  - **Dev** — preview builds, published from `dev` on every green CI run.

  The two install as separate apps, so both can be kept on one device — see
  [Both channels on one device](#both-channels-on-one-device).

## Branding

The launcher icon is a single square of the Wallace tartan set on the bias, and
the app opens on the same cloth carrying the name in a Celtic uncial hand for two
seconds before fading into the menu.

A dev build wears **Wallace Hunting** instead — the same threadcount with green
where the clan sett has red — and installs as *Pro Libertate Games DEV*. That is
what tells the two copies apart when both are on the phone; see
[Both channels on one device](#both-channels-on-one-device).

The tartan is drawn, not an image: `ui/theme/Tartan.kt` lays the sett down as
warp and then as weft at half opacity, which is what produces the blended
squares instead of a flat grid. The icon is the same construction as a vector
drawable, so it stays sharp at every density and needs no PNGs.

Two honest caveats:

- **The sett is the published threadcount: `K/4 R32 K32 Y/4`.** The slashes are
  pivots, so `WallaceSett` holds the half-sett and reflects it out to
  `K4 R32 K32 Y4 K32 R32` — 136 threads, about half black, half red, with a
  yellow line worth 3%. Two details define the pattern and are easy to get
  backwards: the yellow overstripe runs down the *centre of a wide black band*,
  and the narrow black guard sits *between two red blocks*. The icon is
  generated from the same numbers, so the icon and the splash cannot drift.
  Weave scale is derived from the sett rather than fixed, so changing the
  threadcount rescales both instead of silently zooming in. The hunting
  colourway is the same threadcount passed a different field colour, not a
  second copy of the numbers, so it cannot fall out of step either.
- **The typeface is bundled under a licence.** Uncial Antiqua, © 2011 Brian J.
  Bonislawsky DBA Astigmatic, Reserved Font Name "Uncial Antiqua", used under
  the SIL Open Font License 1.1. The full licence is at
  [docs/licenses/UncialAntiqua-OFL.txt](docs/licenses/UncialAntiqua-OFL.txt) and
  must stay with the font — the OFL requires it to be distributed alongside.

## Screen sizes

There are no fixed orientations or hard-coded card sizes. Layouts measure the
space they are given: the menu grid reflows by width, the Sequence board is
always square and sized to fit whatever is left after the hand, and card widths
are a fraction of the smaller screen dimension. Rotation is handled inside the
activity, so a hand is not lost when the device turns.

The Ta Yü board is the tightest fit of the lot — 18 × 18 cells comes out under
20dp a cell on a phone — which is why laying a tile there is a tap to line the
placement up and a button to commit it, rather than a single tap on a target
smaller than a fingertip.

## Building

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # rules engines and AI
```

Requires JDK 17 and the Android SDK (compileSdk 34).

A local build is a dev build: it installs as `org.prolibertate.games.dev` under
the name *Pro Libertate Games DEV* in the hunting sett, so it goes on beside an
installed release rather than over it. Set `PLG_CHANNEL=production` to build the
production package instead.

## CI

- `.github/workflows/ci.yml` — builds, tests and lints on `dev` and on pull
  requests into `dev` or `main`. Its APK artifact is a dev build, versioned
  `1.0.0-dev`.
- `.github/workflows/release.yml` — on push to `main`, builds the APK and
  publishes it as a GitHub Release tagged `v1.0.<run-number>`. The in-app updater
  reads that tag, which is why the APK's `versionCode` is the same run number.

### Installing without the app

The newest production build is always at this link, which never changes:

**https://github.com/adrianoftyriel/ProLibertateGames/releases/latest/download/ProLibertateGames.apk**

Every release carries the same APK twice — once named for its version, and once
as plain `ProLibertateGames.apk`, because GitHub's `/releases/latest/download/`
redirect needs a file name that stays put. The in-app updater deliberately picks
the versioned copy, so an installed build can always be traced back to its tag.

There is no equivalent link for the dev channel: `/releases/latest` skips
prereleases by design, and that is exactly what keeps a production install from
being handed a dev build. Dev builds are reached through the in-app updater with
the channel set to dev, or from the releases page.

### Update channels

Both pipelines publish, to two separate channels:

| Channel | Published by | Tag | Marked |
| --- | --- | --- | --- |
| Production | `release.yml`, push to `main` | `v1.0.<n>` | release |
| Dev | `ci.yml`, any build of `dev` | `v1.0.<n>-dev` | prerelease |

Dev builds are published as **prereleases** deliberately: GitHub's "latest
release" endpoint skips prereleases, so a production-channel install can never
be handed a dev build even by accident. Pull requests build and check but never
publish, so a PR from a fork cannot ship anything.

**Both pipelines gate publishing on the branch rather than on what triggered
the build**, so a manual run from the Actions tab publishes exactly as a push
does — useful when a push cannot fire the workflow itself, which is the case
for anything pushed by an automation token. The safety property is unchanged,
because publishing still happens only after that same job has built, tested and
linted clean. A pull request is still excluded without having to be named: a PR
build runs against `refs/pull/<n>/merge`, never `refs/heads/dev`.

In `ci.yml` the `PLG_CHANNEL` stamp is gated on the same condition as the
publish steps, and the two have to stay in step. Stamping a build that then
ships, or shipping one that was left unstamped, both end with an APK claiming
`versionCode` 1 — which the updater can never see as an upgrade over anything.

`release.yml` refuses to run off any branch but `main`, so a manual dispatch
aimed elsewhere does nothing rather than shipping unreviewed code.

**The two channels have independent version sequences** — a build's `versionCode`
is the run number of the workflow that produced it, and the two workflows count
separately. Versions are therefore only ever compared *within* a channel; the app
reads the `-dev` suffix in its own `versionName` to know which channel it is on,
and treats a change of channel as an explicit switch rather than an upgrade.

### Both channels on one device

A dev build installs under its own `applicationId`, `org.prolibertate.games.dev`,
so it is a separate app as far as Android is concerned and can sit beside the
production copy rather than replacing it. Each channel still updates itself
normally, because an update within a channel keeps the same package.

Three things move together, all off `isDevBuild` in `app/build.gradle.kts`:

| | Production | Dev |
| --- | --- | --- |
| `applicationId` | `org.prolibertate.games` | `org.prolibertate.games.dev` |
| Launcher name | Pro Libertate Games | Pro Libertate Games DEV |
| Sett | Wallace (red) | Wallace Hunting (green) |

Anything that is not an explicit `PLG_CHANNEL=production` build is a dev build,
so a local `assembleDebug` is a dev build too and installs alongside a release
without uninstalling anything.

They have to move together. A dev package wearing the production name and
colours is precisely the mix-up that having both installed is meant to prevent,
which is why one flag drives all three rather than each being set by hand. The
name and the colours reach the resources through `resValue`, so `app_name`,
`tartan_field`, `splash_background` and `ic_launcher_background` are generated
by the build and are deliberately *not* in `res/values/` — the icon vectors
reference `@color/tartan_field` rather than a literal, which is how one set of
vectors serves both colourways. `BuildConfig.DEV_BUILD` carries the same flag
into Kotlin, where `AppSett` picks the matching Compose sett for the splash.

Switching channels in Settings therefore installs the other channel's app
alongside this one and leaves this one in place; the settings screen says so
when the selected channel is not the installed one.

The debug keystore is committed on purpose: every build is signed with the same
key, so an OTA update installs over the previous one instead of being rejected
for a signature mismatch.

## Rules

The exact rules implemented, and the options that vary them, are documented per
game:

- [Euchre](docs/RULES-euchre.md)
- [Sequence](docs/RULES-sequence.md) — **note the caveat about the board layout**
- [Kaiser](docs/RULES-kaiser.md)
- [President](docs/RULES-president.md)
- [Golf](docs/RULES-golf.md)
- [Wizard](docs/RULES-wizard.md)
- [Crazy 8s](docs/RULES-crazy8s.md)
- [Chess](docs/RULES-chess.md)
- [Checkers](docs/RULES-checkers.md) — **note the caveat about flying kings**
- [Backgammon](docs/RULES-backgammon.md) — **note that there is no doubling cube**
- [Mastermind](docs/RULES-mastermind.md) — played as a duel: each player sets
  the code the other has to break
- [Nine Men's Morris](docs/RULES-morris.md)
- [Pirates and Bulgars](docs/RULES-pirates.md) — **note the caveats about the
  reconstruction**: the book the theme comes from could not be obtained, so the
  document says which rules are attested and which were chosen
- [Ta Yü](docs/RULES-tayu.md) — **note the caveats about the reconstruction**:
  the game is out of print and the rulebook could not be obtained, so the
  document says which parts are attested and which were derived

## Layout

```
game/          rules engines and AI — pure Kotlin, no Android, unit-tested
  cards/       deck, suits, ranks
  engine/      GameRules / GameAi contracts, seats, table config
  euchre/      Euchre model, rules, AI
  kaiser/      Kaiser model, bidding, rules, AI
  sequence/    Sequence model, board, rules, AI
  president/   President model, rules, AI
  golf/        Golf model, scoring, rules, AI
  wizard/      Wizard model, rules, AI
  crazyeights/ Crazy 8s model, rules, AI
  chess/       Chess model, FEN, move generation, search
  morris/      Nine Men's Morris board geometry, mills, rules, search
  checkers/    Checkers board, compulsory captures, crowning, search
  backgammon/  Backgammon points, dice, the maximal-roll rule, turn search
  mastermind/  Mastermind codes, scoring, and a code breaker
  pirates/     Pirates and Bulgars — the cross board, the hunt, search
  tayu/        Ta Yü tile geometry, river rules, scoring, AI
net/           wire protocol, LAN transport, lobby, match driver
score/         scorekeeper sheet — pure Kotlin, unit-tested — and its store
settings/      DataStore-backed preferences
update/        GitHub Releases OTA updater
ui/            Compose screens
```

Keeping `game/` free of Android types is deliberate: the same code drives the
AI, the host's authoritative state and the JVM unit tests.
