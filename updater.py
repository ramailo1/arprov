import sys

content = open(sys.argv[1], 'r', encoding='utf-8').read()
lines_txt = content.split('\n')

new_chunk_1 = """    private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mp4", ".ts", ".mpd", ".webm", ".mkv")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg")
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private fun String.isPlayerUrl(): Boolean {
        return contains("videoplayer", true) || contains("video_player", true)
    }

    private fun String.hasPlayerToken(): Boolean {
        return contains("playertoken", true) || contains("player_token", true)
    }

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
    )

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
            if (!session.streamFound.compareAndSet(false, true)) return
            
            Log.i("FaselHD", "Gen ${session.gen} Found Stream -> ${normalized.take(100)}")
            session.result.complete(normalized)
        }
        
        @android.webkit.JavascriptInterface
        fun report(msg: String) { onLog(msg) }

        @android.webkit.JavascriptInterface
        fun onM3u8Intercepted(url: String) { onStreamFound(url) }

        @android.webkit.JavascriptInterface
        fun onXhrResponse(url: String?, body: String?) {
            val safeBody = body ?: ""
            if (safeBody.isBlank()) return
            val m3u8 = Regex(\"\"\"https?://[^\\s\"']+\\.m3u8[^\\s\"']*\"\"\").find(safeBody)?.value
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

    private fun buildJwProbeJs(gen: Int): String = \"\"\"
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
          const playlist = cfg && cfg.playlist && cfg.playlist[0] ? cfg.playlist[0] : null;
          const sources = (playlist && playlist.sources) || cfg?.sources || [];
          for (const s of sources) {
            const file = s && s.file ? String(s.file) : "";
            if (file && /(m3u8|mp4|scdns)/i.test(file)) emit(file);
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
    \"\"\".trimIndent()
    
    private fun injectJwProbe(webView: WebView, session: CaptureSession) {
        val js = buildJwProbeJs(session.gen)
        webView.evaluateJavascript(js, null)
    }

    private val hookScript = \"\"\"
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
    \"\"\".trimIndent()"""

new_chunk_2 = """    @SuppressLint("SetJavaScriptEnabled")
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
                            println("FaselHD: 404 encountered for target URL, aborting session.")
                            session.result.complete(null)
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
                                Log.i("FaselHD", "scdns.io M3U8 CAPTURED: " + u)
                                if (session.streamFound.compareAndSet(false, true)) {
                                    session.result.complete(u)
                                }
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
                            if (session.streamFound.compareAndSet(false, true)) {
                                session.result.complete(u)
                            }
                        }

                        if ((u.contains(".m3u8") || u.contains("/hls/") || u.contains("/manifest")) 
                             && !u.contains("chunk") && !u.contains("segment")) {
                            if (session.streamFound.compareAndSet(false, true)) {
                                session.result.complete(u)
                            }
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
                                            println("FaselHD: Gate passed. Re-injecting probe.")
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
    }"""

start_idx_1 = next(i for i, v in enumerate(lines_txt) if 'private val VIDEO_EXTENSIONS' in v)
end_idx_1 = next(i for i in range(start_idx_1, len(lines_txt)) if 'private suspend fun resolveHost(): String' in lines_txt[i])

start_idx_2 = next(i for i, v in enumerate(lines_txt) if 'private suspend fun extractM3u8ViaWebView(' in v)
# adjust 1 line back for the annotation if it hasn't been eaten yet. (The old annotation is at start_idx_2 - 1)
if '@SuppressLint' in lines_txt[start_idx_2 - 1]:
    start_idx_2 -= 1

end_idx_2 = next(i for i in range(start_idx_2, len(lines_txt)) if 'override val mainPage = mainPageOf(' in lines_txt[i])

new_lines = []
new_lines.extend(lines_txt[:start_idx_1])
new_lines.append(new_chunk_1)
new_lines.extend(lines_txt[end_idx_1:start_idx_2])
new_lines.append(new_chunk_2)
new_lines.extend(lines_txt[end_idx_2:])

with open(sys.argv[1], 'w', encoding='utf-8') as f:
    f.write('\n'.join(new_lines))
print("File updated successfully.")
