# Record Labs

Record Labs adalah aplikasi Android untuk streaming musik, dikembangkan sebagai proyek tugas mata kuliah Pemrograman Seluler

Dikembangkan oleh:

1. Muhammad Hasbi Ramadhan - 23.83.1018
2. Angga Adhi Prasetya - 23.83.1033
3. Abiyyu Shafy Hidayat - 23.831035

## Fitur Utama

- Streaming musik dari YouTube Music
- Lirik lagu dengan banyak provider (BetterLyrics, Paxsenix, LrcLib, KuGou, LyricsPlus) yang bisa diurutkan sesuai prioritas
- Download musik dan lirik untuk didengarkan secara offline
- Custom urutan lagu pada antrian pemutaran (queue)
- Kontrol pemutar musik dengan ikon dari Heroicons
- Tampilan yang menyesuaikan rotasi layar (portrait & landscape) tanpa kehilangan halaman yang sedang dibuka
- Integrasi dengan Discord Rich Presence

## Teknologi

Dibangun menggunakan Kotlin dan Jetpack Compose, dengan Room untuk penyimpanan data lokal, Media3 untuk pemutaran dan pengunduhan musik, serta Hilt untuk dependency injection.
