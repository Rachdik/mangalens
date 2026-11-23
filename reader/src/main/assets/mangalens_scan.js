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

            // Rasio halaman komik; menyaring strip iklan/dekorasi yang sangat pipih.
            var ratio = rect.width / rect.height;
            if (ratio < 0.25 || ratio > 4) continue;

            var viewer = canvasInViewer(el);

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
            // Tanpa sidik jari (canvas tainted), identitas elemen dipakai agar key
            // tetap stabil saat halaman digulir dan unik antar canvas.
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
            var key = 'canvas:v2:' + pageKey + ':' + (sig ? 'h' + sig : el.__mangalensCanvasId) +
                ':' + bw + 'x' + bh;

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
        return JSON.stringify({
            ok: true,
            cssW: rect.width,
            cssH: rect.height,
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
        var target = scroller.scrollTop + (elRect.top - scRect.top) + offsetCss;
        scroller.scrollTop = Math.max(0, target);

        var r = el.getBoundingClientRect();
        var top = Math.max(0, scRect.top, r.top);
        var bottom = Math.min(window.innerHeight, scRect.bottom, r.bottom);
        return JSON.stringify({
            ok: true,
            left: r.left,
            width: r.width,
            sliceTop: top,
            sliceHeight: Math.max(0, bottom - top),
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
        var top = Math.max(0, scRect ? scRect.top : 0, r.top);
        var bottom = Math.min(window.innerHeight, scRect ? scRect.bottom : window.innerHeight, r.bottom);
        return JSON.stringify({
            ok: true,
            left: r.left,
            width: r.width,
            sliceTop: top,
            sliceHeight: Math.max(0, bottom - top),
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

    // ---------------------------------------------------------------
    // Overlay terjemahan
    // ---------------------------------------------------------------

    function removeOverlay(url) {
        var c = window.__mangalensContainers[url];
        if (c && c.parentNode) c.parentNode.removeChild(c);
        delete window.__mangalensContainers[url];
        delete window.__mangalensOverlayState[url];
    }

    function renderOne(url) {
        var payload = window.__mangalensOverlayState[url];
        if (!payload) return;
        var el = window.__mangalensElements[url];
        if (!el || !el.isConnected) {
            removeOverlay(url);
            return;
        }

        var rect = el.getBoundingClientRect();
        var existing = window.__mangalensContainers[url];
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

        // Bangun ulang isi hanya jika jumlahnya berubah atau kontainer baru.
        var items = payload.items || [];
        var signature = items.length + ':' + (payload.version || 0) + ':' +
            Math.round(rect.width) + 'x' + Math.round(rect.height);
        if (container.getAttribute('data-signature') === signature) return;
        container.setAttribute('data-signature', signature);
        while (container.firstChild) container.removeChild(container.firstChild);

        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            var d = document.createElement('div');
            d.className = 'mangalens-translation';

            // Koordinat fraksi (0..1) terhadap gambar, sehingga ikut menyesuaikan
            // bila gambar diperkecil/diperbesar oleh CSS situs.
            var left = it.fx * rect.width;
            var top = it.fy * rect.height;
            var width = Math.max(8, it.fw * rect.width);
            var height = Math.max(8, it.fh * rect.height);
            if (left + width > rect.width) width = Math.max(8, rect.width - left);
            if (top + height > rect.height) height = Math.max(8, rect.height - top);

            // Ukuran teks mengikuti tinggi kotak: kecil untuk balon kecil,
            // dibatasi agar tetap terbaca dan tidak meluber.
            var fontSize = Math.max(8, Math.min(24, Math.min(height * 0.45, width / 6.5)));

            d.style.cssText =
                'position:absolute;box-sizing:border-box;overflow:hidden;' +
                'padding:1px 2px;border-radius:3px;pointer-events:none;' +
                'display:flex;align-items:center;justify-content:center;text-align:center;' +
                'line-height:1.15;word-break:break-word;' +
                'left:' + left + 'px;top:' + top + 'px;width:' + width + 'px;height:' + height + 'px;' +
                'font-size:' + fontSize + 'px;' +
                'background-color:' + it.bg + ';color:' + it.fg + ';';
            d.textContent = it.text;
            container.appendChild(d);
        }
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
        if (scanTimer) clearTimeout(scanTimer);
        scanTimer = setTimeout(function () { window.__mangalensScan(); }, 350);
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

    scheduleScan();
    return 'installed';
})();
