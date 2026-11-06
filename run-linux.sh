#!/usr/bin/env bash
# Record Labs for Linux — run straight from the source, no installer
# (the Linux counterpart of "gradlew.bat run" on Windows).
#
#   ./run-linux.sh
#
# The first run installs what is missing (Java 21 and VLC, password asked once)
# and downloads yt-dlp + Deno; later runs start right away.
set -euo pipefail
cd "$(dirname "$0")"
say() { printf '\n\033[1;35m==>\033[0m %s\n' "$*"; }

have_jdk21() {
  for j in /usr/lib/jvm/*; do
    [ -x "$j/bin/javac" ] && "$j/bin/java" -version 2>&1 | grep -qE 'version "21' && return 0
  done
  command -v javac >/dev/null && java -version 2>&1 | grep -qE 'version "21'
}
have_libvlc() {
  ls src/appResources/linux-x64/vlc/libvlc.so* >/dev/null 2>&1 && return 0
  ldconfig -p 2>/dev/null | grep -q 'libvlc\.so\.5'
}

missing=()
have_jdk21 || missing+=(jdk)
have_libvlc || missing+=(vlc)
command -v curl >/dev/null || missing+=(curl)
command -v unzip >/dev/null || missing+=(unzip)

if [ ${#missing[@]} -gt 0 ]; then
  say "Memasang yang belum ada: ${missing[*]} (akan diminta password)…"
  if command -v apt-get >/dev/null; then
    pkgs=()
    for m in "${missing[@]}"; do case $m in
      jdk) pkgs+=(openjdk-21-jdk) ;; vlc) pkgs+=(libvlc5 vlc-plugin-base vlc-plugin-video-output) ;; *) pkgs+=("$m") ;; esac; done
    sudo apt-get update -qq || true
    sudo apt-get install -y "${pkgs[@]}"
  elif command -v dnf >/dev/null; then
    pkgs=()
    for m in "${missing[@]}"; do case $m in
      jdk) pkgs+=(java-21-openjdk-devel) ;; vlc) pkgs+=(vlc) ;; *) pkgs+=("$m") ;; esac; done
    sudo dnf install -y "${pkgs[@]}"
  elif command -v zypper >/dev/null; then
    pkgs=()
    for m in "${missing[@]}"; do case $m in
      jdk) pkgs+=(java-21-openjdk-devel) ;; vlc) pkgs+=(vlc-noX) ;; *) pkgs+=("$m") ;; esac; done
    sudo zypper --non-interactive install "${pkgs[@]}"
  elif command -v pacman >/dev/null; then
    pkgs=()
    for m in "${missing[@]}"; do case $m in
      jdk) pkgs+=(jdk21-openjdk) ;; vlc) pkgs+=(vlc) ;; *) pkgs+=("$m") ;; esac; done
    sudo pacman -S --needed --noconfirm "${pkgs[@]}"
  else
    echo "Pasang Java 21 (JDK) dan VLC secara manual, lalu jalankan lagi."; exit 1
  fi
fi

# gradlew keeps LF line endings in git, but a copy made on Windows may not.
sed -i 's/\r$//' gradlew 2>/dev/null || true
chmod +x gradlew packaging/linux/*.sh

say "Menjalankan Record Labs (build pertama ±5–10 menit, berikutnya cepat)…"
exec ./gradlew --no-daemon -q run "$@"
