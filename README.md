# MangaLens

Reader manga, manhwa, manhua, dan komik berbasis URL dengan WebView, dilengkapi **penerjemah layar melayang** yang bisa membaca dan menerjemahkan teks di aplikasi mana pun — bukan hanya di MangaLens.

Dibangun dengan Kotlin MVVM modular (tanpa Jetpack Compose, pakai ViewBinding) untuk Android 8.0+
(minSdk 26, targetSdk 34).

## Fitur

### Reader
- **Buka halaman manga lewat URL** — tempel tautan, halaman dibuka di WebView internal.
- **Riwayat baca** disimpan di Room.
- **Deteksi gambar halaman** — mengambil gambar asli dari halaman (bukan screenshot layar) supaya OCR
  tetap pakai resolusi tinggi.
- **OCR on-device** — ML Kit Text Recognition; mendukung Jepang, Korea, Tionghoa, dan Latin.
- **Deteksi orientasi teks** — mengikuti teks vertikal Jepang/Tionghoa biar penempatan teks terjemahan lebih pas.
- **Pemilihan bahasa sumber dan bahasa tujuan** — 14 bahasa sumber (otomatis, Inggris, Jepang, Korea,
  Tionghoa, Spanyol, Prancis, Jerman, Portugis, Rusia, Vietnam, Thailand, Melayu, Arab) dan 14 bahasa tujuan.
- **Pipeline terjemahan otomatis** — saat gambar sudah terdekode, pipeline menjalankan
  `OCR → deteksi balon → terjemahan → rendering`.
- **Tampilan terjemahan di balon** — teks Indonesia ditempel di atas teks asli, seukuran area teks
  sehingga gambar di sekitar balon tetap bisa dilihat. Teks di luar balon (kotak narasi, tulisan di ilustrasi)
  juga diterjemahkan.
- **Mode asli / terjemahan** — bisa beralih antara tampilan gambar asli dan hasil terjemahan.
- **Tipografi adaptif** — ukuran huruf menyesuaikan agar teks terjemahan tetap muat di balon tanpa terpotong.
- **Pinch-zoom** — pembaca mendukung zoom dan scroll; overlay terjemahan mengikuti posisi halaman.

### Penerjemahan
Tersedia beberapa pilihan provider:

- **Google ML Kit** — on-device, tidak butuh kunci API. Hasil masih cukup literal.
- **Gemini** — pilihan berbasis Google (opsional).
- **LLM kompatibel OpenAI** — hasil lebih luwes karena diterjemahkan oleh model bahasa.
- **MyMemory** — opsi daring gratis.

LLM dan Gemini **bukan syarat wajib**: aplikasi tetap bisa dipakai hanya dengan ML Kit.
Ketersediaan hasil ML Kit bergantung pada dukungan model dan, untuk some bahasa, pengunduhan model pertama kali.

### Floating Translate
- **Bubble melayang tersedia** — bisa dipasang dan muncul di atas aplikasi lain.
- **Hasil uji floating yang tercatat**: 5/5 floating test PASS (termasuk render terjemahan Jepang dan uji
  koordinat/permission state).
- **PENTING — status jujur**: alur lintas-aplikasi lengkap
  *(Chrome → bubble → tangkapan layar → OCR → terjemahan → inpainting → pemetaan koordinat → overlay)*
  **belum diverifikasi di perangkat nyata**.

Jadi, floating Translate ada dan berfungsi parsial, tapi belum diklaim sudah selesai sepenuhnya.

## Unduh APK

Versi terbaru bisa diunduh dari release GitHub (debug build, prerelease):

- **https://github.com/Rachdik/mangalens/releases**
- Nama release: **MangaLens Latest Development Build** (prerelease; bukan versi stabil)
- Nama aset: `MangaLens-debug-latest-arm64-v8a.apk`

Catatan:
- APK ini masih **debug**, bukan production/stable release.
- Package `com.mangalens.app` — uninstall versi lama sebelum memasang versi lain.
- Hanya tersedia untuk **arm64-v8a** pada build ini.

## Cara menginstal

1. Buka tautan release di atas dari perangkat Android atau dari komputer.
2. Unduh file `MangaLens-debug-latest-arm64-v8a.apk`.
3. Jika sekadar mengunduh dari browser di HP, keluar notifikasi unduhan — ketuk lalu pilih **Pasang**.
4. Jika mengunduh di komputer, pindahkan APK ke HP lalu pasang lewat file manager atau:
   ```
   adb install -r MangaLens-debug-latest-arm64-v8a.apk
   ```
5. Buka MangaLens dari launcher.

Persyaratan Android: Android 8.0 (API 26) ke atas.

## Cara memakai penerjemah layar

1. Buka MangaLens, pilih **bahasa sumber** (atau biarkan Otomatis) dan **bahasa tujuan**, lalu ketuk
   **aktifkan bubble**.
2. Beri izin yang diminta:
   - izin tampil di atas aplikasi lain,
   - izin notifikasi,
   - izin rekam layar.
3. Buka manga di aplikasi atau browser mana pun.
4. Ketuk bubble — teks di layar diterjemahkan dan ditempel di posisinya.
5. Ketuk bubble lagi untuk menghapus tempelan; tahan lama untuk mematikan bubble.

