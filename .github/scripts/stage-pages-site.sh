#!/usr/bin/env bash
# Assembles the Cloudflare Pages site for plg.wallacenet.ca into ./pages-out.
#
# Usage: stage-pages-site.sh <prod|dev> <tag> <apk-path>
#
# A Pages deploy replaces the whole site, and both channels live in one site,
# so this stages the channel being published and then re-fetches the *other*
# channel's current files from the live site so the deploy does not erase them.
set -euo pipefail

channel="$1"; tag="$2"; apk="$3"
base="${PAGES_BASE_URL:-https://plg.wallacenet.ca}"
out="pages-out"
limit=$((25 * 1024 * 1024)) # Cloudflare Pages: 25 MiB per file

case "$channel" in prod) other=dev ;; dev) other=prod ;; *) echo "bad channel: $channel" >&2; exit 2 ;; esac

rm -rf "$out"
mkdir -p "$out/$channel" "$out/$other"
cp site/index.html site/404.html site/_headers "$out/"

name="$(basename "$apk")"
cp "$apk" "$out/$channel/$name"
cp "$apk" "$out/$channel/ProLibertateGames.apk"
jq -n --arg tag "$tag" --arg name "$name" --arg ch "$channel" --arg url "$base/$channel/$name" \
  '{tag: $tag, apkName: $name, apkUrl: $url, channel: $ch}' > "$out/$channel/latest.json"

# PLG_SKIP_OTHER=1 is for the very first deploy, when the site does not exist
# yet and the domain answers 403 (Cloudflare error 1014) instead of 404.
if [ -n "${PLG_SKIP_OTHER:-}" ]; then
  echo "PLG_SKIP_OTHER set; not carrying over $other."
  rm -rf "$out/$other"
  find "$out" -type f | sort
  exit 0
fi

# Carry the other channel over. 404 means it was never published; any other
# failure must stop the deploy rather than silently dropping that channel.
code="$(curl -sS -o "$out/$other/latest.json" -w '%{http_code}' "$base/$other/latest.json" || true)"
: "${code:=000}"
# Cloudflare error 1014 (403 + "error code: 1014") means the domain's CNAME
# points at a Pages project that does not exist yet, so there is nothing to
# preserve — the same as a 404, and what a push-triggered first deploy sees.
if [ "$code" = 403 ] && grep -q 'error code: 1014' "$out/$other/latest.json" 2>/dev/null; then
  code=404
fi
if [ "$code" = 404 ]; then
  echo "No $other channel published yet; skipping."
  rm -rf "$out/$other"
elif [ "$code" = 200 ] && ! jq -e .apkName "$out/$other/latest.json" >/dev/null 2>&1; then
  # A 200 that is not our JSON: Pages without a 404.html answers unknown paths
  # with index.html. Nothing is published on that channel.
  echo "No $other channel published yet (200 but no latest.json); skipping."
  rm -rf "$out/$other"
elif [ "$code" = 200 ]; then
  other_name="$(jq -er .apkName "$out/$other/latest.json")"
  curl -fsS -o "$out/$other/$other_name" "$base/$other/$other_name"
  cp "$out/$other/$other_name" "$out/$other/ProLibertateGames.apk"
else
  echo "Fetching $other/latest.json returned HTTP $code; refusing to deploy." >&2
  exit 1
fi

find "$out" -name '*.apk' -size +"${limit}c" -print | grep . >&2 && {
  echo "APK exceeds Cloudflare Pages' 25 MiB per-file limit." >&2; exit 1; } || true
find "$out" -type f | sort
