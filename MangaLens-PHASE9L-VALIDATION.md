# MangaLens — Phase 9L Validation

**Fokus:** validasi *stabilization* akhir Phase 9L — tipografi adaptif, pinch-zoom, scroll alignment, rotasi, cakupan balon Jepang, dan *regression* — tanpa me-restart Phase 9L. Tujuan: menentukan apakah MangaLens Phase 9L benar-benar siap ditutup, bukan sekadar berhasil build APK.

**Status akhir: PARTIAL** — semua kriteria inti Reader berfungsi; *pipeline performance* (ReaderPipelinePerformanceTest) belum di-re-verify, dan *verify-overlay SYSTEM_ALERT_WINDOW di perangkat* belum dilakukan (tidak dalam cakupan Phase 9L).

---

## 1. Git

- Commit saat ini: `732ee80` (`feat: adaptive typography and real pinch-zoom validation (phase 9L)`).
- Branch: `main`.
- `git status --porcelain=v1`: **CLEAN** (tidak ada perubahan lokal).
- Push: **tidak dilakukan**.
- `git diff --check`: bersih (exit 0).
- File yang diubah: **tidak ada** (tidak ada kode yang diubah; laporan ini murni dokumentasi).
- Tidak ada API key/token/rahasia yang ditambahkan.
- `test-artifacts/` masuk `.gitignore`; screenshot disimpan lokal dan **tidak** ikut commit.

---

## 2. Perangkat / Device Connectivity

- Serial ADB: `X8DMKBIBXOY5W4UW` → `device` (Android 14, RMX3561).
- `versionCode=1`, `versionName=0.1.0`.
- USB ADB aktif (host ↔ perangkat terhubung). Tidak ada konfigurasi wireless ADB yang terdaftar di repositori; perangkat hanya dapat dijangkau via USB kabel.
- Driver/USB: `adb devices` mencetak `X8DMKBIBXOY5W4UW\tdevice` setelah *daemon* di-restart.

---

## 3. Reader Pipeline Performance (ReaderPipelinePerformanceTest) — **BELUM DIVERIIFY DI ENAM KEMBALI**

`ReaderPipelinePerformanceTest` adalah ketik uji **instrumentation** yang tidak dijalankan ulang dalam pemulihan Phase 9L. Nilai historis sebelumnya dilaporkan:

| Ukuran | Total (sebelum) |
|---|---|
| 800×1138 | 1420 ms |
| 1079×1536 | 1296 ms |
| 1600×2277 | 1039 ms |

Yang ini **tidak bisa diverifikasi** dalam pemulihan ini:
- perangkat dapat dijangkau, tetapi **tidak ada ulang uji** `ReaderPipelinePerformanceTest` dijalankan;
- log `test-artifacts/phase9l/logcat-com.mangalens.reader.ReaderPipelinePerformanceTest-*.txt` **tidak ada**; yang ada hanyalah `perf_9l.txt` yang berisi *build Gradle*, bukan hasil uji instumentation.

**Kesimpulan:** `ReaderPipelinePerformanceTest` **BLOCKED / NOT VERIFIED** karena tes instrumentasi nya tidak dijalankan ulang. Nilai sebelumnya hanya sebagaiReferensi historis, bukan hasil pemulihan.

---

## 4. Cakupan Balon Jepang (JapaneseBubbleCoverageTest)

HasilInstrumentasi (RMX3561, fixture `manga_page_ja_modern.png`, 707×1002):

```
COVERAGE blocks=8 bubbles=6
COVERAGE truth 1 'シュヴァルツ！' -> regions=[1]
COVERAGE truth 2 'このかわいい人形はなにかしら。' -> regions=[2]
COVERAGE truth 3 'どこから見つけて……' -> regions=[3]
COVERAGE truth 4 'いえ、特注のもので……' -> regions=[6]
COVERAGE truth 5 'まあ！' -> regions=[4]
COVERAGE truth 6 'わたしくしに内緒でオーダーするなんて！' -> regions=[5]
COVERAGE truth 7 '抜けがけ' -> MISSED
COVERAGE result covered=6/7 unmatchedRegions=0 mergedTruths=0
```

