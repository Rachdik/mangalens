# MangaLens — Phase 9K Validation

**Fokus:** integrasi OCR → deteksi balon → terjemahan → mask → inpainting → render ke **Reader sungguhan**, plus validasi visual di perangkat.

**Status akhir: PARTIAL** — semua kriteria inti Reader terpenuhi dan terverifikasi di perangkat; dua butir masih belum (verifikasi overlay `SYSTEM_ALERT_WINDOW` di perangkat dan cakupan balon 5/7 pada fixture). Rincian di bagian *Remaining limitations*.

---

## 1. Git

- Commit awal: `6ec7c96` (Phase 9J), sebelumnya `a7829af`.
- Branch: `main`.
- Commit akhir Phase 9K: lihat `git log -1` (commit checkpoint dibuat setelah laporan ini).
- Push: **tidak dilakukan**.
- `git diff --check`: bersih (exit 0), tanpa whitespace error.
- File yang diubah:
  - `inpainting/.../BubbleDetector.kt` — toleransi gabung berbasis lebar kolom untuk teks vertikal.
  - `inpainting/.../MangaPageTranslator.kt` — `TranslationStage` + `onStage`, log diagnostik tahap.
  - `ocr/.../MlKitOcrEngine.kt` — AUTO berhenti lebih awal setelah Jepang berhasil.
  - `reader/.../ReadTranslatePipeline.kt` — pilihan skrip OCR, cache halaman, stage, dua perbaikan kehilangan balon.
  - `reader/.../ReaderActivity.kt` — tombol mode Asli/Terjemahan, `statusView`, helper JS, log state.
  - `reader/src/main/assets/mangalens_scan.js` — mode gambar, `__mangalensApplyInpainted`, `__mangalensSetImageMode`, `__mangalensReaderState`, lewati `data:`.
  - `reader/src/main/res/layout/activity_reader.xml`, `values/strings.xml` — UI mode + status.
  - `reader/build.gradle.kts` — runner instrumentation + dependensi androidTest.
  - Baru: `inpainting/.../BubbleSeparationTest.kt`, `reader/src/androidTest/...` (2 test + manifest + fixture `page.png`).
- Tidak ada API key/token/rahasia yang ditambahkan (`git diff` hanya kode + resource).
- Artefak `test-artifacts/` memang masuk `.gitignore`; screenshot tersimpan lokal dan **tidak** ikut commit.

---

## 2. Arsitektur Reader (yang berubah)

Reader tetap berbasis WebView + injeksi `mangalens_scan.js` (tidak ada rewrite).

Alur nyata yang sekarang berjalan di Reader:

```
<img> halaman terdeteksi script pemindai
  → unduh gambar (NativeImageFetcher)
  → decode + potong vertikal bila perlu
  → OCR ML Kit (skrip dipilih dari bahasa sumber pengguna)
  → BubbleDetector (deteksi + pengelompokan balon)
  → terjemahan provider (ML Kit / MyMemory / LLM + fallback)
  → mask kotak teks → BasicInpaintingEngine
  → komposit balik ke bitmap halaman
  → Bitmap → JPEG 85 → data URL
  → JS: img.src = data URL  (gambar asli disimpan)
  → JS: overlay teks terjemahan (position:fixed, mengikuti rect gambar)
```

Perubahan kunci:

