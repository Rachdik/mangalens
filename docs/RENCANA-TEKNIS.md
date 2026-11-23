# MangaLens — Rencana Teknis (Tahap 2–10)

Status dokumen: rencana, belum ada implementasi.
Audit lingkungan: selesai (lihat bagian 1). Proyek di folder ini masih kosong, jadi rencana ini adalah
baseline pembangunan, bukan rencana perubahan kode lama.

Legenda status:

- `[SIAP]` keputusan sudah jelas dan berisiko rendah, bisa langsung dieksekusi.
- `[RISIKO]` ada ketidakpastian teknis nyata; hasil belum bisa dijanjikan sebelum diukur/diuji.
- `[BLOKIR]` butuh keputusan atau kredensial dari pengguna.

---

## 1. Ringkasan audit (dasar keputusan)

| Aspek | Kondisi aktual | Konsekuensi |
|---|---|---|
| Kode proyek | 0 file Kotlin/Gradle/manifest, bukan repo git | Dibangun dari nol |
| JDK | Temurin 17.0.20.1, `JAVA_HOME` diset | Aman untuk AGP 8.5.x |
| Gradle | tidak di PATH, distribusi **8.9** sudah ter-cache di `~/.gradle/wrapper/dists` | Pakai wrapper 8.9, build pertama tetap butuh internet (Kotlin plugin belum ter-cache) |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`, platform **hanya android-34**, build-tools **34.0.0** | `compileSdk = 34` wajib; naik ke 35+ perlu install platform |
| `ANDROID_HOME` | kosong | wajib `local.properties` → `sdk.dir` |
| Perangkat | tidak ada device; AVD `Medium_Phone` (system image 37.0) ada | uji manual di emulator |
| AGP | 8.5.2 ter-cache | pin 8.5.2 |
| Maven | `dl.google.com` dan `repo1.maven.org` 200 | dependency bisa diunduh |

Versi dependency di bawah sudah diverifikasi ada di repositori (HTTP 200), bukan tebakan.

---

## 2. Matriks modul dan dependency

Gradle multi-module, Kotlin, **MVVM dengan ViewBinding (tanpa Compose)**.

Alasan tanpa Compose: Compose Compiler harus seversi dengan Kotlin, dan artifact Compose belum ada di
cache; ini menambah risiko build pertama tanpa manfaat langsung. Overlay floating juga lebih sederhana
dengan `WindowManager` + View biasa. `[SIAP]`

```
app ──► reader, ui, floating, imageprocessing, storage, core
imageprocessing ──► ocr, translation, inpainting, storage, core
reader ──► imageprocessing, storage, core, ui
floating ──► core, storage            (navigasi lewat core.Navigator, diimplementasi di app)
ocr ──► core
translation ──► core, storage
inpainting ──► core
storage ──► core
core ──► (tidak bergantung modul lain)
```

Aturan arah dependency: tidak boleh ada siklus. `app` adalah satu-satunya modul yang tahu Activity.

| Modul | Isi | Dependency (koordinat) |
|---|---|---|
| `core` | model domain (`MangaImage`, `TextBlock`, `TranslatedBlock`, `ReadingDirection`), `Result`/`AppError`, `Dispatchers` provider, logger, `ImageHasher` (dHash), kontrak `SettingsRepository`, `TranslationProvider` **interface**, `Navigator` interface | `kotlinx-coroutines-android:1.8.1`, `androidx.core:core-ktx:1.13.1` |
| `ui` | resource bersama: tema `Theme.MangaLens`, warna, dimensi (tanpa logika) | `api("androidx.appcompat:appcompat:1.7.0")` |
| `storage` | Room (`PageEntity`, `ImageEntity`, `OcrEntity`, `TranslationEntity`, `CacheEntryEntity`), DAO, `MangaLensDatabase`, DataStore settings, `ApiKeyStore` (Keystore AES-GCM), `CacheManager` (LRU disk), `FileProvider` paths | `room-runtime:2.6.1`, `room-ktx:2.6.1`, kapt `room-compiler:2.6.1`, `datastore-preferences:1.1.1`, `androidx.security` **tidak dipakai** (deprecated) |
| `ocr` | ML Kit wrapper per-skrip, deteksi bahasa via script-range + ML Kit Language ID (opsional), pengelompokan baris → blok, **urutan baca** | `text-recognition:16.0.1`, `text-recognition-japanese:16.0.1`, `text-recognition-korean:16.0.1`, `text-recognition-chinese:16.0.1` |
| `translation` | `ProviderRegistry`, `GoogleFreeProvider` (eksperimental), `GoogleCloudProvider` (API key), `OpenAiCompatibleProvider` (baseUrl+model+key), prompt builder, batching, cache koordinasi | `okhttp:4.12.0`, `kotlinx-serialization-json` atau parser manual (lihat 6.4), `storage`, `core` |
| `inpainting` | mask builder (dilasi), deteksi bubble, diffusion solver (Jacobi multiresolusi), compositing | `core` saja (murni Kotlin/Bitmap) |
| `imageprocessing` | decode + orientasi (`ExifInterface:1.3.7`), downscale, `TranslationPipeline` (orkestrasi ocr→translation→inpainting→render), renderer teks, generator overlay | `ocr`, `translation`, `inpainting`, `storage`, `core` |
| `reader` | `ReaderActivity` (WebView), JS bridge, `ImageExtractor`, `NativeImageFetcher` (OkHttp + Referer + Cookie), `LazyLoadObserver`, overlay injection via `WebViewAssetLoader` (`webkit:1.11.0`), `ReaderViewModel` | `imageprocessing`, `storage`, `core`, `okhttp:4.12.0`, `webkit:1.11.0` |
| `floating` | `FloatingService` (foreground), bubble view + drag, panel, izin overlay, notifikasi | `core`, `storage` |
| `app` | `MainActivity` (URL input + riwayat), `ImageTranslateActivity` (galeri/sharesheet/Intent), `AppContainer` (DI manual), navigasi, tema | semua modul UI + `appcompat:1.7.0`, `activity-ktx:1.9.3`, `lifecycle-viewmodel-ktx:2.8.7`, `coil:2.7.0`, `work-runtime-ktx:2.9.1` (pembersihan cache) |

DI: `AppContainer` manual (tanpa Hilt) — menghindari kapt/ksp tambahan dan mempercepat build. `[SIAP]`

Build config: AGP **8.5.2**, Gradle wrapper **8.9**, Kotlin **1.9.24**, `compileSdk 34`, `targetSdk 34`,
`minSdk 26`, JVM target 17, `viewBinding = true`, `buildFeatures { buildConfig = true }`.
Room memakai **kapt** (bukan KSP) agar tidak ada pin versi compiler tambahan.

Product flavor OCR: `full` (model bundled) dan `slim` (unbundled) — lihat 5.2.

---

## 3. Tahap 2 — URL input + WebView reader

Tujuan: pengguna memasukkan URL, halaman terbuka di WebView internal, navigasi antar halaman dan
history berfungsi.

Rancangan:

- `MainActivity`: `EditText` URL + tombol Buka + daftar riwayat (Room/DataStore), validasi skema
  (`http`/`https` saja), toast untuk URL invalid.
- `ReaderActivity`: `WebView` + `WebViewClient`/`WebViewChromeClient`, `WebSettings`:
  `javaScriptEnabled`, `domStorageEnabled`, `loadsImagesAutomatically`, `useWideViewPort`,
  `loadWithOverviewMode`, `mixedContentMode = COMPATIBILITY_MODE`,
  `setAcceptThirdPartyCookies(webView, true)`.
- Normalisasi URL: tambahkan `https://` bila tidak ada skema; simpan URL halaman (bukan hanya input)
  untuk dipakai sebagai `Referer`.
