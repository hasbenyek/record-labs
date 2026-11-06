# Daftar uji manual Record Labs (Linux + Windows)

Centang yang lulus; kalau gagal, catat **distro + desktop (COSMIC/GNOME/KDE…), X11 atau
Wayland, langkahnya, dan output terminal** (jalankan `record-labs` dari terminal supaya
pesan error terlihat). Simpan catatannya untuk perbaikan berikutnya.

Cek sesi: `echo $XDG_SESSION_TYPE` (x11 / wayland) dan `echo $XDG_CURRENT_DESKTOP`.

---

## A. Instalasi (per distro)

| Distro | Paket | Perintah pasang |
|---|---|---|
| Ubuntu / Debian / Pop!_OS / Mint | `.deb` | `sudo apt install ./record-labs_X.Y.Z_amd64.deb` |
| Fedora | `.rpm` | `sudo dnf install ./record-labs-X.Y.Z-1.x86_64.rpm` |
| openSUSE | `.rpm` | `sudo zypper install --allow-unsigned-rpm ./record-labs-X.Y.Z-1.x86_64.rpm` |
| Arch | `.tar.xz` + `PKGBUILD` | taruh keduanya di satu folder → `makepkg -si` |
| Semua | AppImage | `chmod +x RecordLabs-*.AppImage && ./RecordLabs-*.AppImage` |

- [ ] Ikon "Record Labs" muncul di menu aplikasi dan bisa dibuka
- [ ] `record-labs --self-test-audio` → `SELF-TEST PASS`
- [ ] Hapus aplikasi bersih: `sudo apt purge record-labs` / `sudo dnf remove record-labs` / `sudo zypper rm record-labs` / `sudo pacman -R record-labs-bin`

## B. Fungsi dasar (di tiap distro yang diuji)

- [ ] Login Google lewat browser default (Firefox / Chrome), jendela login tertutup sendiri
- [ ] Cari lagu → putar; suara keluar, **awal lagu tidak tersendat**
- [ ] Pause/lanjut, next/previous, geser posisi, volume, kecepatan & pitch
- [ ] Lirik bergerak tepat waktu (tidak telat); font lirik tebal mirip SF Pro
- [ ] Music video / animated artwork tampil
- [ ] Queue: buka, pindah urutan, hapus, tombol X menutup panel
- [ ] Fullscreen (F11 / tombol) → keluar: kembali ke ukuran sebelumnya (biasa dan maximized)
- [ ] Tombol media keyboard (play/pause, next, previous, stop) saat aplikasi di depan **dan** di belakang
- [ ] Kontrol media desktop (panel/lock screen) menampilkan judul & bisa mengontrol
- [ ] Discord Rich Presence (sudah lulus di Pop!_OS)
- [ ] Download lagu offline & putar tanpa internet
- [ ] Listen Together: buat/join room (butuh build dengan kunci Listen Together)
- [ ] Tutup lalu buka lagi: lagu terakhir, volume, ukuran jendela diingat

## C. Floating lyrics (PiP)

- [ ] Tombol PiP → aplikasi mengecil, lirik melayang muncul **transparan** (Wayland: COSMIC/KDE/sway;
      GNOME & X11 memakai jendela biasa)
- [ ] Lirik bergerak lancar, tidak patah-patah
- [ ] Arahkan kursor → panel kontrol muncul; tombol prev/play/next/buka aplikasi/tutup berfungsi
- [ ] Geser lewat strip atas; ubah ukuran lewat pojok kanan bawah
- [ ] Dua monitor: geser ke layar lain (setelah pindah, lepas klik lalu geser lagi); menjangkau seluruh layar besar
- [ ] Klik aplikasi lain → PiP tetap tampil & tidak mencuri fokus keyboard; kembali ke Record Labs → PiP hilang

## D. Record Labs Connect (Wi-Fi sama, akun Google sama)

Sebelum mulai di Linux: `record-labs --self-test-connect` sambil Record Labs terbuka di perangkat
lain → harus ada baris `heard: ...`. Kalau firewall memblokir, jalankan perintah yang ditampilkan.

Untuk tiap pasangan: **Linux ↔ Windows**, **Linux ↔ Android**, **Linux ↔ Linux** (kalau ada):

- [ ] Kedua perangkat saling muncul di Devices Connect; nama Linux = nama komputer, platform "Linux"
- [ ] Putar di A → B ikut menampilkan lagu, posisi, dan **antrean** (termasuk antrean panjang > 60 lagu)
- [ ] Dari B: play/pause, next/previous, seek → yang bunyi tetap A
- [ ] Dari B: pilih lagu di antrean → A memutar lagu itu
- [ ] Dari B: tambah lagu (Play next), pindah urutan, hapus dari antrean → A ikut berubah
- [ ] **Handoff**: dari B pilih "This … PC/device" → suara pindah ke B. Catat:
      jeda tanpa suara berapa detik, lagu lanjut dari posisi yang sama atau mulai ulang,
      sempat terdengar dobel atau tidak
- [ ] Handoff balik ke A
- [ ] Matikan Wi-Fi A sebentar → B berhenti mengikuti dengan pesan; nyalakan lagi → saling terlihat lagi

Bandingkan handoff Linux dengan Windows ↔ Windows / Windows ↔ Android dan catat bedanya.

## E. Auto-update (setelah rilis lewat GitHub Actions, lihat RILIS.md)

1. Pasang versi lama (mis. 0.1.2) dari tiap jenis paket.
2. Terbitkan rilis baru (mis. 0.1.3).
3. Buka aplikasi (atau Settings → About → Updates → Check now):

- [ ] Muncul badge "1" dan "Update available" dengan catatan rilis
- [ ] Windows `.msi`: Update now → Restart to update → terbuka versi baru, data tetap ada
- [ ] `.deb` (Ubuntu/Pop!_OS): minta password → terpasang → aplikasi terbuka lagi di versi baru
- [ ] `.rpm` Fedora (dnf) dan openSUSE (zypper): sama seperti di atas
- [ ] AppImage: file AppImage diganti tanpa password, terbuka versi baru
- [ ] Arch: hanya pemberitahuan, update lewat AUR
- [ ] Batal di dialog password → aplikasi tetap jalan; di About tertulis update dibatalkan ("was cancelled")
- [ ] Versi sama dengan rilis terbaru → "up to date", tanpa badge

## F. Distro yang wajib dicoba (minimal)

| Distro | Desktop | Sesi | A | B | C | D | E |
|---|---|---|---|---|---|---|---|
| Pop!_OS 24.04 | COSMIC | Wayland | | | | | |
| Ubuntu 24.04 | GNOME | Wayland | | | | | |
| Ubuntu 24.04 | GNOME | X11 ("Ubuntu on Xorg") | | | | | |
| Fedora 42 / 43 | GNOME atau KDE | Wayland | | | | | |
| openSUSE Tumbleweed | KDE | Wayland | | | | | |
| Arch | KDE / GNOME | Wayland | | | | | |

VM (VirtualBox / GNOME Boxes) cukup untuk A, B, C, E. Untuk suara dan Connect, lebih baik
di laptop asli atau VM dengan jaringan "Bridged" (bukan NAT), supaya satu Wi-Fi dengan perangkat lain.
