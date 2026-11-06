#!/usr/bin/env bash
# Menghapus Record Labs. Data (library, cache, pengaturan) disimpan kecuali Anda memilih menghapusnya.
set -euo pipefail
printf '\n==> Menghapus aplikasi Record Labs (akan diminta password)…\n'
sudo apt-get purge -y record-labs || true

read -r -p "Hapus juga data Record Labs (library, riwayat, cache, unduhan)? [y/N] " answer
case "$answer" in
  y|Y|ya|Ya)
    rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/record-labs" "${XDG_CACHE_HOME:-$HOME/.cache}/record-labs"
    rm -rf "$HOME/.java/.userPrefs/com/recordlabs" 2>/dev/null || true
    echo "Data dihapus." ;;
  *) echo "Data disimpan di ~/.local/share/record-labs (dipakai lagi kalau Anda memasang ulang)." ;;
esac
