# MangaLens — PHASE 9J VALIDATION

**Japanese Manga OCR, Translation Rendering & Provider Integration**

Tanggal: 25 November 2026
Perangkat uji: Realme `RMX3561` (Android 14, 1080×2412), serial `X8DMKBIBXOY5W4UW`
Checkpoint awal: `a7829af` — *test: verify real OCR, translation and inpainting pipeline (phase 9I)*
Branch: `main`. Tidak ada reset/revert; seluruh perubahan Phase 9I dipertahankan.

---

## 1. Keadaan Awal

```
git log --oneline -1   -> a7829af test: verify real OCR, translation and inpainting pipeline (phase 9I)
git status --short     -> hanya artefak lama yang belum di-commit (ml-capture/, notes/, verification/,
                          APK-Download/, *.bak AndroidManifest, log)
```

`MangaLens-PHASE9I-VALIDATION.md` dibaca; arsitekturnya tetap dipakai: `MlKitOcrEngine` →
`BubbleDetector` → `MangaPageTranslator` → `BasicInpaintingEngine`/`BubbleOverlay`.

Catatan operasional: `adb` di `PATH` adalah 1.0.32 sedangkan Gradle memakai SDK adb 1.0.41.
Dua server berbeda membuat perangkat tampak "No online devices". Solusi: pakai
`C:\Android\Sdk\platform-tools\adb.exe` untuk semua perintah.

---

## 2. TASK A — Dataset Manga Jepang

### 2.1 Sumber dan lisensi (semua legal, tidak ada manga bajakan)

| Fixture | Isi | Ukuran | Lisensi / sumber |
|---|---|---|---|
| `ocr/.../assets/manga_page_ja_modern.png` | Halaman manga modern berbahasa Jepang, potongan panel asli dari `File:Three versions.png` | 707×1002 | **CC BY-SA 4.0**, karya Okitan (Wikimedia Commons); panel asli oleh tofumentalz. Halaman yang sama juga memuat versi kosong dan versi Inggris, sehingga ground truth Inggris tersedia sebagai pembanding independen |
| `ocr/.../assets/manga_page_ja_1902.jpg` | `Tagosaku to Mokube no Tokyo Kenbutsu`, 6 panel teks vertikal | 600×853 | **Domain publik**, Kitazawa Rakuten (1902), sumber Manga-Museum |

Kandidat yang **ditolak**: `File:Manga page publicdomainq.png` (CC0) ternyata hanya
ilustrasi alat tulis + panel kosong tanpa teks Jepang — tidak dapat dipakai sebagai
ground truth OCR, jadi tidak dimasukkan.

Ground truth ditulis manual dari gambar yang diperbesar 2–3× (skrip `test-artifacts/phase9j/crop*.ps1`),
**tidak** diambil dari keluaran OCR. Halaman modern memuat 7 balon dialog bervariasi:
teks vertikal, furigana, screentone, teks kecil 2 kolom, balon dengan ekor, dan balon
elipsis. Total balon yang dievaluasi: **13** (7 modern + 6 dari halaman 1902).

### 2.2 Hasil OCR halaman manga Jepang modern

```
Image: manga_page_ja_modern.png 707x1002
Duration: 128ms   Blocks: 8   (semua orientation=VERTICAL)
BLOCK 1 [51,250]-[119,386]  lines=3  'この / かわいい人形は / なにかしら?.'
BLOCK 2 [68,525]-[81,582]   lines=1  'なんて!'
BLOCK 3 [87,521]-[100,620]  lines=1  'オーダーする'
BLOCK 4 [101,495]-[119,641] lines=1  'でわたくしに内緒で'
BLOCK 5 [134,481]-[154,534] lines=1  'まあ!'
BLOCK 6 [353,460]-[391,543] lines=2  'どこから / 見つけて...'
BLOCK 7 [329,564]-[366,647] lines=2  'いえ、特注の / もので、'
BLOCK 8 [602,98]-[633,283]  lines=1  'シュヴァルツ!'
```

Per-bubble (kemiripan Levenshtein setelah normalisasi spasi/tanda baca):

