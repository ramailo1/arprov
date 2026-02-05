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

        val watchUrl = if (data.contains("/watch")) data else "${data.trimEnd('/')}/watch/"
        val doc = try {
            app.get(watchUrl, headers = headers, timeout = 30).document
        } catch (_: Exception) {
            app.get(data, headers = headers, timeout = 30).document
        }

        val loaded = HashSet<String>()

        suspend fun safeLoad(url: String) {
            if (url.isBlank()) return
            val fixed = fixUrl(url)
            if (!loaded.add(fixed)) return
            try {
                loadExtractor(fixed, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        // 1. Data attributes
        doc.select("[data-watch], [data-url], [data-embed], [data-player], [data-src]").forEach { e ->
            val url = e.attr("data-watch")
                .ifBlank { e.attr("data-url") }
                .ifBlank { e.attr("data-embed") }
                .ifBlank { e.attr("data-player") }
                .ifBlank { e.attr("data-src") }
            safeLoad(url)
        }

        // 2. Onclick attributes (JS links)
        doc.select("*[onclick]").forEach { e ->
            val onclick = e.attr("onclick")
            val regex = Regex("'(http.*?)'") // Extract any URL inside single quotes
            regex.find(onclick)?.groups?.get(1)?.value?.let { safeLoad(it) }
        }

        // 3. Iframes (nested /embed/)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                safeLoad(src)
                if (src.contains("/embed/")) {
                    try {
                        val embedDoc = app.get(fixUrl(src), headers = headers).document
                        embedDoc.select("iframe[src]").forEach { nested ->
                            safeLoad(nested.attr("src"))
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 4. Download and streaming links from known hosts
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            
            // Check if link is from a known video/download host
            val isKnownHost = href.contains("1cloudfile") || href.contains("multiup") || 
                             href.contains("filemoon") || href.contains("megaup") || 
                             href.contains("iplayerhls") || href.contains("peytonepre") || 
                             href.contains("uqload") || href.contains("vudeo") ||
                             href.contains("luluvdo") || href.contains("listeamed") ||
                             href.contains("bowfile") || href.contains("frdl") ||
                             href.contains("mxdrop") || href.contains("mixdrop")
            
            if (isKnownHost) {
                // Try to extract quality from link text if available
                val qualityText = a.text()
                if (qualityText.contains("1080") || qualityText.contains("720") || qualityText.contains("480")) {
                    val qualityValue = when {
                        qualityText.contains("1080") -> 1080
                        qualityText.contains("720") -> 720
                        qualityText.contains("480") -> 480
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(
                            name,
                            "CimaClub Direct",
                            fixUrl(href),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = qualityValue
                        }
                    )
                } else {
                    // No quality specified, use extractor
                    safeLoad(href)
                }
            }
        }

        return loaded.isNotEmpty()
    }
}
