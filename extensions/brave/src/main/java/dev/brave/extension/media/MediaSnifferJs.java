package dev.brave.extension.media;

public final class MediaSnifferJs {
    private MediaSnifferJs() {}

    public static final String SNIFFER_SCRIPT =
        "(function() {" +
        "  if (window.__brave_sniffer_installed__) return;" +
        "  window.__brave_sniffer_installed__ = true;" +
        "" +
        "  var pendingQueue = [];" +
        "  var reportedUrls = new Set();" +
        "" +
        "  function toAbsolute(url) {" +
        "    try {" +
        "      return new URL(url, window.location.href).href;" +
        "    } catch(e) {" +
        "      return url;" +
        "    }" +
        "  }" +
        "" +
        "  function flushQueue() {" +
        "    if (!window.BraveMediaBridge || pendingQueue.length === 0) return;" +
        "    while (pendingQueue.length > 0) {" +
        "      var item = pendingQueue.shift();" +
        "      try {" +
        "        window.BraveMediaBridge.onMediaFound(JSON.stringify(item));" +
        "      } catch(e) {}" +
        "    }" +
        "  }" +
        "" +
        "  function report(url, type, mime, title, width, height, size, dur) {" +
        "    var absUrl = toAbsolute(url);" +
        "    if (!absUrl) return;" +
        "    var lower = absUrl.toLowerCase();" +
        "    if (!lower.startsWith('http://') && !lower.startsWith('https://')) return;" +
        "" +
        "    // Filter raw chunk segments (.ts, .m4s) so picker isn't flooded" +
        "    if (lower.indexOf('.ts?') !== -1 || lower.endsWith('.ts') || lower.indexOf('.m4s') !== -1) return;" +
        "" +
        "    var payload = {" +
        "      url: absUrl," +
        "      type: type," +
        "      mimeType: mime || ''," +
        "      title: title || document.title || 'Media'," +
        "      width: width || 0," +
        "      height: height || 0," +
        "      size: size || 0," +
        "      duration: dur || 0," +
        "      cookie: document.cookie || ''," +
        "      referer: window.location.href," +
        "      userAgent: navigator.userAgent" +
        "    };" +
        "" +
        "    if (window.BraveMediaBridge) {" +
        "      flushQueue();" +
        "      try {" +
        "        window.BraveMediaBridge.onMediaFound(JSON.stringify(payload));" +
        "      } catch(e) {}" +
        "    } else {" +
        "      if (pendingQueue.length < 50) pendingQueue.push(payload);" +
        "    }" +
        "  }" +
        "" +
        "  function checkAndReport(url, type, mime, title, w, h, s, d) {" +
        "    if (!url) return;" +
        "    var absUrl = toAbsolute(url);" +
        "    if (reportedUrls.has(absUrl)) return;" +
        "    if (absUrl.indexOf('google-analytics') !== -1 || absUrl.indexOf('/telemetry') !== -1) return;" +
        "    reportedUrls.add(absUrl);" +
        "    report(absUrl, type, mime, title, w, h, s, d);" +
        "  }" +
        "" +
        "  // 1. Hook window.fetch" +
        "  var origFetch = window.fetch;" +
        "  if (origFetch) {" +
        "    window.fetch = function() {" +
        "      var args = arguments;" +
        "      var rawUrl = '';" +
        "      if (typeof args[0] === 'string') rawUrl = args[0];" +
        "      else if (args[0] && args[0].url) rawUrl = args[0].url;" +
        "      else if (args[0] && args[0].href) rawUrl = args[0].href;" +
        "" +
        "      return origFetch.apply(this, args).then(function(res) {" +
        "        try {" +
        "          var resUrl = res.url || rawUrl;" +
        "          var ct = (res.headers.get('content-type') || '').toLowerCase();" +
        "          var lowerUrl = (resUrl || '').toLowerCase();" +
        "          if (lowerUrl.indexOf('.m3u8') !== -1 || ct.indexOf('mpegurl') !== -1) {" +
        "            checkAndReport(resUrl, 'HLS_STREAM', ct);" +
        "          } else if (lowerUrl.indexOf('.mpd') !== -1 || ct.indexOf('dash+xml') !== -1) {" +
        "            checkAndReport(resUrl, 'DASH_STREAM', ct);" +
        "          } else if (ct.startsWith('video/')) {" +
        "            checkAndReport(resUrl, 'VIDEO', ct);" +
        "          } else if (ct.startsWith('audio/')) {" +
        "            checkAndReport(resUrl, 'AUDIO', ct);" +
        "          }" +
        "        } catch(e) {}" +
        "        return res;" +
        "      });" +
        "    };" +
        "  }" +
        "" +
        "  // 2. Hook XMLHttpRequest" +
        "  var origOpen = XMLHttpRequest.prototype.open;" +
        "  var origSend = XMLHttpRequest.prototype.send;" +
        "  XMLHttpRequest.prototype.open = function(method, url) {" +
        "    this.__xhr_url = (url && url.href) ? url.href : String(url);" +
        "    return origOpen.apply(this, arguments);" +
        "  };" +
        "  XMLHttpRequest.prototype.send = function() {" +
        "    var xhr = this;" +
        "    if (!xhr.__sniffer_hooked__) {" +
        "      xhr.__sniffer_hooked__ = true;" +
        "      xhr.addEventListener('load', function() {" +
        "        try {" +
        "          var u = xhr.responseURL || xhr.__xhr_url;" +
        "          if (!u) return;" +
        "          var ct = (xhr.getResponseHeader('Content-Type') || '').toLowerCase();" +
        "          var lower = u.toLowerCase();" +
        "          if (lower.indexOf('.m3u8') !== -1 || ct.indexOf('mpegurl') !== -1) {" +
        "            checkAndReport(u, 'HLS_STREAM', ct);" +
        "          } else if (lower.indexOf('.mpd') !== -1 || ct.indexOf('dash+xml') !== -1) {" +
        "            checkAndReport(u, 'DASH_STREAM', ct);" +
        "          } else if (ct.startsWith('video/')) {" +
        "            checkAndReport(u, 'VIDEO', ct);" +
        "          } else if (ct.startsWith('audio/')) {" +
        "            checkAndReport(u, 'AUDIO', ct);" +
        "          }" +
        "        } catch(e) {}" +
        "      });" +
        "    }" +
        "    return origSend.apply(this, arguments);" +
        "  };" +
        "" +
        "  // 3. Scan DOM Video, Audio, and Image elements" +
        "  function scanDom() {" +
        "    var videos = document.querySelectorAll('video, audio, source');" +
        "    for (var i = 0; i < videos.length; i++) {" +
        "      var node = videos[i];" +
        "      var src = node.currentSrc || node.src;" +
        "      if (src) {" +
        "        var isAudio = node.tagName.toLowerCase() === 'audio' || (node.type && node.type.indexOf('audio') !== -1);" +
        "        var w = node.videoWidth || 0;" +
        "        var h = node.videoHeight || 0;" +
        "        var dur = node.duration ? Math.round(node.duration * 1000) : 0;" +
        "        if (src.indexOf('.m3u8') !== -1) {" +
        "          checkAndReport(src, 'HLS_STREAM', '', document.title, w, h, 0, dur);" +
        "        } else {" +
        "          checkAndReport(src, isAudio ? 'AUDIO' : 'VIDEO', node.type || '', document.title, w, h, 0, dur);" +
        "        }" +
        "      }" +
        "    }" +
        "    var imgs = document.querySelectorAll('img');" +
        "    for (var j = 0; j < imgs.length; j++) {" +
        "      var img = imgs[j];" +
        "      var isrc = img.currentSrc || img.src;" +
        "      if (isrc) {" +
        "        if (img.naturalWidth > 120 && img.naturalHeight > 120) {" +
        "          checkAndReport(isrc, 'IMAGE', '', img.alt || document.title, img.naturalWidth, img.naturalHeight);" +
        "        } else if (!img.complete) {" +
        "          img.addEventListener('load', function() {" +
        "            if (this.naturalWidth > 120 && this.naturalHeight > 120) {" +
        "              checkAndReport(this.currentSrc || this.src, 'IMAGE', '', this.alt || document.title, this.naturalWidth, this.naturalHeight);" +
        "            }" +
        "          }, { once: true });" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "" +
        "  // 4. Debounced MutationObserver (250ms)" +
        "  var debounceTimer = null;" +
        "  var observer = new MutationObserver(function() {" +
        "    if (debounceTimer) clearTimeout(debounceTimer);" +
        "    debounceTimer = setTimeout(function() {" +
        "      scanDom();" +
        "      flushQueue();" +
        "    }, 250);" +
        "  });" +
        "  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset'] });" +
        "" +
        "  // 5. PerformanceObserver + buffered inspect" +
        "  try {" +
        "    var inspectPerfEntry = function(entry) {" +
        "      var name = entry.name;" +
        "      if (name) {" +
        "        var lower = name.toLowerCase();" +
        "        if (lower.indexOf('.m3u8') !== -1) checkAndReport(name, 'HLS_STREAM');" +
        "        else if (lower.indexOf('.mpd') !== -1) checkAndReport(name, 'DASH_STREAM');" +
        "        else if (lower.indexOf('.mp4') !== -1 || lower.indexOf('.webm') !== -1) checkAndReport(name, 'VIDEO');" +
        "      }" +
        "    };" +
        "    var entries = performance.getEntriesByType('resource');" +
        "    for (var p = 0; p < entries.length; p++) inspectPerfEntry(entries[p]);" +
        "    var perfObs = new PerformanceObserver(function(list) {" +
        "      var l = list.getEntries();" +
        "      for (var k = 0; k < l.length; k++) inspectPerfEntry(l[k]);" +
        "    });" +
        "    perfObs.observe({ entryTypes: ['resource'] });" +
        "  } catch(e) {}" +
        "" +
        "  scanDom();" +
        "  flushQueue();" +
        "})();";
}