Jika bubble tidak muncul, cek apakah izin **"Tampilkan di atas aplikasi lain"** sudah aktif untuk
MangaLens. Catatan kegagalan pemasangan overlay bisa dilihat di Logcat dengan tag `MangaLensOverlay`.

Catatan: mode **Otomatis** mengenali teks Latin sebagai Inggris.
Untuk mangA berbahasa Spanyol / Prancis / Jerman, pilih bahasa sumbernya secara eksplisit
agar arah terjemahan tidak salah.

## Penyiapan provider LLM / Gemini (opsional)

1. Salin `.env.example` jadi `.env` di root proyek.
2. Isi nilainya, contoh:
   ```
   LLM_API_KEY=kunci-rahasia-kamu
   LLM_BASE_URL=https://api.z.ai/api/paas/v4
   LLM_MODEL=glm-4-flash
   ```
   `LLM_BASE_URL` bisa diganti endpoint lain yang kompatibel OpenAI
   (`POST {base}/chat/completions`).
3. Build ulang:
   ```
   ./gradlew :app:assembleDebug
   ```
4. Pasang APK, lalu pilih provider LLM/Gemini di aplikasi.

`.env` sudah masuk `.gitignore`. **Peringatan:** kunci API ikut tersimpan di APK hasil build,
jadi jangan bagikan atau mengunggah APK yang dibangun dengan kunci asli.

APK di halaman release dibangun tanpa `.env`, jadi opsi LLM/Gemini di sana akan menunjukkan
bahwa kunci API belum diisi.

## Build dari source

Butuh JDK 17 dan Android SDK (compileSdk 34).
`local.properties` harus berisi `sdk.dir=...`.

```
./gradlew :app:assembleDebug
```

Hasil build dipisah per ABI di `app/build/outputs/apk/debug/`:
- `app-arm64-v8a-debug.apk`
- `app-armeabi-v7a-debug.apk`
- `app-x86_64-debug.apk`

## Struktur modul

| Modul | Isi |
| --- | --- |
| `app` | Layar utama, galeri/riwayat, penerima berbagi gambar, pengaturan bahasa |
| `core` | Model, utilitas, warna aplikasi, DI manual |
| `ui` | Tema dan komponen tampilan bersama |
| `storage` | Room untuk riwayat baca |
| `reader` | WebView, deteksi gambar halaman, pipeline terjemahan halaman |
| `floating` | Bubble melayang, overlay, tangkapan layar `MediaProjection` |
| `ocr` | OCR ML Kit, deteksi skrip/bahasa |
| `translation` | Provider terjemahan (ML Kit, LLM, Gemini, MyMemory), perapian ragam bahasa |
| `inpainting` | Deteksi balon, pengelompokan teks, penyusunan region terjemahan |
| `imageprocessing` | Utilitas pemrosesan gambar |

## Status proyek

### Implemented
- Pembaca internal berbasis URL + WebView.
- Deteksi gambar halaman (bukan screenshot).
- OCR on-device ML Kit (Jepang, Korea, Tionghoa, Latin) + deteksi orientasi teks vertikal.
- Pipeline terjemahan otomatis di Reader (OCR → deteksi balon → terjemahan → rendering).
- Tampilan terjemahan di balon + teks di luar balon.
- Mode asli / terjemahan.
- Tipografi adaptif + pinch-zoom.
- Pemilihan bahasa sumber dan tujuan (14 + 14).
- Floating bubble: kode ada, bubble bisa dipasang.

### Tested
- Unit test utama: 132 PASS, 0 FAIL, 1 SKIPPED.
- Floating test di perangkat (RMX3561, Android 14): 5/5 PASS.

### Partially verified
- Floating Translate: implementasi ada, uji floating dasar lewat, tapi alur cross-app end-to-end
  **belum** diverifikasi di perangkat nyata.

### Not yet verified
- Penerjemahan lintas-aplikasi lengkap:
  Chrome → bubble → tangkapan layar → OCR → terjemahan → inpainting → koordinat → overlay.
- Smoke test APK terbaru di perangkat nyata (perangkat tidak tersedia saat sesi ini).
- Upload APK terbaru sebagai release asset (tergantung autentikasi GitHub di lingkungan ini).

## Keterbatasan yang masih diketahui

- Tempelan bersifat statis: jika halaman digulir, perlu ketuk bubble lagi (belum auto-refresh).
- Teks asli ditutup oleh warna latar; inpainting piksel penuh belum jadi klaim fitur utama.
- Kualitas terjemahan mengikuti ML Kit on-device: masih cukup literal.
- Teks yang salah dibaca OCR (huruf sangat kecil/blur) bisa diterjemahkan jadi salah juga.
- ML Kit Translation butuh internet sekali untuk pengunduhan model awal (untuk bahasa yang membutuhkannya).
- Hanya tersedia build debug; APK release butuh konfigurasi keystore tersendiri.
- Floating Translate: bubble tersedia, tapi alur penuh di atas aplikasi lain belum terverifikasi.

Rencana teknis dan catatan tiap tahap ada di [`docs/RENCANA-TEKNIS.md`](docs/RENCANA-TEKNIS.md).

## Lisensi & penggunaan

Proyek ini untuk keperluan pribadi dan pembelajaran.
Gunakan hanya pada konten yang kamu berhak akses; hormati ketentuan situs sumber dan hak pencipta.
