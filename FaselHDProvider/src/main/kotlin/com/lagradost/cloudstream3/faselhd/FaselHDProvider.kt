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
    override var mainUrl = "https://web31118x.faselhdx.bid"
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
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

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
            ?: return null

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
        val doc = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = getPosterUrl()
        val quality = selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
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
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

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
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = safeGet(data) ?: return false

        // Step 1: Extract player URL from server tabs (ul.tabs-ul li[onclick])
        val serverItems = doc.select("ul.tabs-ul li[onclick]")
        var playerUrl: String? = null

        for (server in serverItems) {
            val onclick = server.attr("onclick")
            // Pattern: player_iframe.location.href = 'URL'
            val match = Regex("""['"]([^'"]*video_player[^'"]*)['"]\s*""").find(onclick)
                ?: Regex("""['"]([^'"]+)['"]\s*$""").find(onclick)
            if (match != null) {
                playerUrl = fixUrl(match.groupValues[1])
                break
            }
        }

        // Fallback: look for iframe directly on the page
        if (playerUrl.isNullOrEmpty()) {
            val iframe = doc.selectFirst("iframe[name=player_iframe], iframe[src*=video_player]")
            playerUrl = iframe?.absUrl("src")?.ifEmpty { iframe.absUrl("data-src") }
        }

        if (playerUrl.isNullOrEmpty()) return false

        // Step 2: Load the video_player page and extract m3u8 URLs
        try {
            // Method 1: Try WebViewResolver to intercept m3u8 requests from JWPlayer
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(
                    Regex("""scdns\.io.*\.m3u8"""),
                    Regex("""master\.m3u8"""),
                    Regex("""playlist\.m3u8""")
                ),
                userAgent = USER_AGENT
            )

            val requestHeaders = defaultHeaders + mapOf("Referer" to data)
            val request = requestCreator("GET", playerUrl, headers = requestHeaders)

            val (mainReq, additionalReqs) = resolver.resolveUsingWebView(request)

            val allIntercepted = listOfNotNull(mainReq) + additionalReqs
            val m3u8Urls = mutableSetOf<String>()

            for (intercepted in allIntercepted) {
                val videoUrl = intercepted.url.toString()
                if (videoUrl.contains(".m3u8")) {
                    m3u8Urls.add(videoUrl)
                }
            }

            // Emit all intercepted m3u8 URLs
            for (videoUrl in m3u8Urls) {
                val qualityText = when {
                    videoUrl.contains("1080") -> "1080p"
                    videoUrl.contains("720") -> "720p"
                    videoUrl.contains("480") -> "480p"
                    videoUrl.contains("360") -> "360p"
                    videoUrl.contains("master") -> "Auto"
                    else -> "Unknown"
                }

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "$name - $qualityText",
                        videoUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.quality = getQualityInt(qualityText)
                    }
                )
            }

            if (m3u8Urls.isNotEmpty()) return true

            // Method 2: Fallback — Load player page via HTTP and regex for m3u8 URLs in source
            val playerResponse = app.get(
                playerUrl,
                referer = data,
                headers = defaultHeaders,
                interceptor = cfKiller,
                timeout = 120
            ).text

            // Look for scdns.io m3u8 URLs in the raw HTML/JS source
            val m3u8Pattern = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
            for (match in m3u8Pattern.findAll(playerResponse)) {
                val videoUrl = match.value
                    .replace("\\u002F", "/")
                    .replace("\\/", "/")

                if (videoUrl.contains("scdns.io") || videoUrl.contains("faselhdx")) {
                    val qualityText = when {
                        videoUrl.contains("1080") -> "1080p"
                        videoUrl.contains("720") -> "720p"
                        videoUrl.contains("480") -> "480p"
                        videoUrl.contains("360") -> "360p"
                        videoUrl.contains("master") -> "Auto"
                        else -> "Unknown"
                    }

                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "$name - $qualityText",
                            videoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = getQualityInt(qualityText)
                        }
                    )
                }
            }

            // Method 3: Look for data-url attributes in quality buttons (if JS already executed)
            val dataUrlPattern = Regex("""data-url=["'](https?://[^"']+\.m3u8[^"']*)["']""")
            for (match in dataUrlPattern.findAll(playerResponse)) {
                val videoUrl = match.groupValues[1]
                val qualityText = when {
                    videoUrl.contains("1080") -> "1080p"
                    videoUrl.contains("720") -> "720p"
                    videoUrl.contains("480") -> "480p"
                    videoUrl.contains("360") -> "360p"
                    videoUrl.contains("master") -> "Auto"
                    else -> "Unknown"
                }

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "$name - $qualityText",
                        videoUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.quality = getQualityInt(qualityText)
                    }
                )
            }

        } catch (e: Exception) {
            // e.printStackTrace()
        }

        return true
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
