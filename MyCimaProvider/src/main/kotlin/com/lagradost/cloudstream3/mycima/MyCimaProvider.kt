package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*

class MyCimaProvider : MainAPI() {

    // ---------- AUTO DOMAIN POOL ----------
    private val domainPool = listOf(
        "https://mycima.fan",
        "https://mycima.cc",
        "https://mycima.gold",
        "https://mycima.rip",
        "https://mycima.bond",
        "https://mycima.live"
    )

    private val mutex = Mutex()
    private val cfKiller = CloudflareKiller()
    private var activeDomain: String = domainPool.first()
    private var checkedDomain = false
    
    // Thread-safe rate limiting
    private val rateLimitMutex = Mutex()
    private var lastRequestTime: Long = 0L

    override var mainUrl = activeDomain
    override var name = "MyCima"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val headers: Map<String, String>
        get() = baseHeaders + mapOf("Referer" to "$activeDomain/")

    // ---------- AUTO DOMAIN DETECTOR ----------
    private suspend fun ensureDomain(): Boolean {
        return mutex.withLock {
            if (checkedDomain) {
                // Quick re-verify of current domain
                val stillWorks = runCatching {
                    val res = app.get(activeDomain, timeout = 10)
                    res.isSuccessful && !isBlocked(res.document)
                }.getOrDefault(false)

                if (stillWorks) return@withLock true
                checkedDomain = false
            }

            println("DEBUG_MYCIMA: Checking domains in parallel...")
            
            val workingDomain = coroutineScope {
                val deferreds = domainPool.map { domain ->
                    async {
                        val isWorking = runCatching {
                            // FAST CHECK: Normal HTTP, short timeout (15s)
                            val res = app.get(domain, timeout = 15)
                            if (res.isSuccessful && !isBlocked(res.document)) {
                                println("DEBUG_MYCIMA: Fast check passed for $domain")
                                return@runCatching true
                            }
                            false
                        }.getOrDefault(false)
                        
                        domain to isWorking
                    }
                }
                
                // Wait for all checks to complete or finding a working one
                // We use awaitAll to get results, but we can also just pick the first one that works
                // improved: Wait for all to finish to pick the BEST one (first in list usually preferred if multiple work? 
                // or just first to respond? Let's stick to list order preference if multiple work, or first to respond if speed is critical.
                // Given the user wants speed, let's take the first one that passes checks from the list order OR just valid ones.
                // To keep it simple and deterministic: check all, filter working, pick first.
                // To be faster: we could use select, but map+awaitAll is robust enough for 6 domains.
                
                deferreds.awaitAll().firstOrNull { it.second }?.first
            }

            if (workingDomain != null) {
                println("DEBUG_MYCIMA: Switching to working domain: $workingDomain")
                activeDomain = workingDomain
                mainUrl = workingDomain
                checkedDomain = true
                return@withLock true
            }

            // Fallback: If no "clean" unblocked domain found, try to find ANY reachable domain 
            // (even if blocked, cloudflare killer might handle it later)
             println("DEBUG_MYCIMA: No clean domain found, checking for reachable (even if blocked)...")
             val reachableDomain = coroutineScope {
                val deferreds = domainPool.map { domain ->
                    async {
                        val isReachable = runCatching {
                            val res = app.get(domain, timeout = 10)
                            res.code < 500 // Not a server error
                        }.getOrDefault(false)
                        domain to isReachable
                    }
                }
                deferreds.awaitAll().firstOrNull { it.second }?.first
            }

            if (reachableDomain != null) {
                println("DEBUG_MYCIMA: Setting reachable (potentially blocked) domain: $reachableDomain")
                activeDomain = reachableDomain
                mainUrl = reachableDomain
                // Don't set checkedDomain = true so safeGet triggers proper CF check/solve if needed
                return@withLock true
            }

            false
        }
    }

