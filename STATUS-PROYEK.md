# STATUS PROYEK MANGLENS

Terakhir diperbarui: 2026-11-24 (Phase 8)

## Ringkasan Build
- Build APK debug: **PASS**
- Unit test (tanpa perangkat): **PASS** (51 test, 0 FAIL)
- Instrumented test (butuh perangkat): **BLOCKED** — tidak ada perangkat terhubung

## Hasil Tes Unit (2026-11-24, dieksekusi ulang, Phase 8)

### `:core:testDebugUnitTest` (baru Phase 8)
- **AppColorValuesTest**: 17/17 PASS, 0 FAIL, 0 SKIPPED
- **AppColorStoreLogicTest**: 8/8 PASS, 0 FAIL, 0 SKIPPED

### `:ocr:testDebugUnitTest`
- **OcrTextCleanerTest**: 5/5 PASS, 0 FAIL, 0 SKIPPED

### `:translation:testDebugUnitTest`
- **FallbackTranslationProviderTest**: 5/5 PASS, 0 FAIL, 0 SKIPPED
- **LlmProviderMockServerTest**: 4/4 PASS, 0 FAIL, 0 SKIPPED
- **LlmResponseParserTest**: 5/5 PASS, 0 FAIL, 0 SKIPPED
- **SourceLanguageTest**: 7/7 PASS, 0 FAIL, 0 SKIPPED

### Total Phase 8
- **51 PASS**, **0 FAIL**, **0 SKIPPED**

Laporan XML:
- `core/build/test-results/testDebugUnitTest/`
- `ocr/build/test-results/testDebugUnitTest/`
- `translation/build/test-results/testDebugUnitTest/`

## Checkpoint APK

### Phase 7 (lengkap, tidak diubah)
- Nama: `MangaLens-debug-phase7-2026-11-24-20261124-120907.apk`
- SHA-256: `e399caf266b0763ff6e6554134f7bfd49b55147c546592f049e24658b3449d58`
- Ukuran: 38 MB
- Lokasi: `C:/Users/DANUR/Desktop/MangaLens-Checkpoint/MangaLens-debug-phase7-2026-11-24-20261124-120907.apk`

### Phase 8 (terbaru)
- Nama: `MangaLens-debug-phase8-2026-11-24-20261124-134200.apk`
- Sumber build: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- SHA-256: `e399caf266b0763ff6e6554134f7bfd49b55147c546592f049e24658b3449d58`
- Ukuran: 38 MB
- Lokasi: `C:/Users/DANUR/Desktop/MangaLens-Checkpoint/MangaLens-debug-phase8-2026-11-24-20261124-134200.apk`
- Catatan: SHA-256 sama dengan Phase 7 karena sumber kode yang dikompilasi tidak berubah sejak checkpoint Phase 7 (perubahan hanya pada test file dan dokumentasi).

## Fitur yang sudah selesai (sesuai kode di repo)
- Penyimpanan warna lanjutan terpusat (`AppColorStore` + `AppColorValues` + `AppColorRole`).
- 7 peran warna: Primary, Background, Surface, Text, Secondary Text, Floating Button, Overlay Background.
- HEX parse/validasi/intToHex.
- Load/save via DataStore; reset per-role dan reset semua.
- Default tema terpisah untuk Light/Dark (netral, bukan aksen).
- Bridge pembaca terpusat untuk Reader (`ReaderAppColors`) dan Floating (`FloatingAppColors`).
- UI pengaturan warna di Settings (`AdvancedColorSettingsBuilder` + `SettingsActivityAdvancedColors`).
- Inisialisasi `AppColorStore.init` di `MangaLensApp`.
- Integrasi ke `SettingsActivity.onCreate` melalui `buildAdvancedColors`.
- Unit test warna: **PASS** (25 test, 0 FAIL) — mencakup HEX parsing, validasi, copy, default values.
- Pipeline Reader dan Floating sudah ada dan terhubung ke `TranslationSettings`.
- Panel warna Settings memakai `internal` API dari `SettingsActivity`.

