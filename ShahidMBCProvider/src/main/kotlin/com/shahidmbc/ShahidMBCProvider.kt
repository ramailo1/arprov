package com.shahidmbc

import com.lagradost.cloudstream3.SubtitleFile

import com.lagradost.cloudstream3.ExtractorLink

import com.lagradost.cloudstream3.Episode

import com.lagradost.cloudstream3.HomePageResponse

import com.lagradost.cloudstream3.SearchResponse

import com.lagradost.cloudstream3.LoadResponse

import com.lagradost.cloudstream3.TvType

import com.lagradost.cloudstream3.MainAPI

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ShahidMBCProvider : MainAPI() {
    override var mainUrl = "https://shahid.mbc.net"
    override var name = "ShahidMBC"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Anti-bot configuration
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun getRandomUserAgent(): String = userAgents.random()
    private fun getRandomDelay(): Long = (2000..4000).random().toLong()

    override val mainPage = mainPageOf(
        "$mainUrl/ar/movies" to "أفلام شاهد",
        "$mainUrl/ar/series" to "مسلسلات شاهد",
        "$mainUrl/ar/anime" to "انمي شاهد",
        "$mainUrl/ar/live" to "بث مباشر"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        Thread.sleep(getRandomDelay())
        
        val home = doc.select("div.slider-slide, .media-item").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val img = this.selectFirst("img")
        
        val title = img?.attr("title")?.takeIf { it.isNotBlank() } 
             ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
             ?: this.selectFirst(".title, .name, h2, h3, .media-title")?.text()?.trim() 
             ?: return null
             
        val posterUrl = fixUrlNull(img?.attr("src"))
        val year = this.selectFirst(".year, .date")?.text()?.trim()?.toIntOrNull()
        val quality = this.selectFirst(".quality, .resolution")?.text()?.trim()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ar/search?q=${query.replace(" ", "+")}"
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        return doc.select(".search-results .media-item, .results .content-item, .search-item").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        val title = doc.selectFirst(".media-title, .content-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".poster img, .media-poster img")?.attr("src"))
        val year = doc.selectFirst(".year, .date, .release-date")?.text()?.trim()?.toIntOrNull()
        val description = doc.selectFirst(".description, .plot, .summary")?.text()?.trim()
        val rating = doc.selectFirst(".rating, .imdb-rating")?.text()?.trim()?.toFloatOrNull()
        val duration = doc.selectFirst(".duration, .runtime")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select(".genres a, .categories a, .tags a").map { it.text().trim() }
        
        val isSeries = doc.select(".episodes, .seasons, .episode-list").isNotEmpty()
        
        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".episode-item, .episodes li").forEach { episodeElement ->
                val episodeName = episodeElement.selectFirst(".episode-title, .episode-name")?.text()?.trim()
                val episodeUrl = fixUrlNull(episodeElement.selectFirst("a")?.attr("href"))
                val episodeNumber = episodeElement.selectFirst(".episode-number")?.text()?.trim()?.toIntOrNull()
                val seasonNumber = episodeElement.selectFirst(".season-number")?.text()?.trim()?.toIntOrNull() ?: 1
                
                if (episodeName != null && episodeUrl != null) {
                    episodes.add(Episode(episodeName, episodeUrl, episodeNumber, seasonNumber))
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                this.duration = duration
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Thread.sleep(getRandomDelay() + 1000)
        val doc = app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        doc.select(".video-links a, .download-links a, .watch-links a").forEach { linkElement ->
            val linkUrl = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            val quality = linkElement.select(".quality, .resolution").text().trim()
            val serverName = linkElement.select(".server-name, .host").text().trim()
            
            callback.invoke(
                newExtractorLink(
                    source = serverName.ifEmpty { "ShahidMBC" },
                    name = serverName.ifEmpty { "ShahidMBC" },
                    url = linkUrl,
                    referer = mainUrl,
                    quality = getQualityFromString(quality),
                    type = if (linkUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }

    private fun getQualityFromString(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}