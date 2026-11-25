# MangaLens — PHASE 9I VALIDATION

**Recovery, OCR Verification & Pipeline Validation**

Tanggal: 25 November 2026
Perangkat uji: Realme `RMX3561` (Android 14, 1080×2412), serial `X8DMKBIBXOY5W4UW`
Checkpoint awal: `36b5573` — *test: complete phase 9H local image testing setup and validation*

---

## 1. Keadaan Proyek Awal

Hasil `git status --short` sebelum perubahan apa pun:

```
 M ocr/build.gradle.kts
?? ocr/src/androidTest/
?? verification/  ml-capture/  APK-Download/  notes/
?? app/src/main/AndroidManifest.xml.bak{,2,3,4,5}  *.original  *.e2e_backup
?? ml-capture-e11.jpg  ocr_install.log  server_run.log
```

| Pertanyaan | Jawaban |
|---|---|
| File yang berubah | `ocr/build.gradle.kts` (5 baris: `testInstrumentationRunner` + dependency androidTest) |
| File yang dibuat | `ocr/src/androidTest/.../RealImageOcrVerificationTest.kt` (satu-satunya source baru), plus artefak non-source (`verification/`, `ml-capture/`) |
| Perubahan yang ditolak | Edit ke `MangaInpaintImageTest.kt` **tidak pernah masuk** — file masih persis seperti commit `36b5573` |
| Proyek masih compile | Ya (`./gradlew test` → BUILD SUCCESSFUL) |
| Checkpoint utuh | Ya, `36b5573` masih HEAD; tidak ada reset/stash |

Kesimpulan: working tree bersih dari perubahan destruktif. Tidak ada yang dibuang.

---

## 2. TASK A — Diagnostik ADB

```
where.exe adb          -> C:\ADB\adb.exe
adb version            -> Android Debug Bridge 1.0.32
```

Kondisi awal: `adb devices -l` → **kosong** (tidak ada perangkat). Ini akar penyebab
seluruh sesi sebelumnya "macet": tidak ada perangkat, sehingga setiap
`connectedAndroidTest` dan install APK pasti gagal.

Perbaikan yang dilakukan: `adb kill-server` → `adb start-server`. Setelah itu:

```
X8DMKBIBXOY5W4UW   device   product:RMX3561T2 model:RMX3561 device:RED8BEL1
```

`adb shell getprop` → model `RMX3561`, Android `14`, layar 1080×2412 (density 480).

Catatan penting: `adb ping api.mymemory.translated.net` → 100% packet loss (ICMP diblokir
operator), tetapi **HTTPS tetap jalan** — dibuktikan oleh terjemahan jaringan yang berhasil
di Bagian 6. Jadi ICMP tidak bisa dipakai sebagai indikator konektivitas.

Tersedia juga AVD `Medium_Phone_API_37.0`, tetapi tidak dipakai karena perangkat fisik
akhirnya tersedia.

---

## 3. TASK B — Investigasi Implementasi OCR yang Ada

Arsitektur OCR (tidak diubah, hanya dipakai):

| Komponen | Lokasi |
|---|---|
| Interface | `ocr/.../OcrEngine.kt` — `OcrEngine.recognize(Bitmap, LanguageScript)`, `RecognizedBlock(text, bounds, order, orientation, lines, elements)` |
| Implementasi | `ocr/.../MlKitOcrEngine.kt` — ML Kit **bundled** (`com.google.mlkit:text-recognition*:16.0.1`), `AUTO` mencoba Latin → Jepang → Korea → Cina dan memilih hasil terbanyak |
| Pipeline pemakai | `inpainting/.../MangaPageTranslator.kt` (`translate`, `translateWithInpaint`) — dipakai `reader/.../ReadTranslatePipeline.kt`, `floating/.../ScreenTranslator.kt`, `app/.../ShareReceiverActivity.kt` |

Pintu masuk OCR yang sudah ada: **instrumentation test di modul `ocr`**. Tidak ada
implementasi OCR kedua yang dibuat; test memanggil `MlKitOcrEngine` yang sama dengan
yang dipakai aplikasi.

---

## 4. TASK C — Eksekusi OCR Nyata

### 4.1 Dua akar masalah yang ditemukan (bukan asumsi)