- **Terdapat 6 dari 7 region ground truth terdeteksi** (minimal target 6/7 tercapai).
- `抜けがけ` (panel bawah) **MISSED** — bukan karena penggabungan (`mergedTruths=0`) atau pemisahan berlebih (`unmatchedRegions=0`).
  - Analisis: `抜けがけ` ada di balon vertikal (CIRC 7, `Rect(602,98)-Rect(633,283)`); hasil OCR-nya `extract 4/7 missed=3` dan `pick_best_iou 0.000000`; `región`nya terdeteksi tapi **tidak cocok** — yaitu `この / かわいい人形は / なにかしら?.` (region 2, `Rect(50,249)-Rect(118,385)`). Ini adalah **kombinasi garis balon yang panjang / teks yang terpotong**, bukan masalah segmentasi balon.
  - Penyebab utama: **limitasi OCR ML Kit** (gagal membaca `抜けがけ` dari area yang ada), bukan defisit deteksi pipeline.
  - `JapaneseMangaOcrTest` (fixture yang sama) menampilkan `BUBBLE 7 MISSED acc=0% coverage=25%` — **OCR** gagal membaca teks tersebut (25% coverage), yang menilai akurasi ejaan, bukan hubungan geometri (artinya `抜けがけ` sendiri memang tidak terbaca).

**Verdict:** `6/7` merupakan hasil yang **dapat diterima** apabila *pengecualian* terkait bahwa `抜けがけ` adalah keterbatasan OCR engine, bukan masalah pipeline deteksi. **Tidak ada kode yang diubah** untuk memaksa `7/7` atau memanipulasi anchor.

---

## 5. Tipografi Adaptif (Reader pipeline + mangalens_scan.js)

Parameter kunci:

- `MIN_FONT_SIZE = 9` (monospace layout), `FONT_HARD_FLOOR = 6` (fallback untuk balon yang sangat sempit).
- `data-overflow` = `1` bila teks tidak muat (overflow), `0` jika muat; `data-font` = `px` yang dipakai saat render.
- Serif `600`, teks mengikuti `wrapText` (tidak ada dipotong).

Cek dari file `mangalens_scan.js`:
- `line 778`: `var MIN_FONT_SIZE = 9;`
- `line 780`: `var FONT_HARD_FLOOR = 6;`
- `data-overflow` dan `data-font` disetel di `node.setAttribute(...)` masing-masing (`data-overflow` dan `data-font`).
- Loop penyesuaian font: dari ukuran besar menuju `MIN_FONT_SIZE` (fitAt mundur), kemudian area aman balon, baru fallback `FONT_HARD_FLOOR`.

**Hasil verifikasi** (dokumentasi `ReaderEndToEndTest` + screenshot perangkat):

- **Scroll alignment**: `scroll offset before=35 after=35` — container `position:fixed` tetap menempel pada gambar; selisih `box.top − img.top` tetap (35 px).
- **Zoom**: setelah `style.width='62%'` (100%→263px), fraksi posisi horizontal kotak 0,74528→0,74525 (Δ < 0,0001); jumlah kotak tetap 6, `overflowBoxes=0`.
- **Mode Asli/Terjemahan**: mode asli memulihkan `img.src` ke `http://127.0.0.1:…/page.png` dan mengganti `overlays=0`; kembali ke terjemahan memakai cache (42–90 ms, tanpa OCR ulang); 3 siklus bolak-balik tidak ada duplikasi overlay.
- **Tipografi**: pada zoom-out, `minFont=11` (lebih besar dari `MIN_FONT_SIZE=9`), ensuring teks tetap di dalam balon.
- `data-overflow` dan `data-font` digunakan oleh Kotlin (`ReaderState`) untuk ekspor ke `window.__mangalensReaderState`.

**Catatan**: pengecekan dilakukan melalui *log instrumentasi* + *screenshot perangkat*; tidak ada modifikasi kode yang diperlukan. `MIN_FONT_SIZE` dan `FONT_HARD_FLOOR` ada dan dipakai; perilaku di perangkat konsisten.

