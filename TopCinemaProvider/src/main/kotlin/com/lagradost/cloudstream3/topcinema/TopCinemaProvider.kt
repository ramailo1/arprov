package com.lagradost.cloudstream3.topcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class TopCinemaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://topcima.online"
    override var name = "TopCinema (In Progress)"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
        "Referer" to "$mainUrl/"
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مترجم|مسلسل|اون لاين|كامل|جميع الحلقات|الموسم|الحلقة|انمي|تحميل".toRegex(), "")
            .replace(Regex("\\(\\d+\\)"), "")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), "") // Remove years
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun Element.toSearchResponse(): SearchResponse {
        val titleRaw = select("h3").text().ifEmpty { select("a").attr("title") }.ifEmpty { select(".title").text() }
        val title = titleRaw.cleanTitle()
        val posterUrl = select("img").let { img -> 
            img.attr("data-src").ifEmpty { img.attr("src") } 
        }
        val href = fixUrl(select("a").attr("href"))
        
        // Better type detection: if title has "episode" or "season" keyword, it's a series
        val isSeries = href.contains("/series/|/مسلسل/|/season/|/episodes/".toRegex()) || 
                       titleRaw.contains("مسلسل|انمي|حلقة|موسم".toRegex())
        
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
        
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية",
        "$mainUrl/last/" to "المضاف حديثا",
        "$mainUrl/movies/" to "الأفلام الجديدة",
        "$mainUrl/series/" to "المسلسلات الجديدة",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val response = app.get(url, headers = headers)
        val document = response.document
        
        val home = document.select(".Block--Item, .Small--Box, .AsidePost, .GridItem").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val response = app.get(url, headers = headers)
        val document = response.document

        return document.select(".Block--Item, .Small--Box, .AsidePost, .GridItem").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, headers = headers).document
        
        // Smart Redirect: If on episode page, find breadcrumb series link
        val seriesLink = doc.select(".breadcrumbs a[href*='/series/'], .breadcrumbs a[href*='/مسلسل/'], .breadcrumbs a:nth-last-child(2)").firstOrNull()
        if (seriesLink != null && url.contains("/episodes/|/الحلقة/".toRegex())) {
            val seriesUrl = fixUrl(seriesLink.attr("href"))
            if (seriesUrl != url) {
                doc = app.get(seriesUrl, headers = headers).document
            }
        }

        val titleRaw = doc.select("h1.title, .movie-title, .PostTitle, h1").text()
        val title = titleRaw.cleanTitle()
        val isMovie = !doc.select(".allepcont, .EpisodesList, .list-episodes, .seasonslist").any() && 
                     !url.contains("/series/|/مسلسل/|/season/".toRegex())

        val posterUrl = doc.select(".poster img, .movie-poster img, .MainSingle .image img, meta[property='og:image']").let { img -> 
            img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("content") } 
        }
        val synopsis = doc.select(".description, .plot, .summary, .StoryArea, .Story").text()
        val year = doc.select(".year, .release-year, a[href*='/release-year/']").text().getIntFromText()
        val tags = doc.select(".genre a, .categories a, .TaxContent a").map { it.text() }
        
        val recommendations = doc.select(".related-movies .movie-item, .Block--Item, .Small--Box, .AsidePost").mapNotNull { element ->
            element.toSearchResponse()
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            val cleanSeriesTitle = title.split(" ").take(3).joinToString(" ") // Reference for stripping
            
            // Layout 1: List style (.allepcont .row a)
            doc.select(".allepcont .row a, .EpisodesList .row a").forEach { episode ->
                val epLink = fixUrl(episode.attr("href"))
                val epRawName = episode.select(".ep-info h2").text().ifEmpty { episode.text() }
                var epName = epRawName.cleanTitle()
                
                // Strip redundant series title if present
                if (epName.contains(cleanSeriesTitle, true)) {
                    epName = epName.replace(cleanSeriesTitle, "", true).trim()
                }
                
                val epNum = episode.select(".epnum").text().getIntFromText() ?: epRawName.getIntFromText()
                val epPoster = episode.select("img").let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }.ifEmpty { posterUrl }
                
                episodes.add(newEpisode(epLink) {
                    this.name = epName.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.season = 1 
                })
            }
            
            // Layout 2: Grid style (.Small--Box, often for anime)
            if (episodes.isEmpty()) {
                doc.select(".Small--Box, .Block--Item, .GridItem").forEach { episode ->
                    val a = episode.selectFirst("a") ?: return@forEach
                    val epLink = fixUrl(a.attr("href"))
                    val epRawName = a.select("h3").text().ifEmpty { a.attr("title") }.ifEmpty { a.text() }
                    var epName = epRawName.cleanTitle()
                    
                    // Strip redundant series title
                    if (epName.contains(cleanSeriesTitle, true)) {
                        epName = epName.replace(cleanSeriesTitle, "", true).trim()
                    }
                    
                    val epNum = episode.select(".number em").text().getIntFromText() ?: epRawName.getIntFromText()
                    val epPoster = episode.select("img").let { img ->
                        img.attr("data-src").ifEmpty { img.attr("src") }
                    }.ifEmpty { posterUrl }
                    
                    if (epRawName.contains("حلقة|الحلقة".toRegex()) || epNum != null) {
                        episodes.add(newEpisode(epLink) {
                            this.name = epName.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.season = 1
                        })
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var watchUrl = data
        if (!data.contains("/watch")) {
            val doc = app.get(data, headers = headers).document
            val watchLink = doc.select("a.watch").attr("href")
            if (watchLink.isNotEmpty()) {
                watchUrl = watchLink
            }
        }
        
        val doc = app.get(watchUrl, headers = headers).document
        
        // Extract servers from ul#watch li (User provided structure)
        doc.select("ul#watch li").forEach { element ->
            val serverUrl = element.attr("data-watch").ifEmpty { 
                element.select("iframe").attr("src") 
            }
            if (serverUrl.isNotEmpty()) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }
        
        // Fallback or additional server extraction
        doc.select(".watch-servers li, .servers-list li, .watch--servers--list li").forEach { element ->
             val serverUrl = element.attr("data-link") ?: element.attr("data-url")
             if (!serverUrl.isNullOrEmpty()) {
                 loadExtractor(serverUrl, data, subtitleCallback, callback)
             }
        }

        return true
    }
}