| # | Ground truth | Terdeteksi | Akurasi | Cakupan karakter |
|---|---|---|---|---|
| 1 | シュヴァルツ！ | YA | 100% | 100% |
| 2 | このかわいい人形はなにかしら。 | YA | 100% | 100% |
| 3 | どこから見つけて…… | YA | 100% | 100% |
| 4 | いえ、特注のもので…… | YA | 100% | 100% |
| 5 | まあ！ | YA | 100% | 100% |
| 6 | わたしくしに内緒でオーダーするなんて！ | YA (sebagian) | 43% | 100% |
| 7 | 抜けがけ | **TIDAK** | 0% | 25% |

```
BUBBLES detected=6/7  meanAccuracy=90%
CHAR_COVERAGE bubbles=6/7  mean=89%
```

Kesimpulan terukur: **6 dari 7 balon dikenali dengan akurasi karakter rata-rata 90%**.
Balon 6 terpecah menjadi tiga blok OCR (`なんて!`, `オーダーする`, `でわたくしに内緒で`)
sehingga akurasi per blok 43% walaupun seluruh karakternya terbaca (cakupan 100%).
Blok 4 juga memuat satu karakter palsu (`で` di awal). Balon 7 (`抜けがけ`, teks kecil
2 kolom di tepi balon) gagal dikenali.

### 2.3 Bounding box

- Kedelapan blok diberi kotak yang membungkus teks vertikal dengan benar
  (contoh: `シュヴァルツ` = `[602,98]-[633,283]`, tinggi 185 px untuk 6 karakter vertikal).
- Akurasi kotak tidak diukur sebagai IoU karena tidak ada anotasi kotak resmi;
  yang diverifikasi adalah: kotak berada di dalam balon yang benar (dicek visual pada
  `test-artifacts/phase9j/ja_page_x2.png` dan posisi blok di atas) dan setiap kotak
  hanya memuat satu balon.

### 2.4 Bug urutan baca yang ditemukan dan diperbaiki

Sebelum perbaikan, blok 1 terbaca `この / なにかしら? / かわいい人形は` — urutan kolom
terbalik. Penyebab: ML Kit mengembalikan baris vertikal dari **kiri ke kanan**, sedangkan
manga dibaca **kanan ke kiri**.

Perbaikan produksi (dua tempat, keduanya memakai kode yang sudah ada):

1. `ocr/.../MlKitOcrEngine.kt` — saat orientasi `VERTICAL`, baris dalam satu blok diurutkan
   menurut `boundingBox.left` menurun sebelum digabung menjadi teks.
2. `inpainting/.../BubbleDetector.kt` — saat orientasi `VERTICAL`, blok dalam satu balon
   diurutkan kanan→kiri sebelum teks digabung.

Dampak terukur pada halaman yang sama:

| | Sebelum | Sesudah |
|---|---|---|
| Balon cocok sebagai satu blok | 3/7 | **6/7** |
| Akurasi rata-rata balon yang cocok | 81% | **90%** |
| Teks balon 1 | `この / なにかしら / かわいい人形は` | `この / かわいい人形は / なにかしら` |

Urutan **antar blok** (`order` 0..7) masih dari ML Kit dan berjalan kiri→kanan; urutan baca
balon ditentukan `BubbleDetector.buildRegions` yang sudah menyortir kanan→kiri untuk teks
vertikal.

### 2.5 Halaman 1902 (aksara lama)

```
Image: manga_page_ja_1902.jpg 600x853
Duration: 297ms  Blocks: 12  (semua VERTICAL)
Akurasi per balon: 22%, 16%, 18%, 9%, 28%, 9%   -> 0/6 di atas ambang 40%
```

Blok teks terdeteksi dengan benar (12 blok, orientasi vertikal benar, posisi tepat),
tetapi **pengenalan karakter rendah** karena ortografi 1902 (変体仮名, 旧字, cetakan
kasar). Karena transkripsi manual halaman ini juga tidak bisa dianggap setara cetak
modern, angka ini dilaporkan sebagai indikasi, bukan skor resmi.

### 2.6 Status Task A