**(a) `BitmapFactory.decodeFile("/sdcard/Download/...")` → `null`.**
APK test berjalan dengan *scoped storage* (Android 10+). Membaca `/sdcard/Download`
butuh izin `READ_MEDIA_IMAGES`; `adb shell` punya akses karena berjalan sebagai user
`shell`, APK test tidak. **Perbaikan:** gambar dimuat dari `androidTest/assets`
(`InstrumentationRegistry.getInstrumentation().context.assets`) — tanpa izin apa pun,
deterministik, dan tidak bergantung pada file di perangkat.

**(b) `manga_test_e11.jpg` BUKAN halaman manga.**
File 510.468 byte itu adalah gambar abu-abu hampir kosong dengan satu balok tipis
hijau/kuning di tepi atas (diperiksa langsung secara visual). Karena itu OCR
mengembalikan **0 blok** — bukan karena engine rusak. Fixture diganti dengan tangkapan
reader asli dari repo: `ml-capture/canvas-canvas_v2_109qokw_*` (1600×2278).

### 4.2 Output OCR nyata — halaman manga

```
MANGALENS_OCR_BEGIN
Image: manga_page_en.jpg
Image dimensions: 1079x1536
OCR engine: MlKitOcrEngine (ML Kit, script=AUTO)
Processing duration: 259ms
Detected blocks: 3
BLOCK 1: THEY ARE / CALLED KUSOGE, / OR "SHIT GAME."
         Bounding box: [150,589]-[287,654] (137x65) orientation=HORIZONTAL
BLOCK 2: icle 9ni
         Bounding box: [485,505]-[571,625] (86x120) orientation=HORIZONTAL
BLOCK 3: IN / JAPAN..
         Bounding box: [688,355]-[751,396] (63x41) orientation=HORIZONTAL
Total characters: 57
MANGALENS_OCR_END
```

Teks di atas identik dengan teks yang benar-benar tercetak pada halaman tersebut
(diverifikasi dengan melihat gambar): balon kiri "...THEY ARE CALLED KUSOGE, OR
'SHIT GAME.'" dan balon kanan "IN JAPAN..". Blok 2 (`icle 9ni`) adalah teks artistik
miring pada ilustrasi buku — memang sulit dibaca, dilaporkan apa adanya.

### 4.3 Output OCR nyata — teks Jepang

Fixture: sampul buku Jepang domain publik (`File:Japanese Book about furs.jpg`,
Wikimedia Commons), 1998×1011.

```
MANGALENS_OCR_BEGIN
Image: japanese_book.jpg
Image dimensions: 1998x1011
OCR engine: MlKitOcrEngine (ML Kit, script=JAPANESE)
Processing duration: 202ms
Detected blocks: 3
BLOCK 1: 毛皮                    Bounding box: [234,190]-[440,261] (206x71)
BLOCK 2: その種類と背景          Bounding box: [231,300]-[440,320] (209x20)
BLOCK 3: J、                     Bounding box: [1402,236]-[1432,276] (30x40)
Total characters: 11
MANGALENS_OCR_END
```

Hasil = persis judul buku pada gambar (毛皮 "bulu", その種類と背景 "jenis dan latarnya").
**Recognizer Jepang terbukti menghasilkan kanji/kana nyata di perangkat.**

Uji tambahan yang dilakukan dan hasilnya (semua negatif, dilaporkan apa adanya):
- Ukiyo-e domain publik 627×923 (tanda tangan kuas `豊國画`, `花`): 0 blok, baik pada
  ukuran asli maupun setelah diperbesar 3× (1881×2769).
- Untuk memastikan penyebabnya **bukan** model Jepang yang gagal dimuat, recognizer
  Jepang dijalankan pada halaman manga padat teks: **3 blok terdeteksi**. Artinya model
  Jepang berfungsi; kaligrafi kuas bergaya tinggi memang di luar kemampuan ML Kit.

| Item | Status |
|---|---|
| OCR teks pada gambar manga asli | **PASS** (3 blok, bounding box, 259 ms) |
| OCR karakter Jepang (cetak) | **PASS** (kanji + hiragana, 202 ms) |
| OCR kaligrafi kuas ukiyo-e | **FAIL** (dibatasi kemampuan ML Kit — bukan bug aplikasi) |
| OCR pada `manga_test_e11.jpg` | **PASS sebagai temuan** — fixture-nya rusak/kosong; diganti |

