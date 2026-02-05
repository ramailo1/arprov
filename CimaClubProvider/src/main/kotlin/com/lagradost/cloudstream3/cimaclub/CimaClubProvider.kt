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
            if (!loaded.add(url)) return
            try {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                // Silent fail for extractor issues
            }
        }

        // 1. Try iframes (even if they show 404, structure might change)
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (!src.contains("/embed/")) { // Skip the broken default embed
                safeLoad(src)
            }
        }

        // 2. Check for data attributes on various elements
        // Added .ServersList li and ul#watch li based on user provided HTML
        doc.select("[data-src], [data-url], [data-embed], [data-player], .ServersList li[data-watch], ul#watch li[data-watch]").forEach { element ->
            val dataUrl = element.attr("data-watch")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-player") }
            if (dataUrl.isNotBlank()) {
                safeLoad(fixUrl(dataUrl))
            }
        }

        // 3. Parse download links (MAIN SOURCE OF LINKS)
        doc.select("a[href*='iplayerhls'], a[href*='1cloudfile'], a[href*='peytonepre'], a[href*='filemoon'], a[href*='uqload'], a[href*='multiup'], a[href*='megaup'], a[href*='vudeo']").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank() && !href.contains("/download/")) {
                // Only process non-download direct links
                safeLoad(fixUrl(href))
            } else if (href.contains("/download/")) {
                // Handle direct download links as video sources
                val quality = a.text()
                val qualityValue = when {
                    quality.contains("1080") -> 1080
                    quality.contains("720") -> 720
                    quality.contains("480") -> 480
                    else -> Qualities.Unknown.value
                }
                
                callback(
                    newExtractorLink(
                        name,
                        "CimaClub Direct",
                        fixUrl(href),
                        ExtractorLinkType.VIDEO
                    ) {
                       this.quality = qualityValue
                    }
                )
            }
        }

        return loaded.isNotEmpty()
    }
}