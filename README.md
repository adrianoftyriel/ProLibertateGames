# Pro Libertate Games

A collection of card and board games for Android, playable against the computer
or against other people over Wi-Fi (LAN) or Bluetooth.

## What works today

The app shell is complete and two games are playable end to end:

| | |
| --- | --- |
| **Playable** | Euchre, Sequence |
| **Listed, not yet implemented** | Kaiser, President, Golf, Wizard, Crazy 8s, Mastermind, Backgammon, Chess, Checkers |

Unimplemented games appear in the menu marked *Coming soon*. Adding one means
writing a `GameRules` implementation, a `GameAi`, and a screen, then flipping
`available` in `GameCatalog` — the menu, lobby, networking, settings and OTA
layers are game-agnostic and need no changes.

## Playing with other people

Pick a game, choose **Host a game for others to join**, and the device advertises
itself on every radio it has. Other devices choose **Join a game nearby** and
pick the host from the list. Seats start out filled by the computer and are
handed to people as they join, so any mix of humans and AI works — whatever is
still marked *Computer* when the host starts stays AI.

- **Wi-Fi (LAN)** — both devices must be on the same network. Discovery uses
  mDNS/NSD; play runs over a TCP socket.
- **Bluetooth** — devices must already be **paired** in Android's Bluetooth
  settings. Play runs over Classic RFCOMM. Scanning for unpaired devices is not
  implemented.

Both transports can be live at once, so some players can be on Wi-Fi and others
on Bluetooth at the same table.

### How a match is kept in step

The host is authoritative. Clients send *intents* ("I would like to play this
card") and the host re-validates every one against the rules before it takes
effect — a client cannot make an illegal move by sending a malformed message.
Each client is then sent a state **redacted for its own seat**, so opponents'
hands and the deck order are never transmitted to devices that should not see
them.

## Settings

- **Sound** on/off.
- **Animation speed**, 0.5×–2×. One multiplier drives card movement and how long
  the computer appears to think.
- **Updates** — check on launch, or check on demand. Updates are pulled from the
  latest GitHub Release of this repository, which CI publishes on every push to
  `main`.

## Screen sizes

There are no fixed orientations or hard-coded card sizes. Layouts measure the
space they are given: the menu grid reflows by width, the Sequence board is
always square and sized to fit whatever is left after the hand, and card widths
are a fraction of the smaller screen dimension. Rotation is handled inside the
activity, so a hand is not lost when the device turns.

## Building

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # rules engines and AI
```

Requires JDK 17 and the Android SDK (compileSdk 34).

## CI

- `.github/workflows/ci.yml` — builds, tests and lints on `dev` and on pull
  requests into `dev` or `main`.
- `.github/workflows/release.yml` — on push to `main`, builds the APK and
  publishes it as a GitHub Release tagged `v1.0.<run-number>`. The in-app updater
  reads that tag, which is why the APK's `versionCode` is the same run number.

The debug keystore is committed on purpose: every build is signed with the same
key, so an OTA update installs over the previous one instead of being rejected
for a signature mismatch.

## Rules

The exact rules implemented, and the options that vary them, are documented per
game:

- [Euchre](docs/RULES-euchre.md)
- [Sequence](docs/RULES-sequence.md) — **note the caveat about the board layout**

## Layout

```
game/          rules engines and AI — pure Kotlin, no Android, unit-tested
  cards/       deck, suits, ranks
  engine/      GameRules / GameAi contracts, seats, table config
  euchre/      Euchre model, rules, AI
  sequence/    Sequence model, board, rules, AI
net/           wire protocol, LAN and Bluetooth transports, lobby, match driver
settings/      DataStore-backed preferences
update/        GitHub Releases OTA updater
ui/            Compose screens
```

Keeping `game/` free of Android types is deliberate: the same code drives the
AI, the host's authoritative state and the JVM unit tests.