- Navigasi: `canGoBack`/`goBack`, tombol back hardware, progress bar, `onPageStarted`/`onPageFinished`.
- User-Agent: default WebView (dipakai ulang saat fetch gambar → konsisten dengan situs).
- Cookie: `CookieManager.getInstance().setAcceptCookie(true)` + `flush()`.

Kriteria selesai: URL valid membuka halaman; back/forward konsisten; progress tampil; tidak crash saat
rotasi (WebView disimpan/di-restore dengan `saveState`).

Risiko: `[RISIKO]` situs dengan anti-bot/Cloudflare menampilkan challenge di WebView; tidak ada solusi
otomatis yang dijanjikan — pengguna menyelesaikan challenge secara manual.

---

## 4. Tahap 3 — Ekstraksi gambar dari halaman web

Ini bagian paling menentukan. **Tidak memakai screenshot** dan **tidak memakai canvas**
(canvas akan ter-taint untuk gambar lintas-origin, dan hasil screenshot merusak kualitas).

### 4.1 Deteksi di DOM (JS, di-inject)

`evaluateJavascript` saat `onPageFinished` + `MutationObserver` + `IntersectionObserver`:

1. Kandidat: `img`, `picture > source`, elemen dengan `data-src` / `data-lazy-src` / `data-original` /
   `data-url` / `data-srcset`, `srcset`, dan `background-image` pada elemen besar.
2. Filter: `naturalWidth >= 300` ATAU `rect.width >= 200`; aspect ratio `h/w >= 1.2` untuk strip
   webtoon atau luas area besar; buang elemen `position: fixed`, ikon, iklan, elemen < 100×100.
3. Ambil per kandidat: `url` (absolut via `new URL(src, location.href).href`), `rect` (x, y, w, h),
   `naturalW/H`, `docOrder = Math.round(rect.top + scrollY)`.
4. Dedupe: normalisasi URL (buang parameter resize seperti `?w=`, `?resize=`, hash) — simpan path unik.
5. Kirim ke Kotlin via `@JavascriptInterface` `MangaLensBridge.onImages(json)` (throttle 300 ms,
   hanya kirim delta URL baru) dan `onPageReady(url)`.

### 4.2 Lazy load dan scrolling panjang

- `IntersectionObserver` (rootMargin 1200px) + `MutationObserver` pada `document.body`.
- Kotlin memicu `window.__mangalensScan()` yang di-debounce pada `onScrollChanged`, agar gambar yang
  baru muncul saat scroll ikut terdeteksi.
- Urutan: sort stabil berdasarkan `docOrder`, tie-break `left`. Gambar yang dimuat belakangan tidak
  mengubah urutan karena `docOrder` absolut, bukan urutan deteksi.
- Batas chapter: bila `docOrder` turun tajam antar batch (mis. > 500px mundur) sementara URL tidak
  berubah, tandai `chapterId` baru (heuristik). `[RISIKO]` deteksi chapter otomatis tidak selalu benar.

### 4.3 Anti-hotlink, cookie, dan user-agent

Fetch byte **native** dengan OkHttp (bukan di WebView, bukan canvas):

```
GET <imageUrl>
User-Agent: <WebSettings.getDefaultUserAgent()>   (atau UA halaman)
Referer: <pageUrl>                                 ← kunci anti-hotlink
Accept: image/avif,image/webp,image/*,*/*
Accept-Language: id-ID,id;q=0.9,en;q=0.8
Cookie: CookieManager.getInstance().getCookie(imageUrl)   ← sesi situs ikut
```

Rantai fallback saat 403/401:

1. ulangi dengan `Referer` = origin halaman (`scheme://host/`)
2. ulangi tanpa `Referer`, tetap dengan cookie
3. fallback konteks browser: `fetch(imageUrl, {credentials:'include'})` di JS → `FileReader` base64 →
   kirim ke bridge (hanya jika ukuran < ~8 MB; turunkan resolusinya bila perlu)

Lain-lain: timeout 20 s, retry eksponensial 3× untuk 429/5xx, `Content-Type` harus `image/*`,
batas 20 MB/gambar, konkurensi 2 fetch. URL `blob:`/`data:` hanya bisa lewat fallback browser.
`[RISIKO]` situs dengan signature URL kedaluwarsa atau DRM tidak bisa diambil; ini dilaporkan sebagai
keterbatasan, bukan "berhasil".

### 4.4 Penyimpanan hasil

Byte asli → `cacheDir/source/<dHash>.webp` + baris `ImageEntity` (url, pageId, docOrder, dHash, w/h,
ukuran). Key cache = `dHash` (64-bit, 9×8 grayscale) + URL kanonik → mencegah unduh/OCR ulang.

Kriteria selesai: pada situs uji, semua gambar chapter terdeteksi dan terurut benar; gambar lazy-load
ikut terambil setelah scroll; gambar tidak terunduh dua kali (dibuktikan lewat log & cache hit).

Risiko: `[RISIKO]` situs dengan gambar di dalam `<iframe>` lintas-origin atau di shadow DOM tertutup;
struktur DOM yang berubah-ubah antar situs.

---

## 5. Tahap 4 — OCR dan deteksi area teks

### 5.1 Pipeline

