package com.lagradost.cloudstream3.cimaclub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CimaClubProvider : MainAPI() {

    override var mainUrl = "https://ciimaclub.us"
    override var name = "CimaClub"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept-Language" to "ar-SA,ar;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("الحلقة\\s*\\d+"), "")
            .replace(Regex("الموسم\\s*\\d+"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectTvType(category: String, url: String, title: String = ""): TvType {
        return when {
            category.contains("انمي") || title.contains("انمي") || url.contains("anime") -> TvType.Anime
            url.contains("/series/") || title.contains("مسلسل") || title.contains("الحلقة") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("الحلقة\\s*(\\d+)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = when {
            baseUrl == mainUrl -> if (page == 1) baseUrl else "$mainUrl/page/$page/"
            baseUrl.contains("/movies") -> if (page == 1) baseUrl else "$baseUrl/page/$page/"
            baseUrl.contains("/series") -> if (page == 1) baseUrl else "$baseUrl?offset=$page"
            else -> baseUrl
        }

        val doc = app.get(url, headers = headers, timeout = 30).document
        // Fixed selector - items are direct children with class Small--Box
        val items = doc.select("#MainFiltar > .Small--Box, .Small--Box")
        val home = items.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val link = fixUrl(a.attr("href"))
        
        // Try multiple selectors for title
        val rawTitle = selectFirst("h2, .inner--title h2")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val category = selectFirst(".category")?.text() ?: ""
        val type = detectTvType(category, link, rawTitle)

        return when (type) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
            else -> newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return doc.select(".Small--Box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".Poster img, .image img")?.attr("src")
        val description = doc.selectFirst(".Story, .StoryArea")?.text()?.trim()
        val category = doc.selectFirst(".category")?.text() ?: ""

        val tvType = detectTvType(category, url, rawTitle)
        
        // Check if it's an episode page (contains "الحلقة" in title or URL)
        val isEpisodePage = rawTitle.contains("الحلقة") || url.contains("الحلقة")
        
        if (isEpisodePage || tvType != TvType.Movie) {
            // For episode pages, return single episode pointing to itself
            val episodeNumber = extractEpisodeNumber(rawTitle)
            val episodes = listOf(
                newEpisode(url) {
                    this.name = rawTitle
                    this.episode = episodeNumber
                }
            )

            return newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = headers, timeout = 30).document
        } catch (_: Exception) {
            return false
        }

        val loaded = HashSet<String>()

        fun detectQuality(textOrUrl: String): Int {
            return when {
                textOrUrl.contains("1080") -> 1080
                textOrUrl.contains("720") -> 720
                textOrUrl.contains("480") -> 480
                textOrUrl.contains("360") -> 360
                else -> Qualities.Unknown.value
            }
        }

        suspend fun safeLoad(
            url: String,
            serverName: String = "CimaClub",
            type: ExtractorLinkType = ExtractorLinkType.VIDEO,
            qualityHint: String? = null
        ) {
            if (url.isBlank()) return
            val fixed = fixUrl(url)
            if (!loaded.add(fixed)) return

            val qualityValue = detectQuality(qualityHint ?: url)

            try {
                // Known streaming hosts use extractor
                if (type == ExtractorLinkType.VIDEO && (
                        fixed.contains("peytonepre") ||
                        fixed.contains("iplayerhls") ||
                        fixed.contains("mxdrop") ||
                        fixed.contains("filemoon") ||
                        fixed.contains("vudeo") ||
                        fixed.contains("uqload") ||
                        fixed.contains("luluvdo") ||
                        fixed.contains("listeamed") ||
                        fixed.contains("megaup") ||
                        fixed.contains("1cloudfile")
                    )
                ) {
                    loadExtractor(fixed, mainUrl, subtitleCallback, callback)
                } else {
                    // Direct download or unknown host
                    callback(
                        newExtractorLink(
                            name,
                            serverName,
                            fixed,
                            type
                        ) {
                            this.referer = mainUrl
                            this.quality = qualityValue
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        // 1️⃣ WatchArea — streaming links from #watch list
        doc.select("#watch li[data-watch]").forEach { li ->
            val url = li.attr("data-watch").trim()
            val serverName = li.text().trim().ifBlank { "Server" }
            safeLoad(url, serverName, ExtractorLinkType.VIDEO)
        }

        // 2️⃣ DownloadArea — download links
        doc.select(".DownloadArea ul li a[href]").forEach { a ->
            val url = a.attr("href").trim()
            val serverName = a.selectFirst(".text span")?.text()?.trim()
                ?: a.selectFirst(".text p")?.text()?.trim()
                ?: "Download Server"
            val qualityHint = a.text().trim()
            safeLoad(url, serverName, ExtractorLinkType.VIDEO, qualityHint)
        }

        return loaded.isNotEmpty()
    }
}