| Item | Status |
|---|---|
| Dataset manga Jepang legal + ground truth | **PASS** |
| ≥10 balon dievaluasi | **PASS** (13 balon) |
| Akurasi karakter balon modern | **PASS** — 6/7 balon, rata-rata 90%, cakupan 89% |
| Akurasi halaman aksara 1902 | **FAIL** (9–28%) — batas model ML Kit, bukan bug aplikasi |
| Bounding box & orientasi vertikal | **PASS** (8/8 blok VERTICAL) |
| Urutan baca | **PASS setelah perbaikan** (3/7 → 6/7) |
| Waktu proses | **PASS** (128 ms; 297 ms halaman 1902) |
| Kasus gagal dilaporkan | `抜けがけ`, `でわたくし…`, balon 1902 |

---

## 3. TASK B — Rendering Teks Terjemahan di Perangkat

### 3.1 Metode

`BubbleOverlayRenderTest` (instrumentation, modul `floating`) menjalankan **kode produksi**:

```
OCR (ML Kit, JAPANESE) -> BubbleDetector -> MyMemory (ja->id) -> BubbleOverlay.showTranslations()
```

Overlay ditambahkan ke `RecordingWindowManager` (dynamic proxy) yang mencatat
`WindowManager.LayoutParams`, mengukur/melayout TextView, lalu mengompositkan hasilnya di
atas gambar manga (1080×2412) dan menyimpannya sebagai PNG.

**Batasan jujur:** ini bukan jendela overlay sistem sungguhan (`SYSTEM_ALERT_WINDOW` tidak
bisa diberikan lewat instrumentation tanpa interaksi pengguna). Yang diuji adalah kode
produksi yang sama: pembuatan TextView, auto-size, pembungkusan baris, warna, dan
perhitungan koordinat. Posisi pada layar nyata mengikuti `LayoutParams` yang diverifikasi
di sini.

### 3.2 Hasil nyata

```
IMAGE 707x1002  OCR=8  BUBBLES=3  provider=MyMemory (gratis)
SCALE 1.5275813  SHOWN 3 of 3

OVERLAY 1 rect=[285,56]+[237x134] lines=1 textHeight=35   'Schwartz!'
OVERLAY 2 rect=[116,293]+[237x134] lines=4 textHeight=116  'Di mana kau menemukannya... Tidak, ini barang pesanan khusus,'
OVERLAY 3 rect=[0,156]+[237x269] lines=7 textHeight=245   'Astaga! Jadi, kamu memesannya tanpa memberitahuku! Boneka lucu ini apa?.'

OVERLAP pairs=1 of 3 overlays
SAVED /storage/emulated/0/Android/data/com.mangalens.floating.test/files/overlay_ja_render.png
DRIFT scale=1 Rect(382,190-619,430)   scale=2 Rect(132,95-369,215)   (titik tengah 500.5 -> 250.5, atas 190 -> 95)
```

Bukti visual (disimpan, bukan di-commit karena `test-artifacts/` di-gitignore):

| Berkas | Isi |
|---|---|
| `test-artifacts/phase9j/overlay_before_fix_small.png` | Sebelum perbaikan: lebar 34–85 px, 5–16 baris, 2 kotak meluber, huruf bertumpuk vertikal |
| `test-artifacts/phase9j/overlay_ja_render_small.png` | Sesudah perbaikan: lebar 237 px, 1–7 baris, teks Indonesia terbaca |
| `test-artifacts/phase9j/overlay_ja_render.png` (1080×2412) | Hasil penuh resolusi perangkat |

### 3.3 Bug yang ditemukan dan diperbaiki

Kotak teks Jepang vertikal sangat sempit (mis. 30 px untuk `シュヴァルツ!`). Overlay lama
mewarisi lebar itu apa adanya, sehingga teks Indonesia terbungkus **1–3 huruf per baris**
(`S c h w a r t z !`) dan meluber keluar kotak (2 dari 3 kotak).

Perbaikan (`floating/.../BubbleOverlay.kt`), minimal dan terukur:

- Lebar kotak = `max(lebar teks asli, 22% lebar layar)`, tetap dibatasi
  `MAX_WIDTH_FRACTION` (90% layar).
- Kotak dipusatkan pada titik tengah kotak teks asli, lalu dijepit ke dalam layar.
- Tinggi minimum `MIN_TRANSLATION_HEIGHT` agar teks pendek tidak terpotong.

| Metrik | Sebelum | Sesudah |
|---|---|---|
| Lebar kotak | 34 / 55 / 85 px | 237 / 237 / 237 px |
| Baris teks | 5 / 16 / 12 | 1 / 4 / 7 |
| Kotak meluber | 2 dari 3 | **0** |
| Posisi (drift skala 1 vs 2) | — | titik tengah & tepi atas tepat separuh (±0 px) |