1. Decode byte → `Bitmap` (downscale sisi terpanjang ≤ 2048 px, `inSampleSize`).
2. Pilih recognizer berdasarkan skrip (heuristik regex pada hasil OCR Latin dulu, atau pilihan bahasa
   manual di Settings): Latin / Jepang / Korea / China.
3. ML Kit mengembalikan `Text.Block` → `Line` → `Element` dengan `boundingBox`.
4. Gabungkan `Line` menjadi **blok dialog**: clustering spasial (jarak vertikal < 1.2× tinggi baris,
   irisan horizontal) → satu blok = satu bubble.
5. **Urutan baca**: untuk Jepang/China vertikal → kolom dari kanan ke kiri, dalam kolom atas ke bawah;
   untuk horizontal → atas ke bawah, kiri ke kanan. Urutan disimpan sebagai `block.order` dan
   diteruskan ke prompt terjemahan sebagai konteks.
6. Simpan hasil ke `OcrEntity` (JSON: box, teks, skor) sehingga tidak OCR ulang.

### 5.2 ML Kit: bundled vs unbundled

| | Bundled (`com.google.mlkit:text-recognition-*:16.0.1`) | Unbundled (`com.google.android.gms:play-services-mlkit-text-recognition*`) |
|---|---|---|
| Model | ikut di APK | diunduh via Play Services |
| APK | besar (+ puluhan MB untuk 4 skrip) | kecil |
| Offline | langsung | butuh unduhan awal + Play Services |
| Perangkat tanpa GMS | jalan | **gagal** |

**Keputusan:** default flavor **`full` = bundled** untuk Latin + Jepang + Korea + China, karena target
pengguna adalah pembaca manga luar negeri dan hasil harus jalan tanpa menunggu unduhan model.
Flavor `slim` (unbundled) disediakan sebagai opsi jika ukuran APK jadi masalah. `[SIAP]` untuk mekanisme;
`[RISIKO]` untuk ukuran APK (perlu diukur nyata di Tahap 10).

Catatan kejujuran: ML Kit tidak punya mode "teks vertikal"; akurasi tategaki Jepang lebih rendah dan
sering salah segmentasi kolom. Mitigasi: clustering kolom berbasis koordinat x, bukan urutan ML Kit.
Tetap ditandai `[RISIKO]` sampai diuji pada halaman Jepang nyata.

Kriteria selesai: pada gambar uji 4 bahasa, teks terdeteksi dan blok terurut; hasil OCR tersimpan dan
dipakai ulang dari cache.

---

## 6. Tahap 5 — Translation provider

### 6.1 Kontrak

```kotlin
interface TranslationProvider {
    val id: String                 // "google_free", "google_cloud", "openai_compatible"
    val displayName: String
    suspend fun translate(batch: List<TextBlock>, ctx: PageContext): TranslateResult
    suspend fun validateConfig(): ValidationResult   // tombol "Tes koneksi"
}
```

`batch` per bubble, `PageContext` berisi bahasa sumber, judul seri, ringkasan adegan 1–2 kalimat, dan
teks bubble tetangga untuk menjaga konteks percakapan. `TranslateResult` = `Success(mapIdToText)` atau
`Failure(AppError)` (network, auth, rateLimit, invalidResponse) — tidak ada exception bocor ke UI.

### 6.2 Provider

1. **`GoogleFreeProvider` (eksperimental, default mati).** Endpoint web `translate_a/single` tanpa API
   key. `[RISIKO]` tidak resmi, bisa diblokir/berubah kapan saja, ada rate limit. Harus diberi label
   "Eksperimental" di UI, tidak dijadikan default diam-diam.
2. **`GoogleCloudProvider`.** `POST https://translation.googleapis.com/language/translate/v2` dengan
   `key` dari Settings. Stabil, berbayar/kuota, `[BLOKIR]` butuh API key pengguna.
3. **`OpenAiCompatibleProvider`.** `baseUrl` + `model` + `apiKey` dapat dikonfigurasi → mencakup
   OpenAI, OpenRouter, Groq, hingga server lokal (Ollama/LM Studio via LAN). Ini jalur utama untuk
   "AI Translator" dan "provider AI lain". `[BLOKIR]` butuh key pengguna.

### 6.3 Gaya terjemahan (wajib)

System prompt mengunci: bahasa Indonesia **natural dan informal** (gue/lo atau aku/kamu sesuai nada
karakter), pertahankan emosi, nada, dan gaya bicara; jangan memformalkan dialog; jangan menambah atau
mengurangi makna; jangan menerjemahkan onomatope menjadi kalimat kaku (biarkan atau beri padanan
singkat). Output **JSON ketat** `{"items":[{"id":"b1","text":"..."}]}` + `response_format` bila
didukung; parser menolak output non-JSON dan melakukan satu kali repair attempt.

### 6.4 Batching, cache, dan kontrol biaya

- Satu request per gambar (semua bubble sekaligus) → hemat token dan konteks antar dialog terjaga.
- Cache kunci = `sha256(teks + providerId + model + targetLang + promptVersion)`; disimpan di
  `TranslationEntity`, umur tak terbatas, dibersihkan bersama cache gambar.
- Batas: maksimal N karakter per request (mis. 4000), timeout 30 s, retry 2× untuk 429/5xx.
- Biaya: tampilkan estimasi token/karakter di panel sebelum menerjemahkan. `[SIAP]`

### 6.5 Penyimpanan API key

- **Tidak** di `BuildConfig`, tidak di kode, tidak di Room, tidak di `SharedPreferences` polos.
- `storage.ApiKeyStore`: Android Keystore, alias `mangalens_key_<providerId>`, AES/GCM/NoPadding 256-bit,
  IV 12 byte acak per enkripsi, ciphertext+IV disimpan base64 di `SharedPreferences("secrets", MODE_PRIVATE)`.
  Key tidak pernah bisa diekspor (`setUserAuthenticationRequired(false)` untuk v1).
- Field Settings `inputType="textPassword"` + tombol tampilkan; nilai tidak pernah ditulis ke log,
  pesan error di-redact, dan `allowBackup="false"` + `dataExtractionRules` mengecualikan `secrets`.
- Setiap provider punya key sendiri; `validateConfig()` dipakai tombol "Tes koneksi" sebelum disimpan.

Kriteria selesai: tiga provider terdaftar dan bisa dipilih; key tersimpan terenkripsi dan bertahan
setelah restart; hasil terjemahan informal; cache mencegah panggilan ulang teks yang sama.

