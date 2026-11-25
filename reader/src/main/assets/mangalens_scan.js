(function () {
    // Registry elemen gambar: url absolut -> <img>. Dipakai agar overlay bisa
    // memakai posisi/ukuran gambar yang benar-benar dirender, bukan tebakan.
    if (!window.__mangalensElements) window.__mangalensElements = {};
    if (!window.__mangalensOverlayState) window.__mangalensOverlayState = {};
    if (!window.__mangalensContainers) window.__mangalensContainers = {};

    if (window.__mangalensInstalled) {
        window.__mangalensScan();
        return 'already-installed';
    }
    window.__mangalensInstalled = true;

    var seq = 0;
    var sent = {};
    var scanTimer = null;
    var overlayTimer = null;

    // ---------------------------------------------------------------
    // Deteksi repaint canvas tanpa polling per frame
    // ---------------------------------------------------------------
    // Metode gambar 2D dipasangi penghitung per elemen canvas. Setiap paint
    // menaikkan nomor revisi elemen; revisi ikut masuk ke key canvas tainted
    // (yang tidak bisa di-hash isinya) sehingga redraw benar-benar diproses
    // ulang, tanpa memindai setiap frame.
    var paintHooksInstalled = false;

    function noteCanvasPaint(canvas) {
        if (!canvas || canvas.tagName !== 'CANVAS') return;
        // Hanya canvas yang sudah terdaftar sebagai kandidat yang dilacak;
        // ini menghindari pemindaian akibat animasi thumbnail/banner.
        if (!canvas.__mangalensCanvasId) return;
        canvas.__mangalensPaintPending = true;
        if (canvas.__mangalensPaintTimer) clearTimeout(canvas.__mangalensPaintTimer);
        // Revisi baru dicatat setelah gambar berhenti berubah (debounce), bukan
        // setiap frame, agar animasi tidak memicu capture berulang.
        canvas.__mangalensPaintTimer = setTimeout(function () {
            canvas.__mangalensPaintTimer = null;
            if (!canvas.__mangalensPaintPending) return;
            canvas.__mangalensPaintPending = false;
            canvas.__mangalensPaintSeq = (canvas.__mangalensPaintSeq || 0) + 1;
            canvas.__mangalensPaintedAt = Date.now();
            scheduleScan();
        }, 700);
    }

    function installCanvasPaintHooks() {
        if (paintHooksInstalled) return;
        paintHooksInstalled = true;
        var proto = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
        if (!proto) return;
        var methods = ['drawImage', 'fillRect', 'strokeRect', 'clearRect', 'putImageData',
            'fillText', 'strokeText', 'fill', 'stroke', 'drawFocusIfNeeded'];
        for (var i = 0; i < methods.length; i++) {
            (function (name) {
                var original = proto[name];
                if (typeof original !== 'function' || original.__mangalensWrapped) return;
                var wrapped = function () {
                    var result = original.apply(this, arguments);
                    try { noteCanvasPaint(this.canvas); } catch (e) { }
                    return result;
                };
                wrapped.__mangalensWrapped = true;
                try { proto[name] = wrapped; } catch (e) { }
            })(methods[i]);
        }
    }

    function canvasRevision(el) {
        // Hanya dipakai saat signature tidak tersedia (canvas tainted).
        return el.__mangalensPaintSeq || 0;
    }

    window.__mangalensSetActive = function (active) {
        window.__mangalensActive = !!active;
        return true;
    };

    function absUrl(u) {
        if (!u) return null;
        u = String(u).trim();
        if (!u) return null;
        if (u.indexOf('data:') === 0 || u.indexOf('blob:') === 0 || u.indexOf('javascript:') === 0) return null;
        try {
            return new URL(u, location.href).href;
        } catch (e) {
            return null;
        }
    }

    function largestFromSrcset(value) {
        if (!value) return null;
        var parts = value.split(',');
        var best = null;
        var bestWeight = -1;
        for (var i = 0; i < parts.length; i++) {
            var seg = parts[i].trim().split(/\s+/);
            if (!seg[0]) continue;
            var descriptor = (seg[1] || '0').replace('w', '').replace('x', '');
            var weight = parseFloat(descriptor) || 0;
            if (weight >= bestWeight) {
                bestWeight = weight;
                best = seg[0];
            }
        }
        return best;
    }

    function urlOf(el) {
        var lazyAttrs = ['data-src', 'data-lazy-src', 'data-original', 'data-url', 'data-lazy', 'data-image'];
        for (var i = 0; i < lazyAttrs.length; i++) {
            var v = el.getAttribute(lazyAttrs[i]);
            if (v) return v;
        }
        if (el.tagName === 'IMG') {
            var src = el.currentSrc || el.getAttribute('src');
            var srcset = largestFromSrcset(el.getAttribute('srcset'));
            var looksPlaceholder = !src || src.indexOf('data:') === 0 ||
                (el.naturalWidth > 0 && el.naturalWidth < 400);
            if (srcset && looksPlaceholder) return srcset;
            return src || srcset;
        }
        if (el.tagName === 'SOURCE') {
            return largestFromSrcset(el.getAttribute('srcset')) || el.getAttribute('src');
        }
        return null;
    }

    function collect() {
        var selector = 'img, picture source, [data-src], [data-lazy-src], [data-original], [data-url], [data-lazy]';
        var nodes = document.querySelectorAll(selector);
        var out = [];
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.tagName === 'SOURCE' && !(el.parentElement && el.parentElement.tagName === 'PICTURE')) continue;

            var rect = el.getBoundingClientRect();
            var naturalW = el.naturalWidth || Math.round(rect.width);
            var naturalH = el.naturalHeight || Math.round(rect.height);

            var bigEnough = naturalW >= 300 || rect.width >= 220 || rect.height >= 320;
            if (!bigEnough) continue;
            if (naturalW * naturalH < 90000 && rect.width * rect.height < 40000) continue;

            var style = getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity || '1') === 0) continue;
            if (el.closest('header, nav, footer, aside')) continue;

            var url = absUrl(urlOf(el));
            if (!url) continue;
            // Bitmap hasil inpainting adalah data URL: jangan pernah dianggap
            // gambar baru, karena itu akan menjalankan OCR ulang pada gambar
            // yang teksnya sudah dihapus.
            if (url.indexOf('data:') === 0) continue;

            // Simpan elemen yang benar-benar dirender agar overlay menempel
            // pada gambar ini, termasuk setelah lazy-load mengganti src.
            window.__mangalensElements[url] = (el.tagName === 'IMG') ? el : (el.parentElement || el);

            if (sent[url]) continue;
            sent[url] = 1;

            out.push({
                url: url,
                w: Math.round(naturalW),
                h: Math.round(naturalH),
                rectW: Math.round(rect.width),
                rectH: Math.round(rect.height),
                rectL: Math.round(rect.left + (window.scrollX || window.pageXOffset || 0)),
                docOrder: Math.round(rect.top + (window.scrollY || window.pageYOffset || 0)),
                seq: seq++
            });
        }
        return out;
    }


    // ---------------------------------------------------------------
    // Canvas halaman komik
    // ---------------------------------------------------------------
    // Sebagian situs (mis. kmanga) menggambar halaman ke <canvas>, bukan <img>.
    // Deteksi memakai beberapa sinyal sekaligus (ukuran backing store, ukuran
    // render, rasio, slot pembaca, pengecualian iklan) dan sidik jari isi agar
    // canvas yang digambar ulang tidak memakai overlay lama.
    var CANVAS_MIN_RECT = 200;
    var CANVAS_MIN_BACKING = 300;
    var CANVAS_MAX_PIXELS = 40000000;
    var canvasSeq = 0;

    /** Elemen yang benar-benar menggulir halaman (bisa container internal). */
    function canvasScroller(el) {
        var node = el.parentElement;
        while (node && node !== document.body) {
            var st = getComputedStyle(node);
            if (/(auto|scroll)/.test(st.overflowY) && node.scrollHeight - node.clientHeight > 40) {
                return node;
            }
            node = node.parentElement;
        }
        return document.scrollingElement || null;
    }
    var canvasResizeObserver = window.ResizeObserver
        ? new ResizeObserver(function () { scheduleScan(); scheduleRender(); })
        : null;

    function canvasSignature(canvas) {
        // Sidik jari murah: gambar versi 24x24 lalu hash pikselnya. Bila canvas
        // tainted, getImageData melempar SecurityError dan kita kembalikan null.
        try {
            var s = 24;
            var tmp = document.createElement('canvas');
            tmp.width = s;
            tmp.height = s;
            var ctx = tmp.getContext('2d', { willReadFrequently: true });
            if (!ctx) return null;
            ctx.drawImage(canvas, 0, 0, s, s);
            var data = ctx.getImageData(0, 0, s, s).data;
            var h1 = 0x811c9dc5;
            var h2 = 0x01000193;
            for (var i = 0; i < data.length; i += 4) {
                var v = (data[i] + data[i + 1] * 3 + data[i + 2] * 7 + data[i + 3] * 11) & 0xff;
                h1 = ((h1 ^ v) * 16777619) >>> 0;
                h2 = ((h2 + v * 31) ^ (h2 << 5)) >>> 0;
            }
            return h1.toString(36) + h2.toString(36);
        } catch (e) {
            return null;
        }
    }

    function canvasInAdSlot(el) {
        return !!el.closest(
            '[class*="ad-"], [class*="ads"], [class*="advert"], [class*="banner"], ' +
            '[class*="thumb"], [class*="icon"], [class*="logo"], [id*="ads"], ' +
            '[id*="banner"], header, nav, footer, aside'
        );
    }

    function canvasInViewer(el) {
        return el.closest(
            '[class*="viewer" i], [class*="reader" i], [class*="page" i], ' +
            '[class*="chapter" i], [id*="viewer" i], [id*="reader" i], [id*="page" i]'
        );
    }

    function collectCanvases() {
        var nodes = document.querySelectorAll('canvas');
        var out = [];
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var bw = el.width || 0;
            var bh = el.height || 0;
            if (bw < CANVAS_MIN_BACKING || bh < CANVAS_MIN_BACKING) continue;

            var rect = el.getBoundingClientRect();
            if (rect.width < CANVAS_MIN_RECT || rect.height < CANVAS_MIN_RECT) continue;

            var style = getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' ||
                parseFloat(style.opacity || '1') === 0) continue;
            if (canvasInAdSlot(el)) continue;

            var viewer = canvasInViewer(el);

            // Rasio halaman komik menyaring strip iklan/dekorasi yang pipih.
            // Canvas vertikal panjang (webtoon) di dalam viewer tetap kandidat:
            // rasio ekstrem bukan alasan membuang seluruh halaman.
            var ratio = rect.width / rect.height;
            var tallInViewer = !!viewer && ratio >= 0.05;
            if (!tallInViewer && (ratio < 0.25 || ratio > 4)) continue;

            // Hanya canvas di sekitar viewport yang diproses sekarang; sisanya
            // menyusul saat pengguna menggulir. Ini mencegah pipeline menggulir
            // halaman jauh ke depan hanya untuk menangkap canvas.
            var vh = window.innerHeight || 800;
            if (rect.bottom < -vh * 1.5 || rect.top > vh * 2.5) continue;

            // Canvas besar di luar slot pembaca tetap boleh (beberapa situs tidak
            // memakai nama kelas yang jelas), tetapi harus dominan di layar.
            if (!viewer && rect.width * rect.height < 120000) continue;

            if (canvasResizeObserver && !el.__mangalensResizeObserved) {
                el.__mangalensResizeObserved = 1;
                canvasResizeObserver.observe(el);
            }

            var sig = canvasSignature(el);
            // Tanpa sidik jari (canvas tainted), key memakai id elemen + nomor
            // revisi paint. Id saja tidak cukup: redraw akan dianggap konten lama.
            var pageKey = window.__mangalensPageKey;
            if (!pageKey) {
                var href = String(location.href), ph = 0x811c9dc5;
                for (var hi = 0; hi < href.length; hi++) {
                    ph = ((ph ^ href.charCodeAt(hi)) * 16777619) >>> 0;
                }
                pageKey = window.__mangalensPageKey = ph.toString(36);
            }
            // Key memuat hash URL chapter + hash isi/posisi, sehingga cache
            // tidak pernah bertukar halaman antar chapter atau antar posisi.
            if (!el.__mangalensCanvasId) el.__mangalensCanvasId = 'e' + (++canvasSeq);
            // v2: skema tangkapan piksel (rect setelah paint + guard duplikat).
            // Revisi paint hanya dipakai bila isi tidak bisa di-hash.
            var revision = sig ? '' : ':r' + canvasRevision(el);
            var key = 'canvas:v2:' + pageKey + ':' + (sig ? 'h' + sig : el.__mangalensCanvasId) +
                ':' + bw + 'x' + bh + revision;
            el.__mangalensScannedSeq = canvasRevision(el);

            // Konten berubah (redraw): buang overlay lama untuk key sebelumnya.
            var previous = el.__mangalensKey;
            if (previous && previous !== key) {
                if (window.__mangalensRemoveOverlay) window.__mangalensRemoveOverlay(previous);
                if (window.__mangalensOverlayState) delete window.__mangalensOverlayState[previous];
            }
            el.__mangalensKey = key;
            window.__mangalensElements[key] = el;

            if (sent[key]) continue;
            sent[key] = 1;

            out.push({
                url: key,
                w: bw,
                h: bh,
                rectW: Math.round(rect.width),
                rectH: Math.round(rect.height),
                rectL: Math.round(rect.left + (window.scrollX || window.pageXOffset || 0)),
                docOrder: Math.round(rect.top + (window.scrollY || window.pageYOffset || 0)),
                seq: seq++,
                canvas: 1,
                viewer: viewer ? 1 : 0
            });
        }
        return out;
    }

    /**
     * Dipanggil dari Kotlin untuk mengambil isi canvas sebagai gambar.
     * Selalu mengembalikan JSON string; error dilaporkan sebagai data, bukan
     * dilempar, agar WebView tidak pernah crash.
     */

    /**
     * Informasi geometri canvas untuk fallback tangkapan piksel.
     * Dipakai ketika canvas tainted sehingga toDataURL() diblokir browser.
     */

    /**
     * Persiapan tangkapan piksel (fallback canvas tainted).
     * Mengembalikan geometri canvas dalam CSS px terhadap viewport.
     */
    window.__mangalensCanvasPrep = function (key) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ ok: false, error: 'detached' });
        }
        var rect = el.getBoundingClientRect();
        var scroller = canvasScroller(el);
        var scRect = scroller ? scroller.getBoundingClientRect() : null;
        window.__mangalensActiveScroller = scroller;
        window.__mangalensActiveScrollTop = scroller ? scroller.scrollTop : 0;
        // Backing store dimensions (el.width/el.height) adalah ukuran buffer piksel asli.
        // CSS rect.width/rect.height adalah ukuran render di viewport (bisa di-scale).
        // Keduanya perlu dipakai agar sistem tahuseluruh konten canvas, bukan hanya
        // yang kelihatan di satu viewport.
        return JSON.stringify({
            ok: true,
            cssW: rect.width,
            cssH: rect.height,
            bw: el.width,
            bh: el.height,
            vw: window.innerWidth,
            vh: scRect ? Math.min(scRect.height, window.innerHeight) : window.innerHeight,
            dpr: window.devicePixelRatio || 1,
            scrollY: scroller ? Math.round(scroller.scrollTop) : 0
        });
    };

    /**
     * Menggulir scroller yang benar (bukan selalu window) sehingga bagian canvas
     * pada offset tertentu terlihat, lalu melaporkan irisan yang tampak.
     */
    window.__mangalensCanvasScrollTo = function (key, offsetCss) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ ok: false, error: 'detached' });
        }
        var scroller = window.__mangalensActiveScroller || canvasScroller(el);
        if (!scroller) return JSON.stringify({ ok: false, error: 'no-scroller' });

        var scRect = scroller.getBoundingClientRect();
        var elRect = el.getBoundingClientRect();
        // Posisi canvas di dalam konten scroller (invariant terhadap scroll).
        var elTopInScroller = (elRect.top - scRect.top) + scroller.scrollTop;
        var target = scroller.scrollTop + (elRect.top - scRect.top) + offsetCss;
        scroller.scrollTop = Math.max(0, target);

        var r = el.getBoundingClientRect();
        var top = Math.max(0, scRect.top, r.top);
        var bottom = Math.min(window.innerHeight, scRect.bottom, r.bottom);
        // Offset konten yang BENAR-BENAR terlihat. Bila scroller sudah mentok,
        // nilainya lebih kecil dari offset yang diminta; pemanggil memakai nilai
        // ini agar tile terakhir tidak bergeser/menyisakan celah.
        var contentOffset = Math.max(0, scroller.scrollTop - elTopInScroller);
        return JSON.stringify({
            ok: true,
            left: r.left,
            width: r.width,
            sliceTop: top,
            sliceHeight: Math.max(0, bottom - top),
            contentOffset: contentOffset,
            dpr: window.devicePixelRatio || 1
        });
    };


    /**
     * Rect irisan canvas yang sedang tampak, tanpa menggulir.
     * Dipanggil setelah scroll mendarat supaya tangkapan piksel tidak memakai
     * posisi lama ketika halaman belum sempat digambar ulang.
     */
    window.__mangalensCanvasRectNow = function (key) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ ok: false, error: 'detached' });
        }
        var scroller = window.__mangalensActiveScroller || canvasScroller(el);
        var scRect = scroller ? scroller.getBoundingClientRect() : null;
        var r = el.getBoundingClientRect();
        var scrollerTop = scRect ? scRect.top : 0;
        var scrollTop = scroller ? scroller.scrollTop : (window.scrollY || 0);
        var elTopInScroller = (r.top - scrollerTop) + scrollTop;
        var contentOffset = Math.max(0, scrollTop - elTopInScroller);
        var top = Math.max(0, scrollerTop, r.top);
        var bottom = Math.min(window.innerHeight, scRect ? scRect.bottom : window.innerHeight, r.bottom);
        return JSON.stringify({
            ok: true,
            left: r.left,
            width: r.width,
            sliceTop: top,
            sliceHeight: Math.max(0, bottom - top),
            contentOffset: contentOffset,
            canvasTop: r.top,
            canvasHeight: r.height,
            dpr: window.devicePixelRatio || 1
        });
    };

    window.__mangalensScrollRestore = function (value) {
        var scroller = window.__mangalensActiveScroller;
        var target = Number(value);
        if (!isFinite(target) || target < 0) {
            target = window.__mangalensActiveScrollTop || 0;
        }
        if (scroller) scroller.scrollTop = target;
        else window.scrollTo(0, target);
        return true;
    };

    window.__mangalensCanvasInfo = function (key) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ ok: false, error: 'detached' });
        }
        var vh = window.innerHeight || 0;
        var vw = window.innerWidth || 0;
        var rect = el.getBoundingClientRect();
        if (rect.height > vh - 4 || rect.width > vw + 4) {
            return JSON.stringify({
                ok: false,
                error: 'too-large-for-capture',
                h: Math.round(rect.height),
                vh: Math.round(vh)
            });
        }
        if (rect.top < 0 || rect.bottom > vh) {
            // Tangkapan piksel harus memuat seluruh canvas: gulir dulu ke tengah.
            el.scrollIntoView({ block: 'center', inline: 'nearest' });
            rect = el.getBoundingClientRect();
        }
        return JSON.stringify({
            ok: true,
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height,
            dpr: window.devicePixelRatio || 1,
            bw: el.width,
            bh: el.height
        });
    };

    /**
     * Informasi sumber gambar canvas untuk diagnostik.
     * Mencoba mengidentifikasi apakah canvas berasal dari gambar asli yang bisa
     * diakses lewat URL, atau stateless render.
     */
    window.__mangalensCanvasSourceInfo = function (key) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ sourceType: 'detached' });
        }

        // Cek apakah ada elemen IMG yang merupakan sumber data untuk canvas ini
        var sourceImg = findCanvasSourceImage(el);
        if (sourceImg && sourceImg.src) {
            var absSrc = absUrl(sourceImg.src);
            if (absSrc) {
                return JSON.stringify({
                    sourceType: 'native',
                    nativeUrl: absSrc,
                    nativeWidth: sourceImg.naturalWidth,
                    nativeHeight: sourceImg.naturalHeight,
                    canvasBackingW: el.width,
                    canvasBackingH: el.height,
                    canvasTainted: isCanvasTainted(el),
                    pixelCopyUsed: false
                });
            }
        }

        // Canvas mungkin digambar dari banyak sumber (WebGL, multiple images, dll)
        // Cek canvas tainted status
        var tainted = isCanvasTainted(el);

        // Jika tidak ada sumber gambar yang teridentifikasi, beri tahu bahwa
        // ini adalah canvas yang kompleks
        return JSON.stringify({
            sourceType: tainted ? 'complex-tainted' : 'complex',
            canvasBackingW: el.width,
            canvasBackingH: el.height,
            canvasTainted: tainted,
            pixelCopyUsed: true,
            note: tainted ? 'canvas tainted - native extraction not possible' : 'no identifiable single image source'
        });
    };

    /**
     * Coba temukan elemen IMG yang mungkin menjadi sumber data canvas.
     * Ini adalah heuristik - tidak semua canvas berasal dari single image.
     */
    function findCanvasSourceImage(canvas) {
        // Strategi 1: Cek apakah canvas ini memiliki data attribute yang merujuk ke image
        var dataImg = canvas.getAttribute('data-image-src') || canvas.getAttribute('data-src');
        if (dataImg) {
            var img = document.querySelector('img[src="' + dataImg + '"]');
            if (img && img.naturalWidth > 100) return img;
        }

        // Strategi 2: Cek gambar yang baru saja dimuat sebelum canvas di-render
        // (bisa jadi sumber dari canvas)
        var imgs = document.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
            var img = imgs[i];
            if (!img.src || img.src.indexOf('data:') === 0) continue;
            if (img.naturalWidth < 100 || img.naturalHeight < 100) continue;

            // Cek apakah ukuran gambar cocok dengan canvas
            if (Math.abs(img.naturalWidth - canvas.width) < 50 &&
                Math.abs(img.naturalHeight - canvas.height) < 50) {
                // Ukuran cocok - cek apakah gambar ini visible di area yang sama
                var imgRect = img.getBoundingClientRect();
                var canvasRect = canvas.getBoundingClientRect();
                if (Math.abs(imgRect.left - canvasRect.left) < 10 &&
                    Math.abs(imgRect.top - canvasRect.top) < 10) {
                    return img;
                }
            }
        }

        // Strategi 3: Cek gambar dengan ukuran yang sama persis dengan canvas backing store
        for (var j = 0; j < imgs.length; j++) {
            var img2 = imgs[j];
            if (!img2.src || img2.src.indexOf('data:') === 0) continue;
            if (img2.naturalWidth === canvas.width && img2.naturalHeight === canvas.height) {
                return img2;
            }
        }

        return null;
    }

    /**
     * Cek apakah canvas tainted (tidak bisa dibaca pikselnya).
     * Coba ambil sampel piksel kecil - jika gagal, canvas tainted.
     */
    function isCanvasTainted(canvas) {
        try {
            var ctx = canvas.getContext('2d');
            if (!ctx) return true;
            var testCanvas = document.createElement('canvas');
            testCanvas.width = 2;
            testCanvas.height = 2;
            var testCtx = testCanvas.getContext('2d');
            testCtx.drawImage(canvas, 0, 0, 2, 2);
            testCtx.getImageData(0, 0, 2, 2);
            return false;
        } catch (e) {
            return true;
        }
    }

    /** Sembunyikan overlay sendiri agar tidak ikut tertangkap saat capture. */
    window.__mangalensSetOverlayHidden = function (hidden) {
        var id = 'mangalens-capture-style';
        var style = document.getElementById(id);
        if (hidden) {
            if (!style) {
                style = document.createElement('style');
                style.id = id;
                style.textContent = '.mangalens-overlay{visibility:hidden !important;}';
                (document.head || document.documentElement).appendChild(style);
            }
        } else if (style && style.parentNode) {
            style.parentNode.removeChild(style);
        }
        return true;
    };

    window.__mangalensExtractCanvas = function (key) {
        var el = window.__mangalensElements && window.__mangalensElements[key];
        if (!el || el.tagName !== 'CANVAS' || !el.isConnected) {
            return JSON.stringify({ ok: false, error: 'detached' });
        }
        var bw = el.width || 0;
        var bh = el.height || 0;
        if (!bw || !bh) return JSON.stringify({ ok: false, error: 'empty' });

        try {
            var source = el;
            var outW = bw;
            var outH = bh;
            var pixels = bw * bh;
            if (pixels > CANVAS_MAX_PIXELS) {
                var scale = Math.sqrt(CANVAS_MAX_PIXELS / pixels);
                outW = Math.max(1, Math.floor(bw * scale));
                outH = Math.max(1, Math.floor(bh * scale));
                var tmp = document.createElement('canvas');
                tmp.width = outW;
                tmp.height = outH;
                var ctx = tmp.getContext('2d');
                if (!ctx) return JSON.stringify({ ok: false, error: 'no-2d-context' });
                ctx.drawImage(el, 0, 0, outW, outH);
                source = tmp;
            }
            var url = source.toDataURL('image/webp', 0.9);
            if (!url || url === 'data:,' || url.length < 2048) {
                return JSON.stringify({ ok: false, error: 'blank' });
            }
            return JSON.stringify({ ok: true, key: key, w: outW, h: outH, url: url });
        } catch (e) {
            var name = (e && e.name) || '';
            return JSON.stringify({
                ok: false,
                error: name === 'SecurityError' ? 'tainted' : String((e && e.message) || e)
            });
        }
    };

    window.__mangalensScan = function () {
        var fresh = collect().concat(collectCanvases());
        if (fresh.length && window.MangaLensBridge && window.MangaLensBridge.onImages) {
            window.MangaLensBridge.onImages(JSON.stringify(fresh));
        }
        return fresh.length;
    };

    // Tombol "Terjemahkan ulang": buang penanda 'sudah dikirim' agar gambar
    // yang sama dikirim lagi ke pipeline meski halaman tidak berubah.
    window.__mangalensForceRescan = function () {
        sent = {};
        return window.__mangalensScan();
    };

    // ---------------------------------------------------------------
    // Overlay terjemahan
    // ---------------------------------------------------------------

    function removeOverlay(url) {
        var c = window.__mangalensContainers[url];
        if (c && c.parentNode) c.parentNode.removeChild(c);
        delete window.__mangalensContainers[url];
        delete window.__mangalensOverlayState[url];
    }

    // ---------------------------------------------------------------
    // Mode gambar: Terjemahan (bitmap inpainting) / Asli
    // ---------------------------------------------------------------
    window.__mangalensImageMode = window.__mangalensImageMode || 'translated';
    window.__mangalensOriginalSrc = window.__mangalensOriginalSrc || {};
    window.__mangalensInpaintedSrc = window.__mangalensInpaintedSrc || {};

    function imageElementFor(url) {
        var el = window.__mangalensElements && window.__mangalensElements[url];
        if (el && el.isConnected) return el;
        var imgs = document.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
            var src = imgs[i].src || '';
            if (src.indexOf(url) !== -1) return imgs[i];
        }
        return null;
    }

    /**
     * Pasang bitmap hasil inpainting sebagai sumber gambar. Sumber asli disimpan
     * agar pengguna bisa kembali ke halaman asli tanpa memuat ulang situs.
     */
    window.__mangalensApplyInpainted = function (url, dataUrl) {
        if (!url || !dataUrl) return false;
        window.__mangalensInpaintedSrc[url] = dataUrl;
        var el = imageElementFor(url);
        if (!el) return false;
        if (!window.__mangalensOriginalSrc[url]) {
            window.__mangalensOriginalSrc[url] = el.getAttribute('src') || el.src;
        }
        if (window.__mangalensImageMode === 'translated') el.src = dataUrl;
        return true;
    };

    /** mode: 'translated' (inpainting + overlay) atau 'original' (gambar asli). */
    window.__mangalensSetImageMode = function (mode) {
        window.__mangalensImageMode = mode === 'original' ? 'original' : 'translated';
        for (var url in window.__mangalensOriginalSrc) {
            var el = imageElementFor(url);
            if (!el) continue;
            if (window.__mangalensImageMode === 'original') {
                el.src = window.__mangalensOriginalSrc[url];
            } else if (window.__mangalensInpaintedSrc[url]) {
                el.src = window.__mangalensInpaintedSrc[url];
            }
        }
        renderAll();
        return window.__mangalensImageMode;
    };

    /** Ringkasan keadaan untuk verifikasi Reader (Kotlin/test). */
    window.__mangalensReaderState = function () {
        var out = { mode: window.__mangalensImageMode, images: 0, inpainted: 0,
                    overlays: 0, boxCount: 0, img: null, boxes: [] };
        for (var url in window.__mangalensOriginalSrc) {
            out.images++;
            if (window.__mangalensInpaintedSrc[url]) out.inpainted++;
        }
        for (var c in window.__mangalensContainers) {
            var node = window.__mangalensContainers[c];
            if (node && node.parentNode && node.style.display !== 'none') {
                out.overlays++;
                out.boxCount += node.children.length;
            }
        }
        var firstUrl = null;
        for (var u in window.__mangalensOriginalSrc) { firstUrl = u; break; }
        var el = firstUrl ? imageElementFor(firstUrl) : null;
        if (el) {
            var r = el.getBoundingClientRect();
            out.img = { l: Math.round(r.left), t: Math.round(r.top),
                        w: Math.round(r.width), h: Math.round(r.height) };
            var container = window.__mangalensContainers[firstUrl];
            if (container && container.style.display !== 'none') {
                var box = container.firstChild;
                if (box) {
                    var br = box.getBoundingClientRect();
                    out.boxes.push({ l: Math.round(br.left), t: Math.round(br.top),
                                     w: Math.round(br.width), h: Math.round(br.height) });
                }
            }
        }
        return JSON.stringify(out);
    };

    var MIN_FONT_SIZE = 9;
    var FONT_STACK = '-apple-system, Roboto, sans-serif';

    function renderOne(url) {
        var payload = window.__mangalensOverlayState[url];
        if (!payload) return;
        var el = window.__mangalensElements[url];
        if (!el || !el.isConnected) {
            removeOverlay(url);
            return;
        }

        var existing = window.__mangalensContainers[url];
        if (window.__mangalensImageMode === 'original') {
            // Mode asli: sembunyikan semua tempelan tanpa membuang datanya.
            if (existing) existing.style.display = 'none';
            return;
        }
        var rect = el.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) {
            // Gambar sedang tidak dirender (lazy-load, pindah halaman, atau
            // dihapus situs). Sembunyikan overlay lama agar tidak tertinggal di
            // posisi sebelumnya; state tetap disimpan sehingga bisa tampil lagi.
            if (existing) existing.style.display = 'none';
            return;
        }


        var container = existing;
        if (container && container.style.display === 'none') container.style.display = '';
        if (!container) {
            container = document.createElement('div');
            container.className = 'mangalens-overlay';
            container.style.cssText =
                'position:fixed;pointer-events:none;z-index:9999;overflow:visible;';
            document.body.appendChild(container);
            window.__mangalensContainers[url] = container;
        }

        // Kontainer diposisikan memakai koordinat dokumen dan ukuran gambar
        // yang sedang dirender, sehingga selalu menempel pada gambar.
        container.style.left = rect.left + 'px';
        container.style.top = rect.top + 'px';
        container.style.width = rect.width + 'px';
        container.style.height = rect.height + 'px';
        // Kontainer memakai position:fixed, jadi tanpa klip kotak dialog di
        // luar area baca (mis. panel bawah) bisa muncul di latar hitam.
        var clip = null;
        var p = el.parentElement;
        while (p && p !== document.body) {
            var cs = window.getComputedStyle(p);
            if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
                var pr = p.getBoundingClientRect();
                clip = clip
                    ? { t: Math.max(clip.t, pr.top), l: Math.max(clip.l, pr.left),
                        b: Math.min(clip.b, pr.bottom), r: Math.min(clip.r, pr.right) }
                    : { t: pr.top, l: pr.left, b: pr.bottom, r: pr.right };
            }
            p = p.parentElement;
        }
        if (!clip) clip = { t: 0, l: 0, b: window.innerHeight, r: window.innerWidth };
        var clipT = Math.max(0, clip.t - rect.top);
        var clipL = Math.max(0, clip.l - rect.left);
        var clipB = Math.max(0, rect.bottom - clip.b);
        var clipR = Math.max(0, rect.right - clip.r);
        container.style.clipPath = (clipT + clipB + clipL + clipR > 0)
            ? 'inset(' + clipT + 'px ' + clipR + 'px ' + clipB + 'px ' + clipL + 'px)'
            : 'none';

        // Bangun ulang isi hanya jika jumlahnya berubah atau kontainer baru.
        var items = payload.items || [];
        var signature = items.length + ':' + (payload.version || 0) + ':' +
            Math.round(rect.width) + 'x' + Math.round(rect.height);
        if (container.getAttribute('data-signature') === signature) return;
        container.setAttribute('data-signature', signature);
        while (container.firstChild) container.removeChild(container.firstChild);

        for (var i = 0; i < items.length; i++) {
            var node = buildTranslationBox(items[i], rect.width, rect.height);
            if (node) container.appendChild(node);
        }
    }

    // ---------------------------------------------------------------
    // Adaptive text fitting
    // ---------------------------------------------------------------
    // Terjemahan Indonesia biasanya lebih panjang dari teks aslinya. Kotak
    // ditempatkan pada area teks asli (bukan seluruh balon), lalu font dicari
    // dari besar ke kecil sampai muat. Tidak ada kotak debug pada mode normal.
    window.__mangalensDebug = false;
    window.__mangalensSetDebug = function (enabled) {
        window.__mangalensDebug = !!enabled;
        renderAll();
        return true;
    };

    var measureCtx = null;
    function textMeasure(text, font) {
        if (!measureCtx) {
            var c = document.createElement('canvas');
            c.width = 8;
            c.height = 8;
            measureCtx = c.getContext('2d');
        }
        if (!measureCtx) return text.length * 8;
        measureCtx.font = font;
        return measureCtx.measureText(text).width;
    }

    function wrapText(text, maxWidth, font) {
        var words = String(text).replace(/\s+/g, ' ').trim().split(' ');
        var lines = [];
        var current = '';
        for (var i = 0; i < words.length; i++) {
            var word = words[i];
            if (!word) continue;
            var candidate = current ? current + ' ' + word : word;
            if (textMeasure(candidate, font) <= maxWidth) {
                current = candidate;
                continue;
            }
            if (current) {
                lines.push(current);
                current = '';
            }
            if (textMeasure(word, font) <= maxWidth) {
                current = word;
                continue;
            }
            // Kata tunggal lebih lebar dari area (mis. bahasa tanpa spasi).
            var chunk = '';
            for (var c = 0; c < word.length; c++) {
                var next = chunk + word.charAt(c);
                if (textMeasure(next, font) > maxWidth && chunk) {
                    lines.push(chunk);
                    chunk = word.charAt(c);
                } else {
                    chunk = next;
                }
            }
            current = chunk;
        }
        if (current) lines.push(current);
        return lines.length ? lines : [''];
    }

    function contrastOutline(hex) {
        var m = /^#?([0-9a-f]{6})$/i.exec(String(hex));
        if (!m) return 'rgba(0,0,0,0.55)';
        var v = parseInt(m[1], 16);
        var luma = ((v >> 16 & 255) * 299 + (v >> 8 & 255) * 587 + (v & 255) * 114) / 1000;
        return luma < 128 ? 'rgba(255,255,255,0.65)' : 'rgba(0,0,0,0.5)';
    }

    function buildTranslationBox(it, rectW, rectH) {
        var text = String(it.text || '').trim();
        if (!text) return null;

        var safe = {
            left: (it.fx || 0) * rectW,
            top: (it.fy || 0) * rectH,
            width: Math.max(1, (it.fw || 0) * rectW),
            height: Math.max(1, (it.fh || 0) * rectH)
        };
        var textRect = {
            left: (it.tx !== undefined ? it.tx : it.fx) * rectW,
            top: (it.ty !== undefined ? it.ty : it.fy) * rectH,
            width: Math.max(1, (it.tw !== undefined ? it.tw : it.fw) * rectW),
            height: Math.max(1, (it.th !== undefined ? it.th : it.fh) * rectH)
        };

        // Acuan utama selalu kotak teks asli. Latar putih hanya menutup area
        // teks itu (bukan seluruh balon), sehingga teks lama tidak membayang
        // dan ilustrasi/garis balon tetap utuh.
        var conf = String(it.conf || 'MEDIUM');
        // Teks Jepang vertikal punya kotak sempit dan tinggi. Terjemahan Latin
        // harus ditata horizontal, jadi area layout dilebarkan mendekati
        // ukuran tinggi kolom, tetapi tetap dibatasi area aman balon.
        var vertical = textRect.height > textRect.width * 1.6;
        var wantW = vertical
            ? Math.max(textRect.width * 1.10, Math.min(safe.width, textRect.height * 0.95))
            : Math.max(textRect.width * 1.12, 26);
        var wantH = vertical
            ? Math.max(textRect.height * 1.02, 44)
            : Math.max(textRect.height * 1.45, 22);
        var area = {
            left: textRect.left + textRect.width / 2 - wantW / 2,
            top: textRect.top + textRect.height / 2 - wantH / 2,
            width: Math.min(wantW, Math.max(24, safe.width)),
            height: Math.min(wantH, Math.max(20, safe.height))
        };

        // Jaga agar area tetap di dalam balon/gambar.
        area.width = Math.min(area.width, safe.width);
        area.height = Math.min(area.height, safe.height);
        area.left = Math.min(Math.max(area.left, safe.left), safe.left + safe.width - area.width);
        area.top = Math.min(Math.max(area.top, safe.top), safe.top + safe.height - area.height);

        if (area.width < 18 || area.height < 14) return null;

        var pad = Math.max(1, Math.min(5, area.height * 0.05));
        var availW = Math.max(8, area.width - pad * 2);
        var availH = Math.max(8, area.height - pad * 2);
        var maxFont = Math.max(9, Math.min(30, area.height * 0.82));
        var chosen = null;
        for (var size = Math.floor(maxFont); size >= MIN_FONT_SIZE; size--) {
            var font = '600 ' + size + 'px ' + FONT_STACK;
            var lines = wrapText(text, availW, font);
            var lh = Math.max(size * 1.14, size + 1);
            if (lines.length * lh <= availH) {
                chosen = { size: size, lines: lines, lineHeight: lh };
                break;
            }
        }
        if (!chosen) {
            // Ukuran minimum tetap dipakai; jangan memotong kata atau mengubah
            // makna. Area boleh tumbuh ke batas aman bila masih ada ruang.
            var minFont = '600 ' + MIN_FONT_SIZE + 'px ' + FONT_STACK;
            var lines = wrapText(text, availW, minFont);
            var lh = MIN_FONT_SIZE * 1.14;
            var needed = lines.length * lh + pad * 2;
            if (needed > area.height) {
                area.height = Math.min(safe.height, Math.max(area.height, needed));
                availH = Math.max(8, area.height - pad * 2);
            }
            chosen = { size: MIN_FONT_SIZE, lines: lines, lineHeight: lh };
        }

        var fg = it.fg || '#111111';
        var bg = it.bg || '#ffffff';
        // Latar harus opak: teks lama di bawahnya wajib tertutup, bukan tembus.
        var opaque = (function (c) {
            var m = String(c).match(/rgba?\(([^)]+)\)/);
            if (!m) return c;
            var p = m[1].split(',').map(function (v) { return parseFloat(v); });
            return 'rgb(' + Math.round(p[0] || 255) + ',' + Math.round(p[1] || 255) +
                ',' + Math.round(p[2] || 255) + ')';
        })(bg);
        var debug = !!window.__mangalensDebug;
        var node = document.createElement('div');
        node.className = 'mangalens-translation';
        node.style.cssText =
            'position:absolute;box-sizing:border-box;pointer-events:none;' +
            'display:flex;flex-direction:column;align-items:center;justify-content:center;' +
            'text-align:center;overflow:visible;' +
            'left:' + area.left + 'px;top:' + area.top + 'px;' +
            'width:' + area.width + 'px;height:auto;min-height:' + area.height + 'px;' +
            'padding:' + pad + 'px;border-radius:' + Math.min(6, pad + 2) + 'px;' +
            // LOW: jangan menaruh bidang besar di atas ilustrasi; tiap baris
            // mendapat latar opak setinggi baris agar teks lama tertutup.
            'background:' + (conf === 'LOW' ? 'transparent' : opaque) + ';' +
            'color:' + fg + ';font-weight:600;' +
            'line-height:' + chosen.lineHeight + 'px;font-size:' + chosen.size + 'px;' +
            'text-shadow:0 0 2px ' + contrastOutline(fg) + ';' +
            (debug ? 'outline:1px dashed #ffe000;' : '');

        for (var i = 0; i < chosen.lines.length; i++) {
            var span = document.createElement('span');
            span.textContent = chosen.lines[i];
            span.style.cssText = 'display:block;white-space:pre;box-sizing:border-box;' +
                (conf === 'LOW'
                    ? 'background:' + opaque + ';border-radius:3px;padding:1px 3px;width:100%;'
                    : '');
            node.appendChild(span);
        }
        return node;
    }

    function renderAll() {
        for (var url in window.__mangalensOverlayState) renderOne(url);
        // Bersihkan kontainer untuk gambar yang sudah tidak ada di dokumen.
        for (var c in window.__mangalensContainers) {
            if (!window.__mangalensOverlayState[c]) {
                var el = window.__mangalensContainers[c];
                if (el && el.parentNode) el.parentNode.removeChild(el);
                delete window.__mangalensContainers[c];
            }
        }
        return Object.keys(window.__mangalensContainers).length;
    }

    /**
     * Dipanggil dari Kotlin. payload = {url, items:[{fx,fy,fw,fh,bg,fg,text}]}.
     * Koordinat sudah dinormalisasi (0..1) terhadap ukuran gambar, jadi
     * penskalaan dilakukan terhadap ukuran render sebenarnya di sini.
     */
    window.__mangalensOverlay = function (payload) {
        if (!payload || !payload.url) return 0;
        var current = window.__mangalensOverlayState[payload.url];
        payload.version = current ? (current.version || 0) + 1 : 1;
        window.__mangalensOverlayState[payload.url] = payload;
        renderOne(payload.url);
        renderAll();
        // Ringkasan untuk verifikasi: jumlah kontainer + rect gambar + geometri
        // kotak pertama. Dipakai Kotlin untuk mencatat hasil ke Logcat.
        var el = window.__mangalensElements[payload.url];
        var imgRect = null;
        var firstBox = null;
        if (el && el.isConnected) {
            var r = el.getBoundingClientRect();
            imgRect = {w: Math.round(r.width), h: Math.round(r.height),
                       l: Math.round(r.left), t: Math.round(r.top)};
            var c = window.__mangalensContainers[payload.url];
            if (c && c.firstChild) {
                firstBox = {l: Math.round(c.firstChild.offsetLeft), t: Math.round(c.firstChild.offsetTop),
                            w: Math.round(c.firstChild.offsetWidth), h: Math.round(c.firstChild.offsetHeight)};
            }
        }
        return JSON.stringify({
            containers: Object.keys(window.__mangalensContainers).length,
            img: imgRect,
            box: firstBox
        });
    };

    window.__mangalensRemoveOverlay = function (url) {
        removeOverlay(url);
        return Object.keys(window.__mangalensContainers).length;
    };

    window.__mangalensClearOverlays = function () {
        for (var url in window.__mangalensContainers) {
            var el = window.__mangalensContainers[url];
            if (el && el.parentNode) el.parentNode.removeChild(el);
        }
        window.__mangalensContainers = {};
        window.__mangalensOverlayState = {};
        return 0;
    };

    function scheduleRender() {
        if (overlayTimer) return;
        overlayTimer = setTimeout(function () {
            overlayTimer = null;
            renderAll();
        }, 80);
    }

    function scheduleScan() {
        if (window.__mangalensActive === false) return;
        if (scanTimer) clearTimeout(scanTimer);
        scanTimer = setTimeout(function () {
            scanTimer = null;
            if (window.__mangalensActive === false) return;
            window.__mangalensScan();
        }, 350);
    }

    window.addEventListener('scroll', function () { scheduleScan(); scheduleRender(); }, { passive: true });
    // Scroll pada container internal tidak bubble; capture menangkapnya.
    document.addEventListener('scroll', function () { scheduleScan(); scheduleRender(); }, {
        capture: true,
        passive: true
    });
    window.addEventListener('resize', function () { scheduleScan(); scheduleRender(); });
    window.addEventListener('load', function () { scheduleScan(); scheduleRender(); });

    if (window.MutationObserver && document.documentElement) {
        var mutationObserver = new MutationObserver(function () { scheduleScan(); scheduleRender(); });
        mutationObserver.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['src', 'srcset', 'data-src', 'data-lazy-src', 'data-original', 'style', 'class', 'width', 'height']
        });
    }

    if (window.IntersectionObserver) {
        var intersectionObserver = new IntersectionObserver(function () { scheduleScan(); }, {
            rootMargin: '1200px'
        });
        var imgs = document.querySelectorAll('img');
        for (var j = 0; j < imgs.length; j++) intersectionObserver.observe(imgs[j]);
    }

    installCanvasPaintHooks();

    // Jaring pengaman murah: bila ada canvas yang dilukis ulang tanpa memicu
    // observer (mis. lukisan dari library), pindai maksimal sekali per 2 detik.
    // Tidak berjalan saat halaman tidak aktif atau tab disembunyikan.
    setInterval(function () {
        if (window.__mangalensActive === false || document.hidden) return;
        var nodes = document.querySelectorAll('canvas');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var rev = el.__mangalensPaintSeq || 0;
            if ((el.__mangalensPaintPending || rev > 0) &&
                rev !== (el.__mangalensScannedSeq || 0)) {
                scheduleScan();
                return;
            }
        }
    }, 2000);

    if (document.addEventListener) {
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) scheduleScan();
        });
    }

    scheduleScan();
    return 'installed';
})();
