# Record Labs Windows

Desktop port foundation for Record Labs. This project is intentionally isolated
from the Android application in the parent directory.

## Current state

- Kotlin/JVM desktop application
- Compose Desktop window shell
- Record Labs navigation and playback-bar foundation
- Windows `.exe` and `.msi` packaging configuration
- JDK/runtime and Compose dependencies are bundled by the native distribution
- Platform-neutral contracts for catalog, lyrics, downloads, and playback

## Run on Windows

From the parent project directory:

```powershell
.\gradlew.bat -p .\windows-recordlabs run
```

## Build installers

```powershell
.\gradlew.bat -p .\windows-recordlabs packageExe packageMsi
```

The next stage is implementing the contracts with the existing Record Labs
networking sources, then replacing the placeholder playback layer with a
Windows media backend.

## Building from this repository

This repository has its own Gradle wrapper, so from a clone:

```powershell
.\gradlew.bat run
.\gradlew.bat packageMsi
```

Releases on this repository's GitHub page (tag `vX.Y.Z` with the `.msi` attached)
are picked up by the app's in-app updater.

## Linux

The same sources build Record Labs for Linux (Ubuntu, Debian, Fedora, openSUSE,
Arch). Linux uses libVLC for audio instead of the Windows media helper; VLC,
yt-dlp and Deno are bundled, so users install nothing else.

Installers (built by `.github/workflows/linux.yml` on Ubuntu 22.04 and tested in
clean containers of each distribution, attached to the release for `vX.Y.Z` tags):

| File | For | Install |
|---|---|---|
| `record-labs_X.Y.Z_amd64.deb` | Ubuntu, Debian, Mint, Pop!_OS | `sudo apt install ./record-labs_X.Y.Z_amd64.deb` |
| `record-labs-X.Y.Z-1.x86_64.rpm` | Fedora | `sudo dnf install ./record-labs-X.Y.Z-1.x86_64.rpm` |
| | openSUSE | `sudo zypper install --allow-unsigned-rpm ./record-labs-X.Y.Z-1.x86_64.rpm` |
| `RecordLabs-X.Y.Z-x86_64.AppImage` | any distribution | `chmod +x RecordLabs-*.AppImage` and run it |
| `record-labs-X.Y.Z-linux-x86_64.tar.xz` + `PKGBUILD` | Arch (AUR `record-labs-bin`) | `makepkg -si` next to the PKGBUILD |

The in-app updater handles the AppImage (replaces itself) and the .deb/.rpm
(installs through the system password dialog after the app closes). Arch
installs are updated through the AUR.

One-command scripts at the repository root (guide in Indonesian: `PANDUAN-LINUX.txt`):

```sh
./run-linux.sh               # run from source, no installer (installs Java 21 + VLC if missing)
./build-installer-linux.sh   # build the .deb/.rpm/kit and offer to install it (Pop!_OS/Ubuntu/Debian)
```

Building locally on Linux (Ubuntu 22.04 or newer):

```sh
sudo apt install openjdk-21-jdk binutils libvlc-dev vlc-plugin-base vlc-plugin-video-output patchelf rpm fakeroot xz-utils curl unzip
packaging/linux/build-all.sh            # -> build/linux-packages/
./gradlew run                           # development run (after fetch-tools.sh / bundle-vlc.sh)
packaging/linux/test-install.sh fedora:42 build/linux-packages/record-labs-*.rpm --gui   # needs Docker
```

`record-labs --self-test-audio` plays a test tone through the real audio path
and exits with PASS/FAIL, which is how the container tests check each package.

## Listen Together

Listen Together uses the Record Labs server, which only accepts official builds.
Official builds put the private key in `local.properties` (never committed):

```properties
LISTEN_TOGETHER_KEY=...
```

Builds without the key work normally, except that the Record Labs Listen Together
server refuses them. To use Listen Together in your own build, run your own server
(github.com/hasbenyek/record-labs-listen-server) and change
`WindowsListenTogetherClient.serverUrl`.
