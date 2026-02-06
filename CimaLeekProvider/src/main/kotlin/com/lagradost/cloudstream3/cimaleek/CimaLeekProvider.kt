package com.lagradost.cloudstream3.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaLeekProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimalek.art"
    override var name = "CimaLeek"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1"
    )

    private val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
    )

    private fun requestHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = baseHeaders.toMutableMap()
        h["User-Agent"] = userAgents.random()
        extra.forEach { (k, v) -> h[k] = v }
        return h
    }

    private suspend fun politeDelay(minMs: Long = 900, maxMs: Long = 2000) {
        delay((minMs..maxMs).random())
    }

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.cleanTitle(): String =
        this.replace("مشاهدة|مترجم|مسلسل|فيلم|كامل|جميع الحلقات|الموسم|الحلقة|انمي".toRegex(), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // --- URL helpers ---
    private fun isTvUrl(u: String): Boolean =
        u.contains("/series/") || u.contains("/seasons/") || u.contains("/episodes/")

    private fun isMovieUrl(u: String): Boolean = u.contains("/movies/")

    private fun normalizeUrl(u: String): String = fixUrl(u)

    private fun ensureWatchUrl(u: String): String {
        if (u.contains("/watch/")) return u
        val base = if (u.endsWith("/")) u else "$u/"
        return "${base}watch/"
    }

    private fun parseSeasonFromSeasonUrl(url: String): Int? {
        // Matches both "...-season-3-..." and "...-الموسم-2-..."
        val m = Regex("""/seasons/.*?(?:season-|الموسم-)(\d+)-""").find(url) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSxEFromEpisodeUrl(url: String): Pair<Int?, Int?> {
        val m = Regex("""/episodes/[^/]*?(\d+)x(\d+)(?:-|/)""").find(url) ?: return null to null
        val s = m.groupValues.getOrNull(1)?.toIntOrNull()
        val e = m.groupValues.getOrNull(2)?.toIntOrNull()
        return s to e
    }

    private fun Document.findCanonicalSeriesUrl(): String? =
        selectFirst("""a[href*="/series/"]""")?.attr("href")?.let(::normalizeUrl)

    // --- Search/home extraction ---
    private fun Element.toSearchResponseFallback(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = normalizeUrl(a.attr("href"))
        val tvType = if (isTvUrl(href)) TvType.TvSeries else TvType.Movie

        val title =
            (selectFirst(".data .title")?.text()
                ?: selectFirst("h3, h2, .title")?.text()
                ?: a.attr("title").ifBlank { a.text() })
                .cleanTitle()

        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster =
            img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }.ifBlank { null }

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies-list/" to "أحدث الأفلام",
        "$mainUrl/series-list/" to "أحدث المسلسلات",
        "$mainUrl/seasons-list/" to "المواسم",
        "$mainUrl/episodes-list/" to "الحلقات",
        "$mainUrl/recent-89541/" to "المضاف حديثاً",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        politeDelay()
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = requestHeaders()).document

        val cards = doc.select(".item").ifEmpty { doc.select("article, .post, .result-item") }
        val results = cards.mapNotNull { it.toSearchResponseFallback() }

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        politeDelay()
        val doc = app.get("$mainUrl/search/?s=$query", headers = requestHeaders()).document

        val cards = doc.select(".item").ifEmpty { doc.select("article, .post, .result-item") }
        val results = cards.mapNotNull { it.toSearchResponseFallback() }

        // Hard fallback: search results sometimes are just links
        return if (results.isNotEmpty()) results
        else doc.select("""a[href*="/movies/"],a[href*="/series/"],a[href*="/seasons/"]""")
            .mapNotNull { it.parent()?.toSearchResponseFallback() ?: it.toSearchResponseFallback() }
            .distinctBy { it.url }
    }

    // --- Load details + episodes ---
    override suspend fun load(url: String): LoadResponse {
        politeDelay(1200, 2500)
        val doc = app.get(url, headers = requestHeaders()).document

        val canonicalSeriesUrl = doc.findCanonicalSeriesUrl()
        val fixedUrl = normalizeUrl(url)

        val titleRaw =
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
        val title = titleRaw.cleanTitle().ifBlank { name }

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("img")?.attr("data-src")?.ifBlank { doc.selectFirst("img")?.attr("src") }

        val year =
            doc.select("""a[href*="/release/"]""").text().getIntFromText()
                ?: doc.text().let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        val tags =
            doc.select("""a[href*="/genre/"], a[href*="/category/"]""").map { it.text().trim() }.distinct()

        val plot =
            doc.select(".story, .text, .m_desc, .description, .summary, .plot").text().trim()
                .ifBlank { doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty() }
                .ifBlank { null }

        // Movie
        if (isMovieUrl(fixedUrl) && !isTvUrl(fixedUrl)) {
            return newMovieLoadResponse(
                name = title,
                url = fixedUrl,
                type = TvType.Movie,
                dataUrl = fixedUrl
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
            }
        }

        // TV: series / seasons / episodes pages should all resolve to a tv-series style response with episodes.
        val episodes = ArrayList<Episode>()

        suspend fun addEpisodesFromSeasonPage(seasonUrl: String) {
            politeDelay(700, 1400)
            val sDoc = app.get(seasonUrl, headers = requestHeaders(mapOf("Referer" to fixedUrl))).document
            val seasonNumFromUrl = parseSeasonFromSeasonUrl(seasonUrl)

            val epAnchors = sDoc.select("""a[href*="/episodes/"]""")
            for (a in epAnchors) {
                val epUrl = normalizeUrl(a.attr("href"))
                val (sFromEpUrl, eFromEpUrl) = parseSxEFromEpisodeUrl(epUrl)
                val epNum = eFromEpUrl ?: a.text().getIntFromText()

                if (epNum != null) {
                    episodes.add(newEpisode(epUrl) {
                        name = a.text().cleanTitle().ifBlank { "Episode $epNum" }
                        season = sFromEpUrl ?: seasonNumFromUrl
                        episode = epNum
                    })
                }
            }
        }

        // If we are on an episode page, make it a single-episode list (still useful when opened directly)
        if (fixedUrl.contains("/episodes/")) {
            val (s, e) = parseSxEFromEpisodeUrl(fixedUrl)
            val epNum = e ?: doc.text().getIntFromText()
            if (epNum != null) {
                episodes.add(newEpisode(fixedUrl) {
                    name = title
                    season = s
                    episode = epNum
                })
            }
        } else {
            // Series or Season: try to gather season links first
            val seasonLinks = doc.select("""a[href*="/seasons/"]""")
                .map { normalizeUrl(it.attr("href")) }
                .distinct()
                // avoid pulling random "related" season links if any
                .filter { it.startsWith(mainUrl) }

            if (seasonLinks.isNotEmpty()) {
                // Fetch all seasons to build a complete episode list
                for (sUrl in seasonLinks) addEpisodesFromSeasonPage(sUrl)
            } else {
                // Fallback: just parse episodes present on current page
                val epAnchors = doc.select("""a[href*="/episodes/"]""")
                for (a in epAnchors) {
                    val epUrl = normalizeUrl(a.attr("href"))
                    val (sFromEpUrl, eFromEpUrl) = parseSxEFromEpisodeUrl(epUrl)
                    val epNum = eFromEpUrl ?: a.text().getIntFromText()
                    if (epNum != null) {
                        episodes.add(newEpisode(epUrl) {
                            name = a.text().cleanTitle().ifBlank { "Episode $epNum" }
                            season = sFromEpUrl
                            episode = epNum
                        })
                    }
                }
            }
        }

        val cleanedEpisodes = episodes
            .distinctBy { it.data } // unique by url
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        // Prefer canonical /series/ URL as the "show url" if we came from season/episode page
        val showUrl = canonicalSeriesUrl ?: fixedUrl

        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, cleanedEpisodes) {
            posterUrl = poster
            this.year = year
            this.tags = tags
            this.plot = plot
        }
    }

    private fun detectQuality(text: String): Int {
        val t = text.uppercase()
        return when {
            "2160" in t || "4K" in t -> 2160
            "1080" in t || "FHD" in t -> 1080
            "720" in t || "HD" in t -> 720
            "480" in t || "SD" in t -> 480
            "360" in t -> 360
            else -> Qualities.Unknown.value
        }
    }

    // --- Links ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        politeDelay(1200, 2600)

        val fixedData = normalizeUrl(data)

        // Only movies + episodes are playable.
        if (!isMovieUrl(fixedData) && !fixedData.contains("/episodes/") && !fixedData.contains("/watch/")) {
            return false
        }

        val watchUrl = if (fixedData.contains("/watch/")) fixedData else ensureWatchUrl(fixedData)
        val headers = requestHeaders(mapOf("Referer" to fixedData))
        val watchDoc = app.get(watchUrl, headers = headers).document

        val loaded = HashSet<String>()

        suspend fun emitLink(url: String, name: String, quality: Int = Qualities.Unknown.value) {
            val u = normalizeUrl(url)
            if (!u.startsWith("http") || !loaded.add(u)) return

            if (u.contains(".m3u8", ignoreCase = true) || u.contains(".mp4", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name (Direct)",
                        url = u,
                        type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.quality = quality }
                )
            } else {
                loadExtractor(u, watchUrl, subtitleCallback, callback)
            }
        }

        // 1) DooPlay AJAX servers (Cloud_V2/V3/V5, doodstream, etc. appear as server names on the watch page)
        val ajaxCandidates = watchDoc.select(".lalaplay_player_option, [data-post][data-nume]")
        if (ajaxCandidates.isNotEmpty()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val ajaxHeaders = requestHeaders(
                mapOf(
                    "Referer" to watchUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            for (server in ajaxCandidates) {
                val postId = server.attr("data-post")
                val nume = server.attr("data-nume")
                val type = server.attr("data-type")
                val serverName = server.text().trim().ifBlank { "Server $nume" }

                if (postId.isBlank() || nume.isBlank()) continue

                runCatching {
                    val response = app.post(
                        url = ajaxUrl,
                        headers = ajaxHeaders,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        )
                    )
                    val json = response.parsedSafe<AjaxResponse>()
                    val embedUrl = json?.embed_url
                    if (!embedUrl.isNullOrBlank()) emitLink(embedUrl, serverName)
                }
            }
        }

        // 2) Generic fallbacks: data attributes + iframes + download links
        for (el in watchDoc.select(".serversList li, .nav-tabs li, .server-item, [data-server], .watch-links a, a[href][data-link]")) {
            val u = el.attr("data-link").ifBlank {
                el.attr("data-url").ifBlank {
                    el.attr("data-src").ifBlank { el.attr("href") }
                }
            }
            if (u.isNotBlank()) emitLink(u, el.text().trim().ifBlank { "Server" })
        }

        for (iframe in watchDoc.select("iframe[src]")) {
            emitLink(iframe.attr("src"), "Embed")
        }

        for (a in watchDoc.select("""a.download, .download-links a, a:contains(تحميل)""")) {
            val href = a.attr("href")
            if (href.startsWith("http")) emitLink(href, "Download ${a.text()}", detectQuality(a.text()))
        }

        return true
    }

    data class AjaxResponse(val embed_url: String?)
}