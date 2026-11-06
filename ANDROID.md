# Record Labs untuk Android

Repo ini berisi tiga bagian:

| Folder | Isi |
|---|---|
| `src/` (root) | Titik masuk aplikasi desktop (`Main.kt` hanya memanggil `runDesktopApp`) dan resource desktop (ikon, font, VLC) |
| `shared/` | **Seluruh aplikasi**: tampilan (Home, Search, Library, music player, lirik, queue, dock, Settings, Listen Together, Connect) dan data YouTube Music |
| `androidApp/` | Pembungkus Android: `MainActivity` menampilkan UI dari `shared/`, `PlaybackService` untuk notifikasi & layar kunci |

Di dalam `shared/src/`:

| Folder | Jalan di |
|---|---|
| `jvmSharedMain` | Desktop **dan** Android (hampir semua kode ada di sini) |
| `jvmMain` | Desktop saja: jendela, VLC/JavaFX player, floating lyrics, blur Skia, Discord pipe, updater |
| `androidMain` | Android saja: ExoPlayer (`AndroidPlaybackEngine`), login WebView, font/ikon, shader AGSL |

Hal yang beda antara desktop dan Android ditulis sebagai `expect` di `jvmSharedMain`
(`PlatformUi.kt`, `PlaybackEngine.kt`, `FloatingLetters.kt`, `AppFonts.kt`, ...) dengan
`actual` di `jvmMain` dan `androidMain`.

**Penting:** compile Android tidak menolak API khusus JDK desktop (`java.awt`, `javax.swing`,
`ProcessHandle`, `java.net.http`). Kalau kode di `jvmSharedMain` memakainya, APK akan crash
saat bagian itu dijalankan. Taruh kode seperti itu di `jvmMain` lewat `expect`/`actual`, atau
lindungi dengan `if (!Platform.isAndroid)`.

## Build APK

Butuh Android SDK (Android Studio sudah membawanya). `local.properties` harus berisi
`sdk.dir=...` (atau variabel `ANDROID_HOME`). Tanpa SDK, proyek tetap build seperti dulu,
hanya desktop.

```
gradlew.bat :androidApp:assembleDebug        (Windows)
./gradlew :androidApp:assembleDebug          (Linux)
```

Hasil: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Desktop tetap sama: `gradlew.bat -p .\windows-recordlabs run`, `packageMsi`, dan seterusnya.

## Yang sama dengan desktop

Tampilan potret/lanskap, music player, lirik (termasuk huruf yang membesar saat dinyanyikan),
queue & riwayat, dock kaca, header & pencarian, Settings, Listen Together, Record Labs Connect,
Last.fm, backup/restore, latar belakang warna artwork yang bergerak (Android 13+).

## Yang belum ada di Android

- Animated artwork & music video: Android menampilkan cover biasa.
- Blur progresif di belakang header/dock.
- Folder musik lokal, floating lyrics/PiP, Discord Rich Presence, update otomatis dari GitHub.
- Backup disimpan di `Android/data/com.recordlabs.android/files/backups`; restore memakai file terbaru di sana.
