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
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Anti-bot configuration
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none"
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
        val posterUrl = select("img").attr("src")
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
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
        "$mainUrl/anime/" to "الانمي",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        // Add delay to mimic human behavior
        Thread.sleep((1000..3000).random().toLong())
        
        val document = app.get(request.data + page, headers = requestHeaders).document
        val home = document.select(".Block--Item, .Small--Box").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        // Add delay to mimic human behavior
        Thread.sleep((1000..2500).random().toLong())
        
        val doc = app.get("$mainUrl/search/?s=$query", headers = requestHeaders).document
        return doc.select(".Block--Item, .Small--Box").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        // Add delay to mimic human behavior
        Thread.sleep((1500..3500).random().toLong())
        
        val doc = app.get(url, headers = requestHeaders).document
        val title = doc.select("h1.title, .movie-title").text().cleanTitle()
        val isMovie = !url.contains("/series/|/مسلسل/".toRegex())

        val posterUrl = doc.select(".poster img, .movie-poster img").attr("src")
        val rating = doc.select(".rating, .imdb-rating").text().getIntFromText()
        val synopsis = doc.select(".description, .plot, .summary").text()
        val year = doc.select(".year, .release-year").text().getIntFromText()
        val tags = doc.select(".genre a, .categories a").map { it.text() }
        val recommendations = doc.select(".related-movies .movie-item, .similar-movies .movie-item").mapNotNull { element ->
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
            doc.select(".episodes-list li, .episode-item").forEach { episode ->
                episodes.add(newEpisode(episode.select("a").attr("href")) {
                    this.name = episode.select("a").text().cleanTitle()
                    this.season = 0
                    this.episode = episode.select("a").text().getIntFromText()
                })
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
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        // Add delay to mimic human behavior
        Thread.sleep((2000..4000).random().toLong())
        
        val doc = app.get(data, headers = requestHeaders).document
        
        // Try multiple server selection methods
        doc.select(".servers-list li, .watch-links a, .download-links a").forEach { element ->
            val url = element.attr("href") ?: element.attr("data-link")
            if (url.isNotEmpty()) {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}