### 3.4 Status Task B

| Item | Status |
|---|---|
| Teks Indonesia tampil di posisi balon (koordinat dari OCR) | **PASS** — 3/3 overlay, di dalam layar, tanpa drift skala |
| Teks panjang tidak meluber | **PASS setelah perbaikan** (0 overflow) |
| Teks terbaca pada zoom/resolusi berbeda | **PASS** untuk skala 1× dan 2× (matematika posisi diuji) |
| Posisi pada layar Android sungguhan (overlay sistem) | **UNTESTED** — butuh izin overlay & interaksi manusia |
| Tidak menutupi balon tetangga | **PARTIAL** — 1 pasangan bertumpuk; penyebab: `BubbleDetector` menggabungkan balon この… + まあ… menjadi satu region |
| Teks asli Jepang dihapus di overlay | **TIDAK** — overlay menutupi dengan latar semi-transparan; penghapusan teks asli lewat inpainting belum dipakai UI (lihat Bagian 5) |

---

## 4. TASK C — Provider Terjemahan

Arsitektur multi-provider tidak diubah (`TranslationProviders.create`, `FallbackTranslationProvider`).
LLM tetap opsional; tidak ada kunci API di source, log, atau dokumen ini.

Hasil nyata di perangkat (`TranslationProviderMatrixTest`, 6 test):

| Provider | Kondisi | Hasil |
|---|---|---|
| **MyMemory** (jaringan nyata) | ja→id, 3 dialog | `Tunggu sebentar.` / `Di mana?` / `Apa boneka imut ini?` — 3/3 terisi |
| MyMemory, input kosong | `translate(emptyList())` | `Success(texts={})` — tidak ada request jaringan |
| **ML Kit on-device** | ja→id, model diunduh | `Menunggu` / `Di mana?` / `Boneka imut ini adalah sesuatu.` — 3/3 terisi, `prepare()=true` |
| **Gemini** tanpa kunci | `prepare()` / `translate()` | `false` / `Failure('Kunci API Gemini belum diisi.')` — tidak crash |
| **LLM** tanpa kunci | `prepare()` / `translate()` | `false` / `Failure('Kunci API LLM belum diisi. Buka pengaturan provider lalu simpan kunci.')` |
| **Fallback** Gemini→ML Kit | Gemini tanpa kunci | `Success` dengan keluaran ML Kit (tanpa kunci) |

Batasan & biaya (didokumentasikan, bukan diubah):

- **ML Kit**: gratis, offline setelah unduhan model; unduhan awal butuh internet; hasil literal.
- **MyMemory**: gratis tanpa kunci, ±1000 karakter/hari per IP tanpa key, bisa timeout;
  kegagalan mengosongkan hasil balon (tidak menampilkan pesan error internal sebagai terjemahan).
- **Gemini/LLM**: butuh kunci milik pengguna (disimpan terenkripsi di perangkat, bukan di APK),
  ada biaya per token dan kuota.
- Fallback **tidak** diam-diam: `FallbackTranslationProvider.displayName` menampilkan
  `"<utama> + <cadangan>"`, dan UI menampilkan `PageTranslation.providerName` yang benar-benar dipakai.

| Item | Status |
|---|---|
| MyMemory nyata + input kosong | **PASS** |
| ML Kit nyata (ja→id, on-device) | **PASS** |
| Gemini terkonfigurasi (dengan kunci asli) | **UNTESTED** — tidak ada kunci; jalur tanpa kunci **PASS** |
| LLM terkonfigurasi | **UNTESTED** — idem |
| Fallback otomatis + transparansi provider | **PASS** |
| Rate limit / timeout jaringan nyata | **PARTIAL** — hanya jalur sukses & input kosong yang dieksekusi; timeout tidak disimulasikan |

---

## 5. TASK D — Panel Detection

### 5.1 Implementasi

`inpainting/.../PanelDetector.kt` — tanpa dependency baru, tanpa model, offline, murni bitmap:

1. Perkecil halaman ke sisi terpanjang 600 px.
2. Hitung rasio piksel putih (≥235) dan gelap (≤90) per baris dan per kolom.
3. Baris/kolom dengan ≥97% putih (celah) atau ≥80% gelap (garis batas) = pemisah;
   deretan bersambung digabung.
4. Pita di antara pemisah (min 6% halaman) diiriskan; panel kosong dibuang.

Balon tidak bisa menjadi panel karena balon tidak pernah membentang penuh selebar/tinggi
halaman — syarat pemisah tidak terpenuhi.

### 5.2 Hasil

| Kasus | Hasil |
|---|---|
| Halaman manga Jepang nyata (707×1002) | **2 panel** (`[4,262]`, `[272,595]` pada koordinat analisis) |
| Sintetis 2 panel bergaris + balon di dalamnya | **2 panel** — balon tidak dilaporkan sebagai panel |
| Halaman borderless (ilustrasi penuh) | **1 panel** = seluruh halaman (degradasi aman, bukan error) |

Bukti visual: `test-artifacts/phase9j/panel_detection_ja_small.png` (kotak merah pada panel).

### 5.3 Keterbatasan

- Halaman nyata hanya terpisah menjadi **2** panel, bukan 3 baris: batas antara baris 2 dan 3
  **miring**, sedangkan detektor hanya menerima pemisah horizontal/vertikal. Panel bersudut
  miring/overlap hanya didekati kotak pembatas.
- Panel berisi area putih penuh selebar halaman dianggap celah (panel kosong dibuang).
- **Belum diintegrasikan** ke pipeline OCR/terjemahan; pipeline tetap bekerja per halaman.
  Tidak ada regresi karena komponen baru tidak dipanggil produksi.

| Item | Status |
|---|---|
| Kajian pendekatan & implementasi minimal | **PASS** |
| Uji pada halaman nyata + sintetis | **PASS** (2/3 baris nyata; sintetis 2/2) |
| Panel miring/overlap | **PARTIAL** (kotak pembatas) |
| Integrasi ke pipeline | **BELUM** (sengaja; tidak memblokir OCR/terjemahan) |

---

## 6. TASK E — Kualitas Inpainting

Diukur pada latar sintetis dengan ground truth pasti (`InpaintingQualityMatrixTest`, engine produksi):

| Latar | Metrik | Hasil |
|---|---|---|
| Putih polos + teks hitam | sisa piksel gelap di mask / galat warna | **0 px / 0.00** |
| Gradien halus (200→255) | deviasi dari gradien ideal | **0.26 / 255** |
| Biru solid + teks putih | galat warna / sisa teks putih | **0.00 / 0** |
| Screentone titik 4 px | varians pola yang dipertahankan | **53.91%** (turun dari 3636 → 1960) |
| Garis ilustrasi melintasi teks | baris gelap sisa | **10 baris** = garis ilustrasi ikut tersambung; **0 baris teks** tersisa |

Catatan penting: untuk garis ilustrasi, engine **menyambung** garis (interpolasi dari piksel
gelap di tepi mask), bukan menghilangkannya — berbeda dari dugaan di README lama yang
menyatakan garis ikut hilang. Teks di area mask tetap terhapus seluruhnya.

Tidak ada penggantian engine: `BasicInpaintingEngine` tetap implementasi produksi karena
belum ada alternatif yang terbukti lebih baik tanpa menambah dependency/model. Perbaikan
kualitas screentone (patch-based/OpenCV) **belum** dilakukan — lihat Bagian 9.

| Item | Status |
|---|---|
| Latar polos | **PASS** (galat 0) |
| Gradien | **PASS** (deviasi 0.26) |
| Latar berwarna | **PASS** (galat 0) |
| Screentone | **PARTIAL** — pola tidak direkonstruksi, 54% varians tersisa (tampak halus) |
| Teks di atas artwork | **PASS untuk teks**; garis artwork tersambung, bukan dihapus |
| Perbandingan strategi lain | **BELUM** (tidak ada klaim peningkatan) |

---

## 7. TASK F — Performa di RMX3561

`PipelinePerformanceTest` (AUTO script, perangkat nyata):

