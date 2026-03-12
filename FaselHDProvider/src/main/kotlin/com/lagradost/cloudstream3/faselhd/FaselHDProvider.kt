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

    // FIX #5: function instead of val so Referer always uses current mainUrl
    private fun defaultHeaders() = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent" to userAgent,
        "Referer" to mainUrl
    )

    companion object {
        // FIX #1: Correct regex escaping — in raw """ """ strings, \s is already whitespace class
        // Do NOT double-escape: \\s in raw string = literal \s (wrong)
        private val M3U8_REGEX = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        private val SOURCES_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
        private val PLAYER_TOKEN_REGEX = Regex("""video_player\?player_token=[^"'\s]+""")
    }

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()

    private suspend fun getRealMainUrl(): String {
        return try {
            val resp = app.get(mainUrl, allowRedirects = true, timeout = 10)
            val finalUrl = resp.url.trimEnd('/')
            val uri = java.net.URL(finalUrl)
            "${uri.protocol}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun isBlocked(doc: Document): Boolean {
        val title = doc.select("title").text().lowercase()
        val body = doc.body().text().lowercase()
        return title.contains("just a moment") || title.contains("cloudflare") ||
               title.contains("security verification") || title.contains("access denied") ||
               body.contains("verifying you are not a bot") ||
               body.contains("performing security verification")
    }

    private suspend fun safeGet(url: String): Document? {
        return runCatching {
            val res = app.get(url, headers = defaultHeaders(), timeout = 15)
            if (res.isSuccessful && !isBlocked(res.document)) return res.document
            mutex.withLock {
                val cfRes = app.get(url, headers = defaultHeaders(), interceptor = cfKiller, timeout = 120)
                if (cfRes.isSuccessful) {
                    delay(2000)
                    if (!isBlocked(cfRes.document)) return cfRes.document
                }
                null
            }
        }.getOrNull()
    }

    // FIX #4: Use relative paths as keys — prepend real mainUrl at request time in getMainPage
    override val mainPage = mainPageOf(
        "/most_recent"  to "المضاف حديثاً",
        "/series"       to "مسلسلات",
        "/movies"       to "أفلام",
        "/asian-series" to "مسلسلات آسيوية",
        "/anime"        to "الأنمي",
        "/tvshows"      to "البرامج التلفزيونية",
        "/dubbed-movies" to "أفلام مدبلجة",
        "/hindi"        to "أفلام هندية",
        "/asian-movies" to "أفلام آسيوية",
        "/anime-movies" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = getRealMainUrl()
        // FIX #4: build full URL here, after mainUrl is resolved
        val basePath = request.data  // e.g. "/most_recent"
        val url = if (page == 1) "$mainUrl$basePath"
                  else "$mainUrl${basePath.trimEnd('/')}/page/$page"
        val doc = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(
            request.name,
            doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.h1, .entry-title, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null
        val img = selectFirst("img")
        val posterUrl = img?.let {
            it.attr("data-src").ifEmpty { it.attr("data-original") }.ifEmpty { it.attr("src") }
        }?.let { if (it.startsWith("//")) "https:$it" else it }
        val quality = selectFirst("span.quality, span.qualitySpan")?.text()
        val type = if (href.contains("/episode/") || href.contains("/episodes/"))
            TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = getRealMainUrl()
        val doc = safeGet("$mainUrl/?s=$query") ?: return emptyList()
        return doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = getRealMainUrl()
        val doc = safeGet(url) ?: return null

        val title = doc.selectFirst("div.title, h1.postTitle, div.h1, .entry-title, h1")
            ?.text() ?: doc.title()

        // FIX #23: Only use og:image to avoid 403 Referer issues
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotEmpty() && it.startsWith("http") }
            ?: doc.selectFirst("div.posterImg img, .entry-thumbnail img, img.posterImg")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.let { if (it.startsWith("//")) "https:$it" else it }

        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc, .entry-content p")?.text()

        val year = doc.select("a[href*='series_year'], a[href*='movies_year']")
            .firstOrNull()?.text()?.toIntOrNull()

        val recommendations = doc.select("div.postDiv, article, .entry-box")
            .mapNotNull { it.toSearchResult() }

        val ph = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)

        // FIX #6: Series detection — don't rely on #vihtml (it's empty in raw HTML)
        val isEpisodePage = url.contains("/episodes/")
        val isSeries = url.contains("/series/") || url.contains("/tvshow") ||
                url.contains("/anime/") || url.contains("/asian-series/") ||
                doc.select("#seasonList, div.seasonLoop, #epAll, div.epAll, #DivEpisodesList").isNotEmpty() ||
                doc.select("a[href*='/episodes/']").isNotEmpty()

        return when {
            isEpisodePage -> {
                // Single episode page — treat as 1-episode TvSeries
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                    newEpisode(url) { name = title }
                )) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.posterHeaders = ph
                }
            }
            isSeries -> {
                // FIX #6: Collect episode links from correct selectors (not #vihtml)
                val rawEpisodeLinks = doc.select(
                    "#epAll a, div.epAll a, #DivEpisodesList a, " +
                    ".episodes-list a, a[href*='/episodes/']"
                )
                val episodes = if (rawEpisodeLinks.isNotEmpty()) {
                    rawEpisodeLinks.mapIndexed { idx, el ->
                        val epUrl = el.attr("abs:href")
                        val epTitle = el.text().trim()
                        // Extract episode number from title like "الحلقة 15" → 15
                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        newEpisode(epUrl) {
                            name = epTitle
                            episode = epNum
                        }
                    }.distinctBy { it.data }
                } else {
                    // Seasons page — collect season links and return them as episodes
                    // Each season link leads to a season page with actual episode links
                    doc.select("#seasonList a, div.seasonLoop a").mapIndexed { idx, el ->
                        val seasonUrl = el.attr("abs:href")
                        val seasonTitle = el.text().trim()
                        newEpisode(seasonUrl) {
                            name = seasonTitle
                            season = Regex("""\d+""").find(seasonTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        }
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.recommendations = recommendations
                    this.posterHeaders = ph
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.recommendations = recommendations
                    this.posterHeaders = ph
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
        mainUrl = getRealMainUrl()
        val fixedData = if (data.contains(baseDomain)) {
            val uri = java.net.URL(data)
            data.replace("${uri.protocol}://${uri.host}", mainUrl)
        } else data

        mainUrl = getRealMainUrl()

        // Phase 1: Get CF clearance cookies and extract postId
        val doc = safeGet(fixedData) ?: return false
        val html = doc.html()

        // Extract WP postId needed for AJAX
        val postId = Regex("""[?&]p=(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""\"postid\":\s*\"?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""post_id[\"']?\s*:\s*[\"']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: doc.select("#liskSh").text().let { Regex("""p=(\d+)""").find(it)?.groupValues?.get(1) }
            ?: run {
                println("FaselHD DEBUG: Could not find postId in $fixedData")
                null
            }

        // Phase 2: Call AJAX to get player token
        var playerTokenUrl: String? = null
        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            runCatching {
                // Different possible action names: "get_player_token", "faselhd_player", "doo_player_ajax"
                val ajaxResponse = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "get_player_token",
                        "post_id" to postId,
                        "server" to "1"
                    ),
                    headers = defaultHeaders(),
                    referer = fixedData
                ).text
                
                println("FaselHD AJAX Response: $ajaxResponse")

                playerTokenUrl = Regex("""video_player\?player_token=[^"'\s]+""").find(ajaxResponse)?.value?.let {
                    if (it.startsWith("http")) it else "$mainUrl/$it"
                } ?: Regex(""""(?:url|src|player)"\s*:\s*"([^"]+video_player[^"]+)"""")
                    .find(ajaxResponse)?.groupValues?.get(1)
            }
        }

        // Phase 3: Extract m3u8 from player page
        val safePlayerTokenUrl = playerTokenUrl
        if (safePlayerTokenUrl != null) {
            val playerDoc = safeGet(safePlayerTokenUrl)
            val playerHtml = playerDoc?.html() ?: ""
            
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(playerHtml)
                ?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[.*?file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
                    .find(playerHtml)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(playerHtml)
                    ?.groupValues?.get(1)

            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        m3u8,
                        if (m3u8.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = safePlayerTokenUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        return inlineM3u8Fallback(fixedData, callback)
    }

    private suspend fun inlineM3u8Fallback(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = safeGet(url) ?: return false
        val html = doc.html()

        // Check for pre-rendered iframe src (some CF-bypass sessions cache it)
        val iframeSrc = doc.select("iframe[src*=video_player]").attr("abs:src").ifEmpty {
            doc.select("iframe[data-src*=video_player]").attr("abs:data-src")
        }

        if (iframeSrc.isNotEmpty()) {
            val m3u8 = runCatching {
                WebViewResolver(Regex("""\.m3u8""")).resolveUsingWebView(
                    requestCreator("GET", iframeSrc, referer = url, headers = defaultHeaders())
                ).first?.url?.toString()
            }.getOrNull()

            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeSrc
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        // Last resort: scan raw HTML for any m3u8/mp4 URLs
        val allMatches = (M3U8_REGEX.findAll(html) + SOURCES_REGEX.findAll(html) +
            Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(html))
            .map { it.groupValues.getOrNull(1)?.takeIf { u -> u.startsWith("http") } ?: it.value }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

        if (allMatches.isEmpty()) return false

        allMatches.forEach { videoUrl ->
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = getVideoQuality(videoUrl)
                }
            )
        }
        return true
    }

    private fun getVideoQuality(url: String): Int {
        return when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720")  -> Qualities.P720.value
            url.contains("480")  -> Qualities.P480.value
            url.contains("360")  -> Qualities.P360.value
            else                 -> Qualities.Unknown.value
        }
    }
}