1. **Bitmap inpainting menggantikan gambar asli** — bukan teks ditempel di atas manga asli. Terbukti dari `img.src` yang menjadi `data:image/jpeg` dan uji piksel (3.301 piksel berbeda pada sampling 2 px).
2. **Mode Asli/Terjemahan** — tombol di header. Sumber asli disimpan di `window.__mangalensOriginalSrc`, bitmap inpainting di `window.__mangalensInpaintedSrc`. Berpindah mode tidak memuat ulang situs dan tidak mengulang OCR.
3. **Cache halaman** — LRU 3 halaman di `ReadTranslatePipeline`, kunci = path + ukuran + `lastModified` + bahasa + backend + fallback + flag inpainting + URL. Kembali ke halaman yang sudah diterjemahkan tidak memicu OCR/terjemahan lagi.
4. **Pilihan skrip OCR** — `scriptFor(sourceTag)`: `ja→JAPANESE`, `ko→KOREAN`, `zh→CHINESE`, bahasa Latin→`LATIN`, selain itu `AUTO`. Sebelumnya Reader selalu `AUTO`.
5. **Status terlihat pengguna** — `statusView` di bawah header menampilkan: `Memuat gambar…`, `Mengenali teks…`, `Mengelompokkan balon…`, `Menerjemahkan…`, `Menghapus teks asli…`, `Menampilkan terjemahan…`, `Terjemahan siap · N balon`, atau pesan gagal. Diperoleh dari `MangaPageTranslator.onStage` (bukan dari log).
6. **Gagal tidak menghancurkan halaman** — jalur error memanggil `ImageTranslation(null, …)`: overlay lama dibuang, `img.src` tidak pernah diganti, halaman asli tetap tampil.
7. **`data:` tidak dipindai ulang** — setelah `src` diganti bitmap inpainting, script tidak menganggapnya halaman baru (mencegah OCR berulang pada gambar yang teksnya sudah hilang).

---

## 3. Deteksi & pemisahan balon (Priority B)

### Akar masalah (terbukti lewat log koordinat)

`BubbleDetector.mergeable()` memakai **tinggi** blok sebagai acuan. Pada teks vertikal Jepang, tinggi kolom bisa 300+ px, sehingga toleransi celah ikut membesar dan dua balon berbeda (celah 109 px) digabung.

### Perbaikan

Untuk `TextOrientation.VERTICAL`, acuan diubah ke **lebar** blok (ukuran karakter): batas horizontal `1.6×`, vertikal `0.5×`. Teks horizontal tetap memakai tinggi. Tidak ada pemisahan buta per blok OCR dan urutan baca Jepang (kanan→kiri, atas→bawah) dipertahankan.

### Hasil terukur (fixture `manga_page_ja_modern.png`, 707×1002)

| Tahap | Jumlah |
|---|---|
| Blok OCR | 8 (usable 8) |
| Balon terdeteksi | 5 |
| Region terjemahan | 5 |
| Balon diterjemahkan | 5 |
| Balon dipertahankan komposit | 5 |
| Kotak overlay dirender | 5 |

Sebelum perbaikan: 3 balon (dua pasang balon tetangga tergabung).

`BubbleSeparationTest` (instrumentation, perangkat) **PASS**:
- jumlah balon = 5;
- balon `この…` tidak memuat teks balon lain (`まあ`, `オーダー`);
- balon `まあ/オーダー` utuh (`まあ` + `内緒` + `なんて`) dan tidak memuat `この`;
- tidak ada dua kotak teks balon yang tumpang tindih >10% area kotak terkecil.

### Dua bug kehilangan balon di jalur Reader (ditemukan & diperbaiki fase ini)

1. `compositeInpaintedSlices` membuang balon dengan aturan `top < coveredBottom`. Aturan itu juga membuang balon sah yang berdampingan/bertumpuk vertikal → 5 balon terjemahan menjadi 0 saat komposit. Diganti **deduplikasi identitas spasial** (IoU ≥ 60% dari kotak terkecil = balon yang sama dari dua slice yang tumpang tindih).
2. Hasil `compositeInpaintedSlices` di-destructure ke `bubbles` **lokal** yang menutupi daftar bersama, sehingga hasil inpainting berisi 5 terjemahan tetapi `ImageTranslation` menerima 0 balon. Sekarang digabung ke daftar bersama (`bubbles += inpaintedBubbles`).

Bukti log sesudah perbaikan:

```
MangaLensMT: translate blocks=8 usable=8 bubbles=5 regions=5 tag=ja target=id
MangaLensMT: provider=ML Kit on-device source=ja target=id regions=5
MangaLensMT: translated bubbles=5 provider=ML Kit on-device
MangaLensOverlay: result url=… bubbles=5 provider=ML Kit on-device inpainted=true size=707x1002
MangaLensOverlay: render 5 terjemahan (707x1002)
```

