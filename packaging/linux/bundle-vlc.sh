#!/usr/bin/env bash
# Copies libVLC, the plugins Record Labs uses and every library they need
# into src/appResources/linux-x64/vlc, so the Linux packages work without a
# system VLC (Ubuntu, Fedora, Arch, openSUSE).
#
# Run on the build machine (CI uses Ubuntu 22.04 so the copied libraries only
# need glibc 2.35, older than all four target distros). Needs: libvlc-dev,
# vlc-plugin-base, vlc-plugin-video-output, patchelf.
#
# Layout produced:
#   vlc/libvlc.so, libvlc.so.5, libvlccore.so.9, libvlc_pulse.so.0, deps...
#   vlc/plugins/<category>/*.so   (+ plugins.dat cache)
# Every copied file gets an RPATH pointing at vlc/ so the dynamic linker finds
# the bundled copies instead of (possibly different) system versions.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/src/appResources/linux-x64/vlc"
if [ -z "${VLC_LIB_DIR:-}" ]; then
  if [ -f "/usr/lib64/libvlc.so.5" ]; then
    SRC_LIB="/usr/lib64"
  elif [ -f "/usr/lib/x86_64-linux-gnu/libvlc.so.5" ]; then
    SRC_LIB="/usr/lib/x86_64-linux-gnu"
  elif [ -f "/usr/lib/libvlc.so.5" ]; then
    SRC_LIB="/usr/lib"
  else
    SRC_LIB="/usr/lib/x86_64-linux-gnu"
  fi
else
  SRC_LIB="$VLC_LIB_DIR"
fi
SRC_PLUGINS="$SRC_LIB/vlc/plugins"

[ -f "$SRC_LIB/libvlc.so.5" ] || { echo "libvlc not found in $SRC_LIB (install libvlc-dev vlc-plugin-base)"; exit 1; }
command -v patchelf >/dev/null || { echo "patchelf is required"; exit 1; }

rm -rf "$DEST"
mkdir -p "$DEST/plugins"

# Plugins kept, per category ("*" = whole category). Everything else (GUI,
# Lua, streaming output, visualisations, hardware video...) is left out.
# Audio: Opus/WebM and AAC/MP4 from YouTube, plus MP3/FLAC/ALAC/OGG/WAV for
# local music. Video (music videos, animated artwork): H.264/HEVC/AV1 in MP4.
declare -A KEEP=(
  [access]="filesystem http https idummy imem tcp"
  [audio_filter]="scaletempo scaletempo_pitch audio_format ugly_resampler speex_resampler trivial_channel_mixer simple_channel_mixer remap equalizer normvol gain compressor karaoke"
  [audio_mixer]="*"
  [audio_output]="pulse alsa adummy amem"
  [codec]="avcodec flac opus vorbis mpg123 faad araw lpcm dav1d ddummy edummy spdif adpcm g711 a52"
  [demux]="es mp4 mkv ogg wav flacsys mpgv ps aiff avi rawaud demuxdump"
  [packetizer]="*"
  [stream_filter]="cache_block cache_read prefetch inflate record"
  [video_chroma]="swscale i420_rgb i420_yuy2 i422_i420 i422_yuy2 i420_10_p010 grey_yuv rv32 chain yuy2_i420 yuy2_i422 yuvp"
  [video_output]="vmem vdummy"
  [misc]="gnutls"
  [logger]="console"
)

