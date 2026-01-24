package com.lagradost.cloudstream3.cima4uactor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class Cima4uActorProvider : MainAPI() {
    override var mainUrl = "https://cima4u.forum"
    override var name = "Cima4uActor"
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
    private fun getRandomDelay(): Long = (1500..3500).random().toLong()

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/series" to "أحدث المسلسلات",
        "$mainUrl/anime" to "أحدث الانمي",
        "$mainUrl/trending" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        Thread.sleep(getRandomDelay())
        
        val home = doc.select(".Thumb--GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("strong")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")
        val year = this.selectFirst(".year, .date")?.text()?.trim()?.toIntOrNull()
        val quality = this.selectFirst(".quality, .resolution")?.text()?.trim()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getSearchQualityFromString(quality ?: "")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        return doc.select(".search-results .movie-item, .results .video-item, .search-item").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        val title = doc.selectFirst(".movie-title, .video-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrl(doc.selectFirst(".poster img, .movie-poster img")?.attr("src") ?: "")
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
                val episodeUrl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                val episodeNumber = episodeElement.selectFirst(".episode-number")?.text()?.trim()?.toIntOrNull()
                val seasonNumber = episodeElement.selectFirst(".season-number")?.text()?.trim()?.toIntOrNull() ?: 1
                
                if (episodeName != null && episodeUrl != null) {
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.episode = episodeNumber
                        this.season = seasonNumber
                    })
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = rating
                this.tags = tags
                this.duration = duration
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = rating
                this.tags = tags
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Thread.sleep(getRandomDelay() + 1000)
        val doc = app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        doc.select(".video-links a, .download-links a, .watch-links a").forEach { linkElement ->
            val linkUrl = fixUrl(linkElement.attr("href") ?: "") ?: return@forEach
            val quality = linkElement.select(".quality, .resolution").text().trim()
            val serverName = linkElement.select(".server-name, .host").text().trim()
            
            callback.invoke(
                newExtractorLink(
                    source = serverName.ifEmpty { "Cima4uActor" },
                    name = serverName.ifEmpty { "Cima4uActor" },
                    url = linkUrl,
                    referer = mainUrl,
                    quality = getQualityFromString(quality),
                    isM3u8 = linkUrl.contains(".m3u8")
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

    private fun getSearchQualityFromString(quality: String): SearchQuality? {
        return when {
            quality.contains("1080", ignoreCase = true) -> SearchQuality.HD
            quality.contains("720", ignoreCase = true) -> SearchQuality.HD
            quality.contains("480", ignoreCase = true) -> SearchQuality.SD
            quality.contains("360", ignoreCase = true) -> SearchQuality.SD
            else -> null
        }
    }
}