---

## 4. Verifikasi Reader di perangkat (Priority A/C)

Perangkat: **Realme RMX3561, Android 14** (1080×2412, density 480). Halaman uji dilayani dari `127.0.0.1` oleh test (fixture manga Jepang, bukan situs eksternal), Reader produksi (`ReaderActivity`) dibuka dengan URL itu.

`ReaderEndToEndTest` **PASS**:

| Pemeriksaan | Hasil |
|---|---|
| `img.src` setelah terjemahan | `data:image/jpeg;base64,…` (bitmap inpainting) |
| Piksel berbeda vs gambar asli | 3.301 piksel (sampling tiap 2 px) → teks asli benar-benar dihapus |
| Kotak overlay di dalam area gambar | ya (semua kotak dalam batas `img` ±2 px) |
| Jumlah kotak DOM | 5 |
| Mode Asli | `img.src` kembali `http://127.0.0.1:…/page.png`, overlay 0 |
| Kembali ke Terjemahan | `data:` + 5 kotak, 42–90 ms (cache, tanpa OCR ulang) |
| 3 siklus bolak-balik | tiap siklus mode benar, 5 kotak, tanpa duplikasi |
| Zoom (lebar gambar 100% → 62%) | fraksi posisi horizontal kotak 0,74528 → 0,74525 (Δ < 0,0001) |
| Scroll 420 px | selisih `box.top − img.top` tetap 35 px |
| Status UI terlihat | `Memuat gambar…` → `Mengenali teks…` → `Mengelompokkan balon…` → `Menerjemahkan…` → `Menghapus teks asli…` → `Menampilkan terjemahan…` → `Terjemahan siap · 5 balon` |
| Waktu sampai tampil | 4.055–4.463 ms (termasuk unduh + OCR + terjemahan + inpainting + encode) |

Screenshot asli perangkat (disimpan lokal, `test-artifacts/` di-`.gitignore`):
`test-artifacts/phase9k/reader_translated.png`, `reader_original.png`, `reader_zoomed.png`, `reader_scrolled.png`, `reader_translated_again.png`, `reader_three_cycles.png`.

**Hasil inspeksi visual (bukan hanya output test):**
- Mode Terjemahan: teks Jepang asli hilang dari balon (area balon dikosongkan oleh inpainting) dan teks Indonesia tampil di dalam balon: `Schwarz!`, `Apa boneka imut ini?`, `Baik!`, `Di mana mencari tahu…`, `Tidak, dibuat khusus,`.
- Mode Asli: halaman Jepang utuh kembali, tanpa overlay.
- Setelah 3 siklus: tetap 5 kotak, tidak ada overlay ganda atau kotak tersembunyi.

Catatan jujur: pada skala 62% (gambar 263 px), teks di balon sempit menjadi sangat kecil dan beberapa kotak melebar keluar balon (mis. `Di mana mencari tahu…`, `Tidak, dibuat khusus,`). Kotak-kotak itu tidak saling menimpa, tetapi estetika pada zoom-out ekstrem belum ideal.

---

## 5. Zoom & scroll — batasan pengujian

- Zoom diuji dengan mengubah lebar render gambar di DOM (`style.width`), bukan gesture pinch dua jari. Ini menguji hal yang sama untuk matematika overlay (rect gambar berubah → posisi kotak ikut), tetapi **bukan** bukti gesture pinch WebView.
- Scroll diuji dengan `window.scrollTo`, dan container overlay `position:fixed` mengikuti `getBoundingClientRect()`.
- Rotasi perangkat tidak diuji.
- Belum diuji: pinch zoom setelah scroll, ganti mode saat sedang di-zoom lewat gesture nyata (varian DOM-width sudah diuji dan stabil).

---

## 6. Overlay sistem (Priority D)

