# MangaLens — Phase 9M Validation

**Fokus:** validasi *Floating Bubble & Cross-App Translation* — alur lintas-aplikasi dari izin overlay, capture layar, OCR, terjemahan, inpainting, koordinat, dan lifecycle.

**Status aktual: PARTIAL** — 5/5 floating test unit PASS di perangkat nyata (RMX3561, Android 14); **alur end-to-end lintas-aplikasi (Chrome → bubble → capture → OCR → terjemahan → inpainting → overlay koordinat) BELUM DIVERIFIKASI di perangkat nyata.**

---

## 1. Perangkat / Konektivitas

| Item | Nilai |
|---|---|
| Serial ADB | `X8DMKBIBXOY5W4UW` → `device` |
| Model | RMX3561 |
| Rilis | 14 (Android 14, arm64-v8a) |
| APK | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Ukuran | 38,311,373 byte |
| SHA-256 | `f1047f347d34fe08d451b577c63c21982c805eeb216c44a82c1273070e6fe342` |

---

## 2. Test Floating — Hasil Aktual (perangkat RMX3561)

Hasil berjalan melalui `./gradlew :floating:connectedDebugAndroidTest` pada 2026-11-25 (3m 32s).

### 2.1 `_floating-.xml` (ringkasan)

```xml
<testsuite name="com.mangalens.floating.BubbleOverlayRenderTest"
           tests="5" failures="0" errors="0" skipped="0" time="4.328"
           timestamp="2026-11-25T11:34:14" hostname="localhost">
  <testcase name="rendersRealJapaneseTranslationsOnMangaPage" classname="com.mangalens.floating.BubbleOverlayRenderTest" time="4.02" />
  <testcase name="placementDoesNotDriftWithImageScale" classname="com.mangalens.floating.BubbleOverlayRenderTest" time="0.0" />
  <testcase name="overlayPermissionStateMatchesSystemSettings" classname="com.mangalens.floating.Phase9MMobileCaptureTest" time="0.004" />
  <testcase name="coordinateMappingStaysInsideScreenAtTwoScales" classname="com.mangalens.floating.Phase9MMobileCaptureTest" time="0.007" />
  <testcase name="emptyBubbleListDoesNotTouchOverlay" classname="com.mangalens.floating.Phase9MMobileCaptureTest" time="0.003" />
</testsuite>
```

### 2.2 Test-case detail

| No. | Test | Kelas | Status | Waktu |
|---|---|---|---|---|
| 1 | `rendersRealJapaneseTranslationsOnMangaPage` | `BubbleOverlayRenderTest` | PASS | 4.02 s |
| 2 | `placementDoesNotDriftWithImageScale` | `BubbleOverlayRenderTest` | PASS | 0.000 s |
| 3 | `overlayPermissionStateMatchesSystemSettings` | `Phase9MMobileCaptureTest` | PASS | 0.004 s |
| 4 | `coordinateMappingStaysInsideScreenAtTwoScales` | `Phase9MMobileCaptureTest` | PASS | 0.007 s |
| 5 | `emptyBubbleListDoesNotTouchOverlay` | `Phase9MMobileCaptureTest` | PASS | 0.003 s |

**Total: 5 PASS / 0 FAIL / 0 SKIPPED / 0 ERROR — BUILD SUCCESSFUL**

### 2.3 Logcat per test-case

Logcat hasil di `floating/build/outputs/androidTest-results/connected/debug/RMX3561 - 14/logcat-*.txt`:
- `logcat-com.mangalens.floating.BubbleOverlayRenderTest-rendersRealJapaneseTranslationsOnMangaPage.txt`
- `logcat-com.mangalens.floating.BubbleOverlayRenderTest-placementDoesNotDriftWithImageScale.txt`
- `logcat-com.mangalens.floating.Phase9MMobileCaptureTest-overlayPermissionStateMatchesSystemSettings.txt`
- `logcat-com.mangalens.floating.Phase9MMobileCaptureTest-coordinateMappingStaysInsideScreenAtTwoScales.txt`
- `logcat-com.mangalens.floating.Phase9MMobileCaptureTest-emptyBubbleListDoesNotTouchOverlay.txt`

Semua logcat menunjukkan pematangan berjalan dengan exit 0, tanpa error crash.

---

## 3. Fitur lintas-aplikasi (cross-app E2E) — Status

> **Belum diverifikasi di perangkat nyata.** Sisa P0 berikutnya: aplikasi lintas-aplikasi nyata (Chrome → overlay → capture → OCR → terjemahan → inpainting → koordinat → overlay perbarui).

