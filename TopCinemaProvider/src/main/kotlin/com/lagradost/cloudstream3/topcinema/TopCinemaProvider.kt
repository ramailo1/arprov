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
        return this.replace("مشاهدة فيلم|مترجم|مسلسل|كامل|جميع الحلقات|الموسم|الحلقة".toRegex(), "")
            .trim()
    }
    
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").let { img -> 
            img.attr("data-src").ifEmpty { img.attr("src") } 
        }
        val href = select("a").attr("href")
        val tvType = if (href.contains("/series/|/مسلسل/".toRegex())) TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(
            title,
            href,
            tvType,
        ) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية",
        "$mainUrl/episodes/" to "آخر الحلقات المضافة",
        "$mainUrl/movies/" to "الأفلام الجديدة",
        "$mainUrl/series/" to "المسلسلات الجديدة",
        "$mainUrl/anime/" to "الأنمي الجديد",
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
        
        val home = document.select(".Block--Item, .Small--Box, .AsidePost").mapNotNull {
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
        val doc = app.get(url, headers = headers).document
        val title = doc.select("h1.title, .movie-title, .PostTitle").text().cleanTitle()
        val isMovie = !url.contains("/series/|/مسلسل/".toRegex())

        val posterUrl = doc.select(".poster img, .movie-poster img, .MainSingle .image img").let { img -> 
            img.attr("data-src").ifEmpty { img.attr("src") } 
        }
        // val rating = doc.select(".rating, .imdb-rating, .imdbR span").text().getIntFromText()
        val synopsis = doc.select(".description, .plot, .summary, .StoryArea").text()
        val year = doc.select(".year, .release-year").text().getIntFromText()
        val tags = doc.select(".genre a, .categories a, .TaxContent a").map { it.text() }
        val recommendations = doc.select(".related-movies .movie-item, .similar-movies .movie-item, .Small--Box, .Block--Item, .AsidePost").mapNotNull { element ->
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
                // this.rating = rating
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            // Selector for episodes: .allepcont .row a
            for (episode in doc.select(".allepcont .row a")) {
                val epLink = episode.attr("href")
                if (epLink.isNotEmpty()) {
                    episodes.add(newEpisode(epLink) {
                        this.name = episode.select(".ep-info h2").text().cleanTitle()
                        this.season = 1 // Default to 1 as season info is often separate or implied
                        this.episode = episode.select(".epnum").text().getIntFromText()
                        this.posterUrl = episode.select("img").let { img -> 
                            img.attr("data-src").ifEmpty { img.attr("src") } 
                        }
                    })
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                // this.rating = rating
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