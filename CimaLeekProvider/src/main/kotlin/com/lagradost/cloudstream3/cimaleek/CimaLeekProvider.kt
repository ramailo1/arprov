package com.lagradost.cloudstream3.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class CimaLeekProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimalek.art"
    override var name = "CimaLeek"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Anti-bot configuration
    private val userAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1"
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
    
    // Better title cleaning similar to CimaClub
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|مترجم|مسلسل|كامل|جميع الحلقات|الموسم|الحلقة|انمي".toRegex(), "")
            .replace(Regex("\\(.*?\\)"), "") // Remove content in brackets
            .replace(Regex("\\s+"), " ")   // Remove extra spaces
            .trim()
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = selectFirst(".data .title, h2.title, h3.title, .video-title") ?: return null
        val title = titleElement.text().cleanTitle()
        
        val posterElement = selectFirst("img.film-poster-img, img")
        val posterUrl = posterElement?.let { 
             it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        
        val href = selectFirst("a")?.attr("href") ?: return null
        val tvType = if (href.contains("/series/|/مسلسل/|/season/".toRegex())) TvType.TvSeries else TvType.Movie
        
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
        "$mainUrl/anime/" to "الانمي المترجم",
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
        
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        
        val document = app.get(url, headers = requestHeaders).document
        val home = document.select(".item, .video-item, .movie-item, .post-item").mapNotNull {
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
        return doc.select(".item, .video-item, .movie-item, .search-item").mapNotNull {
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
        val title = doc.select("h1.title, .video-title").text().cleanTitle()
        val isMovie = !url.contains("/series/|/مسلسل/|/season/".toRegex())

        val posterUrl = doc.select(".poster img, .video-poster img").attr("src")
        val rating = doc.select(".rating, .imdb").text().getIntFromText()
        val synopsis = doc.select(".description, .summary, .plot").text()
        val year = doc.select(".year, .date").text().getIntFromText()
        val tags = doc.select(".genre a, .category a, .tags a").map { it.text() }
        val recommendations = doc.select(".related-videos .video-item, .similar-videos .video-item").mapNotNull { element ->
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
            // Using for loop instead of forEach
            for (episode in doc.select(".episodes-list li, .episode-item")) {
                val epLink = episode.select("a").attr("href")
                if(epLink.isNotBlank()) {
                     episodes.add(newEpisode(epLink) {
                        this.name = episode.select("a").text().cleanTitle()
                        this.season = 0
                        this.episode = episode.select("a").text().getIntFromText()
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
    
    private fun detectQuality(text: String): Int {
        return when {
            text.contains("1080") || text.contains("FHD") -> 1080
            text.contains("720") || text.contains("HD") -> 720
            text.contains("480") || text.contains("SD") -> 480
            text.contains("360") -> 360
            else -> Qualities.Unknown.value
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
        val loaded = HashSet<String>()
        
        suspend fun loadLink(url: String, name: String, quality: Int = Qualities.Unknown.value) {
             if (url.startsWith("http") && loaded.add(url)) {
                 if (url.contains(".m3u8") || url.contains(".mp4")) {
                      callback(
                        newExtractorLink(
                            this.name,
                            "$name (Direct)",
                            url,
                            if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                        }
                    )
                 } else {
                     loadExtractor(url, data, subtitleCallback, callback)
                 }
            }
        }

        // 1. Try servers list
        // Using for loop instead of forEach
        for (element in doc.select(".servers-list li, .watch-links a, .download-links a, .video-servers a")) {
            val url = element.attr("data-link").ifBlank { 
                element.attr("data-url").ifBlank { 
                    element.attr("href") 
                } 
            }
            val name = element.text().trim().ifBlank { "Server" }
            
            if (url.isNotBlank()) {
                loadLink(fixUrl(url), name)
            }
        }
        
        // 2. Try iframe extraction directly
        // Using for loop instead of forEach
        for (iframe in doc.select("iframe")) {
             val src = iframe.attr("src")
             if (src.isNotBlank()) {
                 loadLink(fixUrl(src), "Embed")
             }
        }

        return true
    }
}