# Working on this repo with Claude

## Land changes through the GitHub API, never `git push` alone

**A `git push` from a Claude session does not trigger GitHub Actions.** The
sandbox pushes with a bot token, and GitHub creates no workflow run for it. The
branch updates, CI stays silent, and nothing gets built, tested or published.

The evidence, if it ever needs rechecking: every workflow run in this repo's
history is attributed to the repo owner. Commits *authored* by Claude trigger CI
normally — `d165e5ef`, `469c900e`, `d6e237ce` all did — because a human pushed
them. Commits pushed *by a session* (`43394b8`, `742add4`) produced no run at
all. It is the pushing credential that matters, not the commit author.

Calls through the GitHub API (the `mcp__github__*` tools) are attributed to the
repo owner and **do** trigger workflows. So:

1. Commit to a feature branch and `git push` it. This triggers nothing, which is
   fine — a feature branch has no workflow of its own.
2. **Open a pull request via the API.** `ci.yml` runs on
   `pull_request: [main, dev]`, so this is what actually builds and tests the
   change. Wait for it to pass.
3. **Merge via the API.** The resulting push to `dev` or `main` is attributed to
   the owner and triggers the branch's workflow.

Never merge by pushing `dev` or `main` directly from a session. It silently
skips every check and publishes nothing, leaving a branch whose head was never
built.

If a PR needs re-verifying after more commits, note that `synchronize` events
come from a raw push and are also dead. Trigger `ci.yml` manually with
`workflow_dispatch` instead.

## Merging is a shipping action

Both long-lived branches publish on push, so a merge is a release, not just an
integration:

- **`dev`** → `ci.yml` builds, tests, lints, then publishes a `v1.0.N-dev`
  **prerelease**. Offered by the in-app updater to anyone on the Dev channel.
- **`main`** → `release.yml` publishes a **production** release. This is what
  the updater installs for everyone on the Production channel.

Confirm before merging to `main`. Note that `release.yml` runs `assembleDebug`
only — no tests, no lint — so a `main` merge is only as verified as the `dev`
build it replays.

## Gradle does not run in the sandbox

`./gradlew` cannot resolve the Android plugin here: the proxy blocks
`dl.google.com` with a 403. There is no local build, no local test run, and no
local lint. CI is the only verification available, which is the other reason
changes must reach it through a PR before landing.