- Reader **tidak** memakai `SYSTEM_ALERT_WINDOW`. Overlay terjemahan Reader adalah DOM di dalam WebView + bitmap inpainting; tidak ada izin tambahan.
- `SYSTEM_ALERT_WINDOW` tetap dipakai fitur terpisah `FloatingTranslateService` (menerjemahkan aplikasi lain di atas layar). Alur izin ada di `MainActivity`: `canDrawOverlays()` → minta `ACTION_MANAGE_OVERLAY_PERMISSION`; penolakan mengembalikan pengguna ke aplikasi tanpa memulai service.
- Verifikasi overlay sistem **di perangkat belum dilakukan** pada fase ini (butuh pemberian izin overlay + izin rekam layar secara manual). Test overlay yang ada bersifat render in-process (`BubbleOverlayRenderTest`, PASS), bukan overlay sistem nyata.
- Karena Reader sudah terintegrasi penuh, overlay sistem **tidak diperlukan** untuk alur baca manga; arsitekturnya dipertahankan untuk fitur terjemahan lintas-aplikasi.

---

## 7. Performa (Priority E/F, §9)

`ReaderPipelinePerformanceTest` di RMX3561, komponen produksi (ML Kit OCR, `BubbleDetector`, `MangaPageTranslator` + ML Kit on-device, `BasicInpaintingEngine`), fixture yang sama diskalakan:

| Ukuran | Decode | OCR AUTO | OCR JAPANESE | Deteksi balon | Terjemahan | Inpainting | Encode data URL | Total | Balon |
|---|---|---|---|---|---|---|---|---|---|
| 800×1138 | 17 ms | 317 ms | **138 ms** | 136 ms | 814 ms | 104 ms | 17 ms (215 KB) | **1.226 ms** | 5 |
| 1079×1536 | 40 ms | 496 ms | **255 ms** | 59 ms | 453 ms | 390 ms | 24 ms (317 KB) | **1.221 ms** | 5 |
| 1600×2277 | 83 ms | 622 ms | **390 ms** | 76 ms | 512 ms | 537 ms | 46 ms (509 KB) | **1.644 ms** | 5 |

- OCR eksplisit JAPANESE 2,3×–2,6× lebih cepat daripada AUTO dan menghasilkan jumlah blok sama (9/9, 10/10, 10/10) → tidak ada pengorbanan akurasi pada fixture ini.
- AUTO sekarang berhenti setelah Jepang berhasil (tidak menjalankan Korea/Tiongkok) — pengurangan besar dari 811–1113 ms (Phase 9J) ke 317–622 ms.
- Memori: `Debug.getNativeHeapAllocatedSize()` delta 96 MB pada halaman pertama (termuatnya model ML Kit + bitmap), 15 MB dan 28 MB pada halaman berikutnya. Tidak ada OOM/ANR selama pengujian.
- Waktu total Reader di perangkat pada 707×1002: ~4,1–4,5 s (termasuk unduh jaringan lokal + WebView).

### Inpainting / screentone

Tidak ada perubahan algoritma pada fase ini. Mask tetap kotak teks yang dilebarkan; hasil pada latar polos/gradien/warna baik, screentone tetap ~54% variansi pola (blur). Belum ada peningkatan terukur; OpenCV/pattern-aware reconstruction **tidak** ditambahkan (tidak ada bukti perbaikan dan akan menambah dependensi/ABI).

---

## 8. Tes

| Test | Environment | Result | Evidence |
|---|---|---|---|
| `BubbleSeparationTest` | RMX3561 A14 | **PASS** | 5 balon, tanpa teks silang, overlap kotak <10%, 0,49 s |
| `ReaderEndToEndTest` | RMX3561 A14 | **PASS** | inpainted `data:`, 5 kotak, 3 siklus, zoom/scroll stabil, stage UI |
| `ReaderPipelinePerformanceTest` | RMX3561 A14 | **PASS** | 3 resolusi, angka di §7 |
| `:inpainting:connectedDebugAndroidTest` (37 tes) | RMX3561 A14 | **PASS** | `connectedDebugAndroidTest` BUILD SUCCESSFUL |
| `:ocr:connectedDebugAndroidTest` (4) | RMX3561 A14 | **PASS** | idem |
| `:translation:connectedDebugAndroidTest` (8) | RMX3561 A14 | **PASS** | idem |
| `:floating:connectedDebugAndroidTest` (2) | RMX3561 A14 | **PASS** | idem |
| Total instrumentation | RMX3561 A14 | **PASS 53/53** | 37+4+8+2+2, 0 gagal, 0 skipped |
| Unit test (`./gradlew test`) | host | **PASS** 133, 0 gagal, **1 SKIPPED** (varian release duplikat tidak dihitung) | `BUILD SUCCESSFUL in 1m 49s` |
| Clean debug build | host | **PASS** | `./gradlew clean assembleDebug` → `BUILD SUCCESSFUL in 1m 4s` |
| Overlay sistem nyata | perangkat | **NOT TESTED** | butuh izin manual; lihat §6 |

