#!/usr/bin/env bash
# Downloads the Linux helper programs Record Labs bundles (yt-dlp, Deno) into
# src/appResources/linux-x64, where both `./gradlew run` and the packaging
# tasks pick them up. The files are not committed to git; CI runs this script
# before building, and so can you on a Linux machine.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/src/appResources/linux-x64"
YTDLP_VERSION="${YTDLP_VERSION:-latest}"
DENO_VERSION="${DENO_VERSION:-latest}"
mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ "$YTDLP_VERSION" = latest ]; then
  YTDLP_URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
else
  YTDLP_URL="https://github.com/yt-dlp/yt-dlp/releases/download/$YTDLP_VERSION/yt-dlp_linux"
fi
echo "Downloading yt-dlp ($YTDLP_VERSION)"
curl -fL --retry 3 -o "$DEST/yt-dlp" "$YTDLP_URL"
chmod +x "$DEST/yt-dlp"

if [ "$DENO_VERSION" = latest ]; then
  DENO_URL="https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip"
else
  DENO_URL="https://github.com/denoland/deno/releases/download/$DENO_VERSION/deno-x86_64-unknown-linux-gnu.zip"
fi
echo "Downloading Deno ($DENO_VERSION)"
curl -fL --retry 3 -o "$TMP/deno.zip" "$DENO_URL"
unzip -o -q "$TMP/deno.zip" -d "$TMP"
install -m 755 "$TMP/deno" "$DEST/deno"

echo "Done: $(ls "$DEST" | tr '\n' ' ')"
