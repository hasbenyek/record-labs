#!/usr/bin/env bash
# Record Labs for Linux — installer for Pop!_OS, Ubuntu, Linux Mint and Debian.
# Run from this folder:  bash install.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
say() { printf '\n\033[1;35m==>\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31mGagal:\033[0m %s\n' "$*" >&2; exit 1; }

DEB="$(ls "$DIR"/record-labs_*_amd64.deb 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$DEB" ] || fail "file record-labs_*_amd64.deb tidak ditemukan di folder ini."
[ "$(uname -m)" = "x86_64" ] || fail "Record Labs untuk Linux butuh komputer 64-bit (x86_64)."
command -v apt-get >/dev/null || fail "script ini untuk Pop!_OS/Ubuntu/Mint/Debian (apt). Untuk Fedora/openSUSE pakai file .rpm."

say "Memasang $(basename "$DEB") (akan diminta password)…"
sudo apt-get update -qq || true
# apt (bukan dpkg) supaya library yang dibutuhkan ikut terpasang otomatis.
sudo apt-get install -y "$DEB"

say "Mengecek audio dan komponen bawaan (yt-dlp, Deno, VLC)…"
if record-labs --self-test-audio 2>&1 | grep -E '^SELF-TEST'; then :; fi

# Login Google memakai browser berbasis Chromium (Firefox tidak bisa).
browser=""
for b in google-chrome google-chrome-stable chromium chromium-browser brave-browser brave microsoft-edge vivaldi; do
  command -v "$b" >/dev/null && { browser="$b"; break; }
done
[ -z "$browser" ] && for f in /opt/google/chrome/google-chrome /var/lib/flatpak/exports/bin/com.google.Chrome /var/lib/flatpak/exports/bin/com.brave.Browser /var/lib/flatpak/exports/bin/org.chromium.Chromium; do
  [ -x "$f" ] && { browser="$f"; break; }
done
if [ -z "$browser" ]; then
  say "Catatan: untuk login akun Google, pasang Chrome, Chromium atau Brave (Firefox tidak bisa dipakai untuk login)."
  echo "    Memutar lagu tetap bisa tanpa login."
fi

say "Selesai! Buka 'Record Labs' dari menu aplikasi, atau ketik: record-labs"