Risiko: `[RISIKO]` kuota/biaya dan kualitas gaya bahasa berbeda antar model; `[RISIKO]` Google gratis
berhenti bekerja.

---

## 7. Tahap 6 — Inpainting dan penggantian teks

Tanpa native lib dan tanpa OpenCV. Semua di Kotlin murni di atas `Bitmap` + `Canvas`.

### 7.1 Mask

1. Mask awal = bounding box tiap baris teks, diperluas 6% tinggi + padding 4 px.
2. **Deteksi bubble**: luminance → threshold Otsu adaptif per tile → connected components → ambil
   region hampir putih dengan solidity tinggi; union dengan mask teks. Menangkap bubble yang teksnya
   gagal ter-OCR.
3. Dilasi morfologi radius `min(0.15 × min(w,h), 24 px)` agar sisa anti-aliasing huruf ikut tertutup.
4. Mask disimpan per blok, bukan seluruh gambar → piksel di luar mask **tidak disentuh** (ilustrasi utuh).

### 7.2 Isi area (inpainting)

Dua lapis, dipilih otomatis per region:

1. **Cepat (default):** ambil median warna cincin batas mask, isi region dengan gradien halus + noise
   ringan agar tidak terlihat seperti tambalan datar.
2. **Diffusion (kualitas lebih baik):** selesaikan Laplace pada region mask dengan iterasi Jacobi
   multiresolusi (downsample 1/4 → 120 iterasi → upsample bilinear), lalu feather 3 px di tepi.
   Biaya kecil karena hanya region kecil (~200×300 px) yang diproses.
3. **Patch-based (PatchMatch)** untuk area bertekstur/ SFX di atas ilustrasi: **ditunda**, ditandai
   `[RISIKO]` — implementasi berat, kualitas belum dijamin, tidak dijanjikan di v1.

### 7.3 Render teks

- Ukur dengan `Paint.breakText`/`StaticLayout`, auto-shrink font sampai muat (min 10 sp), line-height
  1.1×, warna hitam dengan outline putih tipis untuk kontras.
- Kolom vertikal Jepang: gambar glyph per baris (satu karakter per baris) agar posisi cocok.
- Bubble yang tidak putih: gambar rounded-rect putih semi-opak di belakang teks.
- Teks Indonesia biasanya lebih panjang dari aslinya → prioritas: mengecilkan font, lalu menambah
  tinggi kotak ke bawah selama tidak menutupi bubble lain. `[RISIKO]` overflow teks pada bubble sangat
  kecil.

### 7.4 Menampilkan hasil tanpa merusak halaman

- Render hasil ke `cacheDir/translated/<hash>.webp`.
- `WebViewAssetLoader` (`https://appassets.androidplatform.net/translated/...`) → inject `<img>`
  ber-`position:absolute` di atas gambar asli pada koordinat `rect` yang sama, `pointer-events:none`,
  ikut bergulir bersama halaman.
- Fallback bila CSP situs memblokir: `data:` URL (ukuran dibatasi), atau overlay View di atas WebView
  pada posisi rect (perlu sinkronisasi scroll).
- Tidak ada gambar yang ditulis ke galeri.

Kriteria selesai: teks asli hilang di area mask, ilustrasi di luar mask identik piksel, terjemahan
terbaca dan posisinya sesuai bubble, hasil tetap menempel saat scroll.

Risiko: `[RISIKO]` inpainting pada area bertekstur kompleks; `[RISIKO]` CSP ketat memblokir injeksi;
`[RISIKO]` waktu proses per gambar (diukur di Tahap 9).

---

## 8. Tahap 7 — Floating bubble

- `FloatingService` foreground (`foregroundServiceType="specialUse"` + deklarasi properti untuk
  Android 14; `POST_NOTIFICATIONS` runtime), `START_STICKY`.
- Izin: `Settings.canDrawOverlays()` → `ACTION_MANAGE_OVERLAY_PERMISSION`; UI menjelaskan sebelum
  meminta. Tanpa izin, service berhenti dengan pesan jelas.
