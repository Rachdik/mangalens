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
- **Deteksi orientasi teks** — mendeteksi teks vertikal (tategaki) Jepang/Cina untuk pergeseran
  bubble yang tepat.
- **Bahasa input & output bisa dipilih** — 14 bahasa sumber (otomatis, Inggris, Jepang, Korea,
  Tionghoa, Spanyol, Prancis, Jerman, Portugis, Rusia, Vietnam, Thailand, Melayu, Arab) dan 14
  bahasa output, bukan hanya Indonesia.
- **Tiga provider terjemahan** — ML Kit on-device (offline, tanpa kunci), LLM
  OpenAI-compatible (lebih luwes), atau MyMemory (gratis, daring).
- **Pipeline terjemahan otomatis di Reader** — ReadTranslatePipeline memproses gambar halaman
  dengan OCR → deteksi balon → terjemahan → hasil siap untuk overlay.
- **Tempel di balon** — teks Indonesia ditempel tepat di atas teks aslinya, seukuran kotak teks
  agar gambar di sekitar balon tetap terlihat. Teks di luar balon (kotak narasi, tulisan pada
  gambar) juga diterjemahkan.
- **Terjemah gambar dari aplikasi lain** — kirim gambar manga ke MangaLens lewat Sharesheet
  (`ACTION_SEND` dengan MIME `image/*`). Gambar diproses lewat pipeline yang sama, hasilnya
  ditampilkan sebagai dialog berisi teks asli dan terjemahan.
- **Bubble melayang** — bekerja di atas aplikasi lain (browser, aplikasi baca, galeri) memakai
  `MediaProjection` + overlay. Ketuk sekali untuk menerjemahkan layar, ketuk lagi untuk menghapus
  tempelan, tahan lama untuk mematikan bubble.

## Unduh APK

### Otomatis (jika tersedia)

Semua build pengujian tersedia di halaman release:

**https://github.com/Rachdik/mangalens/releases** pilih APK terbaru yang tersedia.

### Manual upload APK

Jika belum ada release, Anda bisa mengunduh APK dari folder `releases/` di repository ini
setelah commit berikutnya dipush. Atau gunakan GitHub Releases:

1. Buka https://github.com/Rachdik/mangalens/releases/new
2. Buat tag version (mis. `v0.1.0`)
3. Upload file APK dari folder `releases/` (MangaLens-YYYYMMDD-arm64-debug.apk)
4. Publish release

Catatan:

- APK di folder `releases/` adalah debug build (belum ditandatangani untuk rilis).
- `assembleRelease` menghasilkan APK *unsigned*; APK rilis perlu keystore sendiri.
- Package `com.mangalens.app` — uninstall versi lama sebelum memasang versi lain.
- File APK tidak di-commit ke repository karena ukuran besar; Anda harus upload manual
  melalui GitHub Releases atau git push dengan -f setelah mengubah .gitignore.

## Cara memakai penerjemah layar

1. Buka MangaLens, pilih **bahasa sumber** (atau biarkan Otomatis) dan **bahasa output**, lalu
   ketuk **Aktifkan bubble**.
2. Setujui izin "tampil di atas aplikasi lain", izin notifikasi, dan izin rekam layar.
3. Buka manga di aplikasi/browser mana pun.
4. Ketuk bubble biru **訳** — teks di layar diterjemahkan dan ditempel di posisinya.
5. Ketuk bubble sekali lagi untuk menghapus tempelan; tahan lama untuk mematikan bubble.

Jika bubble tidak muncul, pastikan izin **"Tampilkan di atas aplikasi lain"** aktif untuk
MangaLens. Kegagalan pemasangan overlay dicatat di Logcat dengan tag `MangaLensOverlay`.

Catatan: mode **Otomatis** mengenali teks Latin sebagai Inggris. Untuk manga Spanyol/Prancis/Jerman,
pilih bahasa sumbernya secara eksplisit agar terjemahan tidak salah arah.

## Cara memakai terjemahan dari gambar yang dibagikan

1. Buka gambar manga di aplikasi galeri/browser/chat.
2. Pilih **Bagikan** lalu pilih **MangaLens**.
3. MangaLens membuka layar hasil: OCR → deteksi balon → terjemahan.
4. Hasil muncul sebagai dialog berisi daftar *Teks asli* dan *Terjemahan*.