Log mentah: `test-artifacts/phase9k/full_instrumentation.txt`, `unit_tests.txt`, `clean_build.txt`, `reader_perf.txt` (lokal, gitignored).

---

## 9. APK

- Lokasi: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Ukuran: 38.310.513 byte (36,5 MiB)
- SHA-256: `36a7ee7008bd6ecddb416dfe2e09e3b78d9679bba685800a2c82d2658ed93d3a`
- Varian lain: `app-armeabi-v7a-debug.apk` 29.265.671 byte (`6489ebed…`), `app-x86_64-debug.apk` 39.878.144 byte (`dc8ca11c…`).

---

## 10. Remaining limitations

**Terverifikasi bekerja**
- Reader menampilkan bitmap inpainting; teks asli hilang; 5 terjemahan tampil di balon; mode asli/terjemahan bolak-balik 3×; cache 42–90 ms; overlay mengikuti zoom (perubahan lebar render) dan scroll; status tahap terlihat.

**Belum / terbatas**
1. **Cakupan balon 5 dari 7** ground truth pada fixture. `抜けがけ` (panel bawah) dan satu baris balon panjang tidak menjadi region terpisah. Penyebab: OCR menggabungkan/membaca sebagian kolom; bukan penggabungan balon (jumlah balon tetap 5, bukan 3).
2. **Tipografi pada zoom-out ekstrem**: pada lebar gambar 263 px, teks beberapa balon sangat kecil dan kotak melebar keluar balon. Tidak ada tumpang tindih kotak, tetapi penempatan belum rapi.
3. **Zoom gesture pinch** belum diuji; zoom diuji lewat perubahan lebar render di DOM. Rotasi perangkat belum diuji.
4. **Overlay sistem (`SYSTEM_ALERT_WINDOW`)** belum diverifikasi di perangkat (izin manual). Reader sendiri tidak memerlukannya.
5. **Memori** hanya diukur sebagai delta native heap per halaman, bukan peak profile penuh.
6. **Screentone** pada inpainting tetap ~54% variansi; tidak ada perbaikan terukur di fase ini.
7. Jalur **gagal** (provider error) tidak diuji ulang di perangkat pada fase ini; desainnya menjaga halaman asli (tidak mengganti `src`), tetapi belum ada screenshot kegagalan.
8. Halaman uji dilayani lokal (127.0.0.1), bukan situs manga nyata; deteksi gambar/lazy-load situs nyata tidak diuji ulang.

---

## 11. Rekomendasi fase berikutnya

1. **Cakupan balon & OCR Jepang**: tingkatkan pemisahan balon bersebelahan pada panel padat (mis. analisis border balon / connected components pada area putih) dan tangani balon vertikal sempit yang teksnya terpotong — target ≥6/7 balon pada fixture.
2. **Tipografi adaptif**: batas minimum ukuran teks + penyusutan kotak agar teks tidak melebar keluar balon saat zoom-out.
3. **Verifikasi gesture nyata**: pinch zoom, rotasi, dan pergantian mode saat zoom dengan UiAutomator gesture, bukan hanya perubahan lebar DOM.
4. **Panel detection ke pipeline** (sudah ada `PanelDetector`, belum terhubung) agar halaman multi-panel diproses per panel.
5. **Overlay sistem**: verifikasi izin + positioning di perangkat sebelum diumumkan sebagai fitur stabil.