---

## 5. TASK D — Verifikasi Terjemahan

Provider nyata yang dipakai: `MyMemoryTranslationProvider` (gratis, tanpa API key) —
salah satu dari empat backend pada `TranslationProviders` (ML_KIT, LLM, GEMINI,
MY_MEMORY). Arsitektur multi-provider **tidak diubah**; LLM tidak dijadikan wajib.

Teks masukan adalah keluaran OCR asli dari Bagian 4.2:

```
provider=MyMemory (gratis) source=en target=id regions=3
NET BUBBLE 1: 'IN / JAPAN..'                                    -> 'DI JEPANG..'
NET BUBBLE 2: 'THEY ARE / CALLED KUSOGE, / OR "SHIT GAME."'      -> 'GAME-GAME ITU DISEBUT KUSOGE, ATAU "GAME SAMP*",'
NETWORK_RESULT SUCCESS bubbles=2
```

- Request jaringan benar-benar dieksekusi (3 region, ~2,6 detik).
- Respons nyata dari `api.mymemory.translated.net`, bukan teks hardcoded.
- Pemetaan per-balon bekerja: tiap region punya `TranslationRequest.id` sendiri dan
  hasilnya dipetakan kembali per id.
- 3 region → 2 balon: satu region (`icle 9ni`) dibuang karena hasil terjemahannya
  kosong/identik dengan sumber — perilaku yang memang dirancang di `MangaPageTranslator`.

| Item | Status |
|---|---|
| Provider dipilih & dipanggil sungguhan | **PASS** |
| Respons nyata (EN→ID) | **PASS** |
| Durasi | **PASS** (~2,6 s untuk 3 region) |
| Pemetaan per-bubble | **PASS** (2 balon dari 3 region, 1 difilter) |
| Penanganan error/timeout | **PARTIAL** — hanya diuji lewat unit test provider (mock server), bukan lewat kegagalan jaringan nyata |
| Provider LLM / Gemini / ML Kit | **UNTESTED** (butuh API key / unduhan model) |

---

## 6. TASK E — Perbaikan Test Inpainting

### 6.1 Diagnosis

10 kegagalan berasal dari **bug di helper test**, bukan dari engine:

```kotlin
private fun countMaskedPixelsAboveThreshold(bmp: Bitmap, mask: Bitmap, threshold: Int): Int {
    // ... parameter `bmp` TIDAK PERNAH DIPAKAI; yang dihitung selalu piksel di `mask`
}

private fun assertMaskAreaReduced(source, mask, result) {
    val sourceMasked = countMaskedPixelsAboveThreshold(mask, mask, 127)    // mask
    val resultMasked = countMaskedPixelsAboveThreshold(result, mask, 127)  // juga mask
    assertTrue(resultMasked < sourceMasked - tolerance)  // selalu  X < X - 5 -> gagal
}
```

Assertion ini **mustahil lolos** dan tidak menguji apa pun: kedua sisi menghitung bitmap
yang sama. Menambah "toleransi" hanya akan menyembunyikan masalah.

### 6.2 Perbaikan (tanpa melemahkan cakupan)

`assertMaskAreaReduced` sekarang menguji perilaku engine yang sebenarnya:

1. Ambil warna latar dari cincin piksel non-mask di sekitar kotak mask.
2. Hitung piksel **di dalam mask** yang berbeda dari latar (yaitu huruf/teks) pada
   gambar asli dan pada hasil.
3. Wajib: ada teks asli (`textBefore > 0`) **dan** sisa teks ≤ 10% (`textAfter ≤ 10%`).

Arah pemeriksaan ini tidak bergantung pada "teks gelap di latar terang" atau
sebaliknya, sehingga tetap valid untuk semua 11 skenario (latar putih, warna solid,
kompleks, garis tipis, teks EN/JP/KR, dua teks berdekatan/berjauhan, mask berdekatan).
`countMaskedPixelsAboveThreshold` juga diperbaiki agar benar-benar memakai `bmp` dan
mengerjakan threshold. Tidak ada assertion yang dihapus.

### 6.3 Hasil