- Bubble: `WindowManager` `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, drag dengan
  `touchSlop`, snap ke tepi, ukuran 56 dp.
- Panel (expand): tombol **Buka Reader**, **Mulai/Stop terjemahan**, **Pengaturan penerjemah**
  (provider, bahasa, mode tampilan), status progres.
- Minimize = bubble mengecil; pipeline tetap jalan di service, bukan di Activity. Komunikasi state via
  `StateFlow` di `core` (singleton `TranslationBus`).
- Tap bubble → `Intent` ke `ReaderActivity` dengan `FLAG_ACTIVITY_NEW_TASK`.

Kriteria selesai: bubble tampil di atas aplikasi lain, bisa digeser, membuka reader, dan proses tetap
berjalan saat panel diminimalkan.

Risiko: `[RISIKO]` OEM (Xiaomi/Oppo/Samsung) mematikan foreground service; `[RISIKO]` perilaku
`specialUse` berbeda per OEM. Tidak ada jaminan lintas semua perangkat.

**Catatan implementasi aktual:** `foregroundServiceType="mediaProjection"` (bukan `specialUse`),
`START_STICKY`, `stopWithTask="false"`. Sejak perbaikan Tahap 5, hasil terjemahan **ditempel langsung
di atas balon asli** (bukan kartu daftar teks): latar view memakai warna balon hasil deteksi sehingga
teks sumber tertutup, lalu teks Indonesia ditulis di dalamnya dengan auto-shrink font.

Sesi tangkapan layar juga diubah menjadi satu `VirtualDisplay` + `ImageReader` permanen. Ini wajib
sejak Android 14: `createVirtualDisplay()` yang dipanggil berulang pada satu `MediaProjection` akan
melempar `SecurityException`, sehingga versi lama hanya bisa menangkap sekali (dan tampak "mati").
Izin yang dicabut sistem tidak lagi mematikan service — bubble tetap ada dan ketukan berikutnya
meminta izin baru lewat `MainActivity` (`EXTRA_REQUEST_PROJECTION`).

---

## 9. Tahap 8 — Image translator (galeri, sharesheet, Intent)

- Sumber: `PickVisualMedia` (Photo Picker), `ACTION_SEND` (`image/*`, `SEND_MULTIPLE`), `ACTION_VIEW`
  via `intent-filter`, dan `ACTION_PROCESS_TEXT` tidak relevan.
- `launchMode="singleTop"` + `onNewIntent` agar share berulang tidak menumpuk Activity.
- Semua input disalin ke `cacheDir/incoming/<uuid>`; `content://` dibaca via `ContentResolver` +
  `openInputStream`, orientasi dibaca dari `ExifInterface`.
- **Tidak** menulis ke `MediaStore`/galeri. Pembersihan: `WorkManager` periodik (harian) menghapus
  incoming > 24 jam + cache LRU melebihi batas.
- UI: gambar asli / hasil toggle, indikator progres per tahap (OCR → terjemah → render), tombol
  "Simpan" **manual** (opsional, hanya jika pengguna menekan; default tidak ada penulisan otomatis).

Kriteria selesai: kirim gambar dari Galeri/Files ke MangaLens menghasilkan terjemahan; tidak ada file
baru di galeri; memori tidak meledak pada gambar 12 MP.

Risiko: `[SIAP]` secara mekanisme; `[RISIKO]` hanya pada kualitas hasil untuk gambar non-manga
(foto, UI) yang deteksi bubble-nya tidak relevan.

---

## 10. Tahap 9 — Performa, cache, RAM

Anggaran dan mekanisme:

| Masalah | Mekanisme | Target |
|---|---|---|
| Proses ulang gambar sama | `dHash` + URL kanonik → cache `ImageEntity`/`OcrEntity`/`TranslationEntity` | 0 panggilan OCR/terjemahan berulang |
| Scroll tersendat | semua kerja di `Dispatchers.IO/Default`; WebView tidak pernah diblok; overlay di-inject async | tidak ada jank saat scroll |
| Bertahap | antrean per viewport, `Semaphore(2)` untuk fetch, `Semaphore(2)` untuk pipeline | gambar terlihat diterjemahkan lebih dulu |
| Batal saat scroll jauh | `Job.cancel()` bila gambar keluar viewport + 2 layar | CPU/RAM turun |
| RAM | decode `inSampleSize` ≤ 2048 px, maks 4 bitmap in-flight, recycle setelah render, `onTrimMemory` membersihkan cache memori | tidak OOM pada 30+ gambar |
| Cache disk | LRU di `cacheDir` (bukan `filesDir`), batas default 512 MB, prune otomatis | ukuran terkendali |
| Indikator | status per gambar (`queued/fetching/ocr/translating/rendering/done/failed`) di `StateFlow` → badge kecil | selalu ada umpan balik |

Pengukuran wajib: waktu per gambar (fetch/OCR/terjemah/render) dan memori puncak, dicatat di laporan
Tahap 9. Tanpa angka, klaim "dioptimalkan" tidak dibuat.

---

## 11. Tahap 10 — Pengujian dan APK

1. Unit test (JVM): `ImageHasher`, pengurutan blok, mask builder, prompt builder + parser JSON provider,
   normalisasi URL, eviction cache.
2. Instrumented test minimal: Room DAO, `ApiKeyStore` (enkripsi/dekripsi + restart).
3. Uji manual di emulator `Medium_Phone`: alur URL → webtoon panjang, galeri, sharesheet, floating.
4. Matriks uji per situs: webtoon vertikal (Indonesia/Inggris), manga Jepang, manhwa Korea, manhua China.
5. `./gradlew :app:assembleDebug :app:testDebugUnitTest` harus lulus; hasil APK dilaporkan path-nya.
6. `assembleRelease` **butuh keystore** yang belum ada → `[BLOKIR]` sampai pengguna menyediakan.

Laporan akhir memisahkan: **berfungsi / sebagian / belum**, beserta batasan nyata (situs anti-bot,
tategaki, patch-based inpainting, OEM background service).

---

## 12. Register risiko (ringkas)

| # | Risiko | Dampak | Mitigasi | Status |
|---|---|---|---|---|
| R1 | Anti-hotlink / Cloudflare / signature URL | gambar gagal diambil | rantai referer→cookie→fetch browser; lapor gagal eksplisit | `[RISIKO]` |
| R2 | Deteksi gambar bergantung struktur DOM situs | sebagian situs 0 gambar | beberapa selektor + heuristik ukuran; fallback manual "pilih elemen" nanti | `[RISIKO]` |
| R3 | Tategaki (teks vertikal Jepang) | urutan/akurasi OCR turun | clustering kolom berbasis koordinat | `[RISIKO]` |
| R4 | Ukuran APK bundled ML Kit 4 skrip | APK besar | flavor `slim` unbundled | `[RISIKO]`, diukur Tahap 10 |
| R5 | Google Translate gratis tidak resmi | provider mati sewaktu-waktu | label eksperimental, default AI/Cloud | `[RISIKO]` |
| R6 | Inpainting area bertekstur | sisa "hantu" teks | diffusion; PatchMatch ditunda | `[RISIKO]` |
| R7 | CSP situs memblokir injeksi `<img>` | hasil tidak tampil | fallback `data:` / overlay View | `[RISIKO]` |
| R8 | Foreground service dibunuh OEM | floating mati | notifikasi persisten + panduan pengaturan baterai | `[RISIKO]` |
| R9 | Biaya/kuota API pihak ketiga | terjemahan berhenti | cache, batas batch, estimasi biaya | `[RISIKO]` |
| R10 | API key bocor | penyalahgunaan | Keystore AES-GCM, no backup, no log | `[SIAP]` desain |
| R11 | Release APK tanpa keystore | tidak bisa distribusi | pengguna menyediakan keystore | `[BLOKIR]` |
| R12 | Situs DRM / canvas-only (tidak ada `<img>`) | tidak didukung | dilaporkan sebagai batasan | `[RISIKO]` |

---

## 13. Status pelaksanaan

| Tahap | Status | Bukti |
|---|---|---|
| 1 Audit | selesai | laporan audit |
| 2 Scaffold + URL/WebView | scaffold & build selesai; **belum diuji jalan** | `./gradlew :app:assembleDebug` BUILD SUCCESSFUL, `app-debug.apk` 3,6 MB. Uji runtime emulator belum selesai (emulator tidak selesai boot dan dihentikan), jadi belum ada bukti aplikasi berjalan tanpa crash |
| 3 Ekstraksi gambar | diimplementasikan; **belum diuji di device** | skrip `mangalens_scan.js`, `ImageScanSession`, `NativeImageFetcher`, `ImageStore`, panel “Gambar N”. Build lulus; belum ada bukti pada situs nyata |
| 4 OCR (ML Kit) | diimplementasikan; **belum diuji di device** | `ocr/MlKitOcrEngine.kt` memakai `text-recognition` + varian Jepang/Korea/China `16.0.1` (model bundled). Saat ini dipakai oleh jalur floating layar, belum oleh gambar reader |
| 5 Terjemahan | diimplementasikan (on-device); **belum diuji di device** | `translation/MlKitTranslationProvider.kt` (`com.google.mlkit:translate:17.0.3`); model id+ja/ko/zh/en diunduh sekali saat pertama dipakai (butuh internet sekali) |
| 6 Inpainting | sebagian: deteksi balon + tempel teks di posisi balon | `inpainting/BubbleDetector.kt` (clustering blok OCR, warna latar cincin, flood fill batas balon) dan `inpainting/MangaPageTranslator.kt`. Isi ulang **piksel** balon (diffusion/PatchMatch) belum ada; penghapusan teks dilakukan oleh latar view overlay berwarna balon |
| 7 Floating bubble + terjemahan layar | diimplementasikan; **belum diuji di device** | `floating/FloatingTranslateService.kt`, `BubbleOverlay.kt`, `ScreenCapture.kt`, `ScreenTranslator.kt`. Ketuk bubble → MediaProjection + OCR + deteksi balon + terjemah → teks ditempel di posisi balon; ketuk lagi = hapus; tekan lama = mati. Satu sesi VirtualDisplay permanen (aturan Android 14), provider terjemahan di-cache, bubble tidak mati saat izin rekam dicabut (minta izin ulang) |
| 8–10 | belum | — |

Modul yang benar-benar ada di `settings.gradle.kts`: `app, core, ui, storage, reader, floating, ocr,
translation, inpainting, imageprocessing` (10 modul). `ui` adalah modul ke-10 di luar daftar awal, hanya
berisi resource tema bersama agar `app` dan `reader` tidak menduplikasi style.

## 14. Urutan eksekusi

Tahap 2 → 3 harus selesai dan terbukti pada satu situs uji sebelum OCR (4) masuk, karena tanpa gambar
yang benar, semua tahap setelahnya tidak bisa dievaluasi. Tahap 5 dan 6 bisa dikerjakan setelah 4.
Tahap 7 dan 8 memakai pipeline yang sama, jadi dikerjakan setelah 6 stabil. Tahap 9 dan 10 terakhir.

Setiap tahap: build + test, lalu laporan file dibuat/diubah, error yang ditemukan, dan status fitur
(berfungsi / sebagian / belum).

---

## 15. Perbaikan masukan pengguna (APK Tahap 4)

Dua keluhan nyata dari uji perangkat Realme GT Neo 3 dan perbaikannya:

1. **Teks hampir tak terlihat di mode gelap perangkat.** Tema `Theme.AppCompat.DayNight` memakai
teks terang sementara `windowBackground` selalu terang → judul, hint, dan isi kolom URL tampak putih
di atas putih, sehingga URL hasil tempel seolah tidak masuk. Fix: `Theme.AppCompat.Light.NoActionBar`
plus `textColorPrimary/textColorSecondary` eksplisit dan `textColor`/`textColorHint` pada `EditText`.
2. **URL tidak bisa ditempel.** Ditambahkan tombol **Tempel** yang membaca `ClipboardManager`
(mengekstrak URL pertama dari teks clipboard), plus intent-filter `ACTION_VIEW` (http/https) dan
`ACTION_SEND` `text/plain` agar tautan bisa dibagikan dari browser langsung ke MangaLens.
3. **Tidak ada floating translator.** Tahap 7 dikerjakan lebih awal: bubble overlay + MediaProjection
+ ML Kit OCR + terjemahan on-device, dengan sumber bahasa (Jepang/Korea/China/Inggris) dipilih di
layar utama.

APK: `dist/MangaLens-tahap4-arm64-debug.apk` (arm64-v8a, ~36 MB). Split ABI dipakai karena native lib
ML Kit ±16 MB per ABI; tanpa split APK menjadi 112 MB.

---

## 16. Perbaikan masukan pengguna (APK Tahap 5)

Keluhan: aplikasi tidak mendeteksi balon pada manga; yang diinginkan adalah teks di dalam balon pada
gambar diganti langsung (bukan daftar hasil di floating app); dan floating app sering mati mendadak
saat pindah gambar/halaman.

Perubahan:

1. **Deteksi balon.** `inpainting/BubbleDetector.kt` mengelompokkan blok OCR yang berdekatan menjadi
   satu balon (satu balon sering terpecah menjadi beberapa blok, terutama teks vertikal Jepang),
   menghitung warna latar dari cincin di sekitar teks, lalu mencari batas dalam balon dengan flood
   fill yang otomatis berhenti di garis tepi balon. Warna teks dipilih dari kontras latar.
2. **Tempel terjemahan di posisi balon.** `BubbleOverlay.showTranslations()` membuat view overlay
   seukuran balon, berlatar warna balon asli (sehingga teks sumber tertutup) dan berisi teks Indonesia
   dengan font auto-shrink. Panel daftar teks tidak lagi dipakai untuk hasil; panel hanya status.
3. **Service tidak lagi mati saat pindah halaman.** Penyebab nyata: (a) callback
   `MediaProjection.onStop` memanggil `stopSelf()`; (b) `createVirtualDisplay()` dipanggil ulang pada
   setiap ketukan, yang sejak Android 14 melempar `SecurityException`; (c) provider terjemahan dibuat
   lalu ditutup setiap ketukan sehingga model dimuat ulang terus. Perbaikan: satu
   `VirtualDisplay`/`ImageReader` permanen untuk semua tangkapan, provider di-cache, `START_STICKY`,
   `stopWithTask="false"`, dan izin yang dicabut hanya memunculkan status + permintaan izin ulang
   lewat `MainActivity` (`FloatingTranslate.requestProjection`).
4. **Ketuk kedua menghapus tempelan**, tekan lama tetap mematikan bubble.

Batasan yang masih ada: tempelan mengikuti frame saat diterjemahkan, jadi setelah halaman digulir
pengguna perlu mengetuk bubble lagi (belum ada auto-refresh); inpainting piksel penuh
(diffusion/PatchMatch) belum diimplementasikan — teks asli ditutup oleh warna balon; batas balon
bergantung pada akurasi OCR (balon dengan screentone/gradien bisa memakai kotak cadangan yang lebih
longgar); seluruh perilaku ini belum diuji di perangkat.

APK: `dist/MangaLens-tahap5-arm64-debug.apk` (36.604.274 byte, SHA-256
`b9f65b5bab6f278bc567e8c526b67bbb4d24569246abdf465d94aba6ea441604`).

---

## 17. Perbaikan masukan pengguna (APK Tahap 6)

Keluhan: pada halaman manga berbahasa Inggris **tidak ada hasil terjemahan** walaupun bubble aktif;
dan permintaan agar bubble bisa menerjemahkan **di luar aplikasi MangaLens** (aplikasi/browser lain).

Penyebab nyata yang ditemukan di kode:

1. **Bahasa sumber dipaksa manual, default Jepang.** `FloatingSettings` default `JAPANESE`/`ja`, jadi
   halaman Inggris di-OCR dengan recognizer Jepang lalu diterjemahkan dengan model `ja→id`. Hasilnya
   kosong atau teks tidak berubah. Ini penyebab utama keluhan "tidak ada hasil terjemahan".
2. **Tidak ada bahasa otomatis** sehingga bubble yang dipakai di aplikasi lain (webtoon Inggris,
   manhwa Korea) salah bahasa tanpa cara memperbaikinya dari bubble.
3. **Kegagalan tidak terlihat.** Bila OCR 0 blok atau model belum terunduh, hanya ada pesan umum;
   tidak ada angka yang bisa dilaporkan pengguna.
4. **Koordinat tempelan bisa meleset** karena overlay memakai `resources.displayMetrics` (area setelah
   inset) sementara tangkapan layar memakai layar penuh, dan koordinat tidak dijepit ke dalam layar.

Perubahan:

1. `ocr/LanguageScript` mendapat nilai `AUTO`; `ocr/ScriptDetector` mendeteksi skrip dari karakter
   hasil OCR (kana → Jepang, hangul → Korea, han → China, sisanya Latin) dan memetakannya ke tag
   terjemahan (`ja/ko/zh/en`).
2. `MlKitOcrEngine` mode `AUTO` menjalankan recognizer Latin lebih dulu (cepat, akurat untuk huruf
   Latin); bila hasilnya < 40 karakter, recognizer Jepang dicoba dan hasil dengan teks terbanyak dipakai.
3. `MangaPageTranslator` sekarang memilih provider sesuai **bahasa yang benar-benar terbaca**, bukan
   pilihan pengguna, dengan cache provider per bahasa. Hasil mengembalikan `PageTranslation`
   (`ocrBlocks`, `detectedBubbles`, `sourceTag`) untuk diagnostik.
4. Service menampilkan status berisi angka tersebut, mis. `OCR 14 blok · 6 balon · en→id`, sehingga
   kegagalan bisa dibedakan: tidak ada teks terbaca / tidak ada balon / model belum terunduh.
5. `ScreenCapture` menolak frame hampir seluruhnya hitam (compositor belum menggambar) dan
   menyimpan `lastScale` untuk pemetaan koordinat.
6. `BubbleOverlay` memakai `currentWindowMetrics` (sama dengan penangkap layar), memanggil
   `fitInsetsTypes = 0` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, dan menjepit posisi/ukuran tempelan ke
   dalam layar.
7. `BubbleDetector` tidak lagi menganggap blok 1 karakter (nomor halaman, tanda baca) sebagai dialog,
   memperketat penggabungan blok (1.4×/1.2× tinggi huruf), dan memperbesar padding cadangan
   (28%) dengan batas gambar.
8. Default sumber bahasa di layar utama menjadi **Otomatis**; opsi manual tetap ada.
9. Teks UI menjelaskan bahwa bubble bekerja di aplikasi mana pun.

Cara kerja di luar MangaLens: MediaProjection menangkap **seluruh layar**, bukan hanya jendela
MangaLens, jadi bubble yang sama bisa dipakai di browser atau aplikasi baca lain. Karena bahasa sumber
dideteksi otomatis, pengguna tidak perlu kembali ke MangaLens untuk mengganti bahasa.

Batasan yang masih ada (belum berubah): tempelan mengikuti frame saat diterjemahkan sehingga setelah
halaman digulir perlu ketuk ulang (belum ada auto-refresh); teks asli ditutup oleh latar berwarna balon,
belum inpainting piksel penuh; balon dengan screentone memakai kotak cadangan yang lebih longgar;
model ML Kit Translation butuh unduhan sekali dengan internet; deteksi Latin diasumsikan Inggris
(teks Latin non-Inggris bisa salah arah terjemahan). Seluruh perilaku runtime **belum diuji di perangkat**
karena tidak ada device yang tersambung ke agen.

APK: `dist/MangaLens-tahap6-arm64-debug.apk` (36.597.437 byte, SHA-256
`e940948ef848c7c500f2e12ae258b0a9e30e854bede9f4a90a904be05894d154`).

## 18. Tahap 7 — bubble benar-benar muncul & terjemahan seluruh layar

**Gejala yang dilaporkan:** bubble tidak muncul sama sekali, baik di MangaLens maupun di aplikasi
lain, dan mengetuk tidak menghasilkan terjemahan apa pun.

**Akar masalah (bug nyata):** `FloatingTranslateService.onStartCommand()` hanya memanggil
`overlay.show()` pada jalur **gagal** (izin rekam layar gagal / belum ada). Saat pengguna menyetujui
izin rekam layar — jalur normal — `bindProjection()` berhasil tetapi overlay tidak pernah dipasang.
Akibatnya tidak ada bubble untuk diketuk, tidak ada panel status, dan seluruh fitur tampak mati.
Bug ini tidak terlihat dari build karena hanya muncul saat runtime.

**Perbaikan:**

1. `overlay.show()` sekarang dipanggil **selalu** setelah service siap. `BubbleOverlay.show()`
   mengembalikan `Boolean`; bila pemasangan gagal (izin "tampil di atas aplikasi lain" dicabut),
   service menghentikan dirinya dan `FloatingTranslate.isRunning` kembali `false`, bukan diam saja.
   Kegagalan juga ditulis ke Logcat dengan tag `MangaLensOverlay`.
2. Terjemahan tidak lagi bergantung pada deteksi balon. `MangaPageTranslator` menyusun daftar
   *region*: setiap balon yang terdeteksi, **ditambah setiap blok OCR yang tidak tercakup balon**
   (kotak narasi, teks di luar kotak, tulisan pada gambar). Blok bebas memakai warna latar di
   sekitarnya lewat `BubbleDetector.surroundings()` (histogram warna cincin + teks hitam/putih sesuai
   kecerahan), lalu ditempel pada kotak blok itu sendiri dengan padding 18% tinggi huruf.
   Batas 40 region per ketukan.
3. `MlKitOcrEngine` mode `AUTO` mencoba Latin lebih dulu; bila hasilnya < 40 karakter, recognizer
   Jepang, Korea, dan Tionghoa semuanya dicoba dan hasil dengan teks terbanyak yang dipakai. Halaman
   berhuruf Latin tetap hanya satu pass, jadi tidak melambat.
4. Pesan status disederhanakan: bila ada teks tetapi tidak ada hasil, penyebabnya model belum
   terunduh, bukan "balon tidak ditemukan".

**Yang berfungsi (terverifikasi build):** bubble sekarang dipasang pada jalur normal, ketukan
memicu tangkap layar → OCR → terjemahan semua teks → tempelan di posisinya.

**Belum terverifikasi:** seluruh perilaku runtime tetap belum diuji di perangkat (tidak ada device
yang tersambung ke agen). Bila bubble tetap tidak muncul di Realme GT Neo 3, periksa izin
"Tampilkan di atas aplikasi lain" untuk MangaLens dan Logcat tag `MangaLensOverlay`.

APK: `dist/MangaLens-tahap7-arm64-debug.apk` (36.625.806 byte, SHA-256
`cd253d52b61c71feb14f767cdbbeba7b8a13eea9d3177354c2899e0cb3f8d850`).

## 19. Uji langsung di Realme GT Neo 3 (RMX3561, Android 14)

Pengujian dilakukan lewat adb pada perangkat fisik, bukan emulator. APK debug dipasang, izin
"tampil di atas aplikasi lain" sudah aktif, lalu bubble diaktifkan dari layar utama.

**Hasil yang terverifikasi di perangkat:**

1. Bubble "訳" benar-benar muncul setelah izin rekam layar disetujui (bug tahap 7 terbukti beres).
   Notifikasi "Bubble aktif di semua aplikasi" tampil.
2. Ketukan pada bubble menangkap layar, menjalankan OCR + terjemahan, lalu menempelkan teks
   Indonesia di balon. Teruji pada aplikasi lain (Google Photos menampilkan halaman manga), jadi
   fitur tidak bergantung pada MangaLens.
3. Ketukan kedua menghapus semua tempelan dan menampilkan panel "Terjemahan dihapus..."; tidak ada
   crash (Logcat bersih dari `FATAL EXCEPTION` dan `MangaLensOverlay`).
4. Perbaikan pengelompokan berbasis garis tepi bekerja: sebelumnya tiga balon bersebelahan
   ("SHUT UP, UGGO!!" / "DON'T WORRY..." / "STOP TALKING...") digabung menjadi satu kotak besar;
   sekarang masing-masing mendapat tempelan sendiri.
5. Resolusi OCR dinaikkan (frame layar tidak lagi diperkecil ke 1600 px) sehingga teks balon yang
   kecil lebih akurat terbaca. Masih ada salah baca pada teks yang sangat kecil/blur, mis.
   "YOU GOT ANY SIBLINGS?" → "Anda punya Anw Sib ling62"; ini keterbatasan OCR, bukan bug alur.
6. Tempelan yang teksnya panjang otomatis meninggi sampai 1.6× tinggi balon agar tidak terpotong.

**Catatan hasil uji:** kualitas terjemahan mengikuti kualitas OCR. Pada halaman yang ditampilkan
seukuran layar penuh (teks ±8–10 px), sebagian kalimat masih salah baca; membaca dengan zoom lebih
besar di aplikasi baca memberi hasil jauh lebih baik.

APK hasil uji: `dist/MangaLens-tahap8-arm64-debug.apk` (36.626.334 byte, SHA-256
`762f7fc664e9ddf030f7133417a69e823acd988ad4d5f9b4fe699612d03b0bdd`).

## 20. Tempelan lebih kecil & bahasa tidak baku

**Keluhan:** terjemahan terlalu baku, dan beberapa tempelan begitu besar sampai menutupi satu layar
sehingga mengganggu saat membaca.

**Perubahan:**

1. `TranslatedBubble` kini membawa `textBounds` (kotak teks asli) selain `bounds` (balon penuh).
   `BubbleOverlay` memakai `textBounds` + padding 12% tinggi sebagai ukuran tempelan, bukan seluruh
   balon. Gambar di sekitar balon jadi tetap terlihat.
2. Batas keras ukuran satu tempelan: lebar maksimum 90% layar, tinggi maksimum 28% layar. Bila teks
   masih terpotong, tinggi boleh bertambah sampai 1.8× tinggi kotak teks (tetap dibatasi 32% layar).
   Jadi satu tempelan tidak mungkin lagi menutupi satu layar.
3. Lebar tempelan diberi tambahan 20% karena bahasa Indonesia umumnya lebih panjang dari teks
   aslinya; ini mengurangi baris pendek yang terpotong.
4. Ukuran huruf maksimum diturunkan dari 26sp ke 20sp.
5. `translation/IndonesianStyle.kt` baru: penggantian ragam formal → santai pada hasil ML Kit
   (`Anda`→`kamu`, `saya`→`aku`, `apakah`→`apa`, `bagaimana`→`gimana`, `mengapa`→`kenapa`,
   `ingin`→`mau`, `dapat`→`bisa`, `tersebut`/`merupakan`→`itu`, `namun`/`tetapi`→`tapi`,
   `ketika`→`waktu`, `sehingga`→`jadi`, `saat ini`→`sekarang`). Hanya kata yang maknanya tidak
   berubah, supaya tata bahasa tetap benar.

**Hasil uji di perangkat (Realme GT Neo 3):** tempelan kini seukuran teks balon, tidak ada lagi blok
raksasa yang menutupi satu layar; contoh "Ibu aku meninggal karena sakit waktu aku masih sangat
mengupayakan." menggantikan "MY MOTHER PASSED AWAY…" di dalam balonnya. Penggantian ragam formal
terlihat pada hasil (`Anda` → `kamu`, `saya` → `aku`).

**Batasan:** kecanggihan bahasa tetap dibatasi model ML Kit on-device; kalimat yang salah baca OCR
akan tetap salah terjemah. Terjemahan yang benar-benar luwes memerlukan model bahasa (LLM) dengan
kunci API, yang belum dipasang.

APK: `dist/MangaLens-tahap9-arm64-debug.apk` (36.626.701 byte, SHA-256
`b6d5e6157dcaefba2779cd7f9c238a7741b4c1b4bd3aa2844e4d1ce1c6959a06`).
