#!/usr/bin/env bash
# Make sure rtk is on PATH, so the PreToolUse hook in .claude/settings.json has
# something to call. Without this the sandbox starts every session with an empty
# container and the rewrite hook quietly no-ops.
#
# Deliberately never fails: a missing rtk costs tokens, not correctness, and a
# session that refuses to start because a compression tool did not download
# would be a much worse trade.

set -uo pipefail

RTK_VERSION="v0.45.0"
INSTALLER="https://raw.githubusercontent.com/rtk-ai/rtk/refs/heads/master/install.sh"

export PATH="$HOME/.local/bin:$PATH"

if command -v rtk >/dev/null 2>&1; then
  exit 0
fi

# Only install unattended in the ephemeral web sandbox. On a personal machine,
# say what to run instead — dropping a binary into someone's ~/.local/bin
# without asking is not ours to do.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  echo "rtk is not installed, so bash output will not be compressed."
  echo "To install it: curl -fsSL $INSTALLER | sh"
  exit 0
fi

# The version has to be pinned. The installer's default is to look up the latest
# tag through api.github.com, which the sandbox proxy answers with a 403 for
# every repository except this one, so the lookup fails and takes the install
# with it. Bump this by hand.
if ! curl -fsSL "$INSTALLER" 2>/dev/null | RTK_VERSION="$RTK_VERSION" sh >/dev/null 2>&1; then
  echo "rtk $RTK_VERSION did not install; continuing without output compression."
  exit 0
fi

echo "rtk $(rtk --version 2>/dev/null | awk '{print $2}') ready; bash output is compressed."
