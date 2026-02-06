package com.lagradost.cloudstream3.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaLeekProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimalek.art"
    override var name = "CimaLeek"

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun normalizeUrl(u: String): String = fixUrl(u)

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

    private fun isSeasonUrl(u: String): Boolean = u.contains("/seasons/")

    private fun isMovieUrl(u: String): Boolean = u.contains("/movies/")

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
        // If the element itself is the anchor (mobile/archive view), use it.
        // Otherwise search for a nested anchor (desktop/item view).
        val a = if (this.tagName().equals("a", ignoreCase = true)) this else selectFirst("a[href]")
        
        if (a == null) return null
        
        val href = normalizeUrl(a.attr("href"))
        val tvType = when {
            isMovieUrl(href) -> TvType.Movie
            isSeasonUrl(href) -> TvType.TvSeries
            isTvUrl(href) -> TvType.TvSeries
            else -> TvType.Movie
        }

        val title =
            (selectFirst(".data .title")?.text()
                ?: selectFirst(".title")?.text()
                ?: a.attr("title").ifBlank { a.text() })
                .cleanTitle()

        if (title.isBlank()) return null

        val img = selectFirst(".poster img") ?: selectFirst("img") ?: a.selectFirst("img")
        val poster = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }.ifBlank { null }

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
        "$mainUrl/recent-89541/" to "المضاف حديثاً",
        "$mainUrl/movies-list/" to "أحدث الأفلام",
        "$mainUrl/series-list/" to "أحدث المسلسلات",
        "$mainUrl/seasons-list/" to "المواسم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        politeDelay()
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document

        // Check if we got a valid page or if we need to warn (logging usually not visible, but relying on doc)
        val cards = doc.select(".item").ifEmpty {
            doc.select(
                """
                a[href*="/movies/"],
                a[href*="/series/"],
                a[href*="/seasons/"]
                """
            )
        }

        val results = cards
            .mapNotNull { it.toSearchResponseFallback() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, results)
    }



    override suspend fun search(query: String): List<SearchResponse> {
        politeDelay()
        val doc = app.get("$mainUrl/search/?s=$query").document

        val cards = doc.select(".item").ifEmpty { 
             doc.select("#archive-content > a, article, .post, .result-item")
        }
        
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
        val doc = app.get(url).document

        val canonicalSeriesUrl = doc.findCanonicalSeriesUrl()
        val fixedUrl = normalizeUrl(url)

        // Smart Redirect: If this is an episode page but we know the series URL, load the full series instead.
        if (fixedUrl.contains("/episodes/") && canonicalSeriesUrl?.contains("/series/") == true) {
            return load(canonicalSeriesUrl)
        }

        val titleRaw =
            doc.selectFirst("h1.film-name, h2.film-name")?.text()
                ?: doc.selectFirst("h1")?.text()
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
            doc.select(".film-description .text, .story, .text, .m_desc, .description, .summary, .plot").text().trim()
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
            val sDoc = app.get(seasonUrl).document
            val seasonNumFromUrl = parseSeasonFromSeasonUrl(seasonUrl)

            val epAnchors = sDoc.select("""a[href*="/episodes/"]""")
            for (a in epAnchors) {
                val epUrl = normalizeUrl(a.attr("href"))
                val (sFromEpUrl, eFromEpUrl) = parseSxEFromEpisodeUrl(epUrl)
                val epNum = eFromEpUrl ?: a.text().getIntFromText()

                if (epNum != null) {
                    episodes.add(newEpisode(epUrl) {
                        name = "الحلقة $epNum"
                        season = sFromEpUrl ?: seasonNumFromUrl ?: 1
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
                    season = s ?: 1
                    episode = epNum
                })
            }
        } else {
            // Series or Season
            val isSeason = isSeasonUrl(fixedUrl)
            val seasonFromPage = parseSeasonFromSeasonUrl(fixedUrl)
            
            // Strategy: Parse episodes from LOCAL page first (often S1 or latest season)
            val localEpAnchors = doc.select("""a[href*="/episodes/"]""")
            for (a in localEpAnchors) {
                val epUrl = normalizeUrl(a.attr("href"))
                val (sFromEpUrl, eFromEpUrl) = parseSxEFromEpisodeUrl(epUrl)
                val epNum = eFromEpUrl ?: a.text().getIntFromText()
                if (epNum != null) {
                    episodes.add(newEpisode(epUrl) {
                        name = "الحلقة $epNum"
                        season = sFromEpUrl ?: seasonFromPage ?: 1
                        episode = epNum
                    })
                }
            }

            // Only fetch other seasons if we are NOT on a specific season page
            if (!isSeason) {
                val seasonLinks = doc.select("""a[href*="/seasons/"]""")
                    .map { normalizeUrl(it.attr("href")) }
                    .distinct()
                    .filter { it.startsWith(mainUrl) && it != fixedUrl }

                for (sUrl in seasonLinks) addEpisodesFromSeasonPage(sUrl)
            }
        }

        val cleanedEpisodes = episodes
            .distinctBy { it.data } // unique by url
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        // Prefer canonical /series/ URL as the "show url" if we came from season/episode page
        val showUrl = canonicalSeriesUrl?.takeIf { it.contains("/series/") } ?: fixedUrl

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
        // Removed politeDelay here to speed up loading (approx -2s)
        val fixedData = normalizeUrl(data)

        // Only movies + episodes are playable.
        if (!isMovieUrl(fixedData) && !fixedData.contains("/episodes/") && !fixedData.contains("/watch/")) {
            return false
        }

        val watchUrl = if (fixedData.contains("/watch/")) fixedData else ensureWatchUrl(fixedData)
        val watchDoc = app.get(watchUrl).document

        val visited = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        suspend fun emitLink(url: String, name: String, quality: Int = Qualities.Unknown.value) {
            val u = normalizeUrl(url)
            if (!u.startsWith("http") || !visited.add(u)) return

            // Fix Error 3003: "contains" is too loose (matches embed.php?s=video.mp4).
            // We must check if the PATH actually ends with a video extension.
            val cleanPath = u.substringBefore("?")
            val isDirectVideo = cleanPath.endsWith(".m3u8", true) || cleanPath.endsWith(".mp4", true)

            if (isDirectVideo) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name (Direct)",
                        url = u,
                        type = if (cleanPath.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.quality = quality }
                )
            } else {
                loadExtractor(u, watchUrl, subtitleCallback, callback)
            }
        }

        // 1) DooPlay AJAX servers (Cloud_V2/V3/V5, doodstream, etc. appear as server names on the watch page)
        val ajaxCandidates = watchDoc.select(".lalaplay_player_option, [data-post][data-nume], [id^='player-option-']")
        if (ajaxCandidates.isNotEmpty()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            // X-Requested-With is often required for these AJAX endpoints
            val ajaxHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to watchUrl
            )

            coroutineScope {
                ajaxCandidates.map { server ->
                    async {
                        val postId = server.attr("data-post")
                        val nume = server.attr("data-nume")
                        val type = server.attr("data-type")
                        val serverName = server.text().trim().ifBlank { "Server $nume" }

                        if (postId.isNotBlank() && nume.isNotBlank()) {
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
                }.awaitAll()
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

        // iframe loop removed per user optimization


        for (a in watchDoc.select("""a.download, .download-links a, a:contains(تحميل)""")) {
            val href = a.attr("href")
            if (href.startsWith("http")) emitLink(href, "Download ${a.text()}", detectQuality(a.text()))
        }

        return true
    }

    data class AjaxResponse(val embed_url: String?)
}