| Gambar | Blok | Balon | OCR | Deteksi balon | Inpainting | Bitmap | Heap |
|---|---|---|---|---|---|---|---|
| 800×1138 | 1 | 1 | 601 ms (cold) | 49 ms | 70 ms | 3 MB | 7→20 MB |
| 1079×1536 | 3 | 3 | 164 ms | 46 ms | 164 ms | 6 MB | 17→31 MB |
| 1600×2277 | 6 | 4 | 197 ms | 53 ms | 417 ms | 13 MB | 24→62 MB |
| 707×1002 (Jepang, AUTO) | 8 | 3 | 811–1113 ms | 38–101 ms | 123–127 ms | 2 MB | 61→25 MB |

Max heap perangkat 384 MB; puncak terukur 62 MB.

Temuan: pada halaman Jepang, mode **AUTO** memakan **811–1113 ms** karena menjalankan
recognizer Latin **dan** Jepang (plus Korea/Tiongkok bila karakter masih sedikit),
sedangkan `JAPANESE` eksplisit hanya **128 ms** pada gambar yang sama — sekitar **6–8×**.
Ini adalah biaya mode otomatis yang sudah ada, bukan regresi Phase 9J; rekomendasi
optimasi ada di Bagian 10.

Tidak ada ANR/crash selama 50 test perangkat. Tidak ada OCR/terjemahan berulang dalam satu
panggilan pipeline (satu `translate()` = satu OCR + satu permintaan provider).

---

## 8. Build, Test & Artefak

### Unit test (JVM)

```
./gradlew.bat test --no-daemon -> BUILD SUCCESSFUL
```

| Modul | Tests | Failures |
|---|---|---|
| core | 46 | 0 |
| ocr | 41 | 0 |
| inpainting | 13 | 0 |
| translation | 33 | 0 (1 skipped) |
| **Total** | **133** | **0** |

### Instrumentation test (RMX3561, Android 14)

| Suite | Tests | Hasil |
|---|---|---|
| `:ocr:connectedDebugAndroidTest` (2 lama + `JapaneseMangaOcrTest` 2) | 4 | **4 PASS** |
| `:inpainting:connectedDebugAndroidTest` (25 lama + E2E 2 + panel 3 + kualitas 5 + performa 1) | 36 | **36 PASS** |
| `:translation:connectedDebugAndroidTest` (2 lama + matrix 6) | 8 | **8 PASS** |
| `:floating:connectedDebugAndroidTest` (`BubbleOverlayRenderTest`) | 2 | **2 PASS** |
| **Total** | **50** | **50 PASS** |

### Build bersih

```
./gradlew.bat clean assembleDebug --no-daemon -> BUILD SUCCESSFUL in 1m 9s
```

| APK | Ukuran | SHA-256 |
|---|---|---|
| `app-arm64-v8a-debug.apk` | 38.291.605 B | `c2cd4ff5e953cc5c032260cfa2f95992e2b0c0d3970c989e744d1deba93d7a98` |
| `app-armeabi-v7a-debug.apk` | 29.246.763 B | `e491571eac9ef01c17f42a56f97441a4fd28692c4d6019dd6ef2078e464db1c9` |
| `app-x86_64-debug.apk` | 39.859.236 B | `2a7ecc94f621dd83fe3e0bde84613eb43b9e8d0eb752f87f4e2a9ac9977e1ee0` |

---

## 9. Berkas yang Diubah / Ditambah

| Berkas | Perubahan |
|---|---|
| `ocr/.../MlKitOcrEngine.kt` | **Perbaikan**: urutan baris teks vertikal kanan→kiri |
| `inpainting/.../BubbleDetector.kt` | **Perbaikan**: blok dalam satu balon vertikal diurutkan kanan→kiri |
| `floating/.../BubbleOverlay.kt` | **Perbaikan**: lebar baca minimum + pemusatan kotak (overflow 2→0) |
| `inpainting/.../PanelDetector.kt` | **Baru** — deteksi panel berbasis celah/garis batas |
| `ocr/src/androidTest/.../JapaneseMangaOcrTest.kt` | **Baru** — OCR manga Jepang + akurasi & cakupan karakter |
| `ocr/.../assets/manga_page_ja_modern.png`, `manga_page_ja_1902.jpg` | **Baru** — fixture Jepang berlisensi |
| `floating/src/androidTest/.../BubbleOverlayRenderTest.kt` + asset | **Baru** — rendering overlay + PNG + uji drift skala |
| `inpainting/src/androidTest/.../PanelDetectorTest.kt` | **Baru** |
| `inpainting/src/androidTest/.../InpaintingQualityMatrixTest.kt` | **Baru** |
| `inpainting/src/androidTest/.../PipelinePerformanceTest.kt` | **Baru** |
| `translation/src/androidTest/.../TranslationProviderMatrixTest.kt` | **Baru** |
| `floating/build.gradle.kts` | `testInstrumentationRunner` + dependency androidTest |
| `inpainting/src/androidTest/assets/manga_page_ja_modern.png` | **Baru** (fixture panel) |

