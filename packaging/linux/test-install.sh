#!/usr/bin/env bash
# Installs a built package inside a clean distribution container and checks
# that Record Labs starts and plays audio with only the bundled VLC.
#
# Usage: packaging/linux/test-install.sh <docker-image> <package-file> [--gui]
#   deb  -> ubuntu:22.04, ubuntu:24.04, debian:12
#   rpm  -> fedora:42, opensuse/tumbleweed, opensuse/leap:15.6
#   tar  -> archlinux:latest (installed the way the PKGBUILD does)
#   AppDir directory -> any image (runs AppRun)
set -euo pipefail

IMAGE="$1"
PACKAGE="$(readlink -f "$2")"
GUI="${3:-}"
NAME="$(basename "$PACKAGE")"

case "$IMAGE" in
  ubuntu*|debian*)
    install='export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq /pkg/'"$NAME"' xvfb x11-utils >/dev/null' ;;
  fedora*)
    install='dnf install -y -q /pkg/'"$NAME"' xorg-x11-server-Xvfb xwininfo xprop >/dev/null' ;;
  opensuse*)
    install='zypper -q --non-interactive --no-gpg-checks install --allow-unsigned-rpm /pkg/'"$NAME"' xvfb-run xorg-x11-server-Xvfb xprop >/dev/null' ;;
  archlinux*)
    install='pacman -Syu --noconfirm -q alsa-lib libpulse libx11 libxext libxi libxrender libxtst libxcb libglvnd fontconfig freetype2 harfbuzz fribidi glib2 dbus libdrm zlib xdg-utils ttf-dejavu xorg-server-xvfb xorg-xprop >/dev/null && tar -xJf /pkg/'"$NAME"' -C / && echo aur > /opt/record-labs/lib/app/package-type' ;;
  *) echo "unknown image $IMAGE"; exit 1 ;;
esac

if [ -d "$PACKAGE" ]; then
  # AppDir: only the system libraries the packages declare are installed.
  mount=(-v "$PACKAGE:/appdir:ro")
  install="${install%%/pkg/*}"
  run_cmd='/appdir/AppRun'
  install=$(case "$IMAGE" in
    ubuntu*|debian*) echo 'export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && t64=$(apt-cache show libasound2t64 >/dev/null 2>&1 && echo "libasound2t64 libglib2.0-0t64" || echo "libasound2 libglib2.0-0"); apt-get install -y -qq $t64 libpulse0 libx11-6 libx11-xcb1 libxext6 libxi6 libxrender1 libxtst6 libgl1 libfontconfig1 libfreetype6 libharfbuzz0b libfribidi0 libdbus-1-3 libdrm2 fonts-dejavu-core xvfb x11-utils >/dev/null' ;;
    fedora*) echo 'dnf install -y -q alsa-lib pulseaudio-libs libX11 libX11-xcb libXext libXi libXrender libXtst libglvnd-glx fontconfig freetype harfbuzz fribidi glib2 dbus-libs libdrm dejavu-sans-fonts xorg-x11-server-Xvfb xwininfo xprop >/dev/null' ;;
    *) echo "$install" ;;
  esac)
else
  mount=(-v "$(dirname "$PACKAGE"):/pkg:ro")
  run_cmd='record-labs'
fi

script="$install
set -e
echo '--- installed files'
ls /opt/record-labs/bin 2>/dev/null || true
command -v record-labs || true
echo '--- system VLC present?'; (ls /usr/lib*/libvlc.so* /usr/lib/x86_64-linux-gnu/libvlc.so* 2>/dev/null || echo 'no system VLC (good: bundled copy must be used)')
echo '--- audio self-test'
$run_cmd --self-test-audio
"
if [ "$GUI" = "--gui" ]; then
  script="$script
echo '--- GUI start (Xvfb, 45 s)'
Xvfb :5 -screen 0 1440x900x24 >/dev/null 2>&1 &
sleep 2
DISPLAY=:5 $run_cmd > /tmp/gui.log 2>&1 &
pid=\$!
sleep 45
if kill -0 \$pid 2>/dev/null; then
  echo 'GUI still running after 45 s'
  DISPLAY=:5 xprop -root _NET_CLIENT_LIST >/dev/null 2>&1 || true
  DISPLAY=:5 xwininfo -root -tree 2>/dev/null | grep -i 'record labs' | head -2 || true
else
  echo 'GUI exited early:'; tail -30 /tmp/gui.log; exit 1
fi
grep -iE 'exception|error' /tmp/gui.log | grep -viE 'antibot|GL context|ALSA|not a bot|at org.schabi' | head -10 || true
"
fi

# Behind a TLS-inspecting proxy (some CI/cloud sandboxes) the container must
# trust the proxy CA to download dependencies; set TEST_CA_BUNDLE to a PEM file.
ca=()
# (Debian/Ubuntu use plain-HTTP mirrors here and their ca-certificates package
# rewrites the bundle, so they get none.)
case "$IMAGE" in ubuntu*|debian*) TEST_CA_BUNDLE="" ;; esac
if [ -n "${TEST_CA_BUNDLE:-}" ] && [ -f "$TEST_CA_BUNDLE" ]; then
  for target in /etc/ssl/certs/ca-certificates.crt /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem \
    /etc/ca-certificates/extracted/tls-ca-bundle.pem /var/lib/ca-certificates/ca-bundle.pem; do
    ca+=(-v "$TEST_CA_BUNDLE:$target:ro")
  done
fi

docker run --rm "${mount[@]}" "${ca[@]}" -e LANG=C.UTF-8 "$IMAGE" bash -c "$script"