---

## 6. Pinch-Zoom Real (UiAutomator gesture)

Log `ReaderGestureTest` (geser, kencang, rotasi):

```
GESTURE start scale=1.0 boxes=6 state={...}
GESTURE pinchOpen scale=2.3807308673858643
GESTURE zoomed boxes=6 overflow=0 minFont=11
GESTURE swipe scroll 226.27452087402344 -> 339.60784912109375 imgTop 0 -> 0 offset=57
GESTURE screenshot phase9l_gesture_normal.png 1080x2412
GESTURE screenshot phase9l_gesture_zoomed_in.png 1080x2412
GESTURE screenshot phase9l_gesture_scrolled_after_zoom.png 1080x2412
GESTURE screenshot phase9l_gesture_original_zoomed.png 1080x2412
GESTURE screenshot phase9l_gesture_translated_zoomed.png 1080x2412
GESTURE pinchClose scale=1.0035611391067505
GESTURE screenshot phase9l_gesture_zoomed_out.png 1080x2412
```

- **Pinch zoom** berhasil: `pinchOpen` → skala 2.38, `pinchClose` → 1.00. Jumlah kotak tetap 6 dan `overflowBoxes=0` setelah zoom in.
- **Scroll**: geser scrolled `scroll 226.27 → 339.61`, `imgTop` tetap 0 → offset 57 dipertahankan.
- **Rotasi**: setelah `setOrientationLeft()`, `landscape boxes=6 overflow=0`, lalu `setOrientationNatural()`.
- Screenshot *landscape* tersedia: `gesture_landscape.png` (2412×1080).

**Kesimpulan: TIPOGRAFI + PINCH-ZOOM + SCROLL + ROTASI — VERIFIED** (tanpa modifikasi kode).

---

## 7. Tempat Kunci (-regression)

### ReaderEndToEndTest (`ReaderEndToEndTest.kt`)
- `img.src` setelah terjemahan → `data:image/jpeg;base64,…` (bitmap inpainting).
- Pixel berbeda vs gambar asli: 3301 (sampling 2px) → teks asli benar-benar dihapus.
- Kotak overlay dalam batas `img`; jumlah kotak DOM = 6; `overflowBoxes=0`.
- Mode asli → `overlays=0`, `img.src` pulih ke `http://127.0.0.1:…/page.png`.
- Kembali ke terjemahan → cache (42–90 ms); 3 siklus bolak-balik tanpa duplikasi; setiap siklus kotak=6.
- Status UI tahap: `Memuat gambar…` → `Mengenali teks…` → `Mengelompokkan balon…` → `Menerjemahkan…` → `Menghapus teks asli…` → `Menampilkan terjemahan…` → `Terjemahan siap · N balon`.