Jika gambar tidak bisa dibaca, aplikasi menampilkan pesan error (bukan hasil kosong). Gambar
besar diperkecil otomatis (sisi terpanjang 2200 px) agar tidak kehabisan memori.

## Provider terjemahan (ML Kit / LLM / MyMemory)

Di kartu **Floating** ada pilihan **Provider terjemahan**:

- **ML Kit (offline)** — bawaan, tanpa kunci API, model diunduh sekali. Hasil masih literal.
- **LLM (OpenAI-compatible)** — hasil jauh lebih luwes karena diterjemahkan model bahasa.
  Butuh kunci API yang dibaca dari file `.env` saat build.
- **MyMemory (gratis)** — tanpa kunci API, batas pemakaian ketat, kadang timeout. Dipakai
  sebagai opsi ketiga; kualitas bervariasi.

Menyiapkan provider LLM:

1. Salin `.env.example` menjadi `.env` di root proyek.
2. Isi nilainya:

   ```
   LLM_API_KEY=kunci-rahasia-kamu
   LLM_BASE_URL=https://api.z.ai/api/paas/v4
   LLM_MODEL=glm-4-flash
   ```
3. Build ulang (`./gradlew :app:assembleDebug`), pasang APK, lalu pilih
   **LLM (OpenAI-compatible)** di aplikasi.

`.env` sudah masuk `.gitignore`. **Peringatan:** kunci ikut tertanam di APK hasil build; jangan
bagikan atau mengunggah APK yang dibangun dengan kunci asli. APK di halaman release dibangun tanpa
`.env`, jadi opsi LLM di sana akan melaporkan bahwa kunci API belum diisi.

## Build dari source

Butuh JDK 17 dan Android SDK (compileSdk 34). `local.properties` harus berisi `sdk.dir=...`.

```bash
./gradlew :app:assembleDebug      # APK debug per ABI
./gradlew :app:assembleRelease    # APK release unsigned per ABI
./gradlew clean assembleDebug assembleRelease
```

Hasil build dipisah per ABI di `app/build/outputs/apk/debug/` dan
`app/build/outputs/apk/release/`:

- `app-arm64-v8a-*.apk`
- `app-armeabi-v7a-*.apk`
- `app-x86_64-*.apk`

Catatan build: ikon launcher harus berupa PNG asli (bukan JPEG yang di-rename) dan
deklarasi `android:icon` tidak boleh diletakkan di tema library `ui` karena resource `mipmap`
hanya ada di modul `app`. Kedua hal ini pernah membuat build bersih gagal.

## Struktur modul

| Modul | Isi |
| --- | --- |
| `app` | Layar utama, galeri riwayat, pengaturan bahasa sumber, penerima share gambar |
| `core` | Model, util, DI manual |
| `ui` | Tema dan komponen tampilan |
| `storage` | Room (riwayat bacaan) |
| `reader` | WebView, deteksi gambar halaman, ekstraksi gambar asli, pipeline terjemahan |
| `floating` | Bubble melayang, overlay, tangkap layar `MediaProjection` |
| `ocr` | OCR ML Kit, deteksi skrip/bahasa |
| `translation` | Provider terjemahan ML Kit, LLM, MyMemory + perapian ragam bahasa |
| `inpainting` | Deteksi balon, pengelompokan blok teks, engine inpainting sederhana |
| `imageprocessing` | Utilitas pemrosesan gambar (dHash) |

## Inpainting: apa yang benar-benar dilakukan

`BasicInpaintingEngine` **tidak** sekadar menutup area teks dengan satu warna solid. Untuk setiap
piksel di dalam mask, engine mencari piksel asli terdekat di sisi kiri, kanan, atas, dan bawah,
lalu mengisi piksel itu dengan rata-rata berbobot (1/jarak) dari keempat warna tersebut, kemudian
melembutkan tepi mask. Dengan begitu latar polos dan gradien halus ikut tersambung.

Keterbatasan (belum diuji pada perangkat):

- **Latar polos / gradien halus** — biasanya menyatu dengan baik.
- **Screentone dan pola garis** — tidak direkonstruksi; hasilnya tampak halus/blur.
- **Garis gambar yang melewati teks** — ikut hilang di area mask (mask dilebarkan 6%).
- Tidak memakai OpenCV atau model AI: kualitas **tidak** setara Content-Aware Fill/Photoshop
  maupun inpainting berbasis model.

