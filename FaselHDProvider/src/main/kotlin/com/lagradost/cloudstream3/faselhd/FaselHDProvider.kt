package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()

    // ────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────

    /** Resolve the real host once — call at the TOP of each public method, store locally. */
    private suspend fun resolveHost(): String = runCatching {
        println("FaselHD: Resolving host from $mainUrl")
        val resp = app.get(mainUrl, allowRedirects = true, timeout = 10)
        val uri  = java.net.URL(resp.url.trimEnd('/'))
        val host = "${uri.protocol}://${uri.host}"
        println("FaselHD: Host resolved to $host")
        host.also { mainUrl = it }
    }.getOrDefault(mainUrl)

    /** Swap whatever subdomain is in [url] with [host]. */
    private fun normalizeUrl(url: String, host: String): String {
        if (!url.contains(baseDomain)) return url
        val old = runCatching { java.net.URL(url) }.getOrNull() ?: return url
        return url.replace("${old.protocol}://${old.host}", host)
    }

    private fun headers(host: String, referer: String = host) = mapOf(
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent"      to userAgent,
        "Referer"         to referer,
        "Origin"          to host
    )

    private fun isBlocked(doc: Document): Boolean {
        val t = doc.select("title").text().lowercase()
        val b = doc.body().text().lowercase()
        return "just a moment" in t || "cloudflare" in t ||
               "security verification" in t || "access denied" in t ||
               "verifying you are not a bot" in b ||
               "performing security verification" in b
    }

    /** Fetch a page. Try plain first; fall back to cfKiller once per mutex. */
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
                    headers      = headers(mainUrl, referer),
                    interceptor  = cfKiller,
                    timeout      = 120
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

    // ────────────────────────────────────────────────
    // Main page
    // ────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "/most_recent"   to "المضاف حديثاً",
        "/series"        to "مسلسلات",
        "/movies"        to "أفلام",
        "/asian-series"  to "مسلسلات آسيوية",
        "/anime"         to "الأنمي",
        "/tvshows"       to "البرامج التلفزيونية",
        "/dubbed-movies" to "أفلام مدبلجة",
        "/hindi"         to "أفلام هندية",
        "/asian-movies"  to "أفلام آسيوية",
        "/anime-movies"  to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("FaselHD: getMainPage -> ${request.name} (Page: $page)")
        val host = resolveHost()
        val url  = if (page == 1) "$host${request.data}"
                   else "$host${request.data.trimEnd('/')}/page/$page"
        val doc  = safeGet(url, host) ?: return newHomePageResponse(request.name, emptyList())
        val results = doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
        println("FaselHD: Found ${results.size} results on main page")
        return newHomePageResponse(request.name, results)
    }

    // ────────────────────────────────────────────────
    // Search
    // ────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        println("FaselHD: searching for -> $query")
        val host = resolveHost()
        val doc  = safeGet("$host/?s=$query", host) ?: return emptyList()
        val results = doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
        println("FaselHD: Found ${results.size} results for search query")
        return results
    }

    // ────────────────────────────────────────────────
    // Card builder
    // ────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val title   = selectFirst("div.h1, .entry-title, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null
        val href    = selectFirst("a")?.attr("abs:href") ?: return null
        val img     = selectFirst("img")
        val poster  = img?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("data-original") }
                .ifEmpty { it.attr("src") }
        }?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.startsWith("http") }
        val quality = selectFirst("span.quality, span.qualitySpan")?.text()
        val type    = if (href.contains("/episode/") || href.contains("/episodes/"))
                          TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            posterUrl     = poster
            this.quality  = getQualityFromString(quality)
            // Use the card's own page URL as Referer — avoids 403 on poster CDNs
            posterHeaders = mapOf("Referer" to href, "User-Agent" to userAgent)
        }
    }

    // ────────────────────────────────────────────────
    // Load
    // ────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        println("FaselHD: load -> $url")
        val host    = resolveHost()
        val pageUrl = normalizeUrl(url, host)
        println("FaselHD: Normalized page URL -> $pageUrl")
        val doc     = safeGet(pageUrl, pageUrl) ?: return null

        val title = doc.selectFirst("div.title, h1.postTitle, div.h1, .entry-title, h1")
            ?.text() ?: doc.title()
        println("FaselHD: Title -> $title")

        // og:image first — CDN-hosted, no CF cookie needed
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property='og:image:secure_url']")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("div.posterImg img, .entry-thumbnail img, img.posterImg")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.let { if (it.startsWith("//")) "https:$it" else it }
        println("FaselHD: Poster -> $poster")

        val ph   = mapOf("Referer" to pageUrl, "User-Agent" to userAgent)
        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc, .entry-content p")?.text()
        val year = doc.select("a[href*='series_year'], a[href*='movies_year']")
            .firstOrNull()?.text()?.toIntOrNull()
        val recs = doc.select("div.postDiv, article, .entry-box")
            .mapNotNull { it.toSearchResult() }

        val isEpisodePage = "/episodes/" in pageUrl
        val isSeries      = "/series/" in pageUrl || "/tvshow" in pageUrl ||
                "/anime/" in pageUrl || "/asian-series/" in pageUrl ||
                doc.select("#seasonList, div.seasonLoop, #epAll, div.epAll, #DivEpisodesList").isNotEmpty() ||
                doc.select("a[href*='/episodes/']").isNotEmpty()
        println("FaselHD: isSeries=$isSeries, isEpisodePage=$isEpisodePage")

        return when {
            isEpisodePage -> {
                println("FaselHD: Treatment as Single Episode")
                newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries,
                    listOf(newEpisode(pageUrl) { name = title })
                ) {
                    posterUrl     = poster
                    this.year     = year
                    plot          = desc
                    posterHeaders = ph
                }
            }

            isSeries -> {
                println("FaselHD: Treatment as Series")
                val rawLinks = doc.select(
                    "#epAll a, div.epAll a, #DivEpisodesList a, .episodes-list a, a[href*='/episodes/']"
                )
                val episodes = if (rawLinks.isNotEmpty()) {
                    println("FaselHD: Found ${rawLinks.size} episode links")
                    rawLinks.mapIndexed { idx, el ->
                        val epUrl   = normalizeUrl(el.attr("abs:href"), host)
                        val epTitle = el.text().trim()
                        val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        newEpisode(epUrl) { name = epTitle; episode = epNum }
                    }.distinctBy { it.data }
                } else {
                    println("FaselHD: No episode links found, checking for seasons")
                    doc.select("#seasonList a, div.seasonLoop a").mapIndexed { idx, el ->
                        val seasonUrl   = normalizeUrl(el.attr("abs:href"), host)
                        val seasonTitle = el.text().trim()
                        newEpisode(seasonUrl) {
                            name   = seasonTitle
                            season = Regex("""\d+""").find(seasonTitle)?.value?.toIntOrNull() ?: (idx + 1)
                        }
                    }
                }
                println("FaselHD: Total episodes collected -> ${episodes.size}")
                newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    posterUrl        = poster
                    this.year        = year
                    plot             = desc
                    recommendations  = recs
                    posterHeaders    = ph
                }
            }

            else -> {
                println("FaselHD: Treatment as Movie")
                newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                    posterUrl       = poster
                    this.year       = year
                    plot            = desc
                    recommendations = recs
                    posterHeaders   = ph
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    // loadLinks — single clean path
    // ────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FaselHD: loadLinks for data -> $data")
        val host    = resolveHost()
        val pageUrl = normalizeUrl(data, host)
        println("FaselHD: Normalized loadLinks URL -> $pageUrl")

        // Step 1: get the episode/movie page (CF cleared)
        val doc  = safeGet(pageUrl, pageUrl) ?: return false
        val html = doc.html()

        // Step 2: find the player token URL directly in page HTML
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
        
        println("FaselHD: Extracted playerUrl -> $playerUrl")

        if (playerUrl == null) {
            println("FaselHD: No player token in page, falling back to rawScan")
            return rawScan(html, pageUrl, callback)
        }

        // Step 3: use WebViewResolver
        println("FaselHD: Initiating WebViewResolver for playerUrl")
        val resolved = runCatching {
            WebViewResolver(
                Regex("""\.m3u8|\.mp4""")
            ).resolveUsingWebView(
                requestCreator(
                    "GET",
                    normalizeUrl(playerUrl, host),
                    referer  = pageUrl,
                    headers  = headers(host, pageUrl)
                )
            ).first?.url?.toString()
        }.getOrNull()
        
        println("FaselHD: WebViewResolver returned -> $resolved")

        if (resolved != null) {
            callback(
                newExtractorLink(
                    name, name, resolved,
                    if (resolved.contains(".m3u8", true)) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    referer = playerUrl
                    quality = getVideoQuality(resolved)
                }
            )
            return true
        }

        // Step 4: try scanning player page HTML directly
        println("FaselHD: WebView failed, scanning player page HTML directly")
        val playerDoc = safeGet(normalizeUrl(playerUrl, host), pageUrl)
        if (playerDoc != null && rawScan(playerDoc.html(), playerUrl, callback)) return true

        // Step 5: fallback — scan original page HTML
        println("FaselHD: Everything failed, final rawScan of original page")
        return rawScan(html, pageUrl, callback)
    }

    // ────────────────────────────────────────────────
    // Raw HTML scanner (final fallback only)
    // ────────────────────────────────────────────────

    private suspend fun rawScan(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
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
            println("FaselHD: rawScan found -> $url")
            callback(
                newExtractorLink(
                    name, name, url,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    quality      = getVideoQuality(url)
                }
            )
        }
        return true
    }

    // ────────────────────────────────────────────────
    // Quality helper
    // ────────────────────────────────────────────────

    private fun getVideoQuality(url: String) = when {
        "1080" in url -> Qualities.P1080.value
        "720"  in url -> Qualities.P720.value
        "480"  in url -> Qualities.P480.value
        "360"  in url -> Qualities.P360.value
        else          -> Qualities.Unknown.value
    }
}
