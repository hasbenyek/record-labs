#!/usr/bin/env bash
# Record Labs for Linux — build the installers from this source
# (the Linux counterpart of "gradlew.bat packageMsi" on Windows).
#
#   ./build-installer-linux.sh
#
# Builds on Pop!_OS / Ubuntu / Debian / Mint (VLC is bundled from those
# packages). Results:
#   build/linux-packages/   .deb, .rpm, Arch tarball + PKGBUILD (+ AppImage if appimagetool is installed)
#   build/linux-kit/        folder "linux-recordlabs" (deb + install.sh) and its .tar.gz
# At the end it offers to install the .deb on this computer.
set -euo pipefail
cd "$(dirname "$0")"
say() { printf '\n\033[1;35m==>\033[0m %s\n' "$*"; }

command -v apt-get >/dev/null || { echo "Script build ini untuk Pop!_OS/Ubuntu/Debian/Mint (apt). Untuk menjalankan saja: ./run-linux.sh"; exit 1; }

say "Memasang alat build (akan diminta password)…"
sudo apt-get update -qq || true
sudo apt-get install -y openjdk-21-jdk binutils libvlc-dev vlc-plugin-base vlc-plugin-video-output patchelf rpm fakeroot xz-utils curl unzip

sed -i 's/\r$//' gradlew 2>/dev/null || true
chmod +x gradlew packaging/linux/*.sh

say "Membangun installer (±10–15 menit pertama kali)…"
packaging/linux/build-all.sh
packaging/linux/make-kit.sh >/dev/null

DEB="$(ls build/linux-packages/record-labs_*_amd64.deb | sort -V | tail -1)"
say "Selesai. Installer ada di build/linux-packages/ dan folder siap-bagi di build/linux-kit/"
ls -1 build/linux-packages build/linux-kit

read -r -p $'\nPasang Record Labs di komputer ini sekarang? [Y/n] ' answer
case "${answer:-y}" in
  n|N|no|tidak) echo "Pasang nanti dengan:  sudo apt install ./$DEB" ;;
  *) sudo apt-get install -y "./$DEB" && say "Terpasang. Buka 'Record Labs' dari menu aplikasi atau ketik: record-labs" ;;
esac