Integrasi saat ini: `MangaPageTranslator.translateWithInpaint()` tersedia sebagai API dan
mengembalikan bitmap hasil inpainting. **Overlay Reader dan bubble melayang masih memakai
tempelan berwarna** (bukan bitmap hasil inpainting), jadi hasil inpainting belum tampil di UI
mana pun.

## Batasan saat ini

- Tempelan di Reader bersifat statis: setelah halaman digulir perlu diproses lagi (belum
  auto-refresh mengikuti posisi gambar).
- Overlay Reader dan bubble melayang menutup teks asli dengan warna latar balon; bitmap hasil
  inpainting belum dipakai di UI.
- Kualitas terjemahan mengikuti provider: ML Kit masih literal, MyMemory bervariasi, LLM
  bergantung model.
- Teks yang salah dibaca OCR (huruf sangat kecil/blur) akan salah diterjemahkan.
- Model ML Kit Translation perlu internet sekali untuk unduhan awal.
- Hanya build debug/unsigned yang tersedia; APK rilis butuh keystore.
- `android:usesCleartextTraffic="true"` dipertahankan agar situs manga HTTP tetap bisa memuat
  gambar; ini melonggarkan keamanan jaringan dan sebaiknya dianalisis ulang untuk rilis.
- Kunci LLM (bila diisi) tertanam di APK.

## Perubahan terbaru (2026-11-23)

### Sesi 2 — audit performa, perbaikan build, share receiver, inpainting

- **Share receiver** (`ShareReceiverActivity`): menerima `ACTION_SEND` `image/*` dari Sharesheet,
  decode dua tahap dengan batas 2200 px, bitmap selalu di-recycle, hasil berupa dialog teks.
- **Audit auto-translation bridge**: rantai JS scan → download → OCR → terjemahan → overlay
  terverifikasi lengkap; `ImageTranslationManager.reset()` dipanggil saat halaman berubah.
- **Perbaikan bug use-after-recycle**: manager tidak lagi me-recycle bitmap yang masih dipakai
  coroutine pipeline; file diserahkan ke `ReadTranslatePipeline.processImageFile`.
- **Performa OCR**: orientasi dihitung sekali per halaman (sebelumnya satu pass OCR per blok) dan
  gambar diperkecil ke 2400 px untuk OCR dengan pemetaan koordinat balik.
- **Performa jaringan**: MyMemory dan LLM memakai `Dispatchers.IO`, MyMemory punya satu retry.
- **Kebocoran**: WebView melepas JavascriptInterface + di-destroy; `BubbleOverlay` menghapus
  callback handler; translator di-cache per backend.
- **Inpainting**: `BasicInpaintingEngine` mengisi dari piksel asli empat sisi + pelebaran mask.
- **Build bersih**: ikon launcher dikonversi ke PNG asli (48/72/96/144/192 px) dan deklarasi
  ikon dipindah dari tema library `ui` ke `AndroidManifest` aplikasi. `clean assembleDebug
  assembleRelease` kini berhasil.

### Sesi 1 (sebelumnya)

- **Fase 1 - Stabilisasi:** perbaikan resource missing (dimens.xml), peringatan compiler di
  ImageStore.kt, setup Android SDK.
- **Fase 2 - Multi-provider Translation:** tambah MyMemoryTranslationProvider sebagai provider
  gratis ketiga, update TranslationBackend enum, UI strings, dan FloatingTranslateService.
- **Fase 3 - OCR & Text Grouping:** tambah TextOrientation enum dan deteksi orientasi teks Jepang/
  Cina di MlKitOcrEngine, update BubbleDetector untuk support orientasi.
- **Fase 4 - URL Reader & Auto Translation:** ReadTranslatePipeline.kt dengan
  MangaPageTranslator, integrasi di ReaderActivity, support 3 translation backend.
- **Icon:** update ikon aplikasi ke desain Minimalist Bubble dengan gradient purple #4F46E5.

Rencana teknis dan catatan tiap tahap ada di [`docs/RENCANA-TEKNIS.md`](docs/RENCANA-TEKNIS.md).

## Lisensi & penggunaan

Proyek ini untuk keperluan pribadi dan pembelajaran. Gunakan hanya pada konten yang kamu berhak
mengaksesnya; hormati ketentuan situs sumber dan hak pencipta.