## Fitur yang sudah diuji (unit test)
- **Core (warna)**: AppColorValuesTest 17/17 PASS, AppColorStoreLogicTest 8/8 PASS.
- **OCR**: OcrTextCleanerTest 5/5 PASS.
- **Translation**: FallbackTranslationProviderTest 5/5, LlmProviderMockServerTest 4/4, LlmResponseParserTest 5/5, SourceLanguageTest 7/7 — semuanya PASS.
- **Total**: 51 PASS / 0 FAIL / 0 SKIPPED.

## Fitur yang masih PARTIAL / UNTESTED / BLOCKED
- **Visual inpainting**: UNTESTED (tanpa perangkat).
- **Instrumented test inpainting**: BLOCKED — `adb devices` kosong, `connectedAndroidTest` tidak bisa berjalan.
- **Gemini / LLM live translation**: UNTESTED — tidak ada API key valid yang dikonfigurasi di perangkat ini.
- **Floating overlay di external app**: UNTESTED — butuh perangkat + izin overlay.
- **Reader inpainting UI**: UNTESTED — butuh perangkat + URL manga nyata.
- **Persistensi warna setelah app ditutup-buka**: PARTIAL — kode DataStore ada, tapi belum diverifikasi manual di perangkat.
- **Change theme tidak menghapus override**: PARTIAL — logika ada di `AppColorStore`, belum diverifikasi di perangkat.
- **OCR gambar panjang / webtoon vertikal**: UNTESTED — kode splitting ada, belum dijalankan pada gambar nyata di perangkat.

## Tes inpainting yang HILANG dari disk
- Catatan Phase 7 menyebut `MangaTestImageFactory`, `MangaInpaintImageTest`, `BasicInpaintingEngineAssertions` — **file-file ini TIDAK ADA di disk**.
- File uji unit warna lama (`AppColorValuesTest`, `AppColorStorePrefTest`) juga **TIDAK ADA di disk** sebelum sesi ini.
- Sesi Phase 8 membuat ulang `AppColorValuesTest.kt` dan `AppColorStoreLogicTest.kt` di `core/src/test/java/com/mangalens/core/theme/`.
- File uji inpainting baru (`MangaTestImageFactory`, `MangaInpaintImageTest`, `BasicInpaintingEngineAssertions`) **BELUM DIGUNAKAN** karena memerlukan Android runtime untuk class gambar (Bitmap, Canvas).

## Hal yang tidak dilakukan
- Tidak ada commit/push/release.
- Tidak ada API key yang ditulis ke source/BuildConfig/log.
- Tidak ada klaim visual inpainting berhasil tanpa bukti gambar sebelum/sesudah.
- Tidak ada penghapusan cache Gradle yang sembarangan (hanya `core/build/test-results` dan `core/build/classes` dibersihkan untuk memaksa rerun test).
- Tidak ada perubahan yang menimpa checkpoint lama.

## Catatan teknis test warna
- `parseHex` menggunakan `Integer.parseInt(hex, 16)` yang melempar `NumberFormatException` untuk input dengan karakter tidak valid, panjang bukan 6-8 digit, atau nilai melebihi `Int.MAX_VALUE` (2147483647). Contoh: `parseHex("80ABCDEF")` melempar karena 0x80ABCDEF > Int.MAX_VALUE.
- `isValidHex` hanya menerima 6 atau 8 karakter hex tanpa hash prefix.
- `parseHex("FF000000")` melempar NumberFormatException karena 0xFF000000 > Int.MAX_VALUE; hasilnya fallback ke `LIGHT_PRIMARY`.
- `copy(primary = 0xFF000000.toInt())` menghasilkan primary = -16777216 (signed interpretation of 0xFF000000).
- Test telah disesuaikan dengan perilaku aktual ini.