for category in "${!KEEP[@]}"; do
  [ -d "$SRC_PLUGINS/$category" ] || continue
  mkdir -p "$DEST/plugins/$category"
  if [ "${KEEP[$category]}" = "*" ]; then
    cp -L "$SRC_PLUGINS/$category"/*.so "$DEST/plugins/$category/"
  else
    for name in ${KEEP[$category]}; do
      file="$SRC_PLUGINS/$category/lib${name}_plugin.so"
      [ -f "$file" ] && cp -L "$file" "$DEST/plugins/$category/"
    done
  fi
done

cp -L "$SRC_LIB/libvlc.so.5" "$DEST/libvlc.so.5"
cp -L "$SRC_LIB/libvlc.so.5" "$DEST/libvlc.so"
cp -L "$SRC_LIB/libvlccore.so.9" "$DEST/libvlccore.so.9"
[ -f "$SRC_LIB/vlc/libvlc_pulse.so.0" ] && cp -L "$SRC_LIB/vlc/libvlc_pulse.so.0" "$DEST/"

# Libraries every desktop distro provides (or that must match the running
# system: glibc, audio servers, X11/GL, D-Bus). Based on the AppImage
# project's excludelist. libva/libvdpau are bundled: FFmpeg links them, but
# not every distro installs them by default.
is_system_lib() {
  case "$1" in
    ld-linux*|libc.so*|libm.so*|libdl.so*|libpthread.so*|librt.so*|libresolv.so*|libutil.so*|libnsl.so*|libanl.so*|libmvec.so*|libBrokenLocale.so*) return 0 ;;
    libstdc++.so*|libgcc_s.so*|libz.so*|libexpat.so*|libuuid.so*|libcrypt.so*) return 0 ;;
    libasound.so*|libpulse.so*|libpulse-simple.so*|libjack.so*|libpipewire*) return 0 ;;
    libX11*|libxcb*|libXau*|libXdmcp*|libXext*|libXrender*|libGL*|libEGL*|libGLX*|libGLdispatch*|libOpenGL*|libdrm*|libgbm*|libwayland*) return 0 ;;
    libdbus-1.so*|libsystemd.so*|libudev.so*|libfontconfig.so*|libfreetype.so*|libharfbuzz.so*|libfribidi.so*) return 0 ;;
    libglib-2.0.so*|libgobject-2.0.so*|libgio-2.0.so*|libgmodule-2.0.so*|libselinux.so*|libcap.so*|libmount.so*|libblkid.so*) return 0 ;;
  esac
  return 1
}

# Copy dependencies until nothing new appears.
copied=1
while [ "$copied" -gt 0 ]; do
  copied=0
  while IFS= read -r -d '' elf; do
    while read -r name _ path _; do
      [ -n "${path:-}" ] && [ -f "$path" ] || continue
      is_system_lib "$name" && continue
      [ -e "$DEST/$name" ] && continue
      cp -L "$path" "$DEST/$name"
      copied=$((copied + 1))
    done < <(LD_LIBRARY_PATH="$DEST" ldd "$elf" 2>/dev/null | awk '/=>/ {print $1, $2, $3}')
  done < <(find "$DEST" -name '*.so*' -type f -print0)
done

# Find bundled copies first.
find "$DEST" -maxdepth 1 -name '*.so*' -type f -exec patchelf --set-rpath '$ORIGIN' {} \;
find "$DEST/plugins" -name '*.so' -type f -exec patchelf --set-rpath '$ORIGIN/../..' {} \;
chmod 644 "$DEST"/*.so* "$DEST"/plugins/*/*.so

# Plugin cache so VLC starts without scanning every plugin.
if [ -x "$SRC_LIB/vlc/vlc-cache-gen" ]; then
  LD_LIBRARY_PATH="$DEST" "$SRC_LIB/vlc/vlc-cache-gen" "$DEST/plugins" || echo "warning: plugin cache not generated"
fi

# Report anything still unresolved (should only be system libraries).
missing=$(find "$DEST" -name '*.so*' -type f -exec sh -c 'ldd "$1" 2>/dev/null | grep "not found"' _ {} \; | sort -u || true)
[ -n "$missing" ] && { echo "Unresolved libraries:"; echo "$missing"; }

echo "Bundled VLC: $(du -sh "$DEST" | cut -f1), $(find "$DEST/plugins" -name '*.so' | wc -l) plugins, $(find "$DEST" -maxdepth 1 -name '*.so*' | wc -l) libraries"
