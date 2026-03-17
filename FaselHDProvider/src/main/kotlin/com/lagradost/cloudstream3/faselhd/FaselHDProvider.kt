package com.lagradost.cloudstream3.faselhd

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Cookie
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

import android.webkit.JavascriptInterface
import java.net.URI

private val EPISODE_IFRAME_RE = Regex(
    """<iframe[^>]+name=["']playeriframe["'][^>]+(?:data-src|src)=["']([^"']+videoplayer\?playertoken[^"']+)["']""",
    RegexOption.IGNORE_CASE
)

private val EPISODE_TAB_RE = Regex(
    """playeriframe\.location\.href\s*=\s*["']([^"']+videoplayer\?playertoken[^"']+)["']""",
    RegexOption.IGNORE_CASE
)

private val ABS_MEDIA_RE = Regex(
    """https?://[^\s"'<>\\]+?(?:\.m3u8(?:\?[^"'<>\\]*)?|\.mp4(?:\?[^"'<>\\]*)?)""",
    setOf(RegexOption.IGNORE_CASE)
)

private val REL_MEDIA_RE = Regex(
    """(?:"|')((?:/)[^"'<>]+?(?:\.m3u8(?:\?[^"'<>]*)?|\.mp4(?:\?[^"'<>]*)?))(?:"|')""",
    setOf(RegexOption.IGNORE_CASE)
)

private val JSON_FILE_RE = Regex(
    """"(?:file|src)"\s*:\s*"([^"]+?(?:\.m3u8(?:\?[^"\\]*)?|\.mp4(?:\?[^"\\]*)?))""" + "\"",
    setOf(RegexOption.IGNORE_CASE)
)

private fun String.cleanJsUrl(): String {
    return this
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()
        .trim('"', '\'')
}

private fun resolveUrl(baseUrl: String, raw: String): String {
    val cleaned = raw.cleanJsUrl()
    return try {
        URI(baseUrl).resolve(cleaned).toString()
    } catch (_: Throwable) {
        cleaned
    }
}

private fun looksLikeMediaUrl(url: String): Boolean {
    val u = url.lowercase()
    return ".m3u8" in u || ".mp4" in u
}

private val EPISODE_IFRAME_BROAD_RE = Regex(
    """<iframe[^>]+(?:data-src|src)=["']([^"']+(?:videoplayer|video_player)[^"']+)["']""",
    RegexOption.IGNORE_CASE
)

private fun extractEpisodePlayerUrls(html: String, baseUrl: String): List<String> {
    val out = linkedSetOf<String>()

    fun add(raw: String?) {
        if (raw.isNullOrBlank()) return
        val resolved = resolveUrl(baseUrl, raw)
        if ("videoplayer" in resolved || "video_player" in resolved) out += resolved
    }

    val iframeMatches = EPISODE_IFRAME_RE.findAll(html).toList()
    iframeMatches.forEach { add(it.groupValues.getOrNull(1)) }
    
    val broadIframeMatches = EPISODE_IFRAME_BROAD_RE.findAll(html).toList()
    broadIframeMatches.forEach { add(it.groupValues.getOrNull(1)) }

    val tabHrefMatches = EPISODE_TAB_RE.findAll(html).toList()
    tabHrefMatches.forEach { add(it.groupValues.getOrNull(1)) }

    Log.i("FaselHD", "PARSE-IFRAME matches=${iframeMatches.size}")
    Log.i("FaselHD", "PARSE-IFRAME-BROAD matches=${broadIframeMatches.size}")
    Log.i("FaselHD", "PARSE-TAB-HREF matches=${tabHrefMatches.size}")
    Log.i("FaselHD", "PARSE-FINAL candidates=${out.size}")
    Log.i("FaselHD", "PARSE-SAMPLE html=${html.take(1200).replace("\n", " ")}")
    
    return out.toList()
}

private fun extractMediaUrlsFromText(text: String, baseUrl: String): List<String> {
    val out = linkedSetOf<String>()

    fun add(raw: String?) {
        if (raw.isNullOrBlank()) return
        val resolved = resolveUrl(baseUrl, raw)
        if (looksLikeMediaUrl(resolved)) out += resolved
    }

    ABS_MEDIA_RE.findAll(text).forEach { add(it.value) }
    REL_MEDIA_RE.findAll(text).forEach { add(it.groupValues.getOrNull(1)) }
    JSON_FILE_RE.findAll(text).forEach { add(it.groupValues.getOrNull(1)) }

    return out.toList()
}

private class ProbeBridge(
    private val pageUrl: String,
    private val completed: AtomicBoolean,
    private val onMediaUrl: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    @JavascriptInterface
    fun post(raw: String?) {
        if (raw.isNullOrBlank() || completed.get()) return

        try {
            val obj = JSONObject(raw)
            when (obj.optString("kind")) {
                "JW-SETUP" -> {
                    val u = obj.optString("url")
                    if (!u.isNullOrBlank() && completed.compareAndSet(false, true)) {
                        Log.i("FaselHD", "BRIDGE-SUCCESS via JW-SETUP: $u")
                        onMediaUrl(u)
                    }
                }
                "JW-SETUP-RAW" -> {
                    val text = obj.optString("text")
                    log("JW-SETUP-RAW: $text")
                }
                "XHR-STREAM" -> {
                    val u = obj.optString("text")
                    if (!u.isNullOrBlank() && completed.compareAndSet(false, true)) {
                        Log.i("FaselHD", "BRIDGE-SUCCESS via XHR-STREAM: $u")
                        onMediaUrl(u)
                    }
                }
                "XHR-BODY" -> {
                    val url = obj.optString("url")
                    val text = obj.optString("text")
                    log("XHR-BODY from $url: $text")
                }
                "candidate" -> {
                    val url = obj.optString("url")
                    val via = obj.optString("via").ifBlank { obj.optString("kind") }
                    Log.i("FaselHD", "BRIDGE-CANDIDATE via=$via url=$url")
                    if (looksLikeMediaUrl(url) && completed.compareAndSet(false, true)) {
                        Log.i("FaselHD", "BRIDGE-SUCCESS via=$via resolved=$url")
                        onMediaUrl(url)
                    }
                }

                "JW-SETUP-RAW", "body" -> {
                    val base = obj.optString("baseUrl").ifBlank { pageUrl }
                    val body = obj.optString("text")
                    val via = obj.optString("via").ifBlank { obj.optString("kind") }
                    val urls = extractMediaUrlsFromText(body, base)
                    val first = urls.firstOrNull() ?: return
                    Log.i("FaselHD", "BRIDGE-BODY-HIT via=$via firstUrl=$first")
                    if (completed.compareAndSet(false, true)) {
                        Log.i("FaselHD", "BRIDGE-SUCCESS via=$via resolved=$first")
                        onMediaUrl(first)
                    }
                }

                "log" -> {
                    val msg = obj.optString("msg")
                    Log.i("FaselHD", "BRIDGE-RAW kind=log url=none len=0 msg=$msg")
                    log(msg)
                }
            }
        } catch (t: Throwable) {
            log("probe parse error: ${t.message}")
        }
    }
}

