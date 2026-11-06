#!/usr/bin/env bash
# Full Linux build: helper tools, bundled VLC, app image, installers.
# Used by .github/workflows/linux.yml (Ubuntu 22.04) and for local builds.
#
# Needs (Ubuntu/Debian): openjdk-21-jdk (or any JDK 21 with jpackage),
#   binutils (objcopy, used by jlink) libvlc-dev vlc-plugin-base vlc-plugin-video-output patchelf rpm fakeroot
#   xz-utils curl unzip; appimagetool on PATH (or $APPIMAGETOOL) for the AppImage.
# Extra Gradle arguments can be passed in $GRADLE_ARGS (e.g. an init script).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export LANG="${LANG:-C.UTF-8}"

packaging/linux/fetch-tools.sh
packaging/linux/bundle-vlc.sh
rm -rf build/compose-*/binaries/main/app
# shellcheck disable=SC2086
./gradlew --no-daemon ${GRADLE_ARGS:-} createDistributable
packaging/linux/package.sh "$@"
