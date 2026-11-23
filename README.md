# MangaLens

Reader manga/manhwa/manhua berbasis URL dengan WebView, dilengkapi **penerjemah layar melayang**
yang bisa membaca dan menerjemahkan teks di aplikasi mana pun — bukan hanya di MangaLens.

Dibangun dengan Kotlin MVVM modular (tanpa Jetpack Compose, ViewBinding) untuk Android 8.0+
(minSdk 26, targetSdk 34).

## Fitur

- **Reader internal** — tempel URL manga, halaman dibuka di WebView, riwayat bacaan disimpan di
  Room.
- **Deteksi gambar asli** — mengambil gambar dari halaman (bukan screenshot layar) agar kualitas
  OCR tetap tinggi.
- **OCR on-device** — ML Kit Text Recognition bundled di APK, tanpa unduhan model saat pertama
  dipakai.
- **Deteksi bahasa otomatis** — Latin, Jepang, Korea, dan Tionghoa dikenali dari teks yang
  benar-benar terbaca, lalu pasangan bahasa terjemahan dipilih otomatis.
- **Dua provider terjemahan** — ML Kit on-device (offline, tanpa kunci) atau LLM
  OpenAI-compatible (lebih luwes) yang dipilih dari layar utama.
- **Tempel di balon** — teks Indonesia ditempel tepat di atas teks aslinya, seukuran kotak teks
  agar gambar di sekitar balon tetap terlihat. Teks di luar balon (kotak narasi, tulisan pada
  gambar) juga diterjemahkan.
- **Bubble melayang** — bekerja di atas aplikasi lain (browser, aplikasi baca, galeri) memakai
  `MediaProjection` + overlay. Ketuk sekali untuk menerjemahkan layar, ketuk lagi untuk menghapus
  tempelan, tahan lama untuk mematikan bubble.

## Unduh APK

Semua build pengujian tersedia di halaman release:

**https://github.com/Rachdik/mangalens/releases/tag/apk-testing**

Versi terbaru: **[MangaLens-tahap11-arm64-debug.apk](https://github.com/Rachdik/mangalens/releases/download/apk-testing/MangaLens-tahap11-arm64-debug.apk)**

Catatan:

- APK tahap4 ke atas hanya untuk **arm64-v8a**; tahap3 adalah APK universal.
- Semua build masih **debug** (belum ditandatangani untuk rilis).
- Package `com.mangalens.app` — uninstall versi lama sebelum memasang versi lain.

## Cara memakai penerjemah layar

1. Buka MangaLens, pilih bahasa sumber **Otomatis**, lalu ketuk **Aktifkan bubble**.
2. Setujui izin "tampil di atas aplikasi lain", izin notifikasi, dan izin rekam layar.
3. Buka manga di aplikasi/browser mana pun.
4. Ketuk bubble biru **訳** — teks di layar diterjemahkan dan ditempel di posisinya.
5. Ketuk bubble sekali lagi untuk menghapus tempelan; tahan lama untuk mematikan bubble.

Jika bubble tidak muncul, pastikan izin **"Tampilkan di atas aplikasi lain"** aktif untuk
MangaLens. Kegagalan pemasangan overlay dicatat di Logcat dengan tag `MangaLensOverlay`.

## Provider terjemahan (ML Kit / LLM)

Di kartu **Floating** ada pilihan **Provider terjemahan**:

- **ML Kit (offline)** — bawaan, tanpa kunci API, model diunduh sekali. Hasil masih literal.
- **LLM (OpenAI-compatible)** — hasil jauh lebih luwes karena diterjemahkan model bahasa.
  Butuh kunci API yang dibaca dari file `.env` saat build.

Menyiapkan provider LLM:

1. Salin `.env.example` menjadi `.env` di root proyek.
2. Isi nilainya:

   ```
   LLM_API_KEY=kunci-rahasia-kamu
   LLM_BASE_URL=https://api.z.ai/api/paas/v4
   LLM_MODEL=glm-4-flash
   ```

   `LLM_BASE_URL` bisa diganti endpoint apa pun yang kompatibel OpenAI
   (`POST {base}/chat/completions`).
3. Build ulang (`./gradlew :app:assembleDebug`), pasang APK, lalu pilih
   **LLM (OpenAI-compatible)** di aplikasi.

`.env` sudah masuk `.gitignore`. **Peringatan:** kunci ikut tertanam di APK hasil build; jangan
bagikan atau mengunggah APK yang dibangun dengan kunci asli. APK di halaman release dibangun tanpa
`.env`, jadi opsi LLM di sana akan melaporkan bahwa kunci API belum diisi.

## Build dari source

Butuh JDK 17 dan Android SDK (compileSdk 34). `local.properties` harus berisi `sdk.dir=...`.

```bash
./gradlew :app:assembleDebug
```

Hasil build dipisah per ABI di `app/build/outputs/apk/debug/`:

- `app-arm64-v8a-debug.apk`
- `app-armeabi-v7a-debug.apk`
- `app-x86_64-debug.apk`

## Struktur modul

| Modul | Isi |
| --- | --- |
| `app` | Layar utama, galeri riwayat, pengaturan bahasa sumber |
| `core` | Model, util, DI manual |
| `ui` | Tema dan komponen tampilan |
| `storage` | Room (riwayat bacaan) |
| `reader` | WebView, deteksi gambar halaman, ekstraksi gambar asli |
| `floating` | Bubble melayang, overlay, tangkap layar `MediaProjection` |
| `ocr` | OCR ML Kit, deteksi skrip/bahasa |
| `translation` | Provider terjemahan ML Kit + perapian ragam bahasa |
| `inpainting` | Deteksi balon, pengelompokan blok teks, penyusunan region terjemahan |
| `imageprocessing` | Utilitas pemrosesan gambar |

## Batasan saat ini

- Tempelan bersifat statis: setelah halaman digulir perlu ketuk bubble lagi (belum auto-refresh).
- Teks asli ditutup warna latar, belum inpainting piksel penuh.
- Kualitas terjemahan mengikuti ML Kit on-device: masih literal, dan teks yang salah dibaca OCR
  (huruf sangat kecil/blur) akan salah diterjemahkan.
- Model ML Kit Translation perlu internet sekali untuk unduhan awal.
- Hanya build debug; APK release butuh keystore.

Rencana teknis dan catatan tiap tahap ada di [`docs/RENCANA-TEKNIS.md`](docs/RENCANA-TEKNIS.md).

## Lisensi & penggunaan

Proyek ini untuk keperluan pribadi dan pembelajaran. Gunakan hanya pada konten yang kamu berhak
mengaksesnya; hormati ketentuan situs sumber dan hak pencipta.