class FaselHDProvider : MainAPI() {
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val baseDomain = "faselhdx.bid"
    override var mainUrl = "https://web31712x.$baseDomain"

    private var userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()
    private val extractionMutex = Mutex()

    private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mp4", ".ts", ".mpd", ".webm", ".mkv")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg")
    private val ioScope = CoroutineScope(Dispatchers.IO)


    private fun isVideoMediaUrl(url: String): Boolean {
        if (url.contains("challenges.cloudflare.com", true) || url.contains("cdn-cgi", true)) return false

        val lower = url.lowercase()
        
        if (IMAGE_EXTENSIONS.any { lower.contains(it) } || 
            lower.contains("favicon") || 
            lower.contains("thumb") || 
            lower.contains("track")) return false

        val isVideo = VIDEO_EXTENSIONS.any { lower.contains(it) }
                || lower.contains("manifest")
                || lower.contains("playlist")
                || lower.contains("stream")
        return isVideo
    }

    private fun isUsefulMediaUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") ||
               u.contains(".mp4") ||
               u.contains("master.m3u8") ||
               u.contains("playlist.m3u8") ||
               u.contains("scdns")
    }

    private fun String.normalizeHttpUrl(): String {
        var s = trim()
        s = s.replace(Regex("^(https?:/)(?!/)"), "$1/")
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        s = s.replace("https://https://", "https://")
        s = s.replace("http://http://", "http://")
        return s
    }

    private fun String.hostOrigin(): String {
        return runCatching {
            val u = android.net.Uri.parse(this.normalizeHttpUrl())
            "${u.scheme}://${u.host}"
        }.getOrDefault(this)
    }

    private fun normalizeMediaUrl(url: String): String? {
        return url.trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private enum class ResolveExit {
        SUCCESS_MEDIA,
        FAIL_HTTP_404,
        FAIL_NO_PLAYER_URL,
        FAIL_PROBE_NO_HIT,
        FAIL_TIMEOUT
    }

    private data class CaptureSession(
        val gen: Int,
        val targetUrl: String,
        val result: kotlinx.coroutines.CompletableDeferred<String?> = kotlinx.coroutines.CompletableDeferred(),
        val streamFound: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        val quietGateStarted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        val captureWindowStarted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        val closed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    ) {
        fun completeSuccess(url: String) {
            if (streamFound.compareAndSet(false, true)) {
                Log.i("FaselHD", "Gen $gen completing SUCCESS with $url")
                result.complete(url)
            }
        }
        
        fun completeFailure(reason: String) {
            if (!streamFound.get() && closed.compareAndSet(false, true)) {
                Log.i("FaselHD", "Gen $gen completing FAILURE: $reason")
                result.complete("FAIL_$reason")
            }
        }
    }

    private val captureGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeSession = java.util.concurrent.atomic.AtomicReference<CaptureSession?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedPlayerHtml: String? = null

    private fun buildJwProbeJs(): String = """
(function() {
  if (window.__faselProbeInstalled) return;
  window.__faselProbeInstalled = true;

  var BRIDGE = window.FaselProbe;
  var seen = Object.create(null);

  function post(obj) {
    try { BRIDGE.post(JSON.stringify(obj)); } catch (e) {}
  }

  function log(msg) {
    post({ kind: "log", msg: String(msg || "") });
  }

  function once(url, via) {
    if (!url || seen[url]) return false;
    seen[url] = true;
    log("PROBE-SCAN-HIT via=" + via + " url=" + url);
    post({ kind: "candidate", url: url, via: via || "" });
    return true;
  }

  function abs(raw, base) {
    try { return new URL(raw, base || location.href).toString(); }
    catch (e) { return raw; }
  }

  function looksMedia(raw) {
    if (!raw) return false;
    var u = String(raw).toLowerCase();
    return u.indexOf(".m3u8") !== -1 || u.indexOf(".mp4") !== -1;
  }

  function scanText(text, base, via) {
    if (!text) return false;
    try {
      var s = String(text);

      var reAbs = /https?:\/\/[^\s"'<>\\]+?(?:\.m3u8(?:\?[^"'<>\\]*)?|\.mp4(?:\?[^"'<>\\]*)?)/ig;
      var reRel = /["']((?:\/|\\\/)[^"'<>]+?(?:\.m3u8(?:\?[^"'<>]*)?|\.mp4(?:\?[^"'<>]*)?))["']/ig;
      var reJson = /"(?:file|src)"\s*:\s*"([^"]+?(?:\.m3u8(?:\?[^"\\]*)?|\.mp4(?:\?[^"\\]*)?))"/ig;

      var m, hit = false;

      while ((m = reAbs.exec(s)) !== null) {
        var u1 = m[0].replace(/\\\//g, "/");
        if (looksMedia(u1)) hit = once(abs(u1, base), via) || hit;
      }

      while ((m = reRel.exec(s)) !== null) {
        var u2 = m[1].replace(/\\\//g, "/");
        if (looksMedia(u2)) hit = once(abs(u2, base), via) || hit;
      }

      while ((m = reJson.exec(s)) !== null) {
        var u3 = m[1].replace(/\\\//g, "/");
        if (looksMedia(u3)) hit = once(abs(u3, base), via) || hit;
      }

      if (!hit && (s.indexOf(".m3u8") !== -1 || s.indexOf(".mp4") !== -1)) {
        post({ kind: "body", text: s.slice(0, 250000), baseUrl: base || location.href, via: via || "" });
      }
      return hit;
    } catch (e) {
      log("scanText error: " + e);
      return false;
    }
  }

  function scanPlaylist(obj, via) {
    try {
      if (!obj) return false;
      var hit = false;

      if (typeof obj === "string") {
        return scanText(obj, location.href, via);
      }

      if (Array.isArray(obj)) {
        obj.forEach(function(it) { if (scanPlaylist(it, via)) hit = true; });
        return hit;
      }

      if (obj.file && looksMedia(obj.file)) hit = once(abs(obj.file, location.href), via) || hit;
      if (obj.src && looksMedia(obj.src)) hit = once(abs(obj.src, location.href), via) || hit;

      if (Array.isArray(obj.sources)) {
        obj.sources.forEach(function(src) {
          if (!src) return;
          var u = src.file || src.src;
          if (looksMedia(u)) hit = once(abs(u, location.href), via) || hit;
        });
      }

      Object.keys(obj).forEach(function(k) {
        var v = obj[k];
        if (v && typeof v === "object") {
          if (scanPlaylist(v, via)) hit = true;
        } else if (typeof v === "string" && looksMedia(v)) {
          hit = once(abs(v, location.href), via) || hit;
        }
      });

      return hit;
    } catch (e) {
      log("scanPlaylist error: " + e);
      return false;
    }
  }

  function probeJw() {
    try {
      var jw = window.jwplayer;
      if (!jw) return false;

      for (var i = 0; i < 8; i++) {
        try {
          var p = jw(i);
          if (!p) continue;

          try {
            var playlist = p.getPlaylist && p.getPlaylist();
            if (scanPlaylist(playlist, "jw.getPlaylist")) return true;
          } catch (e) {}

          try {
            var cfg = p.getConfig && p.getConfig();
            if (scanPlaylist(cfg, "jw.getConfig")) return true;
          } catch (e) {}

          try {
            var item = p.getPlaylistItem && p.getPlaylistItem();
            if (scanPlaylist(item, "jw.getPlaylistItem")) return true;
          } catch (e) {}
        } catch (e) {}
      }
    } catch (e) {
      log("probeJw error: " + e);
    }
    return false;
  }

  try {
    log("PROBE-INSTALLED href=" + location.href);
    
    // Patch fetch to always include credentials and scan response
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function(resource, init) {
        init = init || {};
        init.credentials = init.credentials || 'include';
        var fetchUrl = (resource && resource.url) ? resource.url : String(resource || "");
        return origFetch.call(this, resource, init).then(function(resp) {
          try {
            var url = (resp && resp.url) || fetchUrl || location.href;
            log("PROBE-FETCH url=" + url);
            if (looksMedia(url)) once(abs(url, location.href), "fetch.url");

            var clone = resp.clone();
            clone.text().then(function(txt) {
              if (txt.includes('.m3u8') || txt.includes('.mp4')) {
                var m = txt.match(/https?:\/\/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*/i);
                if (m) post({ kind: "XHR-STREAM", url: url, text: m[0] });
              }
              scanText(txt, url, "fetch.body");
            }).catch(function(){});
          } catch (e) {}
          return resp;
        });
      };
    }

    // Patch XHR to always include credentials and scan response
    var XO = XMLHttpRequest.prototype.open;
    var XS = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url) {
      this.withCredentials = true;
      this.__faselUrl = (url && url.url) ? url.url : String(url || "");
      return XO.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function() {
      this.addEventListener("load", function() {
        try {
          var finalUrl = this.responseURL || this.__faselUrl || location.href;
          log("PROBE-XHR url=" + finalUrl);
          if (looksMedia(finalUrl)) once(abs(finalUrl, location.href), "xhr.url");

          var text = "";
          try { text = this.responseType === "" || this.responseType === "text" ? this.responseText : ""; } catch (e) {}
          if (text) {
             if (text.includes('.m3u8') || text.includes('.mp4')) {
                var m = text.match(/https?:\/\/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*/i);
                if (m) post({ kind: "XHR-STREAM", url: finalUrl, text: m[0] });
             } else if (text.length > 0 && text.length < 5000) {
                post({ kind: "XHR-BODY", url: finalUrl, text: text.substring(0, 500) });
             }
             scanText(text, finalUrl, "xhr.body");
          }
        } catch (e) {}
      });
      return XS.apply(this, arguments);
    };
  } catch (e) {
    log("probe hooks error: " + e);
  }

  try { scanText(document.documentElement.outerHTML, location.href, "dom.initial"); } catch (e) {}

  var ticks = 0;
  var iv = setInterval(function() {
    ticks++;
    if (probeJw()) { clearInterval(iv); return; }
    try { scanText(document.documentElement.outerHTML, location.href, "dom.tick"); } catch (e) {}
    if (ticks >= 120) clearInterval(iv);
  }, 1000);

  try {
    new MutationObserver(function() {
      probeJw();
      try { scanText(document.documentElement.outerHTML, location.href, "dom.mutation"); } catch (e) {}
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true });
  } catch (e) {}
})();
""".trimIndent()

    private fun buildJwHookScript(): String {
        return """
            (function() {
                if (window.__faselHooked) return;
                window.__faselHooked = true;
                
                var _jwImpl = window.jwplayer;
                Object.defineProperty(window, 'jwplayer', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return _jwImpl; },
                    set: function(jw) {
                        if (typeof jw !== 'function') { _jwImpl = jw; return; }
                        _jwImpl = function() {
                            var inst = jw.apply(this, arguments);
                            if (inst && inst.setup) {
                                var origSetup = inst.setup.bind(inst);
                                inst.setup = function(cfg) {
                                    try {
                                        var src = (cfg.file)
                                            || (cfg.playlist && cfg.playlist[0] && cfg.playlist[0].file)
                                            || (cfg.sources && cfg.sources[0] && cfg.sources[0].file)
                                            || '';
                                        if (src) {
                                            window.FaselProbe.post(JSON.stringify({kind: 'JW-SETUP', url: src}));
                                        } else {
                                            window.FaselProbe.post(JSON.stringify({kind: 'JW-SETUP-RAW', text: JSON.stringify(cfg).substring(0, 800)}));
                                        }
                                    } catch(e) {}
                                    return origSetup(cfg);
                                };
                            }
                            return inst;
                        };
                        try {
                            for (var k in jw) {
                                if (jw.hasOwnProperty(k)) {
                                    try { _jwImpl[k] = jw[k]; } catch(e) {}
                                }
                            }
                        } catch(e) {}
                    }
                });
                
                var origFetch = window.fetch;
                if (origFetch) {
                  window.fetch = function(resource, init) {
                    init = init || {};
                    init.credentials = init.credentials || 'include';
                    return origFetch.call(this, resource, init);
                  };
                }

                var XO = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.withCredentials = true;
                  return XO.apply(this, arguments);
                };
            })();
        """.trimIndent()
    }


    private fun injectJwProbe(webView: WebView, session: CaptureSession) {
        val js = buildJwProbeJs()
        webView.evaluateJavascript(js, null)
    }
    private suspend fun resolveHost(): String = runCatching {
        println("FaselHD: Resolving host from $mainUrl")
        val resp = app.get(mainUrl, allowRedirects = true, timeout = 10)
        val uri = java.net.URL(resp.url.trimEnd('/'))
        resp.okhttpResponse.close()
        val host = "${uri.protocol}://${uri.host}"
        println("FaselHD: Host resolved to $host")
        host.also { mainUrl = it }
    }.getOrDefault(mainUrl)

    private fun normalizeUrl(url: String, host: String): String {
        if (!url.contains(baseDomain)) return url
        val old = runCatching { java.net.URL(url) }.getOrNull() ?: return url
        return url.replace("${old.protocol}://${old.host}", host)
    }

    private fun headers(host: String, referer: String = host) = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Origin" to host,
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    private fun isBlocked(doc: Document): Boolean {
        val t = doc.select("title").text().lowercase()
        val b = doc.body().text().lowercase()
        return "just a moment" in t || "cloudflare" in t ||
            "security verification" in t || "access denied" in t ||
            "verifying you are not a bot" in b ||
            "performing security verification" in b
    }

    private suspend fun safeGet(url: String, referer: String = url, headers: Map<String, String>? = null): Document? {
        println("FaselHD: safeGet -> $url (Referer: $referer)")
        val finalHeaders = headers(mainUrl, referer) + (headers ?: emptyMap())
        return runCatching {
            val res = app.get(url, headers = finalHeaders, timeout = 15)
            val doc = res.document
            if (res.isSuccessful && !isBlocked(doc)) {
                println("FaselHD: Plain GET successful for $url")
                return doc
            }

            println("FaselHD: Plain GET failed or blocked, trying CloudflareKiller for $url")
            mutex.withLock {
                val cfRes = app.get(
                    url,
                    headers = headers(mainUrl, referer),
                    interceptor = cfKiller,
                    timeout = 120
                )
                val cfDoc = cfRes.document
                if (cfRes.isSuccessful) {
                    println("FaselHD: CloudflareKiller successful for $url")
                    delay(2000)
                    if (!isBlocked(cfDoc)) return cfDoc
                }
                println("FaselHD: CloudflareKiller failed or still blocked for $url")
                null
            }
        }.getOrNull()
    }

    private fun buildPosterHeaders(finalPoster: String?, pageUrl: String): Map<String, String>? {
        if (finalPoster.isNullOrBlank()) return null
        return mapOf(
            "Referer" to pageUrl,
            "User-Agent" to userAgent,
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        )
    }

    private fun syncCookiesToWebView(targetOrigin: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val skipNames = setOf("cf_clearance", "__cf_bm", "cfchlrcni5", "__cflb", "__cfruid")
            var count = 0
            val normalizedTarget = targetOrigin.hostOrigin()
            
            cfKiller.savedCookies.forEach { (host, cookies) ->
                cookies.forEach { (name, value) ->
                    if (name !in skipNames) {
                        val cookieString = "$name=$value"
                        val cookieHost = host.hostOrigin()
                        cookieManager.setCookie(cookieHost, cookieString)
                        
                        // Also mirror to mainHost if it's a domain cookie or on same base
                        if (host.endsWith(baseDomain) && cookieHost != normalizedTarget) {
                            cookieManager.setCookie(normalizedTarget, cookieString)
                        }
                        count++
                    }
                }
            }

            cookieManager.flush()
            println("FaselHD: Synced $count non-CF cookies to WebView across all hosts")
            println("FaselHD: WebView cookies for $normalizedTarget: ${cookieManager.getCookie(normalizedTarget)}")
        } catch (e: Exception) {
            println("FaselHD: Cookie sync failed: ${e.message}")
        }
    }

    private fun harvestWebViewCookies(hostUrl: String) {
        try {
            val normalizedHost = hostUrl.hostOrigin()
            val raw = CookieManager.getInstance().getCookie(normalizedHost) ?: return
            raw.split(";").forEach { part ->
                val kv = part.trim()
                if (kv.startsWith("cf_clearance=")) {
                    val value = kv.removePrefix("cf_clearance=")
                    println("FaselHD: Harvested WebView cf_clearance -> ${value.take(30)}...")
                    
                    val host = normalizedHost.removePrefix("https://").removePrefix("http://")
                    val cookies = cfKiller.savedCookies[host]?.toMutableMap() ?: mutableMapOf<String, String>()
                    cookies["cf_clearance"] = value
                    cfKiller.savedCookies[host] = cookies
                }
            }
        } catch (e: Exception) {
            println("FaselHD: Cookie harvest failed: ${e.message}")
        }
    }
    
    private fun clearCfCookiesFromWebView(hostUrl: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            val normalizedHost = hostUrl.hostOrigin()
            listOf("cf_clearance", "__cf_bm", "cfchlrcni5", "__cflb", "__cfruid", "cf_ob_info").forEach { name ->
                cookieManager.setCookie(normalizedHost, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            cookieManager.flush()
            println("FaselHD: Purged stale CF cookies for $normalizedHost")
        } catch (e: Exception) {
            println("FaselHD: Stale CF cookie purge failed: ${e.message}")
        }
    }

    private fun syncCfClearanceToWebView(hostUrl: String) {
        try {
            val normalizedHost = hostUrl.hostOrigin()
            val host = normalizedHost.removePrefix("https://").removePrefix("http://")
            val clearance = cfKiller.savedCookies[host]?.get("cf_clearance") ?: return
            CookieManager.getInstance().apply {
                setCookie(normalizedHost, "cf_clearance=$clearance")
                flush()
            }
            println("FaselHD: Synced fresh cf_clearance to WebView for $normalizedHost")
        } catch (e: Exception) {
            println("FaselHD: CF clearance sync failed: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractM3u8ViaWebView(
        playerUrl: String,
        playerHost: String,
        referer: String,
        cachedHtml: String? = null
    ): String? = kotlinx.coroutines.withTimeoutOrNull(35000) {
        val gen = captureGeneration.incrementAndGet()
        val session = CaptureSession(gen = gen, targetUrl = playerUrl)
        activeSession.set(session)
        
        this@FaselHDProvider.cachedPlayerHtml = cachedHtml

        val webView = suspendCancellableCoroutine<WebView?> { continuation ->
            mainHandler.post {
                val context = AcraApplication.context
                if (context == null) {
                    continuation.resume(null)
                    return@post
                }
                val wv = WebView(context)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cookieManager.setAcceptThirdPartyCookies(wv, true)
                }

                clearCfCookiesFromWebView(playerHost)
                syncCookiesToWebView(playerHost)
                syncCfClearanceToWebView(playerHost)

                val defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                userAgent = defaultUA
                runCatching {
                    val field = cfKiller.javaClass.getDeclaredField("userAgent")
                    field.isAccessible = true
                    field.set(cfKiller, defaultUA)
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    allowFileAccess = true
                    allowContentAccess = true
                    userAgentString = defaultUA
                }

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.i("FaselHD-JS", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                        return true
                    }
                }
                
                val completed = AtomicBoolean(false)
                val bridge = ProbeBridge(
                    pageUrl = playerUrl,
                    completed = completed,
                    onMediaUrl = { mediaUrl ->
                        Log.i("FaselHD", "Gen ${session.gen} Resolved media => $mediaUrl")
                        session.completeSuccess(mediaUrl)
                    },
                    log = { msg -> Log.i("FaselHD", "Gen ${session.gen} $msg") }
                )
                wv.addJavascriptInterface(bridge, "FaselProbe")
                
                var lastChallengeMs = 0L
                var lastOnPageFinishedMs = 0L

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        Log.i("FaselHD", "WV-START generation=$gen url=$url referer=$referer")
                        super.onPageStarted(view, url, favicon)
                        val currentUrl = url ?: ""
                        
                        if (currentUrl.isPlayerUrl() || currentUrl.startsWith("https://web")) {
                            injectJwProbe(view, session)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("intent://") || url.startsWith("market://")) {
                            return true
                        }
                        return false
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        Log.i("FaselHD", "WV-HTTP-ERR generation=${session.gen} status=${errorResponse?.statusCode} url=${request?.url} main=${request?.isForMainFrame}")
                        if (request?.isForMainFrame == true && errorResponse?.statusCode == 404) {
                            println("FaselHD: [Gen ${session.gen}] 404 encountered for target URL, aborting sequence.")
                            session.completeFailure(ResolveExit.FAIL_HTTP_404.name)
                        }
                    }
                    
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        val isMain = request.isForMainFrame
                        val activeUrl = view.tag as? String
                        Log.i("FaselHD", "WV-INTERCEPT gen=${session.gen} url=$u main=$isMain")

                        if (!isMain && u.contains("playertoken")) {
                            Log.i("FaselHD", "WV-SUB-DEBUG gen=${session.gen} activeUrl=${activeUrl != null} cachedHtml=${cachedPlayerHtml != null} url=${u.take(80)}")
                        }

                        // Fix 2: Intercept JW Player XHR at Native Layer to bypass 403
                        if (!isMain
                            && activeUrl != null
                            && "videoplayer" in u
                            && "playertoken" in u
                        ) {
                            Log.i("FaselHD", "WV-XHR-INTERCEPT gen=${session.gen} url=$u")
                            val currentCfClearance = cfKiller.savedCookies[playerHost.removePrefix("https://").removePrefix("http://")]?.get("cf_clearance") ?: ""
                            
                            val okhttpResponse = runBlocking {
                                app.get(u,
                                    headers = mapOf(
                                        "Accept"           to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                        "Accept-Language"  to "en-US,en;q=0.9,ar;q=0.8",
                                        "Referer"          to (activeUrl ?: playerUrl),
                                        "User-Agent"       to userAgent,
                                        "Cache-Control"    to "no-cache"
                                    ),
                                    cookies = mapOf("cf_clearance" to currentCfClearance)
                                )
                            }

                            val body = okhttpResponse.text
                            Log.i("FaselHD", "WV-XHR-OHTTP gen=${session.gen} status=${okhttpResponse.code} bodyLen=${body.length}")

                            val streamUrl = Regex(
                                """https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""",
                                RegexOption.IGNORE_CASE
                            ).find(body)?.value

                            if (streamUrl != null) {
                                Log.i("FaselHD", "WV-XHR-HIT gen=${session.gen} url=$streamUrl")
                                session.completeSuccess(streamUrl)
                            } else {
                                Log.i("FaselHD", "WV-XHR-MISS gen=${session.gen} body=${body.take(300)}")
                            }

                            val contentType = okhttpResponse.headers["content-type"] ?: "application/json"
                            return WebResourceResponse(
                                contentType.substringBefore(";").trim(),
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                body.byteInputStream(Charsets.UTF_8)
                            )
                        }

                        // New Fix A: Synchronous Hook Injection via cached HTML
                        if (isMain && u.contains("videoplayer") && u.contains("playertoken") && cachedPlayerHtml != null) {
                            val hookScript = buildJwHookScript()
                            val html = cachedPlayerHtml!!
                            cachedPlayerHtml = null

                            val injected = html.replace(
                                "<head>",
                                "<head><script type=\"text/javascript\">$hookScript</script>",
                                ignoreCase = true
                            )
                            Log.i("FaselHD", "WV-INJECT-HTML gen=${session.gen} url=$u")
                            return WebResourceResponse(
                                "text/html", "UTF-8",
                                injected.byteInputStream(Charsets.UTF_8)
                            )
                        }

                        // Native interception is the primary path for nested iframe players
                        val isMedia = u.contains(".m3u8", ignoreCase = true)
                            || u.contains(".mp4", ignoreCase = true)
                            || u.contains("/hls/", ignoreCase = true)
                            || u.contains("master.", ignoreCase = true)
                            || u.contains("/playlist", ignoreCase = true)
                            || u.contains("index.m3u8", ignoreCase = true)

                        if (isMedia && !u.contains("chunk") && !u.contains("segment") && !u.contains(".ts")) {
                            Log.i("FaselHD", "INTERCEPT-HIT gen=${session.gen} url=$u main=${request.isForMainFrame}")
                            session.completeSuccess(u)
                            return null
                        }

                        if (u.contains("jwpcdn.com") || u.contains("jwplayer")) {
                            return null
                        }
                        
                        val blockList = listOf("doubleclick.net", "googlesyndication.com", "adservice.google")
                        if (blockList.any { u.contains(it) }) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        if (u.contains("cdn-cgi/challenge", true) || u.contains("cdn-cgi/challenge-platform", true)) {
                            lastChallengeMs = System.currentTimeMillis()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url ?: ""
                        if (currentUrl.contains("challenges.cloudflare.com", true) || currentUrl.contains("/cdn-cgi/", true)) return

                        val isPlayerPage = currentUrl.isPlayerUrl()
                        if (isPlayerPage) {
                            val myGen = session.gen
                            lastOnPageFinishedMs = System.currentTimeMillis()
                            
                            if (session.quietGateStarted.compareAndSet(false, true)) {
                                val checkInterval = 2000L
                                val maxChecks = 75
                                repeat(maxChecks) { i ->
                                    mainHandler.postDelayed({
                                        val active = activeSession.get()
                                        if (active == null || active.gen != myGen || session.closed.get()) return@postDelayed
                                        
                                        if (lastChallengeMs > lastOnPageFinishedMs) {
                                            return@postDelayed
                                        }
                                        
                                        val quiet = System.currentTimeMillis() - lastChallengeMs > 3000
                                        if (quiet && session.captureWindowStarted.compareAndSet(false, true)) {
                                            println("FaselHD: [Gen ${session.gen}] Gate passed. Re-injecting probe.")
                                            injectJwProbe(view, session)
                                        }
                                    }, i * checkInterval)
                                }
                            }
                        }
                    }
                }
                
                continuation.resume(wv)
            }
        } ?: return@withTimeoutOrNull null
        
        mainHandler.post {
            Log.i("FaselHD", "WV-START generation=$gen url=$playerUrl referer=$referer")
            webView.loadUrl(playerUrl, mapOf("Referer" to referer, "Origin" to playerHost, "User-Agent" to userAgent))
        }

        try {
            session.result.await()
        } finally {
            val finalUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { 
                runCatching { webView.url }.getOrNull() 
            }
            Log.i("FaselHD", "WV-FINAL-URL generation=$gen url=$finalUrl")
            cachedPlayerHtml = null
            if (session.closed.compareAndSet(false, true)) {
                activeSession.compareAndSet(session, null)
                mainHandler.post {
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                }
            }
        }
    }
    override val mainPage = mainPageOf(
        "/most_recent" to "المضاف حديثاً",
        "/series" to "مسلسلات",
        "/movies" to "أفلام",
        "/asian-series" to "مسلسلات آسيوية",
        "/anime" to "الأنمي",
        "/tvshows" to "البرامج التلفزيونية",
        "/dubbed-movies" to "أفلام مدبلجة",
        "/hindi" to "أفلام هندية",
        "/asian-movies" to "أفلام آسيوية",
        "/anime-movies" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val host = resolveHost()
        val url = if (page == 1) "$host${request.data}"
        else "$host${request.data.trimEnd('/')}/page/$page"

        val doc = safeGet(url, host) ?: return newHomePageResponse(request.name, emptyList())
        val results = doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val host = resolveHost()
        val doc = safeGet("$host/?s=$query", host) ?: return emptyList()
        return doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.h1, .entry-title, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null

        val href = selectFirst("a")?.attr("abs:href")
            ?.takeIf { it.startsWith("http") }
            ?: return null

        val img = selectFirst("img")
        val poster = img?.let {
            listOf("data-src", "data-original", "data-lazy-src", "src")
                .map { attr -> it.attr(attr) }
                .firstOrNull { it.isNotEmpty() }
        }?.let {
            when {
                it.startsWith("http") -> it
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$mainUrl$it"
                else -> null
            }
        }

        val quality = selectFirst("span.quality, span.qualitySpan")?.text()
        val type = if (href.contains("/episode/") || href.contains("/episodes/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            posterUrl = poster?.takeIf { it.isNotBlank() }
            this.quality = getQualityFromString(quality)

            buildPosterHeaders(posterUrl, href)?.let {
                posterHeaders = it
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("FaselHD: load -> $url")
        val host = resolveHost()
        val pageUrl = normalizeUrl(url, host)
        println("FaselHD: Normalized page URL -> $pageUrl")

        val doc = safeGet(pageUrl, pageUrl) ?: return null

        val title = doc.selectFirst("div.title, h1.postTitle, div.h1, .entry-title, h1")
            ?.text() ?: doc.title()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property='og:image:secure_url']")?.attr("content")
                ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("div.posterImg img, .entry-thumbnail img, img.posterImg")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.let { if (it.startsWith("//")) "https:$it" else it }

        val finalPoster = poster?.takeIf { it.isNotBlank() }
        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc, .entry-content p")?.text()
        val year = doc.select("a[href*='series_year'], a[href*='movies_year']")
            .firstOrNull()?.text()?.toIntOrNull()
        val recs = doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }

        val isEpisodePage = "/episodes/" in pageUrl
        val isSeries =
            "/series/" in pageUrl || "/tvshow" in pageUrl ||
                "/anime/" in pageUrl || "/asian-series/" in pageUrl ||
                doc.select("#seasonList, div.seasonLoop, #epAll, div.epAll, #DivEpisodesList").isNotEmpty() ||
                doc.select("a[href*='/episodes/']").isNotEmpty()

        return when {
            isEpisodePage -> {
                newTvSeriesLoadResponse(
                    title,
                    pageUrl,
                    TvType.TvSeries,
                    listOf(newEpisode(pageUrl) { name = title })
                ) {
                    posterUrl = finalPoster
                    backgroundPosterUrl = finalPoster
                    this.year = year
                    plot = desc
                    buildPosterHeaders(finalPoster, pageUrl)?.let { posterHeaders = it }
                }
            }

            isSeries -> {
                val rawLinks = doc.select(
                    "#epAll a, div.epAll a, #DivEpisodesList a, .episodes-list a, a[href*='/episodes/']"
                )

                val episodes = if (rawLinks.isNotEmpty()) {
                    rawLinks.mapIndexed { idx, el ->
                        val epUrl = normalizeUrl(el.attr("abs:href"), host)
                        val epTitle = el.text().trim()
                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        newEpisode(epUrl) {
                            name = epTitle
                            episode = epNum
                        }
                    }.distinctBy { it.data }
                } else {
                    doc.select("#seasonList a, div.seasonLoop a").mapIndexed { idx, el ->
                        val seasonUrl = normalizeUrl(el.attr("abs:href"), host)
                        val seasonTitle = el.text().trim()
                        newEpisode(seasonUrl) {
                            name = seasonTitle
                            season = Regex("""\d+""").find(seasonTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        }
                    }
                }

                newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    posterUrl = finalPoster
                    backgroundPosterUrl = finalPoster
                    this.year = year
                    plot = desc
                    recommendations = recs
                    buildPosterHeaders(finalPoster, pageUrl)?.let { posterHeaders = it }
                }
            }

            else -> {
                newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                    posterUrl = finalPoster
                    backgroundPosterUrl = finalPoster
                    this.year = year
                    plot = desc
                    recommendations = recs
                    buildPosterHeaders(finalPoster, pageUrl)?.let { posterHeaders = it }
                }
            }
        }
    }

    private fun String.isPlayerUrl(): Boolean {
        return contains("videoplayer", true) || contains("video_player", true)
    }

    private fun String.hasPlayerToken(): Boolean {
        return contains("playertoken", true) || contains("player_token", true)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FaselHD: loadLinks for data -> $data")

        val host = resolveHost()
        val pageUrl = normalizeUrl(data, host)
        println("FaselHD: Normalized loadLinks URL -> $pageUrl")

        val doc = safeGet(pageUrl, pageUrl) ?: return false
        val html = doc.html()

        val allPlayerUrls = mutableListOf<String>()

        val episodePlayerUrls = extractEpisodePlayerUrls(html, pageUrl)
        for (candidate in episodePlayerUrls) {
            Log.i("FaselHD", "Episode HTML player candidate => $candidate")
            allPlayerUrls.add(candidate)
        }

        // 1. Extract from multi-server tabs
        doc.select("ul.tabs-ul li").sortedByDescending { it.hasClass("active") }.forEach { li ->
            val onclick = li.attr("onclick")
            Regex("""(?:playertoken|player_token)=([^'"]+)""", RegexOption.IGNORE_CASE).find(onclick)?.groupValues?.get(1)?.let { token ->
                allPlayerUrls.add("$host/videoplayer?playertoken=$token")
            }
        }

        // 2. Extract from primary iframe (data-src preferred, fallback to src)
        val iframePlayerUrl = doc.selectFirst(
            "iframe[name=playeriframe], " +
            "iframe[src*=videoplayer], " +
            "iframe[data-src*=videoplayer], " +
            "iframe[src*=video_player], " +
            "iframe[data-src*=video_player]"
        )?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
        }?.takeIf { it.isNotBlank() }
        
        if (iframePlayerUrl != null) {
            allPlayerUrls.add(iframePlayerUrl)
        }

        // 3. Fallback to JS variables if all else fails
        if (allPlayerUrls.isEmpty()) {
            val regexPlayerUrl = sequenceOf(
                Regex(
                    """(?:src|url)\s*[=:]\s*["'](https?://[^"'\\]*(?:videoplayer|video_player)\?(?:playertoken|player_token)=[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ),
                Regex(
                    """((?:https?://|//|/)?[^"'\s\\]*(?:videoplayer|video_player)\?(?:playertoken|player_token)=[^"'\s\\]+)""",
                    RegexOption.IGNORE_CASE
                )
            ).mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
                .firstOrNull()
                ?.let { found ->
                    when {
                        found.startsWith("http://", true) || found.startsWith("https://", true) -> normalizeUrl(found, host)
                        found.startsWith("//") -> "https:$found"
                        found.startsWith("/") -> "$host$found"
                        else -> "$host/${found.removePrefix("/")}"
                    }
                }
            if (regexPlayerUrl != null) {
                allPlayerUrls.add(regexPlayerUrl)
            }
        }

        val uniquePlayerUrls = allPlayerUrls.map { normalizeUrl(it, host) }.distinct()

        if (uniquePlayerUrls.isEmpty()) {
            println("FaselHD: No player URLs found, final rawScan of original page")
            Log.i("FaselHD", "RESOLVE-EXIT generation=-1 exit=${ResolveExit.FAIL_NO_PLAYER_URL.name} lastPage=$pageUrl lastCandidate=none")
            return rawScan(html, pageUrl, callback)
        }

        var foundStream = false
        var lastCandidate = "none"

        for ((index, rawPlayerUrl) in uniquePlayerUrls.withIndex()) {
            lastCandidate = rawPlayerUrl
            val playerHost = java.net.URI(rawPlayerUrl).let { "${it.scheme}://${it.host}" }
            println("FaselHD: Extracted playerUrl -> $rawPlayerUrl, playerHost -> $playerHost")

            // Fix #1: Try SafeGet first to avoid single-use token consumption in WebView
            val videoPageHtml = safeGet(
                rawPlayerUrl, 
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            )?.html() ?: run {
                Log.i("FaselHD", "SAFEGET-NULL falling back to WebView")
                null
            }
            if (videoPageHtml != null) {
                val jwFileRegex = Regex("""(?:["']file["']\s*:\s*["'])(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""")
                val jwSrcRegex  = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)

                val streamUrl = jwFileRegex.find(videoPageHtml)?.groupValues?.get(1)
                    ?: jwSrcRegex.find(videoPageHtml)?.value

                if (streamUrl != null) {
                    Log.i("FaselHD", "SAFEGET-HIT url=$streamUrl")
                    val serverName = if (uniquePlayerUrls.size > 1) "$name Server ${index + 1}" else name
                    callback(
                        newExtractorLink(
                            name,
                            serverName,
                            streamUrl,
                            if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = rawPlayerUrl
                            quality = getVideoQuality(streamUrl)
                        }
                    )
                    foundStream = true
                    Log.i("FaselHD", "RESOLVE-EXIT generation=-1 exit=${ResolveExit.SUCCESS_MEDIA.name} lastPage=$pageUrl lastCandidate=$rawPlayerUrl")
                    break
                }
                Log.i("FaselHD", "SAFEGET-MISS url=$rawPlayerUrl sample=${videoPageHtml.take(500).replace("\n", " ")}")
            }

            val resolved = extractM3u8ViaWebView(
                playerUrl = rawPlayerUrl,
                playerHost = playerHost,
                referer = pageUrl,
                cachedHtml = videoPageHtml
            )
            println("FaselHD: extractM3u8ViaWebView returned -> $resolved")

            if (!resolved.isNullOrBlank() && !resolved.startsWith("FAIL_")) {
                val serverName = if (uniquePlayerUrls.size > 1) "$name Server ${index + 1}" else name
                callback(
                    newExtractorLink(
                        name,
                        serverName,
                        resolved,
                        if (resolved.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = rawPlayerUrl
                        quality = getVideoQuality(resolved)
                    }
                )
                foundStream = true
                Log.i("FaselHD", "RESOLVE-EXIT generation=-1 exit=${ResolveExit.SUCCESS_MEDIA.name} lastPage=$pageUrl lastCandidate=$rawPlayerUrl")
                break
            } else {
                Log.i("FaselHD", "CANDIDATE-FAIL reason=${resolved ?: "TIMEOUT"} url=$rawPlayerUrl")
                Log.i("FaselHD", "CANDIDATE-NEXT remaining=${uniquePlayerUrls.size - index - 1}")
            }
        }

        if (!foundStream) {
            println("FaselHD: Everything failed, final rawScan of original page")
            Log.i("FaselHD", "RESOLVE-EXIT generation=-1 exit=${if(lastCandidate=="none") ResolveExit.FAIL_NO_PLAYER_URL.name else ResolveExit.FAIL_PROBE_NO_HIT.name} lastPage=$pageUrl lastCandidate=$lastCandidate")
            return rawScan(html, pageUrl, callback)
        }
        
        return true
    }

    private fun extractFromPlayerHtml(html: String): List<String> {
        val patterns = listOf(
            Regex("""\bfile\b\s*[:=]\s*["'](https?://[^"']+)["']"""),
            Regex("""<source[^>]+src=["'](https?://[^"']+)["']"""),
            Regex("""(?:src|source|url|file)\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^\s"'\\]+/(?:hls|playlist|index)[^\s"'\\]*\.m3u8[^\s"'\\]*)"""),
            Regex("""["'](https?://[^\s"'\\]+\.mp4[^\s"'\\]*)["']""")
        )
        return patterns.flatMap { it.findAll(html).map { m -> m.groupValues[1] } }.distinct()
    }

    private suspend fun rawScan(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FaselHD: Starting rawScan for $referer")
        val urls = Regex("""(https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

        println("FaselHD: rawScan found ${urls.size} potential links")
        if (urls.isEmpty()) return false

        urls.forEach { url ->
            val isM3u8 = url.contains(".m3u8", true)
            callback(
                newExtractorLink(
                    name,
                    name,
                    url,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    quality = getVideoQuality(url)
                }
            )
        }
        return true
    }

    private fun getVideoQuality(url: String) = when {
        "1080" in url -> Qualities.P1080.value
        "720" in url -> Qualities.P720.value
        "480" in url -> Qualities.P480.value
        "360" in url -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