    private fun isBlocked(doc: Document, expectedUrl: String = ""): Boolean {
        val title = doc.select("title").text().lowercase()
        val body = doc.body().text().lowercase()
        val actualUrl = doc.location()
        
        val blocked = title.contains("just a moment") || 
               title.contains("security verification") || 
               title.contains("access denied") ||
               title.contains("cloudflare") ||
               title.contains("verify you are human") ||
               title.contains("performing security verification") ||
               body.contains("cloudflare") ||
               body.contains("verifying you are not a bot") ||
               body.contains("performing security verification") ||
               body.contains("checking your browser")
        
        // Check for redirect to main page (anti-automation detection)
        val redirected = if (expectedUrl.isNotEmpty()) {
            val expectedPath = expectedUrl.substringAfter(activeDomain).substringBefore("?")
            val actualPath = actualUrl.substringAfter(activeDomain).substringBefore("?")
            expectedPath.isNotEmpty() && actualPath.isNotEmpty() && 
            !actualPath.contains(expectedPath.substringAfterLast("/")) &&
            (actualPath == "/" || actualPath.isEmpty())
        } else false

        if (blocked || redirected) {
            println("DEBUG_MYCIMA: Blocked detected! Title: $title, Redirected: $redirected (Expected: $expectedUrl, Got: $actualUrl)")
        }
        return blocked || redirected
    }


    private suspend fun safeGet(url: String): Document? {
        if (!ensureDomain()) return null

        val fullUrl = if (url.startsWith("http")) url else activeDomain + url
        
        // Thread-safe rate limiting: serialize all requests with 2s gap
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTime
            if (timeSinceLastRequest < 2000) {
                delay(2000 - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }
        
        val response = runCatching {
            // First try with normal headers
            val res = app.get(fullUrl, headers = headers, timeout = 15)
            if (res.isSuccessful && !isBlocked(res.document, fullUrl)) {
                res
            } else {
                println("DEBUG_MYCIMA: First attempt failed or blocked ($fullUrl). Syncing with Mutex for CloudflareKiller...")
                // Lock specifically around the Cloudflare solver to prevent resource exhaustion
                mutex.withLock {
                    val cfRes = app.get(fullUrl, headers = headers, interceptor = cfKiller, timeout = 120)
                    if (cfRes.isSuccessful) {
                        delay(2000) // Additional delay after WebView
                    }
                    cfRes
                }
            }
        }.getOrNull()

        if (response?.isSuccessful == true) {
            val doc = response.document
            if (isBlocked(doc, fullUrl)) {
                println("DEBUG_MYCIMA: BLOCKED (Detection Check Post-Solve) - $fullUrl")
                return null
            }
            return doc
        } else {
            println("DEBUG_MYCIMA: Request Failed - $fullUrl (Code: ${response?.code})")
        }

        // Final attempt with domain rotation reset
        checkedDomain = false
        if (!ensureDomain()) return null

        return runCatching {
            mutex.withLock {
                val res = app.get(
                    if (url.startsWith("http")) url else activeDomain + url,
                    headers = headers,
                    interceptor = cfKiller,
                    timeout = 120
                )
                if (res.isSuccessful) {
                    delay(2000)
                }
                if (res.isSuccessful && !isBlocked(res.document, fullUrl)) res.document else null
            }
        }.getOrNull()
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
       "$mainUrl/" to "الرئيسية",
       "$mainUrl/movies/" to "أفلام",
       "$mainUrl/episodes/" to "مسلسلات"
     )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.data.startsWith("http")) request.data else "$mainUrl${request.data}"
        val url = if (page > 1) {
            "${baseUrl.removeSuffix("/")}/page/$page/"
        } else {
            baseUrl
        }
        
