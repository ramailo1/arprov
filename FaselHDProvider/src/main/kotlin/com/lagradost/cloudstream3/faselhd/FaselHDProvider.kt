package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web3126x.faselhdx.bid"
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

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    // Derive poster headers from a given page URL so the Referer always matches the content domain
    private fun posterHeadersFor(pageUrl: String): Map<String, String> {
        val origin = runCatching {
            val uri = java.net.URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrElse { mainUrl }
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to origin
        )
    }

    // ---------- CLOUDFLARE BYPASS ----------
    private fun isBlocked(doc: Document): Boolean {
        val title = doc.select("title").text().lowercase()
        val body = doc.body()?.text()?.lowercase() ?: ""
        return title.contains("just a moment") ||
               title.contains("security verification") ||
               title.contains("access denied") ||
               title.contains("cloudflare") ||
               title.contains("verify you are human") ||
               body.contains("verifying you are not a bot") ||
               body.contains("performing security verification") ||
               body.contains("checking your browser")
    }

    private suspend fun safeGet(url: String): Document? {
        // First try: normal HTTP request
        val response = runCatching {
            val res = app.get(url, headers = defaultHeaders, timeout = 15)
            if (res.isSuccessful && !isBlocked(res.document)) {
                return@runCatching res
            }
            // Cloudflare detected — solve with CloudflareKiller
            mutex.withLock {
                val cfRes = app.get(url, headers = defaultHeaders, interceptor = cfKiller, timeout = 120)
                if (cfRes.isSuccessful) {
                    delay(2000)
                }
                cfRes
            }
        }.getOrNull()

        if (response?.isSuccessful == true) {
            val doc = response.document
            if (isBlocked(doc)) return null
            return doc
        }

        // Final retry with CloudflareKiller
        return runCatching {
            mutex.withLock {
                val res = app.get(url, headers = defaultHeaders, interceptor = cfKiller, timeout = 120)
                if (res.isSuccessful) delay(2000)
                if (res.isSuccessful && !isBlocked(res.document)) res.document else null
            }
        }.getOrNull()
    }

    // ---------- POSTER EXTRACTION ----------
    private fun Element.getPosterUrl(): String? {
        val img = selectFirst("div.imgdiv-class img")
            ?: selectFirst("div.postInner img")
            ?: selectFirst("img")

        if (img == null) return null

        var posterUrl = img.attr("data-src").ifEmpty {
            img.attr("data-original").ifEmpty {
                img.attr("data-image").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("data-srcset").ifEmpty {
                            img.attr("src")
                        }
                    }
                }
            }
        }

        if (posterUrl.isEmpty()) return null
        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
        // Remove resize query parameters for cleaner URLs
        return posterUrl.substringBefore("?resize=").ifEmpty { posterUrl }
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
        "$mainUrl/most_recent" to "المضاف حديثاَ",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/anime" to "الأنمي",
        "$mainUrl/tvshows" to "البرامج التلفزيونية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime-movies" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        println("FaselHD Debug: Requesting main page url: $url")
        
        val doc = safeGet(url) ?: return newHomePageResponse(request.name, emptyList()).also {
            println("FaselHD Debug: safeGet returned null for main page url: $url")
        }
        
        val elements = doc.select("div.postDiv")
        val list = elements.mapNotNull { it.toSearchResult(url) }
        
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(pageUrl: String = mainUrl): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = getPosterUrl()
        val quality = selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = posterHeadersFor(href)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = safeGet("$mainUrl/?s=$query") ?: return emptyList()
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = safeGet(url) ?: return null

        val title = doc.selectFirst("div.title")?.text() ?: doc.selectFirst("title")?.text() ?: ""

        // Poster: check multiple selectors and data-src for lazy loading
        val poster = doc.selectFirst("div.posterImg img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: doc.selectFirst("div.singlePost img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: doc.selectFirst(".single-post img, .posterBg img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val desc = doc.selectFirst("div.singleDesc p")?.text()
            ?: doc.selectFirst("div.singleDesc")?.text()

        val tags = doc.select("div#singleList .col-xl-6").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") }?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult(url) }

        val ph = posterHeadersFor(url)

        // Detect series by looking for episode links or season items
        val episodeElements = doc.select("div#epAll a, div.epAll a")
        val isSeries = episodeElements.isNotEmpty() || doc.select("div.seasonLoop").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
                this.recommendations = recommendations
                this.posterHeaders = ph
            }
        } else {
            val episodes = ArrayList<Episode>()
            for (ep in episodeElements) {
                val epTitle = ep.text()
                val epUrl = ep.attr("href")
                val epNumber = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNumber
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
                this.posterHeaders = ph
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FaselHD Debug: ══════════════════════════════════════")
        println("FaselHD Debug: loadLinks START for: $data")
        println("FaselHD Debug: ══════════════════════════════════════")

        val doc = safeGet(data) ?: return false.also {
            println("FaselHD Debug: [STEP 0 FAIL] safeGet returned null – page could not be fetched")
        }

        println("FaselHD Debug: [STEP 0 OK] Page fetched. Title: '${doc.title()}' | HTML length: ${doc.html().length}")

        // ── STEP 1: Collect ALL onclick server items for diagnosis ──────────────
        val serverItems = doc.select("ul.tabs-ul li[onclick]")
        println("FaselHD Debug: [STEP 1] Found ${serverItems.size} server tab(s) in ul.tabs-ul li[onclick]")
        serverItems.forEachIndexed { i, el ->
            println("FaselHD Debug: [STEP 1]   tab[$i] text='${el.text()}' onclick='${el.attr("onclick")}'")
        }

        // Also log ANY li with onclick in case selector is too narrow
        val allOnclick = doc.select("li[onclick]")
        println("FaselHD Debug: [STEP 1] Total li[onclick] anywhere on page: ${allOnclick.size}")
        if (allOnclick.size != serverItems.size) {
            allOnclick.forEachIndexed { i, el ->
                println("FaselHD Debug: [STEP 1]   li[onclick][$i] text='${el.text().take(60)}' onclick='${el.attr("onclick").take(120)}'")
            }
        }

        // ── STEP 1b: Try to match video_player URL from onclick ──────────────────
        var playerUrl: String? = null
        for (server in serverItems) {
            val onclick = server.attr("onclick")
            val match = Regex("""['"]([^'"]*video_player[^'"]*)['"]""").find(onclick)
                ?: Regex("""['"]([^'"]*https?://[^'"]+)['"]""").find(onclick) // broader fallback
            if (match != null) {
                val raw = match.groupValues[1]
                val url = fixUrl(raw)
                println("FaselHD Debug: [STEP 1b] Regex matched raw='$raw' → fixed='$url'")
                val skip = url.contains("faselhd.life") || onclick.contains("window.open")
                println("FaselHD Debug: [STEP 1b] Skip=$skip (faselhd.life=${url.contains("faselhd.life")}, window.open=${onclick.contains("window.open")})")
                if (!skip) {
                    playerUrl = url
                    break
                }
            } else {
                println("FaselHD Debug: [STEP 1b] No URL regex match in onclick='${onclick.take(120)}'")
            }
        }

        // ── STEP 1c: iframe fallback ─────────────────────────────────────────────
        if (playerUrl.isNullOrEmpty()) {
            println("FaselHD Debug: [STEP 1c] No playerUrl from tabs — trying iframe fallback")
            val allIframes = doc.select("iframe")
            println("FaselHD Debug: [STEP 1c] Total iframes on episode page: ${allIframes.size}")
            allIframes.forEachIndexed { i, f ->
                println("FaselHD Debug: [STEP 1c]   iframe[$i] src='${f.attr("src")}' data-src='${f.attr("data-src")}' name='${f.attr("name")}'")
            }
            val iframe = doc.selectFirst("iframe[name=player_iframe], iframe[src*=video_player]")
            playerUrl = iframe?.absUrl("src")?.ifEmpty { iframe.absUrl("data-src") }
            println("FaselHD Debug: [STEP 1c] iframe-fallback playerUrl=$playerUrl")
        } else {
            println("FaselHD Debug: [STEP 1b] playerUrl resolved: $playerUrl")
        }

        if (playerUrl.isNullOrEmpty()) {
            println("FaselHD Debug: [STEP 1 FAIL] playerUrl is null/empty — dumping page HTML snippet:")
            println(doc.html().take(3000))
            return false
        }

        println("FaselHD Debug: [STEP 1 OK] playerUrl = $playerUrl")

        var foundLinks = false

        // ── STEP 2: loadExtractor on playerUrl directly ──────────────────────────
        println("FaselHD Debug: [STEP 2] Trying loadExtractor(playerUrl)…")
        try {
            val extracted = loadExtractor(playerUrl!!, data, subtitleCallback, callback)
            println("FaselHD Debug: [STEP 2] loadExtractor returned $extracted")
            if (extracted) foundLinks = true
        } catch (e: Exception) {
            println("FaselHD Debug: [STEP 2 EXCEPTION] ${e::class.simpleName}: ${e.message}")
        }

        // ── STEP 3: WebViewResolver with broad pattern ───────────────────────────
        if (!foundLinks) {
            println("FaselHD Debug: [STEP 3] Starting WebViewResolver on: $playerUrl")
            try {
                val pattern = Regex("""(\.m3u8|\.mp4|googlevideo\.com/videoplayback|/playlist\.m3u8|/index\.m3u8)""")
                println("FaselHD Debug: [STEP 3] Pattern: ${pattern.pattern}")
                val result = WebViewResolver(pattern).resolveUsingWebView(playerUrl!!)
                val resolvedRequest = result.first
                println("FaselHD Debug: [STEP 3] WebViewResolver result: $resolvedRequest")

                if (resolvedRequest != null) {
                    val videoUrl = resolvedRequest.url.toString()
                    val headers = resolvedRequest.headers.toMap()
                    println("FaselHD Debug: [STEP 3 OK] Intercepted URL: $videoUrl")
                    println("FaselHD Debug: [STEP 3] Request headers: $headers")

                    val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                    val qualityText = when {
                        videoUrl.contains("1080") -> "1080p"
                        videoUrl.contains("720")  -> "720p"
                        videoUrl.contains("480")  -> "480p"
                        videoUrl.contains("360")  -> "360p"
                        else                      -> "Auto"
                    }
                    callback.invoke(
                        newExtractorLink(this.name, "$name - $qualityText", videoUrl,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playerUrl
                            this.quality = getQualityInt(qualityText)
                        }
                    )
                    foundLinks = true
                } else {
                    println("FaselHD Debug: [STEP 3 NULL] WebViewResolver returned null — pattern not matched in page network requests")
                }
            } catch (e: Exception) {
                println("FaselHD Debug: [STEP 3 EXCEPTION] ${e::class.simpleName}: ${e.message}")
            }
        }

        // ── STEP 4: Raw-fetch player page → iframes + inline video ───────────────
        if (!foundLinks) {
            println("FaselHD Debug: [STEP 4] Raw-fetching player page: $playerUrl")
            try {
                val playerResp = app.get(
                    playerUrl, referer = data, headers = defaultHeaders,
                    interceptor = cfKiller, timeout = 30
                )
                val playerBody = playerResp.text
                println("FaselHD Debug: [STEP 4] HTTP status: ${playerResp.code} | Content-Type: ${playerResp.headers["content-type"]} | Body length: ${playerBody.length}")

                val playerDoc = playerResp.document
                println("FaselHD Debug: [STEP 4] Player page title: '${playerDoc.title()}'")

                // Dump first 2000 chars of player page HTML
                println("FaselHD Debug: [STEP 4] Player page HTML (first 2000 chars):")
                println(playerDoc.html().take(2000))

                // All inline script tags — look for JS variable patterns
                val scripts = playerDoc.select("script:not([src])")
                println("FaselHD Debug: [STEP 4] Inline script blocks: ${scripts.size}")
                scripts.forEachIndexed { i, s ->
                    val txt = s.data().trim()
                    if (txt.isNotEmpty()) println("FaselHD Debug: [STEP 4]   script[$i] (${txt.length} chars): ${txt.take(300)}")
                }

                // All iframes
                val iframes = playerDoc.select("iframe")
                println("FaselHD Debug: [STEP 4] Iframes in player page: ${iframes.size}")
                iframes.forEachIndexed { i, f ->
                    println("FaselHD Debug: [STEP 4]   iframe[$i] src='${f.attr("src")}' data-src='${f.attr("data-src")}'")
                }

                for (iframe in iframes) {
                    val iframeSrc = iframe.absUrl("src").ifEmpty { iframe.absUrl("data-src") }
                    if (iframeSrc.isNotEmpty()) {
                        println("FaselHD Debug: [STEP 4] → loadExtractor on iframe: $iframeSrc")
                        try {
                            val extracted = loadExtractor(iframeSrc, playerUrl, subtitleCallback, callback)
                            println("FaselHD Debug: [STEP 4] loadExtractor(iframe) returned $extracted")
                            if (extracted) foundLinks = true
                        } catch (e: Exception) {
                            println("FaselHD Debug: [STEP 4 EXCEPTION] iframe loadExtractor: ${e::class.simpleName}: ${e.message}")
                        }
                    }
                }

                // Inline video/m3u8 regex on player page
                if (!foundLinks) {
                    val videoPattern = Regex("""(https?://[^\s"'\\]+\.(m3u8|mp4)[^\s"'\\]*)""")
                    val allMatches = videoPattern.findAll(playerDoc.html()).toList()
                    println("FaselHD Debug: [STEP 4] Inline video URL regex matches: ${allMatches.size}")
                    allMatches.forEachIndexed { i, m ->
                        println("FaselHD Debug: [STEP 4]   match[$i]: '${m.value.take(200)}'")
                    }
                    for (match in allMatches) {
                        val videoUrl = match.groupValues[1].replace("\\u002F", "/").replace("\\/", "/")
                        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                        val qualityText = when {
                            videoUrl.contains("1080") -> "1080p"
                            videoUrl.contains("720")  -> "720p"
                            videoUrl.contains("480")  -> "480p"
                            videoUrl.contains("360")  -> "360p"
                            videoUrl.contains("master") -> "Auto"
                            else -> "Unknown"
                        }
                        println("FaselHD Debug: [STEP 4 HIT] Inline video found: $videoUrl")
                        callback.invoke(
                            newExtractorLink(this.name, "$name - $qualityText", videoUrl,
                                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) { this.referer = playerUrl; this.quality = getQualityInt(qualityText) }
                        )
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                println("FaselHD Debug: [STEP 4 EXCEPTION] ${e::class.simpleName}: ${e.message}")
            }
        }

        // ── STEP 5: Last resort — raw-text regex on player URL ────────────────────
        if (!foundLinks) {
            println("FaselHD Debug: [STEP 5] Raw-text regex fallback on playerUrl (timeout=120s)")
            try {
                val resp = app.get(
                    playerUrl, referer = data, headers = defaultHeaders,
                    interceptor = cfKiller, timeout = 120
                )
                val body = resp.text
                println("FaselHD Debug: [STEP 5] HTTP status: ${resp.code} | body length: ${body.length}")
                val m3u8Pattern = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
                val m3u8Matches = m3u8Pattern.findAll(body).toList()
                println("FaselHD Debug: [STEP 5] m3u8 regex matches in raw text: ${m3u8Matches.size}")
                m3u8Matches.forEachIndexed { i, m ->
                    println("FaselHD Debug: [STEP 5]   m3u8[$i]: '${m.value.take(200)}'")
                }
                for (match in m3u8Matches) {
                    val videoUrl = match.value.replace("\\u002F", "/").replace("\\/", "/")
                    val qualityText = when {
                        videoUrl.contains("1080") -> "1080p"
                        videoUrl.contains("720")  -> "720p"
                        videoUrl.contains("480")  -> "480p"
                        videoUrl.contains("360")  -> "360p"
                        videoUrl.contains("master") -> "Auto"
                        else -> "Unknown"
                    }
                    println("FaselHD Debug: [STEP 5 HIT] m3u8 found: $videoUrl")
                    callback.invoke(
                        newExtractorLink(this.name, "$name - $qualityText", videoUrl, ExtractorLinkType.M3U8) {
                            this.referer = playerUrl; this.quality = getQualityInt(qualityText)
                        }
                    )
                    foundLinks = true
                }

                // If still nothing, dump first 3000 chars of raw response for inspection
                if (!foundLinks) {
                    println("FaselHD Debug: [STEP 5 EMPTY] No m3u8 found. Raw body (first 3000 chars):")
                    println(body.take(3000))
                }
            } catch (e: Exception) {
                println("FaselHD Debug: [STEP 5 EXCEPTION] ${e::class.simpleName}: ${e.message}")
            }
        }

        println("FaselHD Debug: ══════════════════════════════════════")
        println("FaselHD Debug: loadLinks END — foundLinks=$foundLinks")
        println("FaselHD Debug: ══════════════════════════════════════")
        return foundLinks
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true)  -> Qualities.P720.value
            quality.contains("480", ignoreCase = true)  -> Qualities.P480.value
            quality.contains("360", ignoreCase = true)  -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
