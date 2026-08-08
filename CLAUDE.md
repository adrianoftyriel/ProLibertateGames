# Working on this repo with Claude

## Nothing a session does triggers CI. Dispatch it explicitly.

GitHub creates **no workflow run** for anything this sandbox does — pushing a
branch, opening a pull request, or merging one. All of it goes through the same
integration, and GitHub suppresses event-triggered runs from it. The pull
request will say it was opened by the repo owner; that is only how the UI
attributes it, and it does not change the suppression.

This was measured, not assumed:

| Action | Result |
| --- | --- |
| Human pushes a Claude-authored commit (`d165e5ef`, `469c900e`) | CI runs |
| Session pushes `dev` / `main` (`43394b8`, `742add4`) | no run |
| Session opens a PR against `dev` (#1) | no run |
| Session calls `workflow_dispatch` | **runs** |

`workflow_dispatch` works because it creates a run directly instead of relying
on an event. It is the only mechanism available from a session, so every build
has to be asked for by hand.

## Verify before merging, without publishing

`ci.yml` builds, tests and lints on any ref, but its staging and publishing
steps are gated on `github.ref == 'refs/heads/dev'`. Dispatching it on a
**feature branch** therefore gives the full check — build, unit tests, Android
lint — and publishes nothing.

So the working loop is:

1. Commit and push the feature branch.
2. Dispatch `ci.yml` on that branch. Wait for green. This is the real review
   gate, and it is the only verification that exists (see below).
3. Open the PR through the API and merge it there, so the change is reviewable
   and the merge commit is honest about what happened. Neither step builds
   anything.
4. Dispatch the target branch's workflow to actually build and publish:
   `ci.yml` on `dev`, `release.yml` on `main`.

Skipping step 4 leaves a branch whose head was never built and no release.

## Merging is a shipping action

Both long-lived branches publish, so a merge is a release, not just an
integration:

- **`dev`** → `ci.yml` publishes a `v<series>.N-dev` **prerelease**, offered by the
  in-app updater to anyone on the Dev channel.
- **`main`** → `release.yml` publishes a **production** release, which the
  updater installs for everyone on the Production channel.

Confirm before shipping to `main`. Note that `release.yml` runs `assembleDebug`
only — no tests, no lint — so a `main` release is only as verified as the `dev`
build it replays.

## Gradle does not run in the sandbox

`./gradlew` cannot resolve the Android plugin here: the proxy blocks
`dl.google.com` with a 403. No local build, no local test run, no local lint. A
dispatched CI run is the only way to find out whether a change compiles.

## A permanent fix, if you want one

The suppression is a property of the sandbox's credential, so it cannot be
fixed from inside a session. Giving the environment a personal access token to
push with would make pushes and PRs behave normally for everyone. Until then,
the dispatch step above is the workaround.

## Bash output is filtered before it reaches the model

`.claude/settings.json` registers a `PreToolUse` hook on `Bash` that rewrites
each command through [rtk](https://github.com/rtk-ai/rtk): `git status` runs as
`rtk git status`, and rtk compresses the output. Write commands normally — the
rewrite is transparent and costs nothing to invoke.

Measured on this repo rather than quoted from rtk's README:

| Command | Raw | Filtered | Saved |
| --- | --- | --- | --- |
| `git diff HEAD~3` | 521 KB | 28 KB | 95% |
| `grep -rn "fun " app/src/main` | 117 KB | 19 KB | 84% |
| `find . -name "*.kt"` | 7.7 KB | 1.7 KB | 79% |
| `git log -20` | 20 KB | 5.3 KB | 74% |
| `git diff --stat` | 4.0 KB | 4.0 KB | 1% |
| `ls` of a single package | 50 B | 62 B | −24% |

The saving is all in the large outputs, which is the point — a full diff of a
few commits is otherwise most of a context window. Already-compact output grows
slightly, because rtk adds a header; at those sizes it does not matter. `rtk
gain` reports what the current session has actually saved.

Two limits are worth knowing. The `Read`, `Grep` and `Glob` tools are not
`Bash`, so they never reach the hook and are unaffected; a deliberate `rg` or
`find` in a shell *is* filtered. And filtering never truncates a file: `cat`
becomes `rtk read`, which was checked byte-for-byte against this repo's longest
Kotlin source (`GameSetupScreen.kt`, 946 lines) and came back identical. Commit
messages survive the rewrite intact, quotes and colons included. To bypass
filtering for one command, run `rtk proxy <cmd>`.

## rtk is reinstalled at the start of every session

The container starts empty, so `.claude/hooks/session-start.sh` installs rtk
before the session does anything. It pins `RTK_VERSION`, because the installer
otherwise asks `api.github.com` for the latest tag and the proxy answers 403 for
every repository except this one — the lookup fails and takes the install with
it. Bump that variable by hand.

The hook is written not to matter when it fails. Outside the sandbox it installs
nothing and just prints the command to run, since dropping a binary into
someone's `~/.local/bin` uninvited is not its business; if the download fails it
says so and exits clean. With rtk absent the rewrite hook no-ops, and the only
consequence is verbose output.
