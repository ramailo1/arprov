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
                result.complete(null)
            }
        }
    }

    private val captureGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeSession = java.util.concurrent.atomic.AtomicReference<CaptureSession?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    private inner class FaselBridge(private val session: CaptureSession) {
        @android.webkit.JavascriptInterface
        fun onLog(msg: String) {
            Log.i("FaselHD-JS", "gen=${session.gen} $msg")
        }

        @android.webkit.JavascriptInterface
        fun onStreamFound(url: String) {
            val current = activeSession.get() ?: return
            if (current.gen != session.gen) return
            val normalized = normalizeMediaUrl(url) ?: return
            if (!isUsefulMediaUrl(normalized)) return
            
            Log.i("FaselHD", "Gen ${session.gen} JS Found Stream -> ${normalized.take(100)}")
            session.completeSuccess(normalized)
        }
        
        @android.webkit.JavascriptInterface
        fun report(msg: String) { onLog(msg) }

        @android.webkit.JavascriptInterface
        fun onM3u8Intercepted(url: String) { onStreamFound(url) }

        @android.webkit.JavascriptInterface
        fun onXhrResponse(url: String?, body: String?) {
            val safeBody = body ?: ""
            if (safeBody.isBlank()) return
            val m3u8 = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(safeBody)?.value
            if (m3u8 != null) {
                onStreamFound(m3u8)
                return
            }
            try {
                val json = JSONObject(safeBody)
                val file = json.optString("file").ifEmpty {
                    json.optJSONArray("sources")?.optJSONObject(0)?.optString("file") ?: ""
                }
                if (file.isNotEmpty()) {
                    onStreamFound(file)
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildJwProbeJs(gen: Int): String = """
    (function() {
      const GEN = $gen;
      if (window.__faselProbeGen === GEN && window.__faselProbeInstalled) return;
      window.__faselProbeGen = GEN;
      window.__faselProbeInstalled = true;

      const armed = new WeakSet();

      function log(msg) {
        try { FaselHDBridge.onLog(String(msg)); } catch (e) {}
      }

      function emit(url) {
        if (!url) return;
        log("emit: " + url);
        try { FaselHDBridge.onStreamFound(String(url)); } catch (e) {}
      }

      function reportSources(p) {
        try {
          const cfg = p && typeof p.getConfig === 'function' ? p.getConfig() : null;
          log("p.getConfig() returned: " + !!cfg);
          
          let sourcesObj = "none";
          let sourcesCount = 0;
          let candidateSource = "";

          const playlist = cfg && cfg.playlist && cfg.playlist[0] ? cfg.playlist[0] : null;
          if (playlist) {
              log("found playlist[0], keys: " + Object.keys(playlist).join(","));
          }
          
          let sources = [];
          if (playlist && playlist.sources && playlist.sources.length > 0) {
              sources = playlist.sources;
              sourcesObj = "playlist[0].sources";
          } else if (cfg && cfg.sources && cfg.sources.length > 0) {
              sources = cfg.sources;
              sourcesObj = "cfg.sources";
          } else if (typeof p.getPlaylist === 'function') {
              const activePlaylist = p.getPlaylist();
              if (activePlaylist && activePlaylist[0] && activePlaylist[0].sources) {
                  sources = activePlaylist[0].sources;
                  sourcesObj = "p.getPlaylist()[0].sources";
              }
          }
          
          sourcesCount = sources.length;
          log("JWPlayer sources location: " + sourcesObj + " (count " + sourcesCount + ")");

          for (const s of sources) {
            const file = s && s.file ? String(s.file) : "";
            log("candidateSource: " + file);
            if (file && /(m3u8|mp4|scdns)/i.test(file)) {
                candidateSource = file;
                emit(file);
            }
          }
        } catch (e) {
          log("reportSources error: " + e);
        }
      }

      function arm(p, label) {
        try {
          if (!p || armed.has(p)) return false;
          if (typeof p.play !== 'function' || typeof p.getState !== 'function') return false;
          armed.add(p);
          log("armed jwplayer instance: " + label);

          try { p.on('ready', function() { log("jw ready: " + label); reportSources(p); try { p.play(true); } catch (e) {} }); } catch (e) {}
          try { p.on('play', function() { log("jw play: " + label); reportSources(p); }); } catch (e) {}
          try { p.on('playlist', function() { reportSources(p); }); } catch (e) {}
          try { p.on('playlistItem', function() { reportSources(p); }); } catch (e) {}

          reportSources(p);
          try { p.play(true); } catch (e) {}

          const btn = document.querySelector('.jw-display, .jw-icon-playback, .jw-video, video');
          if (btn) {
            try { btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window })); } catch (e) {}
          }
          return true;
        } catch (e) {
          log("arm error: " + e);
          return false;
        }
      }

      function scan() {
        try {
          if (typeof window.jwplayer === 'function') {
            try { arm(window.jwplayer(), "default"); } catch (e) {}
            document.querySelectorAll('[id]').forEach(function(el) {
              if (!el.id) return;
              try { arm(window.jwplayer(el.id), "id:" + el.id); } catch (e) {}
            });
          }
        } catch (e) {
          log("scan error: " + e);
        }
      }

      function hookFactory() {
        if (window.__faselJwSetterInstalled) return;
        window.__faselJwSetterInstalled = true;

        let current = window.jwplayer;
        Object.defineProperty(window, 'jwplayer', {
          configurable: true,
          enumerable: true,
          get() { return current; },
          set(v) {
            current = v;
            log("jwplayer library detected via setter");
            setTimeout(scan, 0);
            setTimeout(scan, 300);
            setTimeout(scan, 1000);
          }
        });

        if (typeof current === 'function') {
          log("jwplayer already present");
          setTimeout(scan, 0);
        }
      }

      hookFactory();
      scan();

      const iv = setInterval(scan, 500);
      setTimeout(function() {
        clearInterval(iv);
        log("probe finished");
      }, 45000);
    })();
    """.trimIndent()
    
    private fun injectJwProbe(webView: WebView, session: CaptureSession) {
        val js = buildJwProbeJs(session.gen)
        webView.evaluateJavascript(js, null)
    }

    private val hookScript = """
        (function() {
            try {
                if (window.__cs_hooked) return;
                window.__cs_hooked = true;

                function log(msg) {
                    try { if (window.FaselHDBridge) window.FaselHDBridge.report(msg); } catch(e) {}
                }

                if (window.XMLHttpRequest && XMLHttpRequest.prototype.open) {
                    const origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__url = url;
                        return origOpen.apply(this, arguments);
                    };
                    const origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function() {
                        const self = this;
                        this.addEventListener('readystatechange', function() {
                            if (self.readyState === 4) {
                                try {
                                    const url = self.__url || '';
                                    const body = self.responseText || '';
                                    var bodyLen = body ? body.length : -1;
                                    
                                    log("xhr_res: [" + self.status + "] " + url);
                                    
                                    if (url.includes('jwplayer.com') || url.includes('videoplayer')) {
                                        log("FaselHD-JS xhrBody len=" + bodyLen + " for " + url);
                                        if (window.FaselHDBridge && window.FaselHDBridge.onXhrResponse) {
                                            window.FaselHDBridge.onXhrResponse(url, body);
                                        }
                                    }
                                    if (url.includes('scdns') || url.includes('.m3u8')) {
                                        if (window.FaselHDBridge) window.FaselHDBridge.onM3u8Intercepted(url);
                                    }
                                } catch(e) { log("xhr_hook_err: " + e); }
                            }
                        });
                        return origSend.apply(this, arguments);
                    };
                }

                if (window.fetch) {
                    var _origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var u = typeof input === 'string' ? input : (input && input.url);
                        if (u && (u.includes('scdns') || u.includes('.m3u8'))) {
                            log("fetch_intercepted: " + u);
                            try { if (window.FaselHDBridge) window.FaselHDBridge.onM3u8Intercepted(u); } catch(e) {}
                        }
                        return _origFetch.apply(this, arguments);
                    };
                }

                log("Hooks installed");
            } catch(e) {
                console.error("FaselHD-JS Hook fatal error: " + e.message);
            }
        })();
    """.trimIndent()
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

    private suspend fun safeGet(url: String, referer: String = url): Document? {
        println("FaselHD: safeGet -> $url (Referer: $referer)")
        return runCatching {
            val res = app.get(url, headers = headers(mainUrl, referer), timeout = 15)
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
        referer: String
    ): String? = kotlinx.coroutines.withTimeoutOrNull(120_000) {
        val gen = captureGeneration.incrementAndGet()
        val session = CaptureSession(gen = gen, targetUrl = playerUrl)
        activeSession.set(session)

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
                
                wv.addJavascriptInterface(FaselBridge(session), "FaselHDBridge")
                
                var lastChallengeMs = 0L
                var lastOnPageFinishedMs = 0L

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val currentUrl = url ?: ""
                        if (currentUrl.isPlayerUrl() || currentUrl.startsWith("https://web")) {
                            view.evaluateJavascript(hookScript, null)
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
                        if (request?.isForMainFrame == true && errorResponse?.statusCode == 404) {
                            println("FaselHD: [Gen ${session.gen}] 404 encountered for target URL, aborting session.")
                            session.completeFailure("HTTP 404 Target Not Found")
                        }
                    }
                    
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()

                        if (u.contains("jwpcdn.com") || u.contains("jwplayer")) {
                            return null
                        }
                        
                        if (u.contains("scdns.io")) {
                            if (u.contains(".m3u8") && !u.contains("segment") && !u.contains(".ts")) {
                                Log.i("FaselHD", "[Gen ${session.gen}] scdns.io M3U8 CAPTURED: $u")
                                session.completeSuccess(u)
                            }
                            return null
                        }
                        
                        val blockList = listOf("doubleclick.net", "googlesyndication.com", "adservice.google")
                        if (blockList.any { u.contains(it) }) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        if (u.contains("cdn-cgi/challenge", true) || u.contains("cdn-cgi/challenge-platform", true)) {
                            lastChallengeMs = System.currentTimeMillis()
                        }

                        if (isVideoMediaUrl(u) && !u.contains("challenges.cloudflare.com", true) && !u.contains("cdn-cgi", true)) {
                            session.completeSuccess(u)
                        }

                        if ((u.contains(".m3u8") || u.contains("/hls/") || u.contains("/manifest")) 
                             && !u.contains("chunk") && !u.contains("segment")) {
                            session.completeSuccess(u)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url ?: ""
                        if (currentUrl.contains("challenges.cloudflare.com", true) || currentUrl.contains("/cdn-cgi/", true)) return

                        val isPlayerPage = currentUrl.isPlayerUrl()
                        if (isPlayerPage) {
                            view.evaluateJavascript(hookScript, null)
                            
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
            webView.loadUrl(playerUrl, mapOf("Referer" to referer, "Origin" to playerHost, "User-Agent" to userAgent))
        }

        try {
            session.result.await()
        } finally {
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
            return rawScan(html, pageUrl, callback)
        }

        var foundStream = false

        for ((index, rawPlayerUrl) in uniquePlayerUrls.withIndex()) {
            val playerHost = java.net.URI(rawPlayerUrl).let { "${it.scheme}://${it.host}" }
            println("FaselHD: Extracted playerUrl -> $rawPlayerUrl, playerHost -> $playerHost")

            Log.i("FaselHD", "Skipping safeGet for videoplayer — loading directly in WebView")

            val resolved = extractM3u8ViaWebView(
                playerUrl = rawPlayerUrl,
                playerHost = playerHost,
                referer = pageUrl
            )
            println("FaselHD: extractM3u8ViaWebView returned -> $resolved")

            if (!resolved.isNullOrBlank()) {
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
            }
        }

        if (!foundStream) {
            println("FaselHD: Everything failed, final rawScan of original page")
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

