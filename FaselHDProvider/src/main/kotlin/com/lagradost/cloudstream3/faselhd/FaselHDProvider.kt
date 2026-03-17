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
    override var mainUrl = "https://web31612x.$baseDomain"

    private var userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()
    private val extractionMutex = Mutex()

    private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mp4", ".ts", ".mpd", ".webm", ".mkv")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg")
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val playTriggerStarted = AtomicBoolean(false)

    private fun isVideoMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Hard reject images first
        if (IMAGE_EXTENSIONS.any { lower.endsWith(it) || lower.contains("$it?") }) {
            println("FaselHD-Filter: Rejected image/thumbnail -> $url")
            return false
        }
        // Accept only known video patterns
        val isVideo = VIDEO_EXTENSIONS.any { lower.contains(it) }
                || lower.contains("manifest")
                || lower.contains("playlist")
                || lower.contains("stream")
        
        if (!isVideo) {
            // println("FaselHD-Filter: URL did not match video patterns -> $url")
        }
        return isVideo
    }

    private fun startKotlinDrivenPlayTrigger(webView: WebView?, scope: CoroutineScope) {
        if (webView == null) return
        if (!playTriggerStarted.compareAndSet(false, true)) {
            Log.i("FaselHD", "KotlinPlay already running, skipping duplicate start")
            return
        }

        println("FaselHD: Starting Kotlin-driven play trigger loop...")
        scope.launch {
            // Wait a bit before polling to let page initialize
            delay(2000L)
            repeat(30) { attempt ->
                if (extractionMutex.isLocked) { // Simple way to check if extraction is still active
                    delay(1000L) 
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("""
                        (function() {
                          var jwKeys = Object.keys(window).filter(function(k) {
                            return k.toLowerCase().includes('jw') || k.toLowerCase().includes('player');
                          });
                          var t = typeof window.jwplayer;
                          return JSON.stringify({type: t, jwKeys: jwKeys.slice(0,10)});
                        })()
                        """.trimIndent()) { result ->
                            Log.i("FaselHD", "KotlinPlay attempt $attempt: $result")
                            if (result != null && result.contains("\"function\"")) {
                                triggerJwPlayerPlay(webView, attempt)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerJwPlayerPlay(webView: WebView?, attempt: Int = 0) {
        if (webView == null) return
        println("FaselHD: Injecting JS play trigger (Single attempt API call)...")
        val js = """
        (function() {
            var targets = [
                document.querySelector('.jw-display'),
                document.querySelector('.jw-video'),
                document.querySelector('.jwplayer'),
                document.querySelector('.jw-wrapper'),
                document.getElementById('player')
            ];
            for (var i = 0; i < targets.length; i++) {
                if (targets[i]) {
                    targets[i].dispatchEvent(new MouseEvent('click', {
                        bubbles: true, cancelable: true, view: window
                    }));
                    break;
                }
            }
            try {
                if (typeof jwplayer === 'undefined') return 'no_jw';
                var p = (typeof jwplayer === 'function') ? jwplayer(0) : null;
                if (p && typeof p.play === 'function') {
                    p.play();
                    return 'play_called';
                }
                return 'no_instance';
            } catch(e) { return 'err:' + e.message; }
        })()
        """.trimIndent()
        
        webView.evaluateJavascript(js) { result -> 
            Log.i("FaselHD", "Play trigger attempt $attempt: $result") 
        }
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

    private fun syncCookiesToWebView(mainHost: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val skipNames = setOf("cf_clearance", "__cf_bm", "cfchlrcni5", "__cflb", "__cfruid")
            var count = 0
            cfKiller.savedCookies.forEach { (host, cookies) ->
                cookies.forEach { (name, value) ->
                    if (name !in skipNames) {
                        val cookieString = "$name=$value"
                        cookieManager.setCookie("https://$host", cookieString)
                        
                        // Also mirror to mainHost if it's a domain cookie or on same base
                        if (host.endsWith(baseDomain) && host != mainHost) {
                            cookieManager.setCookie("https://$mainHost", cookieString)
                        }
                        count++
                    }
                }
            }

            cookieManager.flush()
            println("FaselHD: Synced $count non-CF cookies to WebView across all hosts")
            println("FaselHD: WebView cookies for https://$mainHost: ${cookieManager.getCookie("https://$mainHost")}")
        } catch (e: Exception) {
            println("FaselHD: Cookie sync failed: ${e.message}")
        }
    }

    private fun harvestWebViewCookies(host: String) {
        try {
            val raw = CookieManager.getInstance().getCookie("https://$host") ?: return
            raw.split(";").forEach { part ->
                val kv = part.trim()
                if (kv.startsWith("cf_clearance=")) {
                    val value = kv.removePrefix("cf_clearance=")
                    println("FaselHD: Harvested WebView cf_clearance -> ${value.take(30)}...")
                    
                    val cookies = cfKiller.savedCookies[host]?.toMutableMap() ?: mutableMapOf<String, String>()
                    cookies["cf_clearance"] = value
                    cfKiller.savedCookies[host] = cookies
                }
            }
        } catch (e: Exception) {
            println("FaselHD: Cookie harvest failed: ${e.message}")
        }
    }
    
    private fun clearCfCookiesFromWebView(host: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            listOf("cf_clearance", "__cf_bm", "cfchlrcni5", "__cflb", "__cfruid", "cf_ob_info").forEach { name ->
                cookieManager.setCookie("https://$host", "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            cookieManager.flush()
            println("FaselHD: Purged stale CF cookies for $host")
        } catch (e: Exception) {
            println("FaselHD: Cookie purge failed: ${e.message}")
        }
    }

    private fun syncCfClearanceToWebView(host: String) {
        try {
            val clearance = cfKiller.savedCookies[host]?.get("cf_clearance") ?: return
            CookieManager.getInstance().apply {
                setCookie("https://$host", "cf_clearance=$clearance")
                flush()
            }
            println("FaselHD: Synced fresh cf_clearance to WebView for $host")
        } catch (e: Exception) {
            println("FaselHD: CF clearance sync failed: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractM3u8ViaWebView(
        playerUrl: String,
        playerHost: String,
        referer: String
    ): String? = extractionMutex.withLock {
        val hookScript = """
            (function() {
                try {
                    if (window.__cs_hooked) return;
                    window.__cs_hooked = true;

                    function log(msg) {
                        try { if (window.CSBridge) window.CSBridge.report(msg); } catch(e) {}
                        console.log("FaselHD-JS: " + msg);
                    }

                    // --- Fix B: Intercept jwplayer().setup() at the source ---
                    function wrapJwplayer(jw) {
                        if (!jw || jw.__cs_wrapped) return jw;
                        var wrapper = function() {
                            var api = jw.apply(this, arguments);
                            if (api && typeof api.setup === 'function' && !api.__setup_hooked) {
                                api.__setup_hooked = true;
                                var origSetup = api.setup.bind(api);
                                api.setup = function(config) {
                                    try {
                                        log('JW_SETUP_CALLED:' + JSON.stringify(config).substring(0, 500));
                                        var file = config.file;
                                        if (!file && config.playlist && config.playlist[0]) {
                                            var item = config.playlist[0];
                                            file = item.file;
                                            if (!file && item.sources) {
                                                for (var i = 0; i < item.sources.length; i++) {
                                                    if (item.sources[i].file) { file = item.sources[i].file; break; }
                                                }
                                            }
                                        }
                                        if (file) {
                                            log('JW_SETUP_FILE:' + file);
                                            window.CSBridge && window.CSBridge.onStreamUrl(file);
                                        }
                                    } catch(e) { log('setup_hook_err:' + e.message); }
                                    return origSetup(config);
                                };
                            }
                            return api;
                        };
                        // Copy properties from original jwplayer (like .key, .version etc)
                        for (var key in jw) { if (jw.hasOwnProperty(key)) wrapper[key] = jw[key]; }
                        wrapper.__cs_wrapped = true;
                        return wrapper;
                    }

                    if (window.jwplayer) {
                        window.jwplayer = wrapJwplayer(window.jwplayer);
                    } else {
                        // If not yet loaded, use a setter to catch it the moment it's assigned
                        var _jw = undefined;
                        Object.defineProperty(window, 'jwplayer', {
                            get: function() { return _jw; },
                            set: function(val) {
                                log("jwplayer library detected via setter");
                                _jw = wrapJwplayer(val);
                            },
                            configurable: true
                        });
                    }

                    // --- XHR and Fetch Intercept (Fix A) ---
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
                                            if (window.CSBridge && window.CSBridge.onXhrResponse) {
                                                window.CSBridge.onXhrResponse(url, body);
                                            }
                                        }
                                        if (url.includes('scdns') || url.includes('.m3u8')) {
                                            window.CSBridge && window.CSBridge.onM3u8Intercepted(url);
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
                                try { window.CSBridge && window.CSBridge.onM3u8Intercepted(u); } catch(e) {}
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

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val context = AcraApplication.context
                if (context == null) {
                    continuation.resume(null)
                    return@post
                }

                val webView = WebView(context)
                val cookieManager = CookieManager.getInstance()
                var resolved = false
                var captureStarted = false
                var captureTimeout: Runnable? = null
                var loadGeneration = 0

                fun finish(value: String?) {
                    // Bug 6 Fix: Ensure all WebView/continuation calls happen on main thread
                    handler.post {
                        if (resolved) return@post
                        resolved = true
                        loadGeneration++
                        playTriggerStarted.set(false)
                        captureTimeout?.let(handler::removeCallbacks)
                        println("FaselHD: Finished extraction (gen $loadGeneration) -> ${value?.take(50)}${if ((value?.length ?: 0) > 50) "..." else ""}")
                        
                        runCatching { harvestWebViewCookies(java.net.URI(playerUrl).host) }
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                        
                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    }
                }

                val wv = webView
                webView.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun report(value: String?) {
                        if (value.isNullOrBlank()) return
                        
                        // XHR body parsing moved to dedicated method
                        println("FaselHD: JSBridge -> $value")

                        // Fix A: Capture from response body if sent by hook
                        if (value.contains("xhr_body", true)) {
                            val streamUrl = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(value)?.groupValues?.get(1)
                                ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(value)?.groupValues?.get(1)
                            if (streamUrl != null) {
                                println("FaselHD: Captured stream from XHR body -> $streamUrl")
                                finish(streamUrl)
                                return
                            }
                        }

                        val media = if (isVideoMediaUrl(value)) value else null

                        if (media != null && 
                            !media.contains("cdn-cgi", true) && 
                            !media.contains("challenge", true) &&
                            !media.contains("challenges.cloudflare.com", true)) {
                            finish(media)
                            return
                        }

                        if (value.startsWith("mse_") || value.startsWith("blob_url=")) {
                            handler.postDelayed({
                                wv.evaluateJavascript(
                                    """
                                    (function() {
                                        const all = performance.getEntriesByType('resource').map(x => x.name);
                                        const found = all.find(x => /m3u8|\.ts\b|\/hls\/|\/stream\/|manifest/i.test(x) && !x.includes('cdn-cgi') && !/\.(jpg|jpeg|png|webp|gif|svg)/i.test(x));
                                        return found || "";
                                    })();
                                    """.trimIndent()
                                ) { raw ->
                                    val url = raw.trim('"')
                                    if (url.isNotBlank()) {
                                        println("FaselHD: perf API found after MSE trigger -> $url")
                                        finish(url)
                                    }
                                }
                            }, 3000)
                        }
                    }

                    @android.webkit.JavascriptInterface
                    fun onM3u8Intercepted(url: String) {
                        if (url.isNullOrBlank()) return
                        Log.i("FaselHD", "CSBridge: onM3u8Intercepted -> $url")
                        finish(url)
                    }

                    @android.webkit.JavascriptInterface
                    fun onStreamUrl(url: String) {
                        if (url.isNullOrBlank()) return
                        println("FaselHD: JSBridge onStreamUrl -> $url")
                        if (url.startsWith("http")) {
                            finish(url)
                        }
                    }

                    @android.webkit.JavascriptInterface
                    fun onXhrResponse(url: String?, body: String?) {
                        val safeUrl = url ?: ""
                        val safeBody = body ?: ""
                        Log.i("FaselHD", "xhrBody CALLED url=$safeUrl bodyLen=${safeBody.length} snippet=${safeBody.take(200)}")
                        if (safeBody.isBlank()) return

                        // 1. Unconditional regex for m3u8 in ANY captured XHR body
                        val m3u8 = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(safeBody)?.value
                        if (m3u8 != null) {
                            Log.i("FaselHD", "JSBridge - m3u8 caught in XHR body scan: $m3u8")
                            finish(m3u8)
                            return
                        }

                        // 2. Try JSON parsing
                        try {
                            val json = JSONObject(safeBody)
                            val file = json.optString("file").ifEmpty {
                                json.optJSONArray("sources")?.optJSONObject(0)?.optString("file") ?: ""
                            }
                            if (file.isNotEmpty()) {
                                Log.i("FaselHD", "JSBridge - file found in XHR JSON: $file")
                                finish(file)
                            }
                        } catch (_: Exception) {}
                    }
                }, "CSBridge")

                continuation.invokeOnCancellation {
                    handler.post {
                        if (!resolved) {
                            resolved = true
                            loadGeneration++ // invalidate all pending callbacks
                            playTriggerStarted.set(false)
                            runCatching { webView.stopLoading() }
                            runCatching { webView.destroy() }
                        }
                    }
                }

                fun setupGlobalTimeout(gen: Int) {
                    handler.postDelayed({
                        if (!resolved && loadGeneration == gen) {
                            println("FaselHD: Global WebView timeout after 120s (gen $gen)")
                            finish(null)
                        }
                    }, 120_000)
                }
                setupGlobalTimeout(loadGeneration)

                cookieManager.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                }

                val host = java.net.URI(playerUrl).host
                clearCfCookiesFromWebView(host)
                syncCookiesToWebView(host)
                syncCfClearanceToWebView(host)
                
                println("FaselHD: Cookies in cfKiller for $host (before load):")
                cfKiller.savedCookies[host]?.forEach { (name, value) ->
                    println("FaselHD:   $name=${value.take(15)}...")
                }

                // Bug 12 & Empty Body Fix: Force Desktop Chrome UA for player WebView
                val defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                println("FaselHD: WebView default UA forced to desktop: $defaultUA")
                
                // Update provider-wide UA so headers() use it too
                userAgent = defaultUA
                
                // Try to push it to cfKiller if it has the property
                runCatching {
                    val field = cfKiller.javaClass.getDeclaredField("userAgent")
                    field.isAccessible = true
                    field.set(cfKiller, defaultUA)
                }.onFailure { e ->
                    println("FaselHD: Could not set userAgent on cfKiller via reflection: ${e.message}")
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    allowFileAccess = true
                    allowContentAccess = true
                    
                    userAgentString = defaultUA
                    println("FaselHD: WebView UA confirmed: $userAgentString")
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.i("FaselHD-JS", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                        val msg = consoleMessage.message()
                        if (msg.contains("JW_SETUP_FILE:")) {
                            val url = msg.substringAfter("JW_SETUP_FILE:")
                            Log.i("FaselHD", "✅ JW setup() hook fired: $url")
                            if (url.startsWith("http")) {
                                finish(url)
                                Log.i("FaselHD", "setup() hook won the race")
                            }
                        }
                        return true
                    }
                }

                var pollCount = 0
                fun startJWPlayerPolling() {
                    handler.post {
                        if (resolved || pollCount >= 60) {
                            return@post
                        }
                        pollCount++
                        webView.evaluateJavascript("""
                            (function() {
                                try {
                                    if (typeof jwplayer === 'undefined') return 'no_jw';
                                    var p = jwplayer(); if (!p) return 'no_inst';
                                    
                                    if (p.on && !p.__cs_ready_hooked) {
                                        p.__cs_ready_hooked = true;
                                        p.on('ready', function() {
                                            try {
                                                var cfg = p.getConfig();
                                                var src = cfg && cfg.playlist && cfg.playlist[0] && cfg.playlist[0].sources;
                                                if (src && src[0] && src[0].file) {
                                                    if (window.CSBridge) window.CSBridge.onStreamUrl(src[0].file);
                                                }
                                                p.play();
                                            } catch(e) {}
                                        });
                                    }
                                    
                                    var item = p.getPlaylistItem ? p.getPlaylistItem() : null;
                                    if (item && item.file) {
                                        if (item.file.indexOf('blob:') === 0 && item.sources) {
                                            for (var i = 0; i < item.sources.length; i++) {
                                                if (item.sources[i].file && item.sources[i].file.indexOf('http') === 0) return item.sources[i].file;
                                            }
                                        }
                                        return item.file;
                                    }
                                    var pl = p.getPlaylist ? p.getPlaylist() : null;
                                    if (pl && pl.length > 0) {
                                        if (pl[0].file) return pl[0].file;
                                        var src = pl[0].sources;
                                        if (src) for (var i=0;i<src.length;i++) if(src[i].file) return src[i].file;
                                    }
                                    
                                    var entries = performance.getEntriesByType('resource');
                                    for (var i = 0; i < entries.length; i++) {
                                        var n = entries[i].name;
                                        if (n.includes('scdns.io') && n.includes('.m3u8') 
                                            && !n.includes('segment') && !n.includes('.ts')) {
                                            return n;
                                        }
                                    }
                                    
                                    return 'jw_found_no_url';
                                } catch(e) { return 'err:'+e.message; }
                            })()
                        """.trimIndent()) { result ->
                            val v = result?.trim('"') ?: return@evaluateJavascript
                            if (v.startsWith("http")) {
                                println("FaselHD: ✅ JWPlayer POLL SUCCESS: $v")
                                finish(v)
                            } else {
                                handler.postDelayed({ startJWPlayerPolling() }, 100)
                            }
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    private var lastChallengeMs = 0L
                    private var lastOnPageFinishedMs = 0L
                    var captureCheckScheduled = false

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val currentUrl = url ?: ""

                        if (currentUrl.isPlayerUrl() || currentUrl.startsWith("https://web")) {
                            playTriggerStarted.set(false)
                            view?.evaluateJavascript(hookScript, null)
                            Log.i("FaselHD", "onPageStarted $currentUrl")

                            startKotlinDrivenPlayTrigger(view, ioScope)
                            startJWPlayerPolling()
                        } else {
                            Log.i("FaselHD", "onPageStarted IGNORED (non-player URL): $currentUrl")
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("intent://") || url.startsWith("market://")) {
                            Log.i("FaselHD", "Blocked intent redirect: $url")
                            return true  // swallow — do not navigate
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        Log.i("FaselHD-Intercept", "shouldInterceptRequest: ${u.take(200)}")

                        // CRITICAL: must not intercept jwplayer — null = WebView fetches it from CDN normally
                        if (u.contains("jwpcdn.com") || u.contains("jwplayer")) {
                            Log.i("FaselHD-Intercept", "$u → PASS THROUGH (returning null)")
                            return null
                        }
                        
                        // ✅ ALWAYS allow: scdns.io entirely (thumbnails + M3U8)
                        if (u.contains("scdns.io")) {
                            if (u.contains(".m3u8") 
                                && !u.contains("segment") 
                                && !u.contains(".ts")) {
                                Log.i("FaselHD", "scdns.io M3U8 CAPTURED: $u")
                                finish(u)
                            }
                            return null
                        }
                        
                        // Block known ad/tracker domains only
                        val blockList = listOf("doubleclick.net", "googlesyndication.com", "adservice.google")
                        if (blockList.any { u.contains(it) }) {
                            println("FaselHD: Blocking ad/tracker -> $u")
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        val mainFrame = request.isForMainFrame
                        val method = request.method
                        
                        if (u.contains("cdn-cgi/challenge", true) || u.contains("cdn-cgi/challenge-platform", true)) {
                            lastChallengeMs = System.currentTimeMillis()
                            println("FaselHD: CF challenge request detected (MF:$mainFrame $method), resetting capture delay -> $u")
                        }

                        // Broad domain logging to find segment CDN or APIs
                        if (u.contains("faselhdx", true) || u.contains("api", true) || u.contains("v1/", true) ||
                            (!u.contains("cloudflare") && !u.contains("cdn-cgi") &&
                                !u.contains("challenge") && u.contains(playerHost, true))
                        ) {
                            println("FaselHD: WebView request (MF:$mainFrame $method) -> $u")
                        }

                        if (
                            isVideoMediaUrl(u) &&
                            !u.contains("challenges.cloudflare.com", true) &&
                            !u.contains("cdn-cgi", true)
                        ) {
                            println("FaselHD: WebView media subrequest (filtered) -> $u")
                            finish(u)
                        }

                        // Part 1: Intercept M3U8/HLS at the network layer (most robust)
                        if ((u.contains(".m3u8") || u.contains("/hls/") || u.contains("/manifest")) 
                             && !u.contains("chunk") && !u.contains("segment")) {
                            Log.i("FaselHD", "shouldInterceptRequest - STREAM CAPTURED: $u")
                            finish(u)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url ?: ""
                        println("FaselHD: WebView finished loading $currentUrl")

                        if (
                            currentUrl.contains("challenges.cloudflare.com", true) ||
                            currentUrl.contains("/cdn-cgi/", true)
                        ) return

                        val isPlayerPage = currentUrl.isPlayerUrl()
                        val hasPlayerToken = currentUrl.hasPlayerToken()

                        if (isPlayerPage) {
                            view?.evaluateJavascript(hookScript, null)
                        }

                        if (isPlayerPage) {
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var el = document.querySelector('[data-setup]');
                                    if (el) {
                                        var ds = el.getAttribute('data-setup');
                                        if (ds && ds.includes('http')) return 'domsetup:' + ds;
                                    }

                                    var v = document.querySelector('video[src]');
                                    if (v && v.src && v.src.startsWith('http')) return 'domvideo:' + v.src;

                                    var s = document.querySelector('source[src*=".m3u8"]');
                                    if (s && s.src) return 'domsource:' + s.src;

                                    return 'domnotfound';
                                })();
                                """.trimIndent()
                            ) { result ->
                                val cleaned = result?.trim('"') ?: return@evaluateJavascript
                                if (cleaned.startsWith("dom") && cleaned != "domnotfound") {
                                    Log.i("FaselHD", "DOM scan found stream -> $cleaned")
                                    val foundUrl = cleaned.substringAfter(":")
                                    if (foundUrl.startsWith("http")) finish(foundUrl)
                                }
                            }
                        }

                        if (!isPlayerPage) return

                        loadGeneration++
                        val myGen = loadGeneration
                        captureCheckScheduled = false
                        captureStarted = false
                        lastChallengeMs = System.currentTimeMillis()
                        lastOnPageFinishedMs = System.currentTimeMillis()

                        captureTimeout?.let(handler::removeCallbacks)
                        setupGlobalTimeout(myGen)

                        if (captureCheckScheduled) return
                        captureCheckScheduled = true

                        handler.postDelayed({
                            if (resolved || loadGeneration != myGen) return@postDelayed

                            if (lastChallengeMs > lastOnPageFinishedMs) {
                                println("FaselHD: Challenge request detected after onPageFinished. Likely interstitial. Skipping capture start for gen $myGen.")
                                captureCheckScheduled = false
                                return@postDelayed
                            }

                            println("FaselHD: Starting quiet-check loop for gen $myGen")
                            val checkInterval = 2000L
                            val maxChecks = 75

                            repeat(maxChecks) { i ->
                                handler.postDelayed({
                                    if (resolved || loadGeneration != myGen) return@postDelayed

                                    val now = System.currentTimeMillis()
                                    val quiet = now - lastChallengeMs > 3000
                                    val cookieStr = CookieManager.getInstance().getCookie(playerUrl) ?: ""
                                    val hasClearance = cookieStr.contains("cf_clearance", true)
                                    val looksPlayable = isPlayerPage || hasPlayerToken

                                    println("FaselHD: CF quiet check $i gen $myGen -> quiet=$quiet hasClearance=$hasClearance")

                                    if (!hasClearance || !looksPlayable) {
                                        if (i % 5 == 0) {
                                            println("FaselHD: Waiting for cf_clearance or valid player URL...")
                                        }
                                        return@postDelayed
                                    }

                                    if (!captureStarted && quiet) {
                                        captureStarted = true
                                        println("FaselHD: Gate passed. Starting 45s capture window for gen $myGen.")

                                        startKotlinDrivenPlayTrigger(view, ioScope)
                                        triggerJwPlayerPlay(view, 0)

                                        captureTimeout = Runnable {
                                            if (loadGeneration == myGen) {
                                                println("FaselHD: Player capture timed out after final page load gen $myGen")
                                                finish(null)
                                            }
                                        }
                                        handler.postDelayed(captureTimeout!!, 45_000)

                                        startPolling(view, myGen)
                                    } else if (quiet && !hasClearance && i % 5 == 0) {
                                        println("FaselHD: Window is quiet but cf_clearance is missing for playerHost. Waiting...")
                                    }

                                    if (i == 1 && view != null) {
                                        println("FaselHD: Quiet check 1 - retry play trigger")
                                        triggerJwPlayerPlay(view, 1)
                                    }
                                }, i * checkInterval)
                            }
                        }, 1000)
                    }

                    fun startPolling(view: WebView?, myGen: Int) {
                        repeat(90) { i ->
                            view?.postDelayed({
                                if (resolved || loadGeneration != myGen) return@postDelayed

                                view.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            if (window.jwplayer) {
                                                const p = jwplayer();
                                                try { p.setMute(true); } catch(e) {}
                                                try { 
                                                    if (p && typeof p.play === 'function') {
                                                        p.play();
                                                    }
                                                } catch(e) {}
                                                
                                                // Fallback: click play buttons
                                                const sel = ['.jw-icon-playback', '.jw-icon-play', '[aria-label="Play"]', '.vjs-play-control', 'button.play'];
                                                for (let s of sel) {
                                                    const b = document.querySelector(s);
                                                    if (b) { try { b.click(); } catch(e) {} }
                                                }

                                                if (p) {
                                                    const item = p.getPlaylistItem ? p.getPlaylistItem() : null;
                                                    const file =
                                                        item && item.file ? item.file :
                                                        item && item.sources && item.sources[0]
                                                            ? (item.sources[0].file || "")
                                                            : "";
                                                    if (file) return file;
                                                }
                                            }

                                            const perf = performance.getEntriesByType('resource')
                                                .map(x => x.name)
                                                .filter(x => /m3u8|scdns\.io|master\.m3u8|\.ts|\.mp4|playlist|manifest/i.test(x));
                                            if (perf.length) return perf[0];

                                            const video = document.querySelector("video");
                                            if (video && video.src) return video.src;

                                            const source = document.querySelector("video source");
                                            if (source && source.src) return source.src;

                                            // Part 3: Watch for video elements with m3u8 sources
                                            var vid = document.querySelector('video[src*="m3u8"], source[src*="m3u8"]');
                                            if (vid) {
                                                var src = vid.src || vid.getAttribute('src');
                                                if (src && src.includes('.m3u8')) return src;
                                            }

                                            const html = document.documentElement ? document.documentElement.outerHTML : "";
                                            const match = html.match(/https?:\/\/[^\s"'\\]+(?:\.m3u8|\.mp4)[^\s"'\\]*/i);
                                            if (match) return match[0];
                                        } catch (e) {}
                                        return "";
                                    })();
                                    """.trimIndent()
                                ) { raw ->
                                    val found = raw.trim('"')
                                    if (found.isNotBlank() && !resolved && loadGeneration == myGen) {
                                        println("FaselHD: Found stream via JS polling (gen $myGen) -> $found")
                                        finish(found)
                                    }
                                }
                            }, i * 1000L)
                        }
                    }
                }

                println("FaselHD: WebView loading player: $playerUrl")
                // sync logic handled above
                webView.loadUrl(
                    playerUrl,
                    mapOf(
                        "Referer" to referer,
                        "Origin" to playerHost,
                        "User-Agent" to userAgent
                    )
                )

                // Global timeout is now managed via setupGlobalTimeout(gen) inside onPageFinished and at start
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
