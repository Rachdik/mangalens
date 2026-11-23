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
| 3–10 | belum | — |

Modul yang benar-benar ada di `settings.gradle.kts`: `app, core, ui, storage, reader, floating, ocr,
translation, inpainting, imageprocessing` (10 modul). `ui` adalah modul ke-10 di luar daftar awal, hanya
berisi resource tema bersama agar `app` dan `reader` tidak menduplikasi style.

## 14. Urutan eksekusi

Tahap 2 → 3 harus selesai dan terbukti pada satu situs uji sebelum OCR (4) masuk, karena tanpa gambar
yang benar, semua tahap setelahnya tidak bisa dievaluasi. Tahap 5 dan 6 bisa dikerjakan setelah 4.
Tahap 7 dan 8 memakai pipeline yang sama, jadi dikerjakan setelah 6 stabil. Tahap 9 dan 10 terakhir.

Setiap tahap: build + test, lalu laporan file dibuat/diubah, error yang ditemukan, dan status fitur
(berfungsi / sebagian / belum).