| Fitur | Status | Bukti | Kekurangan |
|---|---|---|---|
| Overlay permission (CAN_DRAW_OVERLAYS) | IMPLEMENTED AND VERIFIED | `Phase9MMobileCaptureTest.overlayPermissionStateMatchesSystemSettings` | Tidak ada verifikasi manual persetujuan overlay di perangkat |
| TYPE_APPLICATION_OVERLAY | IMPLEMENTED AND VERIFIED | Manifest `floating/AndroidManifest.xml` + source `BubbleOverlay` | Tidak ada screenshot bubble di atas Chrome |
| Floating bubble UI | IMPLEMENTED BUT NOT VERIFIED | Source: `BubbleOverlay` drag | Tidak ada E2E |
| Drag and dismiss | IMPLEMENTED BUT NOT VERIFIED | Source: `attachTouch` | Tidak ada uji perangkat |
| MediaProjection consent | IMPLEMENTED BUT NOT VERIFIED | Source: `requestProjection()` | Tidak ada capture aplikasi lain |
| Foreground service | IMPLEMENTED BUT NOT VERIFIED | Source: `FloatingTranslateService` | Tidak ada uji lifecycle |
| Screen capture | IMPLEMENTED BUT NOT VERIFIED | Source: `ScreenCapture` (ImageReader + VirtualDisplay) | Tidak ada screenshot aplikasi lain |
| ImageReader lifecycle | IMPLEMENTED BUT NOT VERIFIED | Source: Android 14 rule | Tidak ada uji lifecycle |
| VirtualDisplay cleanup | IMPLEMENTED BUT NOT VERIFIED | Source: `ScreenCapture.close()` | Tidak ada uji cleanup |
| OCR integration | IMPLEMENTED BUT NOT VERIFIED | Source: `MangaPageTranslator` (4+AUTO) | Tidak ada validasi OCR dari capture nyata |
| Translation integration | IMPLEMENTED BUT NOT VERIFIED | Source: `FloatingTranslate` → OCR → translation | Tidak ada testing live |
| Inpainting integration | IMPLEMENTED BUT NOT VERIFIED | Source: `MangaPageTranslator` | Tidak ada bukti inpainting dari capture |
| Coordinate mapping | IMPLEMENTED AND VERIFIED | `Phase9MMobileCaptureTest.coordinateMappingStaysInsideScreenAtTwoScales` | Tidak ada mapping dari screenshot nyata |
| Translation overlay | IMPLEMENTED BUT NOT VERIFIED | Source: `BubbleOverlay.showTranslations` | Tidak ada overlay dari capture |
| Browser interaction | NOT IMPLEMENTED | — | Akummular browser lain tidak diperiksa |
| Rotation | NOT IMPLEMENTED | — | Tidak ada uji rotation |
| Resource management | IMPLEMENTED BUT NOT VERIFIED | Source: cleanup di `ScreenCapture.close()` | Tidak ada uji memory/leak nyata |
| Real device E2E (full) | NOT IMPLEMENTED / BLOCKED | — | P0 berikutnya |

---

## 4. Catatan kejujuran

1. Uji ini menggunakan `RecordingWindowManager` palsu + `FakeWindowContext` (seperti `BubbleOverlayRenderTest.kt`). Permission overlay sebenarnya **tidak bisa** diberikan melalui instrumentasi tanpa interaksi pengguna.
2. Koordinat terpecaya pada skala dua (dari teks byte, bukan dari screenshot Chrome nyata). Perlu verifikasi dari screenshot Chrome.
3. Uji end-to-end lintas-aplikasi (Chrome → bubble → capture → OCR → terjemahan → inpainting → koordinat) belum pernah dijalankan di perangkat nyata.
4. *Gradle* tidak berada di PATH. Gunakan `./gradlew` (Gradle 8.9, Kotlin 1.9.23).

---

## 5. Deliverables

- **Arti baru:** `docs/HANDOFF-AGENT-MANGALENS.md` — panduan serah terima lengkap.
- **Laporan Phase 9M:** `MangaLens-PHASE9M-VALIDATION.md` (ini).
- **Log cat:** `test-artifacts/phase9m/logcat-20261125.txt` + `floating/build/outputs/androidTest-results/...`.
- **Validasi unit:** `floating/build/outputs/androidTest-results/connected/debug/TEST-RMX3561-...-_floating-.xml`.
- **Test source:** `floating/src/androidTest/java/com/mangalens/floating/Phase9MMobileCaptureTest.kt`.

---

## 6. Langkah berikutnya (P0 dep?)

1. Tentukan siapa yang akan melanjutkan Phase 9M.
2. Lakukan real-device E2E: buka Chrome, buka halaman manga, klik bubble, beri otorisasi capture, tangkap screenshot aplikasi lain.
3. Verifikasi koordinat overlay dari screenshot Chrome yang sebenarnya.
4. Jalankan full floating regression.
5. Setelah alur lintas-aplikasi berhasil di perangkat, tulis final Phase 9M report dengan semua metode semua tepat.
