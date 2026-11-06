#!/usr/bin/env bash
# Builds the "linux-recordlabs" folder for people who install by hand:
# the .deb, install.sh, uninstall.sh and PANDUAN-INSTALL.txt (Indonesian guide),
# plus linux-recordlabs-<version>.tar.gz of that folder.
# Run after package.sh:  packaging/linux/make-kit.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIT="$ROOT/packaging/linux/kit"
PKGS="$ROOT/build/linux-packages"
DEB="$(ls "$PKGS"/record-labs_*_amd64.deb 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$DEB" ] || { echo "No .deb in $PKGS — run packaging/linux/package.sh first."; exit 1; }
VERSION="$(basename "$DEB" | sed 's/^record-labs_\(.*\)_amd64\.deb$/\1/')"
OUT="$ROOT/build/linux-kit/linux-recordlabs"

if [ -n "${LISTEN_TOGETHER_KEY:-}" ] || grep -qs '^LISTEN_TOGETHER_KEY=.\+' "$ROOT/local.properties"; then
  note=""
else
  note="
Catatan: build ini dibuat tanpa kunci Listen Together resmi, jadi server Record Labs
menolak Listen Together. Fitur lain berjalan normal."
fi

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$DEB" "$OUT/"
install -m 755 "$KIT/install.sh" "$KIT/uninstall.sh" "$OUT/"
python3 - "$KIT/PANDUAN-INSTALL.txt" "$OUT/PANDUAN-INSTALL.txt" "$VERSION" "$note" <<'PY'
import sys
src, dst, version, note = sys.argv[1:5]
text = open(src, encoding="utf-8").read().replace("@VERSION@", version).replace("@LISTEN_TOGETHER_NOTE@", note)
open(dst, "w", encoding="utf-8").write(text)
PY
tar -C "$(dirname "$OUT")" -czf "$ROOT/build/linux-kit/linux-recordlabs-$VERSION.tar.gz" linux-recordlabs
ls -la "$OUT" "$ROOT/build/linux-kit"
