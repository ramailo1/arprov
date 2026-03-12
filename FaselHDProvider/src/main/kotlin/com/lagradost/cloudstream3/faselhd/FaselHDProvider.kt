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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
    override var mainUrl = "https://web3126x.$baseDomain"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()

    private suspend fun resolveHost(): String = runCatching {
        println("FaselHD: Resolving host from $mainUrl")
        val resp = app.get(mainUrl, allowRedirects = true, timeout = 10)
        val uri = java.net.URL(resp.url.trimEnd('/'))
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
            if (res.isSuccessful && !isBlocked(res.document)) {
                println("FaselHD: Plain GET successful for $url")
                return res.document
            }

            println("FaselHD: Plain GET failed or blocked, trying CloudflareKiller for $url")
            mutex.withLock {
                val cfRes = app.get(
                    url,
                    headers = headers(mainUrl, referer),
                    interceptor = cfKiller,
                    timeout = 120
                )
                if (cfRes.isSuccessful) {
                    println("FaselHD: CloudflareKiller successful for $url")
                    delay(2000)
                    if (!isBlocked(cfRes.document)) return cfRes.document
                }
                println("FaselHD: CloudflareKiller failed or still blocked for $url")
                null
            }
        }.getOrNull()
    }

    private fun buildPosterHeaders(finalPoster: String?, fallbackReferer: String): Map<String, String>? {
        if (finalPoster.isNullOrBlank()) return null
        val posterReferer = runCatching {
            java.net.URI(finalPoster).let { "${it.scheme}://${it.host}/" }
        }.getOrNull() ?: fallbackReferer

        return mapOf(
            "Referer" to posterReferer,
            "User-Agent" to userAgent,
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        )
    }

    private fun syncCookiesToWebView(mainHost: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            var count = 0
            cfKiller.savedCookies.forEach { (host, cookies) ->
                cookies.forEach { cookie ->
                    val cookieString = cookie.toString()
                    cookieManager.setCookie("https://$host", cookieString)
                    
                    // Also mirror to mainHost if it's a domain cookie or on same base
                    if (host.endsWith(baseDomain) && host != mainHost) {
                        cookieManager.setCookie("https://$mainHost", cookieString)
                    }
                    count++
                }
            }

            cookieManager.flush()
            println("FaselHD: Synced $count total cookies to WebView across all hosts")
            println("FaselHD: WebView cookies for https://$mainHost: ${cookieManager.getCookie("https://$mainHost")}")
        } catch (e: Exception) {
            println("FaselHD: Cookie sync failed: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractM3u8ViaWebView(
        playerUrl: String,
        playerHost: String,
        referer: String
    ): String? {
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

                fun finish(value: String?) {
                    if (resolved) return
                    resolved = true
                    captureTimeout?.let(handler::removeCallbacks)
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                    continuation.resume(value)
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        if (!resolved) {
                            resolved = true
                            runCatching { webView.stopLoading() }
                            runCatching { webView.destroy() }
                        }
                    }
                }

                cookieManager.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                }

                syncCookiesToWebView(java.net.URI(playerUrl).host)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    allowFileAccess = true
                    allowContentAccess = true
                    userAgentString = userAgent
                }

                webView.webChromeClient = android.webkit.WebChromeClient()

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        if (
                            u.contains("m3u8", true) ||
                            u.contains("scdns.io", true) ||
                            u.contains(".ts", true) ||
                            u.contains(".mp4", true) ||
                            u.contains("playlist", true) ||
                            u.contains("manifest", true)
                        ) {
                            println("FaselHD: WebView subrequest -> $u")
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

                        if (!currentUrl.contains("video_player", true)) return
                        if (captureStarted) return
                        captureStarted = true

                        println("FaselHD: Player page landed. Starting 45s capture window.")
                        captureTimeout = Runnable {
                            println("FaselHD: Player capture timed out after final page load")
                            finish(null)
                        }
                        handler.postDelayed(captureTimeout!!, 45_000)

                        repeat(90) { i ->
                            view?.postDelayed({
                                if (resolved) return@postDelayed

                                view.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            if (window.jwplayer) {
                                                const p = jwplayer();
                                                try { p.setMute(true); } catch(e) {}
                                                try { p.play(); } catch(e) {}
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

                                            const html = document.documentElement ? document.documentElement.outerHTML : "";
                                            const match = html.match(/https?:\/\/[^\s"'\\]+(?:\.m3u8|\.mp4)[^\s"'\\]*/i);
                                            if (match) return match[0];
                                        } catch (e) {}
                                        return "";
                                    })();
                                    """.trimIndent()
                                ) { raw ->
                                    val found = raw.trim('"')
                                    if (found.isNotBlank() && !resolved) {
                                        println("FaselHD: Found stream via JS polling -> $found")
                                        finish(found)
                                    }
                                }
                            }, i * 1000L)
                        }
                    }
                }

                println("FaselHD: WebView loading player: $playerUrl")
                webView.loadUrl(
                    playerUrl,
                    mapOf(
                        "Referer" to referer,
                        "Origin" to playerHost,
                        "User-Agent" to userAgent
                    )
                )

                handler.postDelayed({
                    if (!resolved) {
                        println("FaselHD: Global WebView timeout after 120s")
                        finish(null)
                    }
                }, 120_000)
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

        val playerUrl = doc.selectFirst(
            "iframe[src*=video_player], iframe[data-src*=video_player]"
        )?.let {
            it.attr("abs:src").ifEmpty { it.attr("abs:data-src") }
        }?.takeIf { it.isNotEmpty() }
            ?: Regex("""(?:src|url)\s*[=:]\s*["'](https?://[^"']+video_player\?player_token=[^"']+)["']""")
                .find(html)?.groupValues?.get(1)
            ?: Regex("""video_player\?player_token=[^\s"'\\]+""")
                .find(html)?.value?.let {
                    if (it.startsWith("http")) it else "$host/$it"
                }

        val rawPlayerUrl = playerUrl ?: return rawScan(html, pageUrl, callback)
        val playerHost = java.net.URI(rawPlayerUrl).let { "${it.scheme}://${it.host}" }

        println("FaselHD: Extracted playerUrl -> $rawPlayerUrl, playerHost -> $playerHost")

        val playerDoc = safeGet(rawPlayerUrl, pageUrl)
        if (playerDoc != null) {
            val playerHtml = playerDoc.html()
            val links = extractFromPlayerHtml(playerHtml)
            println("FaselHD: extractFromPlayerHtml found ${links.size} links")
            if (links.isNotEmpty()) {
                links.forEach { url ->
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            url,
                            if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = rawPlayerUrl
                            quality = getVideoQuality(url)
                        }
                    )
                }
                return true
            }
        }

        println("FaselHD: Direct extraction failed, attempting custom WebView extraction...")
        val resolved = extractM3u8ViaWebView(
            playerUrl = rawPlayerUrl,
            playerHost = playerHost,
            referer = pageUrl
        )
        println("FaselHD: extractM3u8ViaWebView returned -> $resolved")

        if (resolved != null) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    resolved,
                    if (resolved.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = rawPlayerUrl
                    quality = getVideoQuality(resolved)
                }
            )
            return true
        }

        println("FaselHD: Everything failed, final rawScan of original page")
        return rawScan(html, pageUrl, callback)
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