        println("DEBUG_MYCIMA: Loading Main Page ($page): $url")
        val doc = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        
        // Exclude slider items (inside .Slider--Grid / wecimabegin) from results
        val items = doc.select("div.GridItem, .GridItem").filter { el ->
            el.parents().none { it.hasClass("Slider--Grid") || it.tagName().equals("wecimabegin", ignoreCase = true) }
        }
        println("DEBUG_MYCIMA: Found ${items.size} items on $url (after slider filter)")
        val searchResults = items.mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, searchResults, hasNext = true)
    }


    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("/?s=$encoded") ?: return emptyList()

        return document.select("div#MainFiltar > .GridItem, .GridItem")
            .mapNotNull { it.toSearchResult() }
    }

    private fun detectType(url: String, document: Document? = null): TvType {
        val path = try { java.net.URLDecoder.decode(url, "UTF-8").lowercase() } catch (_: Exception) { url.lowercase() }

        return when {
            // Priority 1: Explicit path segments
            "/series/" in path && "/movies/" !in path && "/film/" !in path -> TvType.TvSeries
            "/anime/" in path || "انمي" in path -> TvType.Anime
            "/movies/" in path || "/film/" in path -> TvType.Movie
            
            // Priority 2: HTML Breadcrumbs / Categories as fallback
            document?.selectFirst(".breadcrumb, .Category, .category")?.text()?.let { 
                "مسلسلات" in it || "مسلسل" in it || "حلقات" in it || "انمي" in it
            } == true -> if (document.selectFirst(".breadcrumb, .Category, .category")?.text()?.contains("انمي") == true) TvType.Anime else TvType.TvSeries
            document?.selectFirst(".breadcrumb, .Category, .category")?.text()?.contains("افلام") == true -> TvType.Movie
            
            // Priority 3: Keywords in slug (Decoded path)
            "مسلسل" in path || "حلقة" in path || "الحلقة" in path || "وي-سيما" in path || "وي_سيما" in path -> TvType.TvSeries
            path.contains("مسلسلات") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a")
        val title = anchor?.selectFirst("strong")?.text()?.trim() 
            ?: anchor?.attr("title")?.trim()
            ?: this.selectFirst("strong")?.text()?.trim()
            ?: return null
            
        val href = fixUrl(anchor?.attr("href") ?: this.attr("href"))
        val posterUrl = extractPosterUrl(this)
        val type = detectType(href, this.ownerDocument())
        
        return when (type) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun extractPosterUrl(element: Element, document: Element? = null): String? {
        val cssUrlRegex = Regex("""url\(['"]?([^')"]+)['"]?\)""")
        val cssVarRegex = Regex("""--image:\s*url\(['"]?([^')"]+)['"]?\)""")
        // Added data-poster and specific background-image style checks
        val attrs = listOf("style", "data-lazy-style", "data-style", "data-bg", "data-bgset", "data-poster")
        
        // 1. Check the element itself and common child elements
        val elems = listOf(element) + element.select(".BG--GridItem, .BG--Single-begin, .Img--Poster--Single-begin, .Thumb--GridItem, span, a, picture, img, wecima")
        
        for (el in elems) {
            // Priority: Check --image CSS custom property (used by current MyCima site)
            val style = el.attr("style")
            if (style.isNotBlank()) {
                cssVarRegex.find(style)?.groupValues?.getOrNull(1)?.takeIf {
                    it.isNotBlank() && !it.contains("logo") && !it.contains("placeholder")
                }?.let { return fixUrl(it) }
                
                // Also check for standard background-image: url(...)
                if (style.contains("background-image")) {
                     cssUrlRegex.find(style)?.groupValues?.getOrNull(1)?.takeIf {
                        it.isNotBlank() && !it.contains("logo") && !it.contains("placeholder")
                    }?.let { return fixUrl(it) }
                }
            }
            
            // a) Check attributes for CSS url(...) or direct link
            for (attr in attrs) {
                el.attr(attr).takeIf { it.isNotBlank() }?.let { value ->
                    // CSS url(...)
                    cssUrlRegex.find(value)?.groupValues?.getOrNull(1)?.takeIf { 
                        it.isNotBlank() && !it.contains("logo") && !it.contains("placeholder") 
                    }?.let { return fixUrl(it) }
                    
                    // Direct HTTP/HTTPS or protocol-relative
                    if ((value.startsWith("http") || value.startsWith("//")) && !value.contains("logo") && !value.contains("placeholder")) {
                         return fixUrl(if (value.startsWith("//")) "https:$value" else value)
                    }
                }
            }

            // b) Check <img> tags
            el.select("img[data-src], img[data-lazy-src], img[src], img[data-srcset]").forEach { img ->
                val url = img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { img.attr("src").ifBlank { img.attr("data-srcset") } }
                }
                if (url.isNotBlank() && !url.contains("logo") && !url.contains("placeholder")) return fixUrl(url)
            }

            // c) Check <picture> sources
            el.select("picture source[data-srcset], picture img[data-src]").forEach { pic ->
                val url = pic.attr("data-srcset").ifBlank { pic.attr("data-src") }
                if (url.isNotBlank() && !url.contains("logo") && !url.contains("placeholder")) return fixUrl(url)
            }
        }

        // 2. Check meta tags in the document (if provided)
        document?.let { doc ->
            listOf("meta[property=og:image]", "meta[name=twitter:image]", "link[rel=image_src]").forEach { selector ->
                doc.selectFirst(selector)?.attr("content")?.takeIf { 
                    it.isNotBlank() && !it.contains("logo") && !it.contains("default") && !it.contains("placeholder")
                }?.let { return fixUrl(it) }
            }

        }

        return null // fallback if nothing found
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse? {

        val document = safeGet(url) ?: return null
        val fixedUrl = fixUrl(url)

        val title = document.selectFirst(
            "h1[itemprop=name], h1, h2, .Title, .title, meta[property=og:title]"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: return null

        // TIERED POSTER EXTRACTION
        val posterUrl = document.selectFirst(".Img--Poster--Single-begin")?.let { extractPosterUrl(it, document) }
            ?: extractPosterUrl(document.selectFirst(".Poster--Single-begin") ?: document, document)

        val year = document.selectFirst("a[href*=release-year]")
            ?.text()?.toIntOrNull()

        val plot = document.selectFirst("div.story p, div:contains(قصة العرض) + div, .AsideContext")
            ?.text()?.trim()

        val genres = document.select("a[href*=/genre/]")
            .map { it.text() }

        val actors = document.select("a[href*=/actor/], a[href*=/producer/]")
            .map { Actor(it.text(), "") }

        val duration = document.selectFirst("span:contains(دقيقة)")
            ?.text()?.replace("[^0-9]".toRegex(), "")
            ?.toIntOrNull()

        // Improved series detection using precision logic + DOM override
        val type = detectType(fixedUrl, document)
        val hasEpisodeList = document.select(".EpisodesList, .episodes-list, .season-episodes").isNotEmpty() 
        
        // Only consider it a series if:
        // 1. Explicitly detected as Series/Anime by URL/Breadcrumbs
        // 2. has an explicit Episode List container (ignoring sidebars)
        val isSeries = type == TvType.TvSeries || type == TvType.Anime || hasEpisodeList

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val episodeUrls = mutableSetOf<String>()

            // 1. Always include current URL if it looks like an episode
            val currentUrlDecoded = try { java.net.URLDecoder.decode(fixedUrl, "UTF-8") } catch(_: Exception) { fixedUrl }
            if (currentUrlDecoded.contains("حلقة") || currentUrlDecoded.contains("/episode/")) {
                val epNum = currentUrlDecoded.substringAfterLast("حلقة-").substringBefore("-").replace("[^0-9]".toRegex(), "").toIntOrNull()
                    ?: currentUrlDecoded.replace("[^0-9]".toRegex(), "").toIntOrNull()
                
                if (epNum != null && episodeUrls.add(fixedUrl)) {
                    episodes.add(
                        newEpisode(fixedUrl) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            // 2. Get episodes from current page list or seasons
            document.select(".EpisodesList a, div.episodes-list a, div.season-episodes a, a:has(span.episode), a.GridItem:has(strong)").forEach { ep ->
                val epHref = ep.attr("href")
                val fixedEp = fixUrl(epHref)
                // softened filter: only skip if it's the exact series root or category
                if (!fixedEp.startsWith("http") || fixedEp.endsWith("/series/") || fixedEp.contains("/category/") || !episodeUrls.add(fixedEp)) return@forEach
                
                val epText = ep.text().trim()
                val epNum = ep.selectFirst("span.episode, span:contains(حلقة), span:contains(الحلقة)")?.text()
                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                    ?: epText.replace("[^0-9]".toRegex(), "").toIntOrNull()
                
                val currentSeason = 1
                val cleanEpisodeNumber = epNum ?: (episodes.count { (it.season ?: 1) == currentSeason } + 1)

                episodes.add(
                    newEpisode(fixedEp) {
                        this.name = "Episode $cleanEpisodeNumber".trim()
                        this.episode = cleanEpisodeNumber
                        this.season = currentSeason
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            // Optimistic Episode Loading: Only try season links if no episodes found
            if (episodes.isEmpty()) {
                val seasonLinks = document.select("a[href*=/season/], a:contains(الموسم)")
                for (seasonLink in seasonLinks) {
                    val seasonHref = seasonLink.attr("href")
                    val seasonNumber = seasonLink.text()
                        .replace("[^0-9]".toRegex(), "")
                        .toIntOrNull()

                    if(seasonHref.isNotEmpty()) {
                        val seasonDoc = safeGet(seasonHref)
                        val epLabels = seasonDoc?.select(".EpisodesList a, a.GridItem, a:has(span.episode)")
                        if (epLabels != null) {
                            val currentSeason = seasonNumber ?: 1
                            for (epLabelInner in epLabels) {
                                val epHref = epLabelInner.attr("href")
                                val fixedEp = fixUrl(epHref)
                                if (!fixedEp.startsWith("http") || !episodeUrls.add(fixedEp)) continue
                                
                                val epText = epLabelInner.text().trim()
                                val epNum = epLabelInner.selectFirst("span.episode, span:contains(حلقة), span:contains(الحلقة)")?.text()
                                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                                    ?: epText.replace("[^0-9]".toRegex(), "").toIntOrNull()
                                
                                val cleanEpisodeNumber = epNum ?: (
                                    episodes.count { (it.season ?: 1) == currentSeason } + 1
                                )

                                episodes.add(
                                    newEpisode(fixedEp) {
                                        this.name = "Episode $cleanEpisodeNumber".trim()
                                        this.episode = cleanEpisodeNumber
                                        this.season = currentSeason
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            episodes.sortWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

            return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addActors(actors)
            }
        } else {
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addActors(actors)
            }
        }
    }

    // ---------- SMART SERVER RANKING ----------
    private val serverPriority = listOf(
        "vidsharing",
        "fsdcmo",
        "govid",
        "voe",
        "filemoon",
        "uqload",
        "streamhg",
        "dood",
        "okru"
    )

    private fun getServerScore(name: String): Int {
        val lower = name.lowercase()
        return serverPriority.indexOfFirst { lower.contains(it) }
            .let { if (it == -1) 999 else it }
    }

    private fun getQuality(text: String?): Int {
        val t = text?.lowercase() ?: return Qualities.Unknown.value

        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeProxy(url: String): String {
        return if (url.contains("/play/")) {
            runCatching {
                val b64 = url.substringAfter("/play/")
                    .substringBefore("/")
                    .replace("_", "/")
                    .replace("-", "+")
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }.getOrElse { url }
        } else url
    }

    private fun decodeHex(hex: String): String {
        return try {
            val sb = StringBuilder()
            for (i in 0 until hex.length step 2) {
                val byteHex = hex.substring(i, i + 2)
                sb.append(Integer.parseInt(byteHex, 16).toChar())
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun getMohixLink(url: String, data: String, callback: (ExtractorLink) -> Unit): Boolean {
        // data param is kept for signature consistency if needed elsewhere, but marked as ignored by prefixing with _ or just left as is if it's private.
        // Actually, it's private, so I can just remove it.
        val page = app.get(url, headers = headers).text
        val hex = Regex("""(?:const|var)\s+Mohix\s*=\s*["']([0-9a-fA-F]+)["']""").find(page)?.groupValues?.get(1)
        if (hex != null) {
            val decoded = decodeHex(hex)
            if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                callback(
                    newExtractorLink(
                        "MyCima Player",
                        "MyCima Player",
                        decoded,
                        if (decoded.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQuality(decoded)
                    }
                )
                return true
            }
        }
        return false
    }

    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = safeGet(data) ?: return false.also { println("DEBUG_MYCIMA: Failed to get document for $data") }
        println("DEBUG_MYCIMA: loadLinks started for $data")
        val pageHtml = document.html()
        val usedLinks = mutableSetOf<String>()
        val foundAtomic = java.util.concurrent.atomic.AtomicBoolean(false)




        // Method 1: Robust Regex Extraction from Page Source
        println("DEBUG_MYCIMA: Starting Method 1 (Regex)")
        // a) Embed IDs: /e/NUMBER
        for (match in Regex("""/e/(\d+)/?[?]""").findAll(pageHtml)) {
            val embedId = match.groupValues[1]
            println("DEBUG_MYCIMA: Found Embed ID: $embedId")
            processUrl("$activeDomain/e/$embedId/", data, usedLinks, foundAtomic, subtitleCallback, callback)
        }
        // b) Base64 Play Links: /play/BASE64
        for (match in Regex("""/play/([A-Za-z0-9+/=_-]+)""").findAll(pageHtml)) {
            val base64 = match.groupValues[1]
            println("DEBUG_MYCIMA: Found Play Link (Base64)")
            processUrl("$activeDomain/play/$base64/", data, usedLinks, foundAtomic, subtitleCallback, callback)
        }

        // Method 2: Script Tag Parsing
        println("DEBUG_MYCIMA: Starting Method 2 (Script Parsing)")
        for (script in document.select("script")) {
            val scriptText = script.html()
            for (match in Regex("""data-watch\s*[=:]\s*["']([^"']+)["']""").findAll(scriptText)) {
                println("DEBUG_MYCIMA: Found data-watch in script")
                processUrl(match.groupValues[1], data, usedLinks, foundAtomic, subtitleCallback, callback)
            }
            for (match in Regex("""data-id\s*[=:]\s*["']([^"']+)["']""").findAll(scriptText)) {
                val serverId = match.groupValues[1]
                if (serverId.isNotBlank()) {
                    try {
                        val response = app.post(
                            "$activeDomain/wp-admin/admin-ajax.php",
                            headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                            data = mapOf("action" to "get_player", "server" to serverId)
                        ).document
                        val iframeSrc = response.selectFirst("iframe")?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            processUrl(iframeSrc, data, usedLinks, foundAtomic, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        // Method 3: Static HTML Extraction (Enhanced)
        println("DEBUG_MYCIMA: Starting Method 3 (Broad Static HTML)")

        // User Request: Target specific download section
        val downloadItems = document.select("div.DownloadsList a[href]")
        println("DEBUG_MYCIMA: Found ${downloadItems.size} download list items")
        for (link in downloadItems) {
            val href = link.attr("href")
            if (href.isNotBlank()) processUrl(href, data, usedLinks, foundAtomic, subtitleCallback, callback)
        }
        
        // a) Broad Download & Embed Link Search
        val allLinks = document.select("a[href]")
        println("DEBUG_MYCIMA: Scanning ${allLinks.size} total links")
        
        for (link in allLinks) {
            val href = link.attr("href")
            if (href.isBlank()) continue
            
            val absoluteHref = if (href.startsWith("http")) href else {
                if (href.startsWith("/")) "$activeDomain$href" else "$activeDomain/$href"
            }
            
            // Check for known servers (case-insensitive)
            val lowerHref = absoluteHref.lowercase()
            val shouldProcess = listOf(
                "hglink", "vinovo", "mxdrop", "dsvplay", "filemoon", 
                "govid", "vidsharing", "streamhg", "dood", "uqload", "voe", "fsdcmo", "fdewsdc",
                "/e/", "/play/"
            ).any { lowerHref.contains(it) }
            
            if (shouldProcess) {
                println("DEBUG_MYCIMA: Found matching link: $absoluteHref")
                processUrl(absoluteHref, data, usedLinks, foundAtomic, subtitleCallback, callback)
            }
        }

        // b) Standard Iframes
        for (iframe in document.select("iframe[src]")) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                println("DEBUG_MYCIMA: Found iframe: $src")
                processUrl(src, data, usedLinks, foundAtomic, subtitleCallback, callback)
            }
        }

        // c) Server List DOM (Fallback if they ARE present)
        val serverItems = document.select(".WatchServersList li, #watch li")
        println("DEBUG_MYCIMA: Found ${serverItems.size} server list items")
        
        for (li in serverItems) {
            val id = li.attr("data-id")
            val watchUrl = li.attr("data-watch")
            
            if (id.isNotBlank()) {
                println("DEBUG_MYCIMA: Found server with data-id: $id")
                try {
                    val response = app.post(
                        "$activeDomain/wp-admin/admin-ajax.php",
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        data = mapOf("action" to "get_player", "server" to id)
                    ).document
                    val iframeSrc = response.selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        processUrl(iframeSrc, data, usedLinks, foundAtomic, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            if (watchUrl.isNotBlank()) {
                println("DEBUG_MYCIMA: Found server with data-watch: $watchUrl")
                processUrl(watchUrl, data, usedLinks, foundAtomic, subtitleCallback, callback)
            }
        }


        val result = foundAtomic.get()
        println("DEBUG_MYCIMA: loadLinks finished. Result: $result")
        return result
    }

    private suspend fun processUrl(
        rawUrl: String, 
        data: String, 
        usedLinks: MutableSet<String>, 
        foundAtomic: java.util.concurrent.atomic.AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("DEBUG_MYCIMA: Processing URL: $rawUrl")
        var finalUrl = fixUrl(decodeProxy(rawUrl))

        // Fix malformed URLs (e.g. https://mxdrop.to/e/IDhttps://mxdrop.to)
        val duplicationMatch = Regex("""(https?://.*?)(?<![?=&])(https?://.*)""").find(finalUrl)
        if (duplicationMatch != null) {
            println("DEBUG_MYCIMA: Fixing malformed URL: $finalUrl -> ${duplicationMatch.groupValues[1]}")
            finalUrl = duplicationMatch.groupValues[1]
        }

        // Handle slp_watch (Base64 encoded redirect)
        if (finalUrl.contains("slp_watch=")) {
            val slpMatch = Regex("""slp_watch=([a-zA-Z0-9+/=]+)""").find(finalUrl)
            if (slpMatch != null) {
                val base64Url = slpMatch.groupValues[1]
                try {
                    val decodedUrl = String(android.util.Base64.decode(base64Url, android.util.Base64.DEFAULT))
                    println("DEBUG_MYCIMA: Decoded slp_watch URL: $decodedUrl")
                    processUrl(decodedUrl, data, usedLinks, foundAtomic, subtitleCallback, callback)
                    return
                } catch (e: Exception) {
                    println("DEBUG_MYCIMA: Failed to decode slp_watch: ${e.message}")
                }
            }
        }
        
        // Handle direct links (e.g. linkfas2)
        if (finalUrl.contains("linkfas2.ecotabia.online")) {
             println("DEBUG_MYCIMA: Found direct linkfas2 link: $finalUrl")
             callback(
                newExtractorLink(
                    this.name,
                    "MyCima Server",
                    finalUrl,
                    if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQuality(finalUrl)
                }
            )
            foundAtomic.set(true)
            return
        }

        if (finalUrl.isNotBlank() && usedLinks.add(finalUrl)) {
            println("DEBUG_MYCIMA: Found valid candidate: $finalUrl")
            if (finalUrl.contains("govid") || finalUrl.contains("vidsharing") || finalUrl.contains("fsdcmo") || finalUrl.contains("fdewsdc")) {
                if (getMohixLink(finalUrl, data, callback)) {
                    foundAtomic.set(true)
                }
            }
            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                println("DEBUG_MYCIMA: Extractor found link: ${link.url}")
                foundAtomic.set(true)
                callback(link)
            }
        } else {
            println("DEBUG_MYCIMA: URL ignored (blank or duplicate): $finalUrl")
        }
    }
}