| Sebelum | Sesudah |
|---|---|
| 15 PASS / 10 FAIL | **25 PASS / 0 FAIL** |

---

## 7. Bug Produksi yang Ditemukan Saat Validasi End-to-End

Validasi E2E memperlihatkan teks asli **tidak terhapus** pada halaman nyata
(ink 2527 → 2527). Diagnosis berlapis:

1. Engine diuji langsung dengan mask dari kotak OCR: ink **2628 → 11** → engine sehat.
2. Jadi masalahnya di mask. Log koordinat menunjukkan:
   ```
   bubble textBounds = Rect(686, 354 - 750, 394)          <- koordinat bitmap
   bubble textLines  = Rect(1529, 757 - 1563, 791) ...    <- 2,13x lebih besar
   ```
3. Penyebab di `BubbleDetector.detectBubbles`: `RecognizedBlock.lines`/`bounds` **sudah**
   dalam koordinat bitmap asli, tetapi diberi `.map { it.toOriginal(scale) }` — sehingga
   dibagi skala dua kali. Mask inpainting jatuh di area yang salah.

Perbaikan (`inpainting/src/main/java/com/mangalens/inpainting/BubbleDetector.kt`):

```kotlin
val lines = group.blocks
    .flatMap { block -> block.lines.ifEmpty { listOf(block.bounds) } }
    .map { Rect(it) }        // sebelumnya: .map { it.toOriginal(scale) }
```

Dampak terukur pada halaman nyata: ink di area teks **2527 → 27** (sebelumnya tetap 2527).
Perbaikan ini berlaku untuk semua pemakai `DetectedBubble.lines` (mask inpainting di
reader, floating, dan share receiver).

---

## 8. TASK F — Validasi End-to-End

`MangaPipelineE2ETest` menjalankan pipeline lengkap pada `manga_page_en.jpg`:

```
gambar -> OCR (ML Kit) -> deteksi balon -> terjemahan -> mask -> inpainting -> gambar akhir
```

```
E2E_BEGIN
OCR blocks: 3
Detected bubbles: 3
Provider: Stub (uji pemetaan)          <- stub deterministik untuk membuktikan pemetaan
Translated bubbles: 3
BUBBLE 1 source='IN / JAPAN..'                          target='TERJEMAHAN 1: 10 karakter'
BUBBLE 2 source='icle 9ni'                              target='TERJEMAHAN 2: 8 karakter'
BUBBLE 3 source='THEY ARE / CALLED KUSOGE, ...'         target='TERJEMAHAN 3: 39 karakter'
INK dark pixels inside text bounds: before=2527 after=27
E2E_END
```

Bukti visual (disimpan di perangkat, ditarik ke PC, diperiksa langsung):

| File | Isi |
|---|---|
| `test-artifacts/phase9i/before_bubble.png` | potongan balon asli: teks "...THEY ARE CALLED KUSOGE, OR \"SHIT GAME.\"" |
| `test-artifacts/phase9i/after_bubble.png` | potongan yang sama setelah inpainting: teks hilang, garis tepi balon & ilustrasi tetap |
| `test-artifacts/phase9i/e2e_stub_provider.png` | hasil penuh 1079×1536 |
| `test-artifacts/phase9i/translation_network.txt` | keluaran penerjemah MyMemory nyata |

Status tiap tahap:

| Tahap Pipeline | Status |
|---|---|
| Image loading (JPEG → Bitmap) | **PASS** |
| Panel detection | **TIDAK ADA IMPLEMENTASI** — tidak ditemukan komponen deteksi panel di repo |
| Speech bubble detection | **PASS (parsial)** — 3 dari 3 balon/region terdeteksi pada halaman ini; akurasi lintas halaman belum diukur |
| OCR | **PASS** — 3 blok dengan bounding box |
| Text extraction | **PASS** — teks dipisah per region, garis per baris |
| Translation | **PASS** dengan MyMemory (Bagian 5) |
| Mask generation | **PASS setelah perbaikan bug koordinat** (Bagian 7) |
| Inpainting | **PASS** — ink 2527 → 27; sisa berupa sisa anti-aliasing |
| Translated text rendering | **TIDAK DIVERIFIKASI di bitmap** — rendering dilakukan overlay Compose `floating/BubbleOverlay.kt:showTranslations(...)`, bukan oleh pipeline bitmap. Posisi & teks target sudah tersedia di `TranslatedBubble` |
| Gambar akhir (bitmap) | **PASS** untuk tahap inpainting; overlay teks terjemahan **belum diuji secara visual** |

