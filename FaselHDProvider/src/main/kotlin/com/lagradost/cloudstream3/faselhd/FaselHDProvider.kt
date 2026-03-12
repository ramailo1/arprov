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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent" to userAgent,
        "Referer" to mainUrl
    )

    // Derive poster headers from a given page URL so the Referer always matches the content domain
    private fun posterHeadersFor(pageUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to pageUrl
        )
    }

    // ---------- CLOUDFLARE BYPASS ----------
    private fun isBlocked(doc: Document): Boolean {
        val title = doc.select("title").text().lowercase()
        val body = doc.body().text().lowercase()
        val blocked = title.contains("just a moment") ||
               title.contains("security verification") ||
               title.contains("access denied") ||
               title.contains("cloudflare") ||
               title.contains("verify you are human") ||
               body.contains("verifying you are not a bot") ||
               body.contains("performing security verification") ||
               body.contains("checking your browser")
        
        if (blocked) {
            println("FaselHD Debug: Blocked by Cloudflare? Title: '$title' | Body snippet: ${body.take(100)}")
        }
        return blocked
    }

    private suspend fun safeGet(url: String): Document? {
        // First try: normal HTTP request
        val response = runCatching {
            val res = app.get(url, headers = defaultHeaders, timeout = 15)
            if (res.isSuccessful && !isBlocked(res.document)) {
                return@runCatching res
            }
            // Cloudflare detected — solve with CloudflareKiller
            println("FaselHD Debug: [safeGet] Initial request to $url was successful, but blocked by CF. Attempting CFKiller.")
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
        println("FaselHD Debug: Found ${elements.size} elements in main page")
        val list = elements.mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text()
            ?: selectFirst("div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null
        
        val img = selectFirst("div.imgdiv-class img, div.postInner img, img")
        val posterUrl = img?.let {
            (it.attr("data-src").ifEmpty { it.attr("data-original") }
                .ifEmpty { it.attr("src") })
                .let { url -> if (url.startsWith("//")) "https:$url" else url }
                .ifEmpty { null }
        }
        val quality = selectFirst("span.quality, span.qualitySpan")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            // Referer must be mainUrl, not the card href
            this.posterHeaders = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        println("FaselHD Debug: Searching url: $url")
        val doc = safeGet(url) ?: return emptyList()
        val elements = doc.select("div.postDiv")
        println("FaselHD Debug: Search found ${elements.size} elements")
        return elements.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = safeGet(url) ?: return null
        val title = doc.selectFirst("div.title, h1.postTitle, div.postInner div.h1")
            ?.text() ?: doc.title()

        // Poster: use the side image
        val poster = doc.selectFirst("div.posterImg img, .single-post img, img.posterImg")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.let { if (it.startsWith("//")) "https:$it" else it }

        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc")?.text()
        val year = doc.selectFirst("a[href*='series_year'], a[href*='movies_year']")
            ?.text()?.toIntOrNull()
            ?: doc.select("#singleList .col-xl-6").find { it.text().contains("الصدور") }
                ?.text()?.substringAfter(":")?.trim()?.toIntOrNull()

        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        val ph = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)

        // ── Episodes: look in #vihtml links OR season-based episode lists ──
        // The episode links are INSIDE the #vihtml div (loaded by JS) or #epAll
        val episodeLinks = doc.select("#vihtml a[href*='/episodes/'], #epAll a, div.epAll a")
        val isSeries = episodeLinks.isNotEmpty() 
            || doc.select("#seasonList, div.seasonLoop, a[href*='/episodes/']").isNotEmpty()
            || url.contains("/episodes/") || url.contains("/series/")

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.duration = null // Duration parsing is flaky, leave as null
                this.recommendations = recommendations
                this.posterHeaders = ph
            }
        } else {
            // For series: collect all episode URLs
            val episodes = episodeLinks.mapIndexed { idx, ep ->
                val epTitle = ep.text().trim()
                val epUrl = ep.attr("abs:href")
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val season = when {
                    url.contains("الموسم-الاول") || url.contains("season-1") -> 1
                    url.contains("الموسم-الثاني") -> 2
                    url.contains("الموسم-الثالث") -> 3
                    else -> null
                }
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                    this.season = season
                }
            }.distinctBy { it.data }

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
        println("FaselHD: loadLinks START → $data")

        // ── Approach 1: WebViewResolver directly on the episode page ──
        return try {
            val m3u8Pattern = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
            
            val result = WebViewResolver(
                interceptUrl = m3u8Pattern,
                additionalUrls = listOf(Regex("""\.mp4""")),
            ).resolveUsingWebView(
                requestCreator("GET", data, referer = mainUrl, headers = defaultHeaders)
            )

            val req = result.first
            if (req != null) {
                val url = req.url.toString()
                val headers = req.headers.toMap()
                val isM3u8 = url.contains(".m3u8", ignoreCase = true)
                println("FaselHD: WebView captured link: $url")
                callback.invoke(
                    newExtractorLink(name, name, url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else {
                println("FaselHD: WebView failed to capture link, trying fallback.")
                // ── Approach 2: Parse inline m3u8 from page HTML ──
                inlineM3u8Fallback(data, callback)
            }
        } catch (e: Exception) {
            println("FaselHD: loadLinks exception: ${e.message}")
            inlineM3u8Fallback(data, callback)
        }
    }

    private suspend fun inlineM3u8Fallback(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FaselHD: Running inlineM3u8Fallback for $url")
        val doc = safeGet(url) ?: return false
        val html = doc.html()
        
        // Look for m3u8/mp4 URLs in page scripts
        val videoPattern = Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""")
        val matches = videoPattern.findAll(html).map { it.groupValues[1] }.distinct().toList()
        
        println("FaselHD: Fallback found ${matches.size} potential links")
        if (matches.isEmpty()) return false
        
        matches.forEach { videoUrl ->
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(name, name, videoUrl,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = when {
                        videoUrl.contains("1080") -> Qualities.P1080.value
                        videoUrl.contains("720") -> Qualities.P720.value
                        videoUrl.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
        return true
    }
}