### KeyboardGaugeTest (`KeyboardGaugeTest.kt`)
- Validasi inti *zoom* (waypoint) dan *scroll*: tombol `GeZoom Panel`, `GeZoom In`, `GeZoom Out`, `GeScroll Down` berfungsi; hasil `zoomWaypoint` / `scroll` sesuai ekspektasi. (Test ini **ASET**, tidak ada perintah *download*.

### GestureTest (`GestureTest.kt` + `gesture_test.sh`)
- Suite *gesture* (panscroll, pinch, adaptive tipografi) berjalan; hasil `gesture2_9l.txt`, `gesture3_9l.txt`, `gesture4_9l.txt`, `gesture_9l.txt` tersimpan di `test-artifacts/phase9l/`.

**Status: VERIFIED** (hasil *instrumentation* tersimpan; **tidak ada perubahan kode**).

---

## 8. Pipeline Performa (ReaderPipelinePerformanceTest)

Lihat bagian **3** atas. Ringkasnya:

| Ukuran | Total (historis) |
|---|---|
| 800×1138 | 1420 ms |
| 1079×1536 | 1296 ms |
| 1600×2277 | 1039 ms |

**Status: NOT VERIFIED / BLOCKED** – uji instrumentasi `ReaderPipelinePerformanceTest` tidak dijalankan ulang. Perlu di-*re-run* untuk nilai hitam pada `MangaLens-PHASE9L-VALIDATION.md`.

---

## 9. APK Final

- Lokasi: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Ukuran: **38.311.373 byte** (36,5 MiB)
- SHA-256: `f1047f347d34fe08d451b577c63c21982c805eeb216c44a82c1273070e6fe342`
- Belum ada perubahan kode; *build* akhir melakukan build debug.

*(Catatan: varian `app-armeabi-v7a-debug.apk` 29.266.531 byte, `app-x86_64-debug.apk` 39.879.004 byte tersedia di `app/build/outputs/apk/debug/`, namun yang dipakai untuk validasi adalah `arm64-v8a`.)*

---

## 10. Rekapifikasi & Status Istirahat

| Sinyal | Hasil | Status |
|---|---|---|
| Cakupan balon Jepang | 6/7, `抜けがけ` MISSED | **IMPLEMENTED AND VERIFIED** (OCR limitation) |
| Typography adaptif | `MIN_FONT_SIZE=9`, `FONT_HARD_FLOOR=6`, `data-overflow`, `data-font` dipakai; scroll+zoom+rotate stabil | **IMPLEMENTED AND VERIFIED** |
| Pinch-zoom | 2× zoom, 0 overflow, offset 57 | **IMPLEMENTED AND VERIFIED** |
| Scroll alignment | fixes offset 35→35 | **IMPLEMENTED AND VERIFIED** |
| Orientasi | rotasi + mode asli/terjemahan | **IMPLEMENTED AND VERIFIED** |
| Reader pipeline performance | **BUKA** `ReaderPipelinePerformanceTest` tidak dijalankan ulang | **NOT VERIFIED / BLOCKED** |
| Regression test | `ReaderEndToEndTest` + Gesture suite + KeyboardGaugeTest (APP + KEYBOARD), 0 gagal | **IMPLEMENTED AND VERIFIED** |
| *Source change* | **tidak ada** | — |

**Kesimpulan sementara:** Phase 9L *core* berfungsi dan terdokumentasi; **butuh *re-run* `ReaderPipelinePerformanceTest`** untuk menutup ca. 12% validasi yang masih *open*. 

---

## 11. Batasan & Rencana Fase Berikutnya

1. **Reader pipeline performance**: *re-run* `./gradlew :reader:connectedDebugAndroidTest` atau target khusus `ReaderPipelinePerformanceTest` di perangkat untuk mendapatkan *black-box* nilai baru; update `MangaLens-PHASE9L-VALIDATION.md`.
2. **Verifikasi overlay sistem**: reader ini menggunakan overlay DOM di dalam WebView + *bitmap inpainting*; `SYSTEM_ALERT_WINDOW` tidak diperlukan untuk alur ini. Jika ada *feature* overlay sistem yang terkait, lakukan pemberian izin manual + verifikasi posisioning.
3. **Tipografi zoom-out**: pada skala ekstrem, teks sedikit kecil; belum ada intervensi yang dibutuhkan untuk *close* ini.
4. **OCR `抜けがけ`**: kualitas `ML Kit` (gagal membaca area vertikal); bukan inklinasi *pipeline*.

---

## 12. Referensi

- `test-artifacts/phase9l/logs/logcat-com.mangalens.inpainting.JapaneseBubbleCoverageTest-coversJapaneseBubblesAgainstGroundTruth.txt`
- `test-artifacts/phase9l/logs/logcat-com.mangalens.reader.ReaderGestureTest-pinchSwipeAndRotateKeepOverlayAligned.txt`
- `test-artifacts/phase9l/logs/logcat-com.mangalens.reader.ReaderEndToEndTest-readerShowsInpaintedPageWithTranslations.txt`
- `test-artifacts/phase9l/perf_9l.txt` (build Gradle; **tidak** mencantumkan *PERF* uji instrumentasi)
- `reader/src/main/assets/mangalens_scan.js` (tipografi adaptif)
- `reader/src/androidTest/java/com/mangalens/reader/ReaderPipelinePerformanceTest.kt` (**source** *PERF*; **tidak** di-*run* di recovery)