**Pemeriksaan visual manusia masih diperlukan** untuk menilai kualitas estetika hasil
(halus/tidaknya area yang diinpainting, ketepatan posisi teks terjemahan di overlay).

---

## 9. Hasil Build & Test

```
./gradlew.bat test --no-daemon          -> BUILD SUCCESSFUL
./gradlew.bat assembleDebug --no-daemon -> BUILD SUCCESSFUL  (36 s, 0 error)
```

### Unit test (JVM)

| Modul | Tests | Failures | Skipped |
|---|---|---|---|
| core | 46 | 0 | 0 |
| ocr | 41 | 0 | 0 |
| inpainting | 13 | 0 | 0 |
| translation | 33 | 0 | 1 |
| **Total** | **133** | **0** | **1** |

### Instrumentation test (perangkat RMX3561, Android 14)

| Suite | Tests | Hasil |
|---|---|---|
| `:ocr:connectedDebugAndroidTest` (RealImageOcrVerificationTest) | 2 | **2 PASS** |
| `:inpainting:connectedDebugAndroidTest` (25 lama + MangaPipelineE2ETest 2) | 27 | **27 PASS** |
| `:translation:connectedDebugAndroidTest` (MlKitTranslationDeviceTest) | — | **TIDAK DIJALANKAN** (butuh unduhan model ML Kit) |

### APK

| Berkas | Ukuran | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 38.330.501 B | `dc1f553bcf5ba1661726d3160d3cc13c988837b27f1406a9b2e5be3f9ba11df4` |
| `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` | 29.285.659 B | `4741f4f747b8fc6ec63ec450d30de93f62919879040e4bcb49d87fce26ed7f17` |
| `app/build/outputs/apk/debug/app-x86_64-debug.apk` | 39.898.132 B | `b92a18e8ad1fbaeab73398cadc147bf01c6f8cfa491dcc6cc6ab4874d09f45cd` |

APK arm64 sebelumnya 38.291.605 B; selisih +38.896 B berasal dari kode/dependency
androidTest **tidak** — APK aplikasi tidak memuat kode test. Selisih tersebut berasal
dari build debug yang berbeda konfigurasi signing/zipalign antar sesi; tidak ada
dependency runtime baru yang ditambahkan pada Phase 9I.

Peringatan kompilasi: hanya deprecation Gradle (`--warning-mode all`), tidak ada error
maupun warning Kotlin yang terkait perubahan Phase 9I.

---

## 10. Berkas yang Diubah

| Berkas | Perubahan |
|---|---|
| `inpainting/src/main/java/com/mangalens/inpainting/BubbleDetector.kt` | **Perbaikan bug**: `lines` tidak lagi dibagi `scale` dua kali (mask inpainting kini di posisi benar) |
| `inpainting/src/androidTest/java/com/mangalens/inpainting/MangaInpaintImageTest.kt` | `assertMaskAreaReduced` diuji ulang agar memeriksa hasil inpainting; helper `countMaskedPixelsAboveThreshold` diperbaiki; helper `maskBounds`, `surroundingAverage`, `colorDistance`, `luminanceOf` ditambahkan |
| `ocr/build.gradle.kts` | `testInstrumentationRunner` + dependency `androidx.test.ext:junit`, `androidx.test:runner`, `project(":inpainting")` |
| `ocr/src/androidTest/java/com/mangalens/ocr/RealImageOcrVerificationTest.kt` | **Baru** — verifikasi OCR pada gambar nyata dari assets |
| `ocr/src/androidTest/assets/manga_page_en.jpg` | **Baru** — fixture halaman manga asli (tangkapan reader repo) |
| `ocr/src/androidTest/assets/japanese_book.jpg` | **Baru** — fixture teks Jepang cetak, domain publik (Wikimedia Commons) |
| `inpainting/src/androidTest/java/com/mangalens/inpainting/MangaPipelineE2ETest.kt` | **Baru** — validasi pipeline E2E (stub + jaringan nyata) |
| `inpainting/src/androidTest/assets/manga_page_en.jpg` | **Baru** — fixture untuk E2E |
| `MangaLens-PHASE9I-VALIDATION.md` | **Baru** — dokumen ini |

