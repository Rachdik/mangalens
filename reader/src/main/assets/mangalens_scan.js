(function () {
    if (window.__mangalensInstalled) {
        window.__mangalensScan();
        return 'already-installed';
    }
    window.__mangalensInstalled = true;

    var seq = 0;
    var sent = {};
    var scanTimer = null;

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
            if (!url || sent[url]) continue;
            sent[url] = 1;

            out.push({
                url: url,
                w: Math.round(naturalW),
                h: Math.round(naturalH),
                rectW: Math.round(rect.width),
                rectH: Math.round(rect.height),
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

    function scheduleScan() {
        if (scanTimer) clearTimeout(scanTimer);
        scanTimer = setTimeout(function () { window.__mangalensScan(); }, 350);
    }

    window.addEventListener('scroll', scheduleScan, { passive: true });
    window.addEventListener('resize', scheduleScan);
    window.addEventListener('load', scheduleScan);

    if (window.MutationObserver && document.documentElement) {
        var mutationObserver = new MutationObserver(scheduleScan);
        mutationObserver.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['src', 'srcset', 'data-src', 'data-lazy-src', 'data-original']
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
