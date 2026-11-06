# Cara merilis update Record Labs (Windows + Linux)

Satu tag versi membuat semua installer dan satu GitHub Release. Aplikasi yang
sudah terpasang (Windows maupun Linux) melihat rilis itu dan menawarkan update.

## Sekali saja: kunci Listen Together untuk build otomatis

GitHub → repo `record-labs-windows` → **Settings → Secrets and variables → Actions →
New repository secret**:

- Name: `LISTEN_TOGETHER_KEY`
- Secret: isi yang sama dengan `LISTEN_TOGETHER_KEY=` di `local.properties`

Tanpa secret ini installer tetap jadi, tapi Listen Together ditolak server.

## Setiap kali rilis

1. **Naikkan versi** (satu perintah, di Windows atau Linux):

   ```
   gradlew.bat setVersion -PnewVersion=0.1.3      (Windows)
   ./gradlew setVersion -PnewVersion=0.1.3        (Linux)
   ```

   Ini mengubah `appVersion` di `build.gradle.kts` dan `AppInfo.BUILD_VERSION`
   di `AppSettings.kt`. Versi harus lebih tinggi dari rilis sebelumnya (MSI hanya
   meng-upgrade ke versi lebih tinggi).

2. **(Opsional) Catatan rilis**: tulis `RELEASE_NOTES.md` di folder utama. Isinya
   tampil di aplikasi pada bagian "What's new". Kalau tidak ada, catatannya cuma
   "Record Labs 0.1.3" (bisa diedit nanti di halaman release GitHub).

3. **Commit dan push ke `main`**:

   ```
   git commit -am "Record Labs 0.1.3"
   git push origin main
   ```

   Versi baru di `main` yang belum punya rilis langsung dirilis; tag `v0.1.3`
   dibuat otomatis. (Push tag `v0.1.3` sendiri juga tetap bisa.)

4. **Tunggu GitHub Actions** (tab *Actions* → "Release (Windows + Linux)", ±30–45 menit):
   - Windows: `.msi` dibuat di mesin Windows GitHub (sama seperti `gradlew.bat packageMsi`).
   - Linux: `.deb`, `.rpm`, AppImage, paket Arch dibuat lalu dites di 7 distro.
   - Kalau semua lulus, Release `v0.1.3` terbit (bukan pre-release) berisi semua file;
     rilis baru tampil setelah semua file selesai diunggah. Kalau ada yang gagal,
     tidak ada rilis yang terbit.

   Push ke `main` tanpa menaikkan versi hanya membangun dan mengetes (tidak merilis).
   Kalau memakai tag, tag harus sama dengan versi di kode (`v0.1.3` ↔ `0.1.3`).

## Yang terjadi di aplikasi pengguna

| Sistem | Cara update |
|---|---|
| Windows | Unduh `.msi` → pasang saat aplikasi ditutup / "Restart to update" |
| Ubuntu, Debian, Pop!_OS, Mint (.deb) | Unduh `.deb` → dialog password → `apt` memasang → aplikasi dibuka lagi |
| Fedora, openSUSE (.rpm) | Unduh `.rpm` → dialog password → `dnf`/`zypper` memasang |
| AppImage | Unduh AppImage baru → mengganti file lama (tanpa password) |
| Arch (paket AUR) | Diberi tahu; update lewat AUR (`yay -Syu`) |

Aplikasi mengecek 8 detik setelah dibuka lalu tiap 6 jam (atau manual: Settings →
About → Updates → Check now). Draft dan pre-release diabaikan.

## Catatan

- Jangan ubah `packageName` ("Record Labs") dan `vendor` di `build.gradle.kts`:
  upgrade MSI di Windows bergantung pada keduanya.
- Rilis lama bernama `Record-Labs_Release` (pre-release 0.1.2) tidak mengganggu.
- Build lokal tetap bisa seperti biasa (`gradlew.bat packageMsi`, `./build-installer-linux.sh`).