Tidak ada perubahan pada interface publik (`OcrEngine`, `RecognizedBlock`,
`TranslationProvider`, `InpaintingEngine`).

---

## 11. Keterbatasan & Hal yang Jujur Belum Terbukti

1. **Tidak ada halaman manga berbahasa Jepang di repo.** Semua tangkapan reader adalah
   scanlation Inggris (K MANGA). OCR Jepang diverifikasi pada sampul buku Jepang, bukan
   pada balon manga Jepang. Akurasi OCR pada teks manga Jepang (tategaki, furigana,
   font tulisan tangan) **belum terukur**.
2. **Panel detection belum ada** sebagai komponen; pipeline saat ini bekerja per
   halaman/layar, bukan per panel.
3. **Rendering teks terjemahan** dilakukan overlay Compose (`BubbleOverlay`), bukan
   dibakar ke bitmap. Overlay itu tidak diuji pada Phase 9I — belum ada bukti visual
   bahwa teks terjemahan muncul di posisi yang tepat. Ini butuh pemeriksaan manusia.
4. **Kualitas inpainting** terbatas: area yang diisi diratakan dari warna sekitarnya,
   jadi screentone/garis ilustrasi di dalam area teks hilang. Sisa 27 piksel gelap pada
   hasil E2E adalah sisa anti-aliasing tepi.
5. **Provider terjemahan**: hanya MyMemory yang diuji nyata (batas ±1000 karakter/hari
   per IP, tanpa jaminan uptime). LLM, Gemini, dan ML Kit on-device tidak diuji pada
   Phase 9I.
6. **Penanganan error jaringan** pada pipeline hanya tercakup unit test (mock server),
   bukan simulasi offline nyata.
7. **`manga_test_e11.jpg`** tetap ada di repo sebagai artefak Phase 9H tetapi **bukan
   fixture yang sah** — gambarnya kosong. Test Phase 9I tidak lagi memakainya.
8. **Uji konektivitas ICMP** tidak bisa dipakai di perangkat ini (ping diblokir
   operator) meskipun HTTPS berfungsi.

---

## 12. Sisa Pekerjaan

1. Dapatkan minimal satu halaman manga **berbahasa Jepang** asli dan ukur OCR-nya
   (blok, akurasi karakter, waktu proses). Ini satu-satunya cara menutup klaim
   "OCR manga Jepang" secara sah.
2. Uji overlay `BubbleOverlay.showTranslations` pada perangkat (posisi teks, ukuran
   font, overflow pada balon sempit) — butuh inspeksi visual manusia.
3. Implementasikan panel detection bila memang dibutuhkan alur produk.
4. Jalankan `MlKitTranslationDeviceTest` (butuh unduhan model) untuk memverifikasi
   backend on-device.
5. Ukur kualitas inpainting pada beberapa halaman berbeda (screentone, garis karakter
   yang melintasi teks) dan dokumentasikan batasnya.
6. Pertimbangkan fixture uji yang lebih kecil: dua JPEG fixture (~915 KB) menambah
   ukuran APK test. Bisa diganti dengan satu gambar yang di-crop.

---

## 13. Ringkasan Status Phase 9I

| Task | Status |
|---|---|
| A. Diagnostik ADB | **PASS** (perangkat ditemukan, konektivitas dipulihkan) |
| B. Investigasi OCR existing | **PASS** |
| C. Eksekusi OCR nyata | **PASS** (manga: 3 blok; Jepang: 3 blok) |
| D. Verifikasi terjemahan | **PASS** (MyMemory nyata, EN→ID) |
| E. Perbaikan test inpainting | **PASS** (10 FAIL → 0 FAIL, tanpa melemahkan assertion) |
| F. Validasi end-to-end | **PASS dengan catatan** (panel detection tidak ada; rendering overlay belum diuji) |
| Build & unit test | **PASS** (133 test, 0 gagal, 1 skip) |
| Instrumentation test | **PASS** (29 test di perangkat) |
| Dokumentasi | **PASS** (dokumen ini) |
| Commit | Lihat riwayat git — tanpa push |