Tidak ada interface publik yang berubah. Tidak ada dependency runtime baru.

---

## 10. Keterbatasan & Sisa Pekerjaan

1. **Overlay sistem sungguhan belum diuji** — rendering diverifikasi lewat kode produksi +
   WindowManager palsu; menampilkan overlay di atas aplikasi lain butuh izin
   `SYSTEM_ALERT_WINDOW` dan interaksi manusia.
2. **Posisi teks belum dipakai untuk menghapus teks asli di UI** — Reader/overlay masih
   menutupi dengan latar; bitmap hasil `translateWithInpaint()` belum ditampilkan.
3. **Dua balon dapat digabung** `BubbleDetector` pada halaman yang sama (この… + まあ…),
   menghasilkan satu kotak terjemahan panjang dan 1 pasang overlay bertumpuk.
4. **Balon 6 terpecah 3 blok** dan blok 4 memuat karakter palsu (`で`), sehingga teks satu
   balon tidak selalu utuh dalam satu blok.
5. **Halaman aksara 1902** hanya 9–28% akurasi — batas model ML Kit untuk 変体仮名.
6. **Screentone** tidak direkonstruksi (54% varians tersisa, tampak halus).
7. **Panel miring/overlap** tidak terpisah (2 dari 3 baris nyata); detektor belum dipakai pipeline.
8. **Gemini/LLM dengan kunci asli** belum diuji (tidak ada kunci); hanya jalur tanpa kunci.
9. **Timeout/rate-limit jaringan nyata** belum disimulasikan.
10. **Mode AUTO 6–8× lebih lambat** dari pemilihan bahasa eksplisit pada halaman Jepang.

Rekomendasi fase berikutnya:

1. Tampilkan hasil inpainting + overlay di Reader, lalu uji visual di perangkat (menutup #1, #2).
2. Perbaiki penggabungan balon (pemisahan berdasarkan celah/tepi balon, bukan hanya jarak) (#3, #4).
3. Optimasi AUTO: hentikan percobaan skrip lain bila recognizer Jepang sudah menghasilkan
   cukup karakter (#10).
4. Integrasikan `PanelDetector` sebagai pemecah halaman sebelum OCR, dengan penanganan
   batas miring (#7).
5. Uji Gemini/LLM dengan kunci pengguna dan simulasi timeout (#8, #9).

---

## 11. Ringkasan Status Phase 9J

| Task | Status |
|---|---|
| A. OCR manga Jepang (13 balon, ground truth) | **PASS dengan catatan** (6/7 modern @90%; halaman 1902 FAIL; 1 balon kecil gagal) |
| B. Rendering terjemahan di perangkat | **PARTIAL** (posisi & overflow PASS lewat kode produksi + PNG; overlay sistem & hapus teks asli belum) |
| C. Integrasi provider | **PASS** (MyMemory + ML Kit nyata; Gemini/LLM jalur tanpa kunci; fallback) |
| D. Panel detection | **PASS untuk implementasi & investigasi; PARTIAL untuk akurasi** (2/3 baris; belum diintegrasikan) |
| E. Kualitas inpainting | **PARTIAL** (polos/gradien/warna PASS; screentone 54%; garis tersambung) |
| F. Performa | **PASS** (4 resolusi terukur, 62 MB puncak heap, tanpa ANR) |
| Build bersih + unit test | **PASS** (133 test, 0 gagal, 1 skip) |
| Instrumentation test | **PASS** (50 test) |
| Dokumentasi & commit | **PASS** |

**Status Phase 9J: PARTIAL** — semua task dieksekusi dengan bukti terukur, tetapi overlay
sistem sungguhan dan kualitas screentone belum tuntas.
