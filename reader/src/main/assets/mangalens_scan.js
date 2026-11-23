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

    window.__mangalensScan = function () {
        var fresh = collect();
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

        var scrollX = window.scrollX || window.pageXOffset || 0;
        var scrollY = window.scrollY || window.pageYOffset || 0;

        var container = existing;
        if (container && container.style.display === 'none') container.style.display = '';
        if (!container) {
            container = document.createElement('div');
            container.className = 'mangalens-overlay';
            container.style.cssText =
                'position:absolute;pointer-events:none;z-index:9999;overflow:visible;';
            document.body.appendChild(container);
            window.__mangalensContainers[url] = container;
        }

        // Kontainer diposisikan memakai koordinat dokumen dan ukuran gambar
        // yang sedang dirender, sehingga selalu menempel pada gambar.
        container.style.left = (rect.left + scrollX) + 'px';
        container.style.top = (rect.top + scrollY) + 'px';
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
            var fontSize = Math.max(9, Math.min(26, height * 0.52));

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
    window.addEventListener('resize', function () { scheduleScan(); scheduleRender(); });
    window.addEventListener('load', function () { scheduleScan(); scheduleRender(); });

    if (window.MutationObserver && document.documentElement) {
        var mutationObserver = new MutationObserver(function () { scheduleScan(); scheduleRender(); });
        mutationObserver.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['src', 'srcset', 'data-src', 'data-lazy-src', 'data-original', 'style', 'class']
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
